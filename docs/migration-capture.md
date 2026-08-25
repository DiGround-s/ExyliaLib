# Migrating ExyliaCapture

**Done.** ExyliaCapture builds against ExyliaLib 1.61.0 and carries no
ExyliaCommons import. This is what the move consisted of, kept because the
column formats and the behaviour changes below are the parts somebody will have
to check against a live server.

ExyliaCapture was 76 files and about 10,900 lines. It is the second-largest
migration after ExyliaFFA, which is the reference for every mechanical part of
this one — see [migration-ffa.md](migration-ffa.md).

## Nothing blocks the migration

Every ExyliaCommons module ExyliaCapture imports has an ExyliaLib counterpart.
The audit below is the full import list, counted from the source.

| ExyliaCommons | ExyliaLib | Note |
| --- | --- | --- |
| `loader.ExyliaLoaderPlugin` | `LoaderPlugin<SpigotLukittuLoader>` | copy `ExyliaFFA` |
| `config.schema.*`, `Configs.get` | `Configs.define(...)` + records | needs `Migration`s, see below |
| `database.entity.Entity`, `Repository` | `Databases.of(this).repository(Record.class)` | entities become records; every read is a future |
| `action.api.ActionAPI` | `Actions.of(plugin, "capture")` | 73 actions, mechanical |
| `ui.api.MenuAPI`, `ui.model.MenuData` | `Menus.of(plugin)`, `PluginMenus.open(player, id, context)` | 22 menu YAMLs load unchanged |
| `placeholders.api.Placeholders` + annotations | `Placeholders.group(plugin, "capture")` | imperative groups, as in `FFAPlaceholders` |
| `placeholders.context.PlaceholderContext` | a `Map<String, Object>` | 32 files touched, all one-line changes |
| `visual.api.MessageAPI` | `Text.from(plugin, raw).send(...)` | |
| `visual.api.BossBarAPI`, `visual.config.BossBarConfig` | `Effects.of(plugin).bossBar(...)`, `EffectConfig` | `Display.text(...)` is `sendUpdatable` |
| `scoreboard.api.ScoreboardAPI`, `model.Scoreboard` | `Scoreboards.show(...)`, `SidebarConfig` | see *the multi-event board* below |
| `hologram.api.HologramAPI`, `HologramTemplate` | `Holograms.show(...)`, `HologramConfig` | keys are the ones commons wrote |
| `clan.api.ClanAPI`, `clan.model.Clan` | `Clans`, `Clan` | |
| `tasks.api.Tasks`, `ScheduledTask` | `Tasks.of(plugin)`, `TaskHandle` | `Tasks.db*` disappears — see below |
| `chat.api.ChatInputAPI` | `Inputs.of(plugin)` | `text`, `integer`, `decimal`, `id` all exist |
| `wizard.api.WizardAPI` | `Wizards.of(plugin)` | `askRegion`, `askPoint`, `askStand` |
| `ui.selector.api.SelectorAPI` | `Rewards.of(plugin).editor(...)`, `NamedCommands.editor(...)` | |
| `ui.selector.impl.iconpicker.IconPickerAPI` | `Inputs.of(plugin).icon(...)` | |
| `region.selection.Selection` | `Cuboid` + `SelectionResult` | column format must be kept, see below |
| `region.RegionManager` | `Regions.of(plugin)` | initialised but otherwise unused today |
| `reward.api.RewardAPI`, `reward.model.RewardEntry` | `Rewards.of(plugin)`, `util.reward.RewardEntry` | column-compatible by construction |
| `namedcommand.model.NamedCommandEntry` | `util.command.NamedCommand` | same stored field names |
| `reload.api.ReloadAPI` | `Reloads.of(plugin)` | |
| `clientapi.waypoint.WaypointAPI` | `Clients.of(plugin).waypoints()` | keyed by name, see below |
| `debug.api.DebugAPI` | `Debug.of(plugin)` | |
| `formatter.api.FormatterAPI.formatTime` | `TimeFormats` | 31 call sites |
| `command.api.CommandAPI` | `Commands.of(plugin)` | Lamp stays built by the plugin |

