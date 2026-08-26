# Migrating ExyliaPracticeCore

**Done.** ExyliaPracticeCore builds against ExyliaLib and carries no
ExyliaCommons import. This is what the move consisted of, kept because the
column formats and the behaviour changes below are the parts somebody has to
check against a live server.

ExyliaPracticeCore was 223 files and about 27,000 lines — the largest migration
so far. [migration-ffa.md](migration-ffa.md) is the reference for every
mechanical part of it, and [migration-capture.md](migration-capture.md) for the
zones-as-regions half.

## Nothing was missing

Every ExyliaCommons module this plugin imported has an ExyliaLib counterpart.

| ExyliaCommons | ExyliaLib | Note |
| --- | --- | --- |
| `loader.ExyliaLoaderPlugin` | `LoaderPlugin<SpigotLukittuLoader>` | as `ExyliaFFA` |
| `config.schema.*` | `Configs.define(...)` + records | three files, one needs migrations |
| `database.entity.Entity`, `Repository` | `Databases.of(this).repository(Record.class)` | 17 entities become records; every read is a future |
| `action.api.ActionAPI` | `Actions.of(plugin, "practice")` | 213 actions, mechanical |
| `ui.api.MenuAPI`, `MenuData`, `SectionData` | `Menus.of(plugin)`, `PluginMenus` | the 60 menu YAMLs load unchanged |
| `ui.menu.MultiPaginationMenu`, `PaginationTracker` | `UiSession.entries(section, rows)` | the selection lives in the session |
| `placeholders.api.Placeholders` + annotations | `Placeholders.group(plugin, prefix)` | imperative groups, as in `FFAPlaceholders` |
| `placeholders.context.PlaceholderContext` | a `Map<String, Object>` | 71 files touched |
| `visual.api.MessageAPI` | `Text.from(plugin, raw).send(...)` | 315 call sites behind one helper |
| `visual.api.{Title,ActionBar,BossBar,Sound,Effect}API` | `Effects.of(plugin)`, `EffectConfig`, `util.Effects` | a `Display` is the handle |
| `scoreboard.api.ScoreboardAPI` | `Scoreboards.show(...)`, `SidebarConfig` | `Board.updateData` replaces `updateContext` + `forceUpdate` |
| `region.RegionManager`, `RegionFlag`, `Selection` | `Regions.of(plugin)`, `PolicySet`, `Cuboid` | see the column note below |
| `region.schematic.SchematicManager` | `Schematics.of(plugin)` | `SchematicResult` carries why it failed |
| `chat.api.ChatInputAPI` | `Inputs.of(plugin)` | 31 requests |
| `wizard.api.WizardAPI` | `Wizards.of(plugin)` | `askPoint`, `askRegion` |
| `items.api.ItemsAPI`, `ItemData` | `Items.of(plugin)`, `Item` | the lobby hotbar keeps its own slots and commands |
| `items.input.IconInputHelper` | `Inputs.of(plugin).icon(...)` | |
| `ui.selector.api.SelectorAPI` | `Effects.editor(plugin, effects)` | potion effects |
| `hologram.api.HologramAPI` | `Holograms.show(...)`, `HologramConfig` | the two lit-fuse counters |
| `cooldown.api.ItemCooldownAPI` | `util.ItemCooldowns` | |
| `clientapi.team.api.TeamTrackerAPI` | `Clients.of(plugin).teams()` | |
| `teleport.api.TeleportAPI` | `Teleports.of(plugin)` | straight there, no warmup |
| `database.api.Database.export/importData` | `Transfers.of(plugin)` | `/epc data export` keeps its folder |
| `compat.{Attribute,EntityType,InventoryView}Compat` | Paper 1.21.4 itself | the shims existed because Commons was shaded |
| `utils.PlayerUtils` | nothing — three call sites inlined | |
| `formatter.api.FormatterAPI` | `TimeFormats`, `Numbers` | behind one plugin-side `Format` |
| `reload.api.ReloadAPI` | `Reloads.of(plugin)` | `/epc reload` reloads this plugin, not the server |

## The things that change behaviour

