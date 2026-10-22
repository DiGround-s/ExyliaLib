# Arenas

Arenas handed out one match at a time. The original when it is free, else a
free copy, else a new copy pasted into the [temporary world](world.md) — up to
a limit per arena. Released arenas are regenerated before anyone gets them
again.

```java
ArenaPool arenas = ArenaPool.of(this, "duels_arena_");   // once, in onEnable

arenas.register(ArenaTemplate.of("forest", world,
        Cuboid.blocks(100, 60, 100, 160, 90, 160),       // the whole arena
        "duels_forest",                                  // saved from exactly that box
        Map.of("a", spawnA, "b", spawnB, "spectator", spectator),
        8));                                             // up to 8 copies

arenas.acquireAny(shuffled(kit.arenaIds())).thenAccept(lease -> lease.ifPresentOrElse(
        arena -> tasks.run(() -> start(arena)),          // arena.point("a"), arena.box("goal")
        () -> tellBoth("No arena is free.")));

// When the match ends, players out first:
arena.release();
```

## Templates

`ArenaTemplate` is an arena as the plugin configured it:

| Component | |
| --- | --- |
| `id` | unique within the pool |
| `world`, `bounds` | where the original stands; `bounds` is the whole arena |
| `schematic` | the plugin's schematic of exactly `bounds` — `PluginSchematics.save(name, bounds, world)` |
| `points` | named positions (`"a"`, `"b"`, `"spectator"`); their world is ignored |
| `boxes` | named boxes inside the arena (a goal, a capture zone) |
| `policies` | what the region over each instance declares |
| `maxCopies` | copies allowed besides the original; `0` never copies |

`ArenaTemplate.of(...)` leaves out boxes and policies. `register` adds a
template or replaces the one with the same id, which is what a reload calls.

## Acquiring

`acquire(id)` and `acquireAny(ids)` complete with an `Optional<ArenaLease>`:

1. a free instance — the original first, then copies — of any of the ids, in the
   order given;
2. otherwise a new copy of the first id still under its `maxCopies`: a plot is
   reserved in the temporary world and the schematic pasted on it, at the
   original's lowest Y;
3. otherwise empty.

A paste that fails (no temporary world, schematic missing) is logged, gives its
slot back and moves on to the next id. Unknown ids are skipped. The futures
never complete exceptionally. The order is the caller's: shuffle the ids for a
random arena.

## Leases

A lease is already moved to its instance, so the plugin never cares whether it
got the original:

| | |
| --- | --- |
| `world()` | the template's world, or the temporary world for a copy |
| `bounds()` | the whole arena |
| `point(name)` | a new `Location`, or `null` when the template has no such point |
| `box(name)` | a `Cuboid`, or `null` |
| `region()` | the `RegionId` registered over `bounds()` |
| `isClone()`, `copy()` | `0` for the original, `1`+ for copies |
| `release()` | regenerate, then free |

A copy is a pure translation along X and Z: the schematic's origin (the box
minimum) lands on the plot's corner, and every point and box moves by the same
offset. Yaw and pitch are kept.

`release()` regenerates the arena from the schematic with
`RegenerateOptions.defaults()` — loose entities removed, the blocks put back,
anyone buried lifted out — and only then makes the instance acquirable. A failed
regeneration is logged and the instance is freed anyway; the future says
`false`. Calling `release()` again returns the same future and does nothing:
a lease can never free an arena somebody else has since taken. Move the
players out before releasing; nobody is teleported away.

## After a crash, and in menus (1.348.0)

- `regenerateIdle()` regenerates every **free** instance from its schematic,
  holding it out of the pool until its blocks are back; instances in a match are
  left alone. Call it once after registering on start: a crash mid-match left the
  original with the blocks that match placed, and nothing in memory remembers it
  was taken. Completes with how many came back, never exceptionally.
- `usage(id)` answers `Optional<Usage(busy, copies, maxCopies)>` from memory —
  `busy` counts instances in a match or regenerating — cheap enough for a menu
  `Supplier`.

## Copies are kept

`TemporaryWorld` plots are never given back, so the pool keeps every copy it
makes and reuses it. `maxCopies` is therefore also the most temporary-world
space a template can ever use in a run.

## Regions

Every instance gets one of the plugin's regions over its bounds, keyed
`<prefix><id>` for the original and `<prefix><id>_<n>` for copy `n`
(sanitised with `RegionId.sanitize`), with the template's policies. That is
what makes `regions.enforce(...)`, `player_build_only` and `temporary_blocks`
work in copies exactly as in the original. If the plugin already has a region
under the original's key, the pool's replaces it.

## Reloading and removing

- `register` with an existing id: the old template's free copies are dropped
  now (their regions unregistered), busy ones when they are released. The
  original keeps its state — a match in it keeps it taken. New copies get new
  numbers, so an old copy's region is never confused with a new one's.
- `unregister(id)`: the same, plus the original's region. Leases already out
  still work and still regenerate on release.

## Threads

Every method is safe from any thread and none blocks: plots, pastes,
regenerations and region changes are already asynchronous or lock-free. Futures
complete on whichever thread finished the work, often a world thread on Folia,
so hop through `Tasks.of(plugin)` (`runAtEntity` for teleporting players in)
before touching the game. Move players with [Teleports](teleport.md).

## Lifecycle

Hold one pool per plugin. Nothing outlives the plugin: its regions are released
with it and the temporary world is deleted with the library. Nothing derived
from the palette is kept, so there is nothing to reload.

## What it does not do

- Regenerate only a playable sub-box; the whole `bounds` is the schematic.
- Free copies or shrink the pool; copies live for the run.
- Round restarts without freeing (`PluginSchematics.regenerate` on
  `lease.bounds()` does that).

## Source and tests

- Public: `util/arena/ArenaPool.java`, `ArenaTemplate.java`, `ArenaLease.java`.
- Package-private: `ArenaInstance` (the `FREE → IN_USE → REGENERATING → FREE`
  state machine), `ArenaPool.Placement` (the world, replaced in tests).
- Tests: `src/test/java/net/exylia/lib/util/arena/ArenaPoolTest.java` — offsets
  of points and boxes, the copy limit, reuse, double release, failed pastes,
  reload and removal.
