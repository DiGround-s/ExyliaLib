# ExyliaLib documentation

One file per module. Each documents the public API, its contracts (threads,
lifecycle, performance) and where the code lives. Write and update these
against the source — rules and routes are in `AGENTS.md` under
*Documentation and path map*.

| Module | What it is for | Since |
| --- | --- | --- |
| [Task](task.md) | Scheduling that runs on Spigot, Paper and Folia from one call | 1.0.0 |
| [Config](config.md) | YAML files declared and read as Java records | 1.1.0 |
| [Text](text.md) | Every player-facing string, into Adventure components — plus inline effect tags and centring | 1.2.0 |
| [Placeholders](placeholders.md) | One registry for `%placeholders%`, with or without PlaceholderAPI | 1.3.0 |
| [Effects](effects.md) | Titles, action bars, boss bars, sounds, particles, fireworks — from config | 1.4.0 |
| [Action bars](actionbars.md) | One action bar line per player shared by every plugin: segments in order, sent only when they change | 1.323.0 |
| [Scoreboard](scoreboard.md) | Packet sidebars declared in config | 1.5.0 |
| [Hologram](hologram.md) | Packet holograms declared in config | 1.6.0 |
| [Client](client.md) | Lunar/Feather waypoints, client cooldowns, teammate markers and teams; owned per plugin since 1.48.0 | 1.7.0 |
| [Clan](clan.md) | One API over eight clan plugins and external bridges | 1.8.0 |
| [Cooldowns](cooldowns.md) | The base every cooldown in the ecosystem sits on; `NetworkCooldowns` that survive relogs and server hops since 1.184.0 | 1.10.0 |
| [Utilities](util.md) | `util.Effects` (potions from strings), `TimeFormats`, `Expressions`, `Weighted`, `PlayerStates` (god mode and vanish over EssentialsX and CMI, 1.332.0) and `PvpZones` (is PvP refused here, over WorldGuard, ExyliaLib regions, Lands, GriefPrevention, Towny and SuperiorSkyblock2, 1.335.0); `Totp` (authenticator codes) and `QrCodes` (QR codes on a map) since 1.352.0 | 1.9.0 |
| [Debug](debug.md) | Coloured console output: log, success, warn, error, debug — and the banner; server-wide switch since 1.27.0, gradient name and type labels since 1.35.0 | 1.13.0 |
| [Reloading](reload.md) | `Reloads` steps, library-reload listeners, and `/exylialib` (`reload`, `info`, `stats`) | 1.14.0 |
| [Skulls](skulls.md) | Player heads from base64, a URL or a name — cached, shared and never blocking | 1.19.0 |
| [Server commands](server-commands.md) | Commands run for a player on a given server of the network, now or on their next join there | 1.337.0 |
| [Runtime commands](runtime-commands.md) | Commands named by a plugin's data (`/blocks` for a shop, `/gems` for a currency), installed into the command map and replaced as a set | 1.333.0 |
| [Interactions](interactions.md) | What clicking something in the world does: lines with click filters, commands, effect tokens, `[SERVER]`, `[TELEPORT]`, `[ACTION]`, `[DELAY]` and tokens a plugin registers | 1.291.0 |
| [Actions](actions.md) | Compiled, namespaced actions shared by menus, items and other event boundaries | 1.20.0 |
| [Items](items.md) | Items described in configuration — menu icons, special items, kits, shields — read once and drawn per player; values stored on live items since 1.63.0; Nexo, Oraxen, ItemsAdder and CraftEngine items by id since 1.328.0 | 1.22.0 |
| [Menus](menus.md) | Menus written in configuration: paginated lists, reactive redrawing, clicks bound to actions | 1.22.0 |
| [Regions](regions.md) | Areas of the world: shapes, an immutable spatial index, overlapping policies, enter/exit events, selection and outlines | 1.23.0 |
| [Database](database.md) | Records stored in H2, MySQL, MariaDB, PostgreSQL or MongoDB — one pool for the server, no reflection per row, no blocking calls; atomic counters, compare-and-set and cross-server `RowLocks` since 1.163.0 (renewed leases since 1.167.0, `findFresh` for reads inside a hold since 1.319.0) | 1.24.0 |
| [Formats](formats.md) | Numbers, money, percentages and dates a player reads — and amounts a player types, such as `10M` | 1.25.0 |
| [Economy](economy.md) | Balances, charges and transfers over Vault, PlayerPoints or a currency you write — one economy choice for the whole server; item and experience currencies since 1.163.0 | 1.26.0 |
| [Input](input.md) | Asking a player for something: text, numbers, amounts, choices, a searchable registry, or a whole form in one window | 1.31.0 |
| [Tuner](tuner.md) | Fine adjustment with the mouse wheel: named axes, steps, snap, a boss-bar HUD and a live preview, saved or put back at the end | 1.298.0 |
| [NPCs](npcs.md) | Player-shaped entities that exist only on a client — a body where somebody died, a statue, a double | 1.88.2 |
| [Ragdolls](ragdolls.md) | Bodies that come apart into their own pieces, each one solved in advance and drawn by the client | 1.120.0 |
| [Models](models.md) | 3D models made in Blockbench worn by living entities, with no resource pack: bones, state animations with blending and events, textured heads, own hitboxes, distance tiers and a server ceiling, death animations; feet on the ground, cubes that bounce apart, casts fitted to wind-ups, fight phases, tweaks and their editor (1.321.0) | 1.317.0 |
| [Replays](replays.md) | Recording what happened and watching it again — the server's own state, sampled every tick, played back as packet bodies on the screens that asked for it, arena and debris included | 1.175.0 |
| [Cameras](cameras.md) | Showing a player themselves from outside — a shot solved in advance, drawn by the client, and always given back | 1.172.0 |
| [Displays](displays.md) | Item, block and text displays that move, spin and fall by themselves — packet-only, animated by the client; restyled, retargeted from their pose on screen and shown to a tracked audience while they play since 1.272.0 | 1.85.0 |
| [VFX](vfx.md) | Effects built in Java and played as one: a timeline of displays, sounds, particles and screen shake, ground telegraphs, floating damage numbers, chained motions and flat ragdoll skins | 1.197.0 |
| [Sequences](sequences.md) | Choreographed effects from configuration: shapes, sounds, delays — the ExyliaCommons syntax, compiled once; effects with odds, conditions and an audience since 1.57.0 | 1.30.0 |
| [Previews](previews.md) | Showing one player an effect on a configured stage, and putting them back | 1.30.0 |
| [Showcases](showcases.md) | Places that show a plugin's cosmetics off on a loop to whoever stands near — the places, the pacing and the cleanup shared, the turn left to the plugin | 1.188.0 |
| [Crates](crates.md) | A crate a plugin gets by handing over its catalogue — the roll, the keys, the reels, the payout, the blocks and the player rows shared, the rewards left to the plugin; importing a plugin's own crate data, legacy key items, placeholder aliases and item icons since 1.190.0; a shared admin menu since 1.342.0 | 1.189.0 |
| [Redis](redis.md) | A shared cache that makes one database look the same from every server — a change on one is visible on the others immediately | 1.31.0 |
| [Proxy](proxy.md) | Talking to the proxy: `player-proxy:`/`console-proxy:` lines and `Proxy.request` reach ExyliaProxyUtils on Velocity or BungeeCord, and every request is answered | 1.101.0 |
| [Network](network.md) | Messages from a plugin to itself on one other server or on a player's server, and the wait for players arriving here for a match | 1.347.0 |
| [Players](players.md) | A player who is not here: one `ExyliaPlayer` over online, offline and network players, and the command arguments that resolve them without blocking; `Accounts` (same person? first seen) since 1.184.0 | 1.146.0 |
| [Playtime](playtime.md) | How long a player has actually played on the whole network: one tracker per server shared by every plugin, idle time dropped, a once-a-minute hook for milestones | 1.341.0 |
| [Rewards](rewards.md) | What a player earned — items, commands, money, odds and conditions — stored exactly as ExyliaCommons stored it; items thrown out at a spot with `giveDropping` since 1.191.0 | 1.34.0 |
| [Snapshots](snapshots.md) | A player's state kept for later — in memory for a menu, or stored so it survives a restart; restores this server's snapshots only, on request, since 1.350.0 | 1.34.0 |
| [Stays](stays.md) | A player kept in an arena or on a board and given back exactly as they came — claim, snapshot, teleport and restore in the one order that works; `DeathDrops` (whose a death's drops are) since 1.322.0; join recovery holds the player until it lands and `recoverLocal` for networks since 1.350.0 | 1.292.0 |
| [Teleport](teleport.md) | Moving a player: countdowns that moving or damage calls off, safe landings, `/back`, requests, random spots and handovers to another server | 1.34.0 |
| [WorldGuard](worldguard.md) | Region lookups and Exylia's own region flags — `kill-effects`, `hit-effects`, `arrows-effects` | 1.74.0 |
| [Wizards](wizard.md) | Walking a player through several questions — branches, a review they can go back from, and nothing applied until they confirm | 1.34.0 |
| [Nametags](nametags.md) | Name colour, glow and collision as one player sees another — vanilla clients included | 1.36.0 |
| [Packets](packets.md) | Vanish, drawing invisible players, fake blocks, freezing, a fake spectator, silent chest viewing, per-player world borders, lines under items, chest lids and maps held only on screen — packets to one client, nothing on the server | 1.75.0 (`WorldBorders` 1.162.0, `ItemLines` 1.203.0, `ChestLids` 1.272.0, `HeldMaps` 1.352.0) |
| [Overlays](overlays.md) | Items in a player's own inventory that the server does not have — a staff hotbar a crash cannot leave behind | 1.79.0 |
| [Combat](combat.md) | Whether a player is in combat, over DeluxeCombat, PvPManager or a bridge you write | 1.36.0 |
| [World](world.md) | Creating and deleting worlds through the Worlds plugin, from any thread — and doing nothing loudly when it is absent | 1.36.0 |
| [Transfer](transfer.md) | A plugin's whole database out to one file and back — moving from H2 to MySQL, or onto another server; and wiping tables (1.76.0) | 1.36.0 |
| [Schematics](schematics.md) | Saving a box of the world and pasting it back — arenas regenerated between matches, with their chests still full; FastAsyncWorldEdit, or a built-in engine where it cannot run | 1.48.0 |
| [Arenas](arenas.md) | Arenas handed out one match at a time: the original when it is free, else a copy pasted into the temporary world, regenerated and reused on release | 1.346.0 |
| [Mobs](mobs.md) | Custom mobs from a template — name, equipment, attributes, flags, effects and skills — spawned anywhere, with who hurt them reported on death; hits mode, lifetime, roam, looks and auras since 1.195.0; staged casts, aims, conditions, rotation groups, chains and fight phases since 1.198.0; reactions since 1.199.0; the skill library, styles and five more skill types since 1.200.0; model mobs whose vanilla carrier never shows (1.324.0), with a body, mind and attacks of their own (1.326.0); storage and payouts stay with the plugin | 1.192.0 |
| [Loot](loot.md) | What comes out of a chest, a spawner or a broken block — the ExyliaCommons tables, read and written unchanged; importing a chest (1.77.0); money lines (1.204.0) | 1.56.0 |
| [Editors](editors.md) | Screens for editing the lists a plugin configures: rewards, loot, commands, effects, items, places — one engine, batteries included; an inventory-shaped loadout editor since 1.110.0; a region policy editor since 1.202.0 | 1.56.0 |
| [Settings](settings.md) | Player preferences every plugin registers into, announcement channels players can mute, and the one `/settings` screen (off unless the server enables the command) | 1.261.0 |
| [Modifiers](modifiers.md) | One multiplier pipeline for what players earn — money, experience, drops and a plugin's own types — fed by booster plugins, ranks by permission and per-place boosts | 1.263.0 |
| [Prices](prices.md) | What an item sells for, whoever buys it back — shops and worth lists answer by priority, any plugin asks; boosting stays with the payer | 1.264.0 |
| [Toasts](toasts.md) | The advancement popup in the corner — packets only, nothing kept, a configurable fallback without PacketEvents; `[TOAST]` in sequences | 1.278.0 |
| [Notices](notices.md) | Messages left for a player: shown now if they are here, across the network if they are elsewhere, as a grouped summary on their next join otherwise — taken exactly once | 1.278.0 |
| [Escrow](escrow.md) | Money and items held while a match is played — written before anything moves, settled or refunded exactly once, refunded after a crash, owed rows delivered on join or respawn; `giveItems` for items that belong to nobody's match since 1.349.0; the player file saved as items move since 1.350.0 | 1.345.0 |
| [Trades](trades.md) | A two-player trade window: both put items in, both see both offers live, both confirm, a countdown any change resets — closing, leaving, dying or a stop gives everything back; refuses players another plugin holds since 1.350.0 | 1.349.0 |
| [Logs](logs.md) | A staff audit log every plugin records into, browsed with /exylialib logs | 1.296.0 |
| [Schedules](schedules.md) | Timetables: what starts by itself, at what times, on what days, and under what conditions — one asynchronous timer for the whole server | 1.70.0 |
| [Chat](chat.md) | Who reads whose chat messages: an event, an arena or a match whose chat is its own | 1.89.0 |
| [Cleanup](cleanup.md) | The folders the server fills and never empties — old logs deleted on a timer | 1.90.0 |
| [Heads](heads.md) | The decorative head catalogue, searchable from a menu — a page at a time, nothing downloaded | 1.82.0 |
| [Metrics](metrics.md) | What the library reports to stats.exylia.net — server software, installed plugins, Exylia plugin setup and errors — how a plugin describes its setup, and how to turn it off | 1.160.0 (`Metrics.describe` 1.163.0) |
| [Blocks](blocks.md) | Blocks in the world that answer clicks instead of behaving like their material — a crate, a shop, a warp pad — protected and registered by whoever placed them; whether a player placed a block (`PlacedBlocks`) since 1.163.0 | 1.110.0 |
| [Discord webhooks](discord.md) | Messages to Discord webhooks written in config — Components V2 cards with link buttons, pings only from the template, escaped values, rate limits, dead-webhook detection, and asking a player for a URL | 1.245.0 |
| [Modules](modules.md) | Switchable parts of a plugin — `PluginModule` and `PluginModules`: per-module files, listeners, actions, menus, commands and timers, switched in config, with modules that require others | 1.276.0 |
| [Web editor](web-editor.md) | Every plugin file edited from the browser: config files, messages, menus and other YAML discovered on their own (with `exclude`/`discover` to opt out), plus registered subjects (records, YAML a plugin compiles itself, stores of entries such as mines), uploaded plugin by plugin gzip-compressed; changes pulled by a command and confirmed when they matter | 1.304.0 (discovery and protocol v2 1.307.0) |
| [Public API](api.md) | The contract third-party plugins write against: one published artifact covering every Exylia plugin, reached through `ExyliaAPI` | 1.112.0 |
| [Custom minigames](custom-minigames.md) | Registering a minigame of your own with ExyliaEvents: what you declare, what it does for you | 1.186.0 |
Migration notes:

- [Migrating ExyliaFFA](migration-ffa.md) — what FFA needs, and the four things
  that change behaviour rather than moving.
- [Migrating ExyliaCapture](migration-capture.md) — the full module audit, the
  three things the library does not have, and the zone column that must keep its
  exact JSON.
- [Migrating ExyliaPracticeCore](migration-practicecore.md) — the largest one:
  the config migrations, the region columns, and what stopped blocking the
  enable.

Root classes that are not a module:

- `net.exylia.lib.ExyliaLib` — the plugin itself; only lifecycle and cleanup.
- `net.exylia.lib.platform.Platform` — `current()`, `isFolia()`, `isPaper()`.
- `net.exylia.lib.internal.LibrarySettings`, `internal.ExyliaLibUpdater` —
  internal, free to change without notice.
- `net.exylia.lib.internal.MinimumVersion` — the minimum Minecraft version is
  1.21.4. On an older server the lib still loads (nothing changes for whoever
  runs it) but logs a boxed console error at startup naming the detected
  version, because Exylia plugins will fail mid-game there with
  `NoSuchMethodError`. Versions compare number by number, so `26.x` counts as
  newer than `1.21.x`.
