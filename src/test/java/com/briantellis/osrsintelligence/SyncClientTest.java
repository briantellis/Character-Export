package com.briantellis.osrsintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SyncClientTest
{
    private HttpServer server;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> auths = Collections.synchronizedList(new ArrayList<>());
    private volatile String remote = "pending";
    private final Map<String, String> secrets = Collections.synchronizedMap(new HashMap<>());
    private final List<String> opened = Collections.synchronizedList(new ArrayList<>());
    private volatile int paired;
    private volatile String url;
    private SyncClient client;

    @Before
    public void start() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", (x) ->
        {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(x.getRequestMethod() + " " + x.getRequestURI().getPath());
            bodies.add(body);
            auths.add(String.valueOf(x.getRequestHeaders().getFirst("Authorization")));
            String path = x.getRequestURI().getPath();
            String reply = path.endsWith("/pair") ? "{\"code\":\"ABCD-EFGH\",\"approveUrl\":\"https://site/?link=ABCD-EFGH\"}" : path.endsWith("/status") ? "{\"state\":\"" + remote + "\"}" : "{}";
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, out.length);
            x.getResponseBody().write(out);
            x.close();
        });
        server.start();
        url = "http://localhost:" + server.getAddress().getPort() + "/";
        client = make(true);
    }

    private SyncClient make(boolean allowLocal)
    {
        return new SyncClient(new OkHttpClient(), new Gson(), new SyncClient.SecretStore()
        {
            @Override
            public String get(String key)
            {
                return secrets.get(key);
            }

            @Override
            public void set(String key, String value)
            {
                secrets.put(key, value);
            }

            @Override
            public void unset(String key)
            {
                secrets.remove(key);
            }
        }, new SyncClient.Listener()
        {
            @Override
            public void onSyncState(SyncClient.State state, String detail)
            {
            }

            @Override
            public void openApproval(String u)
            {
                opened.add(u);
            }

            @Override
            public void onPaired()
            {
                paired++;
            }
        }, () -> url, allowLocal);
    }

    @After
    public void stop()
    {
        server.stop(0);
    }

    private static void await(BooleanSupplier done) throws Exception
    {
        for (int i = 0; i < 200 && !done.getAsBoolean(); i++)
        {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(done.getAsBoolean());
    }

    private void link() throws Exception
    {
        client.setAccount(42L, "Test Player");
        await(() -> client.state() == SyncClient.State.NOT_PAIRED);
        client.pair();
        await(() -> client.state() == SyncClient.State.PENDING);
        remote = "active";
        for (int i = 0; i < 8; i++)
        {
            client.tick();
        }
        await(() -> client.state() == SyncClient.State.ACTIVE);
    }

    @Test
    public void nothingIsSentBeforeTheLinkIsApproved() throws Exception
    {
        client.setAccount(42L, "Test Player");
        await(() -> client.state() == SyncClient.State.NOT_PAIRED);
        client.offer("bank", "{\"items\":[]}");
        for (int i = 0; i < 300; i++)
        {
            client.tick();
        }
        client.flush(true);
        assertTrue(calls.stream().noneMatch(c -> c.contains("upload")));
    }

    @Test
    public void pairingSendsOnlyTheHashOfTheSecret() throws Exception
    {
        link();
        String secret = secrets.values().iterator().next();
        JsonObject pair = new Gson().fromJson(bodies.get(calls.indexOf("POST /api/sync/pair")), JsonObject.class);
        assertEquals(SyncClient.sha256(secret), pair.get("secretHash").getAsString());
        assertFalse(bodies.toString().contains(secret));
        assertEquals("https://site/?link=ABCD-EFGH", opened.get(0));
        assertEquals(1, paired);
    }

    @Test
    public void uploadsOnlyAllowedDatasetsAndOnlyWhenChanged() throws Exception
    {
        link();
        client.offer("bank", "{\"a\":1}");
        client.offer("collection_log", "{}");
        client.flush(true);
        await(() -> calls.contains("POST /api/sync/upload/bank") && client.pendingDatasets().isEmpty());
        assertFalse(calls.contains("POST /api/sync/upload/collection_log"));
        String secret = secrets.values().iterator().next();
        assertEquals("Bearer " + secret, auths.get(calls.indexOf("POST /api/sync/upload/bank")));
        client.offer("bank", "{\"a\":1}");
        assertTrue(client.pendingDatasets().isEmpty());
    }

    @Test
    public void itemsWaitForTheSlowerFlushButProgressDoesNot() throws Exception
    {
        link();
        client.offer("bank", "{\"a\":1}");
        client.offer("account", "{\"quest_points\":10}");
        client.flush(false);
        await(() -> calls.contains("POST /api/sync/upload/account"));
        assertFalse(calls.contains("POST /api/sync/upload/bank"));
        assertTrue(client.pendingDatasets().contains("bank"));
        client.flush(true);
        await(() -> calls.contains("POST /api/sync/upload/bank"));
    }

    @Test
    public void knowsWhichDatasetsItHoldsForThisAccount() throws Exception
    {
        link();
        assertFalse(client.has("quests"));
        client.offer("quests", "{}");
        assertTrue(client.has("quests"));
        client.setAccount(7L, "Other");
        assertFalse(client.has("quests"));
    }

    @Test
    public void revokingForgetsTheSecret() throws Exception
    {
        link();
        client.revoke();
        await(() -> client.state() == SyncClient.State.NOT_PAIRED);
        assertTrue(secrets.isEmpty());
        assertTrue(calls.contains("POST /api/sync/revoke"));
    }

    @Test
    public void aSecretIsNeverSentToADifferentServer() throws Exception
    {
        link();
        url = "http://localhost:1/";
        assertNotNull(client.base());
        client.offer("bank", "{\"b\":2}");
        client.flush(true);
        assertNull(secrets.get("sync.localhost.1.42"));
    }

    /** Runs real exports through a real server when SYNC_E2E_URL and SYNC_E2E_DIR are set. */
    @Test
    public void uploadsRealExportsToARealServer() throws Exception
    {
        String target = System.getenv("SYNC_E2E_URL");
        org.junit.Assume.assumeNotNull(target);
        url = target;
        client = make(true);
        client.setAccount(987654321L, "Test Player");
        await(() -> client.state() == SyncClient.State.NOT_PAIRED);
        client.pair();
        await(() -> client.state() == SyncClient.State.PENDING);
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("SYNC_E2E_DIR"), "code.txt"), opened.get(0));
        for (int i = 0; i < 300 && client.state() != SyncClient.State.ACTIVE; i++)
        {
            client.tick();
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertEquals(SyncClient.State.ACTIVE, client.state());
        for (String name : new String[] {"character", "quests", "diaries", "combat_achievements", "account", "bank", "inventory", "equipment"})
        {
            java.nio.file.Path file = java.nio.file.Path.of(System.getenv("SYNC_E2E_DIR"), name + ".json");
            if (java.nio.file.Files.exists(file))
            {
                client.offer(name, java.nio.file.Files.readString(file));
            }
        }
        client.flush(true);
        await(() -> client.pendingDatasets().isEmpty());
        assertEquals(SyncClient.State.ACTIVE, client.state());
    }

    @Test
    public void plainHttpIsRefused()
    {
        url = "http://example.com/";
        assertNull(client.base());
        url = "https://example.com/";
        assertNotNull(client.base());
        url = "http://localhost:8080/";
        assertNull(make(false).base());
    }
}