### The region columns keep the exact JSON

`ArenaEntity.playableRegion`, `copyRegion`, `extraRegions`,
`PvpRegionEntity.region` and `PortalRegionEntity.region` are
`@Column(autoSerialize = true)` holding an ExyliaCommons `Region`. Every arena,
PvP zone and portal on every server is a row in that shape, so the plugin owns a
`Zone` record and a `Codec` that reads and writes it verbatim — geometry under
`selection`, the unused halves written back empty. `ZoneTest` pins both
directions.

What the region *does* is not stored any more. A Commons region carried its own
flags; a library region declares policies when it is registered, so
`PvpRegionManager` builds a `PolicySet` from the row's own columns and
`GameService` builds one per match from the kit's rules — at priority 1, so the
match's answer is read before the arena's own registration.

### `config.yml` needed migrations, the other two did not

The nine titles, three action bars and three boss bars were written flat
(`{title, subtitle, fade-in, stay, fade-out, update-interval}`);
`EffectConfig` nests them one level deeper. Without `MIGRATION_FROM_1` the first
boot would prune every one of those keys and regenerate the shipped defaults
over the owner's text. The three title durations were ticks and are now seconds,
and `%time_decimal%` becomes `%time%`. `MigrationTest` covers all three shapes.

`scoreboards.yml` needs nothing: `SidebarConfig` keeps the keys Commons wrote and
the interval stays in ticks. `messages.yml` keeps every key path.

The six sections Commons registered against the same `config.yml` — `debug`,
`formatters`, `text`, `tasks`, `chat-input`, `discord` — are removed on the first
load and reported as `UNKNOWN_KEY`. They were the library's own settings.

### Every row is a value

The 17 entities are records, so an edit is a copy. Each admin screen owns the row
it is editing through one `update(player, change)` — under Commons the buttons
wrote into a shared bean, which is how two admins editing one arena could
overwrite each other. `StatsManager` applies every counter inside `Map.compute`,
so two matches ending in the same tick cannot interleave.

### Nothing blocks the enable any more

`KitManager`, `ArenaManager`, `SeasonManager`, `PvpRegionManager` and
`PortalRegionManager` each expose `ready()`, and the queue is **locked until all
five complete**. Commons could build them in one pass because its repositories
blocked on the main thread during enable; every read is a future here, so the
queue is what holds the gap rather than the server thread.

`SeasonSchemaMigrator` is the same change at a larger scale: it used to hold
startup for minutes on a big table and is now a chain of pages.

### The write-behind buffer is gone, and two things depended on it

- `Database.flushAllNow()` before a season restart has nothing to flush: a save
  completes when the row is durable.
- `Repository.deleteWhereLessThan` and `deleteBounded` have no equivalent —
  filters are equalities. `MatchHistoryManager` reads the oldest batch, keeps the
  rows past the cutoff and deletes those by id, bounded per sweep.

### Four menu sections needed their clicks bound in Java

`kit_list`, `arena_kit_select`, `leaderboard` and `match_history` declare
templates and no `actions`: Commons built a `ClickAction` per row, per render.
The bindings live in `PracticeMenus.CLICKS` rather than in the files, which stay
byte for byte as they are on disk. Everything else in the sixty files is read
unchanged — `RealMenusTest` in this repository loads them.

### A row's values are a snapshot

`withPaginationSupplier` and supplier-valued contexts were re-read on every
redraw. `PracticeMenus.live` and `liveContext` re-supply the rows and the context
on the interval each file already asks to be redrawn on, which is what the queue
teaser, the spectator list, the party screens and the running-matches list use.

## Order that worked

1. `build.gradle`: `compileOnly` ExyliaLib, paper-api 1.21.4, `api-version: 1.21`.
2. The three config records, then the call sites.
3. Text, tasks, debug, formats, teleports.
4. Actions before menus. Then the menus and the placeholders.
5. Database: entities to records, the zone codec, the async bootstraps.
6. Regions, schematics, visuals, scoreboard, holograms, teams.
7. Remove `ExyliaLoaderPlugin` and drop ExyliaCommons from `build.gradle`.
