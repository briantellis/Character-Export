package com.briantellis.osrsintelligence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends a character's exports to a server the player chose and paired with. Nothing leaves the client until
 * pairing is approved on that server's website. The secret is made here, only its hash is sent to pair, and each
 * server and account gets its own, so changing the endpoint can never hand one server another's secret.
 */
@Slf4j
class SyncClient
{
    interface SecretStore
    {
        String get(String key);

        void set(String key, String value);

        void unset(String key);
    }

    interface Listener
    {
        void onSyncState(State state, String detail);

        void openApproval(String url);

        void onPaired();
    }

    enum State
    {
        OFF, NOT_PAIRED, PENDING, ACTIVE, ERROR
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Set<String> DATASETS = Set.of("character", "quests", "diaries", "combat_achievements", "account",
        "bank", "seed_vault", "inventory", "equipment", "looting_bag", "grand_exchange");
    // Items and trades change by the second while playing; sending them every few minutes is enough for planning
    // and keeps this from reading as live monitoring.
    private static final Set<String> SLOW = Set.of("bank", "seed_vault", "inventory", "equipment", "looting_bag", "grand_exchange");
    private static final int FLUSH_TICKS = 100; // a minute of game time, so a burst of XP drops sends once
    private static final int SLOW_FLUSH_TICKS = 500;
    private static final int POLL_TICKS = 8;
    private static final long MAX_BACKOFF_MS = 10 * 60 * 1000L;

    private final OkHttpClient http;
    private final Gson gson;
    private final SecretStore store;
    private final Listener listener;
    private final Supplier<String> endpoint;
    private final boolean allowLocalHttp;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, String> latest = new HashMap<>();
    private final Map<String, String> sentHash = new HashMap<>();
    private final Set<String> dirty = new LinkedHashSet<>();
    private long accountHash = -1;
    private String character;
    private State state = State.OFF;
    private int ticks;
    private boolean uploading;
    private boolean includeSlow;
    private int failures;
    private long retryAt;

    SyncClient(OkHttpClient http, Gson gson, SecretStore store, Listener listener, Supplier<String> endpoint, boolean allowLocalHttp)
    {
        this.http = http;
        this.gson = gson;
        this.store = store;
        this.listener = listener;
        this.endpoint = endpoint;
        this.allowLocalHttp = allowLocalHttp;
    }

    /** The endpoint as a usable URL, or null. Plain HTTP is refused: this carries a player's bank. */
    HttpUrl base()
    {
        String raw = endpoint.get();
        HttpUrl url = raw == null ? null : HttpUrl.parse(raw.trim());
        if (url == null || !(url.isHttps() || (allowLocalHttp && "localhost".equals(url.host()))))
        {
            return null;
        }
        return url;
    }

    private String secretKey()
    {
        HttpUrl url = base();
        return url == null || accountHash == -1 ? null : "sync." + url.host() + "." + url.port() + "." + accountHash;
    }

    private String secret()
    {
        String key = secretKey();
        return key == null ? null : store.get(key);
    }

    synchronized void setAccount(long hash, String name)
    {
        if (hash == accountHash)
        {
            return;
        }
        accountHash = hash;
        character = name;
        latest.clear();
        sentHash.clear();
        dirty.clear();
        refresh();
    }

    synchronized void clearAccount()
    {
        flush(true);
        accountHash = -1;
        character = null;
        setState(State.OFF, "Log in to sync");
    }

    /** Asks the server where this secret stands. */
    synchronized void refresh()
    {
        if (base() == null)
        {
            setState(State.OFF, "Set an HTTPS server in the plugin settings");
            return;
        }
        String secret = secret();
        if (secret == null)
        {
            setState(State.NOT_PAIRED, "Not linked");
            return;
        }
        checkStatus(secret);
    }

    private void checkStatus(String secret)
    {
        enqueue(request("api/sync/status", secret).get().build(), new Reply()
        {
            @Override
            public void ok(String body)
            {
                String remote = gson.fromJson(body, JsonObject.class).get("state").getAsString();
                synchronized (SyncClient.this)
                {
                    if (!secret.equals(secret()))
                    {
                        return;
                    }
                    if ("active".equals(remote))
                    {
                        boolean fresh = state != State.ACTIVE;
                        setState(State.ACTIVE, "Linked");
                        if (fresh)
                        {
                            dirty.addAll(latest.keySet());
                            listener.onPaired();
                        }
                    }
                    else if ("pending".equals(remote))
                    {
                        if (state != State.PENDING)
                        {
                            setState(State.PENDING, "Approve on the website");
                        }
                    }
                    else
                    {
                        store.unset(secretKey());
                        setState(State.NOT_PAIRED, "revoked".equals(remote) ? "Revoked on the website" : "Not linked");
                    }
                }
            }
        });
    }

    /** Whether this account's copy of a dataset has been offered since the account was set. */
    synchronized boolean has(String dataset)
    {
        return latest.containsKey(dataset);
    }

