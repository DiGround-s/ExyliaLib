# ExyliaLib documentation

One file per module. Each documents the public API, its contracts (threads,
lifecycle, performance) and where the code lives. Write and update these
against the source — rules and routes are in `AGENTS.md` under
*Mapa del proyecto y documentación*.

| Module | What it is for | Since |
| --- | --- | --- |
| [Task](task.md) | Scheduling that runs on Spigot, Paper and Folia from one call | 1.0.0 |
| [Config](config.md) | YAML files declared and read as Java records | 1.1.0 |
| [Text](text.md) | Every player-facing string, into Adventure components — plus inline effect tags and centring | 1.2.0 |
| [Placeholders](placeholders.md) | One registry for `%placeholders%`, with or without PlaceholderAPI | 1.3.0 |
| [Effects](effects.md) | Titles, action bars, boss bars, sounds, particles, fireworks — from config | 1.4.0 |
| [Scoreboard](scoreboard.md) | Packet sidebars declared in config | 1.5.0 |
| [Hologram](hologram.md) | Packet holograms declared in config | 1.6.0 |
| [Client](client.md) | Lunar/Feather waypoints, client cooldowns, teammate markers | 1.7.0 |
| [Clan](clan.md) | One API over SimpleClans, Kingdoms, UltimateClans and external bridges | 1.8.0 |
| [Cooldowns](cooldowns.md) | The base every cooldown in the ecosystem sits on | 1.10.0 |
| [Utilities](util.md) | `util.Effects` (potions from strings) and `TimeFormats` | 1.9.0 |
| [Debug](debug.md) | Coloured console output: log, success, warn, error, debug — and the banner; server-wide switch since 1.27.0 | 1.13.0 |
| [Reloading](reload.md) | `Reloads` steps, library-reload listeners, and `/exylialib reload` | 1.14.0 |
| [Skulls](skulls.md) | Player heads from base64, a URL or a name — cached, shared and never blocking | 1.19.0 |
| [Actions](actions.md) | Compiled, namespaced actions shared by menus, items and other event boundaries | 1.20.0 |
| [Items](items.md) | Items described in configuration — menu icons, special items, kits, shields — read once and drawn per player | 1.22.0 |
| [Menus](menus.md) | Menus written in configuration: paginated lists, reactive redrawing, clicks bound to actions | 1.22.0 |
| [Regions](regions.md) | Areas of the world: shapes, an immutable spatial index, overlapping policies, enter/exit events, selection and outlines | 1.23.0 |
| [Database](database.md) | Records stored in H2, MySQL, MariaDB, PostgreSQL or MongoDB — one pool for the server, no reflection per row, no blocking calls | 1.24.0 |
| [Formats](formats.md) | Numbers, money, percentages and dates a player reads — and amounts a player types, such as `10M` | 1.25.0 |
| [Economy](economy.md) | Balances, charges and transfers over Vault, PlayerPoints or a currency you write — one economy choice for the whole server | 1.26.0 |
| [Input](input.md) | Asking a player for something: text, numbers, amounts, choices, a searchable registry, or a whole form in one window | 1.31.0 |
| [Sequences](sequences.md) | Choreographed effects from configuration: shapes, sounds, delays — the ExyliaCommons syntax, compiled once | 1.30.0 |
| [Previews](previews.md) | Showing one player an effect against an empty sky, and putting them back | 1.30.0 |
| [Redis](redis.md) | A shared cache that makes one database look the same from every server — a change on one is visible on the others immediately | 1.31.0 |
| [Rewards](rewards.md) | What a player earned — items, commands, money, odds and conditions — stored exactly as ExyliaCommons stored it | 1.34.0 |
| [Snapshots](snapshots.md) | A player's state kept for later — in memory for a menu, or stored so it survives a restart | 1.34.0 |
| [Teleport](teleport.md) | Moving a player: countdowns that moving or damage calls off, safe landings, `/back`, requests, random spots and handovers to another server | 1.34.0 |
| [Wizards](wizard.md) | Walking a player through several questions — branches, a review they can go back from, and nothing applied until they confirm | 1.34.0 |

Root classes that are not a module:

- `net.exylia.lib.ExyliaLib` — the plugin itself; only lifecycle and cleanup.
- `net.exylia.lib.platform.Platform` — `current()`, `isFolia()`, `isPaper()`.
- `net.exylia.lib.internal.LibrarySettings`, `internal.ExyliaLibUpdater` —
  internal, free to change without notice.
