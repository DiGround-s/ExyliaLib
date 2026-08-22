# Migrating ExyliaFFA

What ExyliaFFA needs from this library, and the four things that change
behaviour rather than just moving it. Written while the library work was done,
so it is the state of the library, not a guess about it.

FFA is not migrated yet. This is the preparation.

## What was missing, and is now here

Two of the gaps found when auditing FFA were library gaps, and both are fixed:

- **A waypoint had no owner.** FFA keeps `Map<UUID, Map<String, UUID>>` of
  tracking ids because ExyliaCommons handed one back per waypoint. The library
  removed by name out of one shared bucket, so `Clients.of(this)` is the
  replacement: keyed by plugin and name, cleared per plugin, and taken down when
  the plugin is disabled. See [client](client.md).
- **The region event carried the whole server.** `exited(regions)` and
  `entered(regions)` return only this plugin's. See [regions](regions.md).

Still missing at the time of writing: **schematics**. FFA regenerates an arena
between matches, and that is its core loop. It is being built separately; FFA
cannot migrate before it lands.

## The four that change behaviour

### 1. Waypoints are keyed by name, not by a tracking id

`WaypointAPI.show` returned a `UUID`; `Waypoint` is removed by the name it was
shown with. `FFAManager.playerSpawnTrackingIds` goes away — the library already
remembers what it sent.

```java
// FFAManager
clients.waypoints().show(player, Waypoint.at(name, spawn.getLocation())
        .colour(Waypoint.Colour.of(255, 85, 85)));

// removeSpawnWaypoints, for the whole player
clients.waypoints().clear(player);
```

**Check the names before doing this.** `showSpawnWaypoints` names a waypoint
after `spawn.getDisplayName()` and only falls back to `spawn.getId()` when it is
empty. Nothing in `SpawnEntity` makes a display name unique, so two spawns
called `Red` in one arena work today and would collide after the move: the
second `show` replaces the first, and the arena draws one marker instead of two.

Use `spawn.getId()` as the waypoint name — it is already unique per arena — and
keep the display name for what the player reads.

### 2. The region event is the whole server's

`RegionExitEvent` was one region. `PlayerRegionChangeEvent` is a batch, and it
carries other plugins' regions too. `ProtectionListener.onRegionExit` kills the
player who leaves their arena, so reading the unfiltered list means a player who
steps out of somebody else's claim dies in a game they were never in.

```java
@EventHandler(priority = EventPriority.HIGH)
public void onRegionChange(PlayerRegionChangeEvent event) {
    for (RegionSnapshot left : event.exited(regions)) {
        // the existing body, unchanged
    }
}
```

The existing `session.getRegionId().equals(...)` check stays: filtering by owner
says the region is FFA's, not that it is *this session's*.

### 3. `config.yml` cannot move on its own

Six ExyliaCommons schemas declare sections in the same `config.yml`
(`debug.*`, `formatters.*`, `text.*`, `tasks.*`, `chat-input.*`, `discord.*`),
all with `strict = true`. Both libraries prune keys they do not recognise, so
whichever writes last deletes the other's. `config.yml` moves in the same commit
that removes `ExyliaLoaderPlugin`, never before. `messages.yml` and
`scoreboards.yml` are safe — no Commons schema claims them.

`GlobalDefaults.Combat.ACTIONBAR` uses `updateInterval(2)` in **ticks**;
`EffectConfig` is in **seconds**. Every visual block needs a `Migration`, or the
first boot prunes what the owner wrote. ExyliaSandBox has a worked example.

### 4. The repositories are futures

Commons has blocking `findAll()`/`findById()`. This library does not, on
purpose. `StatsLeaderboardMenu` and `FFAManager.saveArena` get restructured, not
translated. The eight entities become records: `@Id`/`@Column` are
`@Target(RECORD_COMPONENT)` and will not compile on a Lombok bean.

Tables that must keep their names and columns: `ffa_arenas`, `ffa_kits`,
`ffa_kit_layouts`, `ffa_player_settings`, `ffa_player_stats`,
`ffa_saved_inventories`, `ffa_settings`, `ffa_spawns`. Column names derive from
the component name verbatim in both libraries, and `ItemStack`, `ItemStack[]`
and `Location` encode identically, so the rows load unchanged.

## Order that worked for ExyliaSandBox

Keep both libraries on the compile classpath until the last step. That is what
lets every commit build and be committed on its own.

1. `build.gradle`: `compileOnly` ExyliaLib, paper-api 1.21.4, add `ExyliaLib` to
   `depend:` in **both** `plugin.yml` files. FFA has it in neither.
2. Database: entities to records, async bootstrap.
3. `messages.yml` and `scoreboards.yml`.
4. Text, tasks, teleports, snapshots, items, menus.
5. Regions, selection, the change event.
6. Schematics, once the module lands.
7. Remove `ExyliaLoaderPlugin`, move `config.yml`, drop ExyliaCommons from
   `build.gradle` — one commit, because of the shared file.

## Smaller things with no direct replacement

| Commons | What to do |
| --- | --- |
| `AttributeCompat.getMaxHealth()` | `Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"))`. Never `valueOf`: several of these stopped being enums in 1.21 and throw at runtime. |
| `ItemSnapshot.displayLabel(...)` | A local helper. `item.Source` already parses the same prefixes. |
| `IconInputHelper.ask(...)` | `Inputs.of(plugin).text(...)` plus `Source.parse`, or `WizardBuilder.hand(...)`. |
| `ClickTypeGroup` | `ui.ClickKind`, with `ClickKind.ANY`. |
| `SerializationType.JSON` | `Databases.codec(Class, Codec)`. |