    /** Keeps the newest copy of a dataset. It only goes out if it changed and the account is linked. */
    synchronized void offer(String dataset, String json)
    {
        if (!DATASETS.contains(dataset))
        {
            return;
        }
        latest.put(dataset, json);
        if (!sha256(json).equals(sentHash.get(dataset)))
        {
            dirty.add(dataset);
        }
    }

    synchronized void tick()
    {
        ticks++;
        if (state == State.PENDING && ticks % POLL_TICKS == 0)
        {
            String secret = secret();
            if (secret != null)
            {
                checkStatus(secret);
            }
        }
        if (ticks % FLUSH_TICKS == 0)
        {
            flush(ticks % SLOW_FLUSH_TICKS == 0);
        }
    }

    /** Sends what changed. Item datasets wait for {@code withItems}, except on logout. */
    synchronized void flush(boolean withItems)
    {
        includeSlow = withItems;
        if (state == State.ACTIVE && !uploading && !dirty.isEmpty() && System.currentTimeMillis() >= retryAt)
        {
            sendNext();
        }
    }

    private String nextDataset()
    {
        for (String dataset : dirty)
        {
            if (includeSlow || !SLOW.contains(dataset))
            {
                return dataset;
            }
        }
        return null;
    }

    private void sendNext()
    {
        String secret = secret();
        String dataset = nextDataset();
        if (secret == null || dataset == null)
        {
            uploading = false;
            return;
        }
        String json = latest.get(dataset);
        if (json == null)
        {
            dirty.remove(dataset);
            sendNext();
            return;
        }
        uploading = true;
        enqueue(request("api/sync/upload/" + dataset, secret).post(RequestBody.create(JSON, json)).build(), new Reply()
        {
            @Override
            public void ok(String body)
            {
                synchronized (SyncClient.this)
                {
                    sentHash.put(dataset, sha256(json));
                    if (json.equals(latest.get(dataset)))
                    {
                        dirty.remove(dataset);
                    }
                    failures = 0;
                    sendNext();
                }
            }

            @Override
            public void failed(int code)
            {
                synchronized (SyncClient.this)
                {
                    uploading = false;
                    if (code == 401)
                    {
                        refresh();
                    }
                    else if (code == 400 || code == 404 || code == 413)
                    {
                        dirty.remove(dataset); // the server will never take this copy
                    }
                    else
                    {
                        failures++;
                        retryAt = System.currentTimeMillis() + Math.min(MAX_BACKOFF_MS, 30_000L << Math.min(failures, 8));
                    }
                }
            }
        });
    }

    /** Starts linking this character. The player approves the code on the website. */
    synchronized void pair()
    {
        HttpUrl url = base();
        if (url == null || accountHash == -1 || character == null)
        {
            setState(State.ERROR, url == null ? "Set an HTTPS server first" : "Log in first");
            return;
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        JsonObject body = new JsonObject();
        body.addProperty("secretHash", sha256(secret));
        body.addProperty("accountHash", Long.toString(accountHash));
        body.addProperty("character", character);
        enqueue(request("api/sync/pair", null).post(RequestBody.create(JSON, gson.toJson(body))).build(), new Reply()
        {
            @Override
            public void ok(String reply)
            {
                JsonObject out = gson.fromJson(reply, JsonObject.class);
                synchronized (SyncClient.this)
                {
                    store.set(secretKey(), secret);
                    setState(State.PENDING, "Code " + out.get("code").getAsString());
                }
                listener.openApproval(out.get("approveUrl").getAsString());
            }
        });
    }

    /** Ends this link for good. The server refuses the secret from now on, even if a copy survives elsewhere. */
    synchronized void revoke()
    {
        String secret = secret();
        if (secret == null)
        {
            return;
        }
        String key = secretKey();
        enqueue(request("api/sync/revoke", secret).post(RequestBody.create(JSON, "{}")).build(), new Reply()
        {
            @Override
            public void ok(String body)
            {
                synchronized (SyncClient.this)
                {
                    store.unset(key);
                    sentHash.clear();
                    setState(State.NOT_PAIRED, "Revoked");
                }
            }
        });
    }

    synchronized State state()
    {
        return state;
    }

    Set<String> pendingDatasets()
    {
        synchronized (this)
        {
            return Collections.unmodifiableSet(new LinkedHashSet<>(dirty));
        }
    }

    private Request.Builder request(String path, String secret)
    {
        Request.Builder b = new Request.Builder().url(base().newBuilder(path).build());
        return secret == null ? b : b.header("Authorization", "Bearer " + secret);
    }

    private void setState(State next, String detail)
    {
        state = next;
        listener.onSyncState(next, detail);
    }

    private abstract class Reply
    {
        abstract void ok(String body);

        void failed(int code)
        {
            synchronized (SyncClient.this)
            {
                setState(State.ERROR, code == 0 ? "Can't reach the server" : "Server said " + code);
            }
        }
    }

    private void enqueue(Request request, Reply reply)
    {
        http.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("sync request failed", e);
                reply.failed(0);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    if (r.isSuccessful())
                    {
                        reply.ok(r.body().string());
                    }
                    else
                    {
                        reply.failed(r.code());
                    }
                }
            }
        });
    }

    static String sha256(String text)
    {
        try
        {
            StringBuilder out = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
            {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
