# ExyliaLib documentation

One file per module. Each documents the public API, its contracts (threads,
lifecycle, performance) and where the code lives. Write and update these
against the source — rules and routes are in `AGENTS.md` under
*Mapa del proyecto y documentación*.

| Module | What it is for | Since |
| --- | --- | --- |
| [Task](task.md) | Scheduling that runs on Spigot, Paper and Folia from one call | 1.0.0 |
| [Config](config.md) | YAML files declared and read as Java records | 1.1.0 |
| [Text](text.md) | Every player-facing string, into Adventure components | 1.2.0 |
| [Placeholders](placeholders.md) | One registry for `%placeholders%`, with or without PlaceholderAPI | 1.3.0 |
| [Effects](effects.md) | Titles, action bars, boss bars, sounds, particles, fireworks — from config | 1.4.0 |
| [Scoreboard](scoreboard.md) | Packet sidebars declared in config | 1.5.0 |
| [Hologram](hologram.md) | Packet holograms declared in config | 1.6.0 |
| [Client](client.md) | Lunar/Feather waypoints, client cooldowns, teammate markers | 1.7.0 |
| [Clan](clan.md) | One API over SimpleClans, Kingdoms, UltimateClans and external bridges | 1.8.0 |
| [Cooldowns](cooldowns.md) | The base every cooldown in the ecosystem sits on | 1.10.0 |
| [Utilities](util.md) | `util.Effects` (potions from strings) and `TimeFormats` | 1.9.0 |
| [Debug](debug.md) | Coloured console output: log, success, warn, error, debug — and the banner | 1.13.0 |
| [Reloading](reload.md) | `Reloads` steps, library-reload listeners, and `/exylialib reload` | 1.14.0 |

Root classes that are not a module:

- `net.exylia.lib.ExyliaLib` — the plugin itself; only lifecycle and cleanup.
- `net.exylia.lib.platform.Platform` — `current()`, `isFolia()`, `isPaper()`.
- `net.exylia.lib.internal.LibrarySettings`, `internal.ExyliaLibUpdater` —
  internal, free to change without notice.