`PendingRewards` in the reward module names `capture_pending_rewards` in its own
javadoc: the interface exists so this plugin keeps its table and hands the
library a store. `PendingRewardManager` becomes that store and about twenty
lines shorter.

## What was missing, and is now here

Three gaps, all small. Two were plugin-side answers; the third was a library
gap and is closed as of 1.61.0.

### 1. `EntityTypeCompat.resolveMinecartType` is not needed

Used in three places — `PayloadEvent` spawning the cart, `CaptureHologramManager`
spawning the preview cart, `ActionRegister` offering the five types.

ExyliaCommons carried that compat table because it was shaded into plugins
compiled against different Minecraft versions, so it had to answer for both
`PRIMED_TNT` and `TNT`. ExyliaLib is not shaded and its base is paper-api
1.21.4, and none of the five minecart names ever changed. What is left is a
lookup:

```java
EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
```

Through the registry rather than `EntityType.valueOf`, which is the standing
rule — several of these types stopped being enums in 1.21 and `valueOf` compiles
and then throws. A registry miss is `null`, which is an answer.

### 2. `ItemSnapshot.displayLabel` is `Source.label()`

One call site, in `EventConfigSetupMenu`, turning the stored icon into something
the lore can say. It is not the `Icons` engine — that builds the `ItemStack`,
and does not name it.

`Source` already parses that whole grammar at load time, so the label belongs on
it rather than on a plugin-side helper doing `startsWith` all over again:

```java
Source.of(config.iconMaterial()).label();   // "Nether Star"
```

