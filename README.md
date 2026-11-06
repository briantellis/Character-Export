# OSRS Intelligence

A RuneLite plugin that saves your character as local JSON files and, if you link it, syncs them to an HTTPS server you choose, such as a planner that works out quests, skills, gear and goals from what your account really has.

Sync is off by default. Nothing leaves your computer until you turn it on and approve the link on the website.

Based on [Character Export](https://github.com/DZWNK/Character-Export) by DZWNK (BSD 2-Clause). The file formats match, so tools that read Character Export files read these too.

## What it saves

| File | When | Contents |
|------|------|----------|
| `character.json` | Login, stat change | Skills (level, boosted level, XP), world, world types, account type |
| `quests.json` | Login, varbit change | Every quest's state: not started, in progress or finished |
| `diaries.json` | Login, varbit change, diary journal open | 12 areas × 4 tiers: completion, tasks done, named tasks |
| `combat_achievements.json` | Login, varbit change | Every combat task with its completion, by tier |
| `account.json` | Login, varbit change, new kill count | Quest points, combat level, point balances (Slayer, Nightmare Zone, Tithe Farm, Soul Wars, Giants' Foundry), the current Slayer task, kill counts and personal bests, pets, Rigour/Augury/Preserve unlocks, daily claims, Kingdom of Miscellania, rune pouch, collection log totals |
| `collection_log.json` | Collection log page viewed | Items per entry, built up as you browse |
| `bank.json` | Bank opened | Bank contents |
| `seed_vault.json` | Seed vault opened | Seed vault contents |
| `looting_bag.json` | Looting bag checked | Looting bag contents |
| `inventory.json` | Inventory changed | Carried items |
| `equipment.json` | Equipment changed | Worn items |
| `grand_exchange.json` | Login, offer changed, trade completed | Your offers (item, price, quantity, progress) and completed trades from RuneLite's Grand Exchange plugin (buy or sell, item, quantity, average price, time; up to a year) |

Every file has its own toggle in the plugin settings.

Kill counts, personal bests and pets come from RuneLite's built-in Chat Commands plugin, which records them from game messages. Completed trades come from RuneLite's built-in Grand Exchange plugin. With either plugin off, its lists stay empty.

Files are written under the RuneLite directory, one folder per account:

```
.runelite/osrs-intelligence/<Character Name>/
```

## Sync

1. In the plugin settings, turn on **Sync to a server** and enter the server's HTTPS address. There is no default.
2. Log in and press **Link this character** in the sidebar.
3. Sign in on that website and approve the code it shows.

While linked, the plugin sends a dataset only when it has changed. Skills, quests, diaries, combat tasks and account details go at most once a minute. Bank, seed vault, inventory, equipment, looting bag and Grand Exchange offers go at most every five minutes, plus once when you log out. The collection log stays local.

The plugin generates the link secret itself. Only its hash is sent when pairing, and afterwards the secret is only ever sent, as a bearer token, to the server it was paired with. Each server and account gets its own secret. **Revoke link** in the sidebar revokes it on the server and forgets it. Plain HTTP is refused.

It never sends your location, other players, chat, or anything about the game client beyond what's listed above.

### Server routes

A server that wants to receive data implements these routes. Each takes and returns JSON.

| Route | Purpose |
|---|---|
| `POST /api/sync/pair` | Starts pairing with `secretHash`, `accountHash` and `character`. Returns `code` and `approveUrl`. |
| `GET /api/sync/status` | Returns `state`: `pending`, `active`, `revoked`, `expired` or `unknown`. |
| `POST /api/sync/upload/<dataset>` | Stores one dataset. The dataset name is the file name without `.json`. |
| `POST /api/sync/revoke` | Ends the link permanently. |

## Sidebar

- The account and its output folder
- Each dataset's last save, or what to do to fill it
- **Sync Available** saves everything the client has in memory now
- **Open Folder** and **Open File** open the exports
- Link status, **Link this character** and **Revoke link**

## Debug logging

Turn on **Debug logging** to also write `exporter.log`, `status.json` and `recent_events.json` to `.runelite/osrs-intelligence/`. It takes effect immediately.

## Safety

The plugin reads game state through the standard RuneLite API and the client's injected HTTP client. It does not automate input, read memory, intercept packets, or write outside the RuneLite directory.

## Building

Requires JDK 11.

```bash
./gradlew build    # compile and test
./gradlew run      # launch RuneLite in developer mode with the plugin loaded
```

## Support

[github.com/briantellis/Character-Export/issues](https://github.com/briantellis/Character-Export/issues)
