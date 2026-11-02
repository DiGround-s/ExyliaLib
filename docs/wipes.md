# Wipes and player resets

One place where a server owner wipes data — a new season, every player's data,
one plugin, or one player — without knowing a single table name. Every table
says what it holds; the owner picks kinds. *Since 1.439.0.*

```java
// What a plugin writes: what each table holds, and whose row it is.
@Table(value = "homes", data = DataKind.PROGRESS)
public record Home(@Id String id, @Owner @Indexed @Column UUID player, @Column String name) { }

// Only when the plugin keeps a player's rows in memory beyond their session.
Wipes.of(this).onPlayerReset(uuid -> topList.evict(uuid));

// A menu button that resets a player: the library's flow, narrowed to this plugin.
Wipes.openPlayerReset(viewer, target.id(), target.name(), this);
```

No plugin keeps its own "wipe everything" or "reset everything of a player"
code: those commands are the library's.

## How to classify a table

Every `@Table` gets `data = DataKind.X`. Ask "what should a **new season** do to
this table?" and pick the kind whose wipe is *correct*.

| Kind | Meaning | Examples |
|---|---|---|
| `UNSPECIFIED` (default) | Not classified yet. **Never** touched by a preset or a player reset; only by a wipe that names the table or the whole plugin. | any table not annotated |
| `PROGRESS` | Per-player progress a new season starts over | homes, balances, stats, kit cooldowns, rank, missions, farming, ELO, kit layouts, playtime |
| `WORLD` | Server-wide state of the current playthrough (may have an owner) | project progress, the live season, market listings, auctions, claims, graves, placed spawners, supply-drop crates, clans |
| `ACCOUNT` | Per-player data that survives a season | purchased cosmetics and unlocks, crate keys bought, preferences and settings, punishments, 2FA, web links, referral links |
| `HISTORY` | Logs and history | match history, ledgers, market log, staff audit, replays, opening history, past seasons |
| `CACHE` | Rebuilt by itself | stat caches, cooldowns, seen markers, leases, outboxes, delivered queues |
| `SETUP` | Built by the admin | warps, kits, arenas, crate definitions, zones, shop products, currencies, mob templates, settings rows |

- **Both at once?** Pick the kind whose wipe is correct: rewards owed for a
  season being wiped are `PROGRESS`; purchases owed to a player are `ACCOUNT`.
- **`@Owner`** goes on the component that holds the player the row belongs to (a
  `UUID`, or its text form). It may be the `@Id`. One per record. Index it: a
  player reset is one `DELETE … WHERE owner = ?` per table.
- **No owner column?** Leave `@Owner` off: player resets skip the table and the
  screens say how many were skipped. If the player is only inside a composite id
  string (`uuid:kit`), add a real indexed owner column (backfilled on start or by
  a `Migration`) or leave it without `@Owner` and say so.
- **`onPlayerReset` hook:** only for a plugin that holds a player's rows in
  memory beyond their session (indexed on enable, a cached top list, clan member
  maps). It runs on every server of the network, on a background thread, after
  the rows are gone and while the player is still locked out. A plugin that loads
  on join and drops on quit needs nothing.
- **Whole wipes need no hook:** the emptied repositories are frozen (every write
  is dropped) and every server asks for a restart.

The library's own tables are classified the same way: `exylia_playtime`,
`exylia_pending_rewards`, `exylia_snapshots`, `exylia_server_commands` and the
escrow tables are `PROGRESS`; `exylia_crate_players`, `exylia_player_settings`,
`exylia_accounts` and `exylia_account_addresses` are `ACCOUNT`; `exylia_logs` is
`HISTORY`; `exylia_notices`, `exylia_network_cooldowns`, `exylia_row_locks` and
`exylia_ragdoll_skins` are `CACHE`; `exylia_account_keys` (the network's hashing
key) is `SETUP`.

## Presets

A wipe is a scope × kinds × plugins.

| Preset | Scope | Kinds on | Can be added |
|---|---|---|---|
| New season | every row, every plugin | PROGRESS, WORLD, CACHE | HISTORY, ACCOUNT, vanilla player files |
| Player data only | every row, every plugin | PROGRESS, CACHE | HISTORY, vanilla player files |
| One plugin | every row, one plugin | every kind, SETUP and UNSPECIFIED included | each one can be turned off |
| One player | rows whose `@Owner` is the player, every plugin | PROGRESS, WORLD, CACHE | ACCOUNT, HISTORY, vanilla player files |

`UNSPECIFIED` and `SETUP` are never part of the first two presets nor of a player
reset.