Added in 1.61.0. Heads read as `Notch's Head` or `Custom Head`, a `bytes:`
snapshot as the material inside it, and a value still carrying a placeholder as
itself. See [items.md](items.md#naming-a-stored-icon).

### 3. The outline is the region module's, and it grew two settings

`ParticleRenderer` has no equivalent because a raw box is not what the library
draws: `regions.visualize(player, id)` draws a **registered** region. Registering
the zones is the right move for this plugin anyway — see below — but two things
had to change before an outline could belong to a match rather than to an admin
looking at a claim. Both landed in 1.61.0:

- **`untilClosed()`.** `durationTicks` was mandatory and finite, and an event's
  length is not known when it starts — a KOTH runs until somebody holds the
  point. The outline now ends when its handle is closed, which is what the event
  already knows how to do.
- **`viewDistance`.** The renderer sent the whole outline to every viewer
  regardless of distance. Capture culled at 32 blocks by hand; the library now
  culls at 48 by default, measured to the nearest point of the region's bounds
  and applied *before* the outline is sampled.

`VisualizationOptions` is a builder now, the way `SelectionOptions` in the same
module already was. The old four-argument constructor still compiles.

This makes the migrated version cheaper than what it replaces.
`renderZoneParticles` walks every cached border point for every online player
and calls `distanceSquared` on each, every forty ticks; the library budgets 512
points before generating any, and skips a distant viewer for the cost of three
subtractions.

## The things that change behaviour

### The zone column must keep its exact JSON

`CaptureEventConfig.zoneSelection` is a `@Column(autoSerialize = true)`, and
ExyliaCommons wrote it through `SelectionSerializer` as:

```json
{"world":"world","x1":0.0,"y1":64.0,"z1":0.0,"x2":10.0,"y2":74.0,"z2":10.0}
```

Every configured event on every server is a row in that shape. The replacement
is a plugin-owned `Codec` registered with `Databases.codec(Zone.class, ...)`
that reads and writes those seven fields verbatim. Anything else — a
`RegionData`, a `Cuboid` encoded the library's way — silently loses every zone
already configured.

`Selection.getVolume()`, `getCenter()`, `getMinimumPoint()`, `getMaximumPoint()`
and `contains(Location)` all have `Cuboid` equivalents; `getPos1().getWorld()`
becomes the world the codec stored.

### The event config is mutated in place, and a record is not

`CaptureEventConfig` is the hard part of this migration, not the database.
Menus, wizards and actions mutate it directly — `config.setZoneSelection(...)`,
`config.getSettings().set(...)`, `config.setStartCommandsList(...)` — and
`EventConfigSetupMenu` keeps the live object in a `Map<UUID, CaptureEventConfig>`
while an admin edits it across several screens.

`@Id` and `@Column` are `@Target(RECORD_COMPONENT)` and will not compile on a
Lombok bean, so the class becomes a record with `withX` copies, and every
mutation site becomes an assignment of the copy back into the editing session.
The `settingsJson` blob stays a blob: it is a free-form `Map<String, Object>`
per event type, and turning it into typed fields is a different change that
would break the same rows.

`ExyliaFFA`'s `ArenaEntity` is the worked example, including the codec
registration that must happen **before** the first `repository(...)` call.

### Everything the leaderboards read is a future

`StatsManager` has ten blocking reads — `findById`, `findAllOrderedBy`,
`findAllByOrderedBy` — several of them from the main thread behind a Caffeine
cache. `PlayerLeaderboardMenu` and `ClanLeaderboardMenu` open by doing the query
inside `Tasks.db` and bouncing back with `Tasks.sync`, which is the shape that
survives: `where(...).orderByDescending(...).limit(n).find().thenAccept(...)`
then `menus.open(player, id, context)`, which relocates itself onto the player's
thread on its own.

`getGlobalStats(uuid)` and its three siblings return a value today and are
called from placeholders and scoreboard lines. Those must read the cache only —
a placeholder that waits for a database is a placeholder that stalls a sidebar
render. The cache is filled on join and after each write, and a miss answers
with an empty stats object rather than blocking.

`Tasks.db`, `Tasks.dbRun` and `Tasks.dbValue` have no replacement because they
need none: repository calls are already off the main thread.

### Waypoints are keyed by name

Four events show waypoints and keep the returned tracking id in a
`Map<UUID, UUID>` to remove them later. `Clients.of(this).waypoints()` removes
by the name it was shown with, so those maps go away — but the names must be
unique per player. Today `BaseCaptureEvent`, `ConquestEvent`, `DtcEvent` and
`PayloadEvent` can all have a waypoint up at once, so the name has to carry the
event id, not just the zone's label. Two concurrent events named the same thing
would otherwise draw one marker.

Reconnects are the second half: commons' `showPersistent` re-sent on join.
`waypoints().restoreWith(player -> ...)` is where that lives now, answering from
the active events rather than from anything stored.

### The zones become regions, and three things go with them

The recommendation, and it deletes more code than it adds. A capture zone
already is a region: a world, a box, an owner and a lifetime. Registering it
says so.

```java
// BaseCaptureEvent.start
for (CaptureZone zone : zones) {
    regions.register(regions.region(zone.key(), zone.world(), zone.cuboid(),
            0, PolicySet.empty()));
}

// BaseCaptureEvent.end
zones.forEach(zone -> regions.unregister(zone.key()));
```

No policies: Capture does not protect anything, it asks who is standing inside.
Priority zero for the same reason. `register`/`unregister` per zone rather than
`replaceAll`, which replaces the **whole plugin's** set and would take down the
zones of every other event running at the same time.

**Occupancy stops being a scan.** `CaptureZone.refreshPlayers`,
`checkPlayerTransition` and `BaseCaptureEvent.syncZonePlayers` all go, and so
does the `PlayerMoveEvent` handler that drove them. One listener replaces them:

```java
@EventHandler
public void onRegionChange(PlayerRegionChangeEvent event) {
    if (!event.involves(regions)) return;
    for (RegionSnapshot left : event.exited(regions)) zoneOf(left).onPlayerLeave(...);
    for (RegionSnapshot entered : event.entered(regions)) zoneOf(entered).onPlayerEnter(...);
}
```

Today every move loops over every zone of every running event, and a full
rescan of every player in the world runs once a second on top of it as a safety
net. The library keeps one spatial index for the whole server and fires only
when membership actually changed.

Two cases the rescan existed to cover are the library's own, and better handled
there than by a timer: a `REGISTER` fires for players already standing where a
zone is created — which is what makes an event started around its own
participants work — and `UNREGISTER` exits them when it ends. `TELEPORT` and
`WORLD_CHANGE` are causes, not misses.

**Eligibility is not geometry, so it stays in the plugin.** Dead, creative and
spectator players are inside the zone and do not count; the region says the
first part and Capture decides the second. Keeping them apart also fixes
something: today a player switching to creative is only noticed on the next
one-second rescan, and the gate is re-evaluated on the gamemode and respawn
events instead.

**The outline is per viewer, and closed by the event.** One
`regions.visualize(player, zoneId, options)` per online player when the event
starts and per player who joins while it runs, the handles kept on the event,
closed in `end`. `GlobalDefaults.Particles` keeps its keys — `enabled` and
`interval-ticks` become the particle, the period and the view distance.

`config.isInZone(location)` and `zone.isInside(player)` are still asked while
nothing is registered, from the setup menus, so they stay on the stored
`Cuboid`. Registering is what a *running* event does.

### The multi-event board is built in Java

`CaptureScoreboardManager.buildMultiScoreboard(n)` uses
`ScoreboardAPI.builder()` to assemble a board whose line count depends on how
many events are running. ExyliaLib has no builder — but `SidebarConfig` is a
plain record, so the same loop constructs one directly:

```java
new SidebarConfig(true, List.of(title), lines, new SidebarConfig.Update(interval, true, true));
```

`ScoreboardAPI.updateContext(player, ctx)` becomes `board.updateData(map)` on
the `Board` that `Scoreboards.show` returned, so the manager holds the board per
player instead of asking the API to find it.

### `config.yml` moves last

The same rule as FFA, for the same reason: ExyliaCommons schemas and ExyliaLib
records both prune keys they do not recognise, so whichever writes last deletes
the other's. `config.yml` moves in the commit that removes
`ExyliaLoaderPlugin`. `messages.yml`, `scoreboards.yml` and the 22 files under
`menus/` are safe at any point.

`GlobalDefaults` needs migrations for the visual blocks:
`Koth.BOSS_BAR.updateInterval(2)` and its five siblings are in **ticks**, and
`EffectConfig` is in **seconds**. `HologramOffsetMigration` — which today runs
imperatively from `GlobalDefaults.register()` — becomes a `Migration` on the
config file, so the config module owns the version rather than the plugin
re-checking every boot.

## Order

Both libraries stay on the compile classpath until the last step, so every
commit builds.

1. `build.gradle`: `compileOnly` ExyliaLib, paper-api 1.21.4, `ExyliaLib` into
   `depend:` in **both** plugin.yml files. It is in neither today.
2. Database: the ten entities to records, the zone codec, the async bootstrap of
   `CaptureConfigManager` and `StatsManager`.
3. `messages.yml`, `scoreboards.yml`, and the menu files.
4. Text, tasks, debug, formats, clans, rewards, named commands, pending rewards.
5. Actions before menus. Then placeholders, scoreboard, holograms, waypoints.
6. Regions: the zones registered, the outline, the change event — and the
   move listener, the per-second rescan and `ParticleRenderer` deleted.
7. Remove `ExyliaLoaderPlugin`, move `config.yml`, drop ExyliaCommons from
   `build.gradle` — one commit.

Enable order inside the plugin is configs → actions → menus → managers, and a
row carries its own value through `UiKeys.ENTRY`. Both are mistakes ExyliaSandBox
made and FFA fixed; neither is worth making a third time.