## In game: `/exylialib wipe`

Permission `exylialib.admin.wipe`. With no arguments a player gets the screens,
the console a help panel.

1. **Home** — the four ways in.
2. **Plugins** (paginated) — one card per plugin with tables in scope: rows that
   go, kind by kind (counted in the background; "counting…" until known), and the
   tables kept. Left-click leaves a plugin out; right-click lists its tables with
   their kind, rows and owner column. Bottom row: **What is deleted** (the kinds),
   **Vanilla player files** (when the preset allows it), page arrows, **Back**,
   **Wipe**.
3. **Confirm** — a whole wipe asks to type `WIPE`; a player reset asks a
   dangerous confirmation.
4. **One plugin** opens a plugin picker, then the kinds with all of them on.
   **One player** asks a name and opens the plugins screen scoped to owner tables.
5. The result is a chat panel: rows removed per plugin and kind, the backups and
   how to restore them, and the restart notice. Kicks come after it.

## Commands

```
/exylialib wipe <plugin|all> <table|@kind|@preset|all> [code]
/exylialib resetplayer <player> [plugin|all] [@kind ...] [code]
```

- Presets `@season`, `@players`; kinds `@progress @world @account @history
  @cache @setup @unspecified`. `all all` is every table of every plugin.
  `<plugin> <table|all>` keeps working exactly as before (see
  [reload.md](reload.md)).
- The first run counts and prints what would go plus a six-digit code; the same
  line with the code runs it, once, within 60 seconds, for that sender only. A
  code issued for one request never confirms another.
- `resetplayer` defaults to the One player kinds; `@setup` and `@unspecified`
  are refused.

## What happens when it runs

- **Every table is found first.** Each Exylia plugin's jar is scanned for
  `@Table` records (class bytes searched for the annotation, then loaded without
  initialising) and their repositories registered, so a table a plugin only
  creates on first use is wiped too. One physical table registered by several
  plugins is counted and deleted once; two records that disagree on its kind
  leave it `UNSPECIFIED`, with a warning. A library table that names its plugin
  in a `plugin` column (logs, notices, crate players, escrow, pending rewards) is
  wiped per plugin: only that plugin's rows go.
- **Backup first, always.** Each affected plugin is exported into
  `plugins/ExyliaLib/dumps/`; any failure cancels everything before the first
  delete. Restore with `/exylialib import <plugin> <file> true`.
- **Whole wipes** freeze the tables on every server of the network (over the
  library's `wipes` channel), delete, then lock: players without
  `exylialib.admin.wipe` are kicked and refused until the server restarts.
  Restart every server.
- **Player resets** are refused while the player is online on another server.
  The player is kicked and locked out network-wide, a few seconds pass so quit
  saves land, their rows are deleted (the shared cache is dropped), every
  server runs the `onPlayerReset` hooks and, when asked, deletes their vanilla
  files; then they may join again. No restart.
- **Vanilla player files** (`playerdata`, `stats`, `advancements` of the main
  world): a whole wipe leaves a marker and the next ExyliaLib start, before any
  world loads, deletes them; a player reset deletes that player's files at once
  on every server.
- **Staff log:** `data-wipe` and `player-reset` entries in ExyliaLib's log
  (`/exylialib logs`).

## API

| Member | |
|---|---|
| `DataKind` (`net.exylia.lib.database`) | the seven kinds above |
| `@Table(value, data = DataKind.X)` | what the table holds; `UNSPECIFIED` by default |
| `@Owner` (`net.exylia.lib.database`) | the component holding the row's player |
| `Wipes.of(plugin).onPlayerReset(Consumer<UUID>)` (`net.exylia.lib.database.wipe`) | forget a reset player's cached rows; every server, background thread; released on disable |
| `Wipes.openPlayerReset(Player viewer, UUID target, String name, @Nullable Plugin only)` | open the library's player reset, scoped to one plugin when given; the viewer needs `exylialib.admin.wipe` |

## Where the code is

`database/DataKind`, `database/Owner`, `database/Table#data`,
`database/wipe/Wipes`; `database/wipe/internal/` (`WipeRuntime` catalog, jar
scan, backup, delete, channel, login gate, vanilla files; `WipeRequest` presets
and selection; `WipeTable`; `WipePanels`; `WipeMenu`). Commands in
`internal/ReloadCommand` (`wipe`, `wipeMenu`, `resetPlayer`, `ResetArguments`)
on top of `internal/TransferAccess`. Tests: `WipeTest`, `ReloadCommandTest`.
