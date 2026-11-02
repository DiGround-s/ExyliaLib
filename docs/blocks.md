# Block module

Blocks in the world that answer clicks instead of behaving like their material.
Since 1.110.0.

Entry point: `net.exylia.lib.block.Blocks`.

```java
PluginBlocks blocks = Blocks.of(this);

blocks.at(crate.location())
        .onRight(click -> open(click.player(), crate))
        .onLeft(click -> preview(click.player(), crate))
        .register();
```

A crate, a shop, a warp pad, a quest board. Every plugin that has placed one
wrote the same three things: an interact listener that finds the block by its
coordinates, a break listener so nobody takes it away, and a guard so a barrel
does not open its own inventory.

## What it does not do

**It does not remember anything across restarts.** Where a plugin's blocks are
is that plugin's data, stored in its own table next to what each block *means*;
a registry holding only locations would keep half of a row. Register them again
on enable, from whatever the plugin already loads.

## Who placed a block (since 1.163.0)

`PlacedBlocks` answers "did a player put this here" anywhere in the world and
across restarts: the check a mining or farming reward needs so nobody is paid
for placing and breaking the same block.

```java
PlacedBlocks.track(this, List.of(Material.SUGAR_CANE, Material.DIAMOND_ORE));

if (!PlacedBlocks.placedByPlayer(event.getBlock())) reward(player);
```

- **Only tracked materials are recorded**, the union of what every plugin asked
  for; a plugin's request goes with it when it is disabled. Placing an untracked
  block costs one set lookup.
- **The record lives in the chunk's persistent data**, saved and loaded with the
  blocks it describes and free while the chunk is unloaded. A chunk keeps at
  most 4096 positions and forgets the oldest past that.
- **A break forgets at `MONITOR`**, after every other listener asked, so a break
  handler at any lower priority still sees the block as placed. A piston moves
  the record with the block.
- **Other endings keep the record**: an explosion, fire, a block grown over. The
  answer errs on "placed", which withholds a reward rather than paying a farm.
- Ask from the thread that owns the block, where its events already run.
- Inside regions declaring `player_build_only`, ask `PluginRegions.placedByPlayer`
  ([regions.md](regions.md)) instead: that record lives exactly as long as the
  region, which is what an arena re-registered for its next match needs.

## API

### `Blocks`

| Method | Contract |
| --- | --- |
| `of(plugin)` | that plugin's view, the same instance every time |
| `at(location)` | what is registered there, whoever owns it, or `null` |
| `at(block)` | the same from a block; either half of a double chest finds it (1.458.0) |
| `isRegistered(block)` | whether any plugin owns it |
| `active()` | how many are registered across the server |
| `release(pluginName)` / `releaseAll()` | called by the library; consumers do not |

### `PluginBlocks`

| Method | Contract |
| --- | --- |
| `at(location)` / `at(block)` | starts a registration; nothing happens until `register()` |
| `registered(location)` | this plugin's registration there, or `null` — including when another plugin owns it |
| `unregister(location)` | takes down this plugin's, and says whether there was one |
| `count()` | how many this plugin has |
| `unregisterAll()` | takes down all of them — what a reload wants before re-registering |

### The builder

| Method | Default |
| --- | --- |
| `onLeft(handler)` | none; a left click is refused and nothing runs |
| `onRight(handler)` | none |
| `onClick(handler)` | both buttons at once |
| `protect(boolean)` | `true` — cannot be broken, blown up, pushed by a piston or burnt |
| `vanilla(boolean)` | `false` — the click does not do what the material does |
| `ownExplosions(boolean)` | `false` — a protected block is taken out of every explosion; `true` leaves it in the block list for the owner to decide (1.438.0) |
| `protectInventory(boolean)` | `false` — `true` keeps hoppers, droppers and hopper minecarts from pulling items out or pushing them in (1.458.0) |
| `ignoreCancelled(boolean)` | `false` — `true` leaves a click another plugin already cancelled alone: no handler runs, so a claim that keeps the block shut keeps it shut (1.458.0) |
| `register()` | returns the `ClickableBlock` |

`ClickableBlock` carries `plugin()`, `owner()`, `location()`, `isRegistered()`
and `unregister()`. A plugin that keeps its blocks for as long as it is enabled
can throw the handle away.

### The click

`BlockClick` is `player()`, `block()`, `location()`, `button()` and
`sneaking()`, so one block can offer a second action behind shift without a
second registration. `button()` is `BlockButton.LEFT` or `RIGHT`.

`event()` (since 1.458.0) is the `PlayerInteractEvent` behind the click. A block
registered with `vanilla(true)` refuses its material for one click with
`click.event().setUseInteractedBlock(Event.Result.DENY)`: a loot chest that is
refilling stays shut while the others open as chests.

## Behavior

- **One block, one registration.** Registering over an existing one replaces it,
  whoever owned it. The replaced handle knows it is no longer standing, and its
  `unregister()` cannot take down the one that replaced it.
- **A location means the block that contains it.** A player's feet, the centre
  of the block and its corner all resolve to the same registration. The corner
  is built arithmetically rather than through `Location#getBlock()`, which loads
  the chunk — registering happens at startup, long before anybody is near.
- **Handlers run on the thread that owns the block**, from the interact event,
  so the world can be read and written from them on Folia as well as Bukkit.
- **A repeated click is one click.** A held left button fires every tick and both
  hands fire a right click; within 250ms, the same button on the same block by
  the same player does not call the handler again. A crate that opened twice
  from one press would cost a key.
- **A handler that throws costs its own click.** It is reported through `Debug`
  against its own plugin, and the interact event carries on to the plugins
  behind it.
- **An explosion loses the protected blocks, not its blast.** They are taken out
  of the block list rather than the event being cancelled, so one crate in range
  does not save the rest of the street.
- **An owner can decide explosions itself.** With `ownExplosions(true)` the
  library leaves the block in the list. The owner listens at `HIGHEST`, after
  claim plugins have trimmed the list, and handles only the blocks still in it
  (a stacked spawner losing one of its stack, a block that needs its own drop).
- **A piston that would move one is cancelled**, because a moved block is a
  registration pointing at air.
- **A double chest is one block** (since 1.458.0). Registering either half
  covers both: a click, a break, a blast, a piston or a hopper on the other half
  finds the registration. `Blocks.at(block)` and `Blocks.isRegistered(block)` answer
  for both halves; `at(location)` and `registered(location)` stay exact.
- **Hoppers only when asked.** `protectInventory(true)` registers the hopper
  listener the first time any block asks for it: Paper skips building a move
  event for every hopper while nobody listens to one.
- **Nothing survives its plugin.** Disabling it unregisters every block it owns;
  the blocks themselves are left standing, which is what a restart expects to
  find.

## Walls (since 1.458.0)

Real blocks put up for a while — the walls a duel room closes with — that always
come down, a crash included.

```java
Walls walls = Walls.of(this);

// onEnable, before any wall goes up: what a crash left standing comes down
walls.recover(id -> arenas.regenerate(id));

walls.raise("duel:" + room.id(), world, shellPositions, Material.BARRIER)
        .thenAccept(wall -> match.wall = wall);

match.wall.lower();      // the fight is over
match.wall.lowerNow();   // onDisable: what this thread may write
```

| `Walls` | |
| --- | --- |
| `of(plugin)` | that plugin's walls, the same instance every time |
| `raise(id, world, Collection<BlockVector>, material)` | puts it up and completes with the `Wall`; fails when a wall with that id is standing |
| `recover(Consumer<String> lowered)` | takes down every wall the last run left, telling each id once it was tried; walls raised in this run are never touched. Never completes exceptionally |
| `folder()` | `<data folder>/walls` |

| `Wall` | |
| --- | --- |
| `id()`, `world()`, `material()`, `size()` | what it is; `size()` counts only the blocks it replaced |
| `contains(x, y, z)` / `contains(block)` | whether it replaced that block; memory, any thread, for the plugin's own break guard |
| `lower()` | puts back what it replaced, then deletes its file; `false` keeps the file for the next start |
| `lowerNow()` | the same, inline, for the chunks the calling thread owns: for a stop no task will outlive |

- **Only air and replaceable blocks become the wall**, and what each was is
  written to one file per wall (atomically) before the future completes.
- **Lowering puts a block back only where the wall's material still stands**, so
  a block somebody changed since is left as they left it, and lowering twice
  does nothing the second time.
- **The file is local to the server**, where the blocks are: servers sharing a
  database never take each other's walls down.
- **A wall in a world that is not loaded** keeps its file for a start that has it.
- Blocks are written on the thread that owns their chunk, on Folia as on Bukkit.
  Guarding the wall against breaking stays the plugin's (`contains`).

## Performance

One `ConcurrentHashMap` for the whole server, keyed by world and block
coordinates. Every handler leaves on a single lookup when nothing is registered
where the event happened, so a server with no clickable blocks pays a map miss
per interact. The only thing that expires is the record of who clicked what a
moment ago: a Caffeine cache, five seconds, two thousand entries.

## Source and tests

- Public: `block/` — `Blocks`, `PluginBlocks`, `ClickableBlock`, `BlockClick`,
  `BlockButton`.
- Public since 1.458.0: `block/Walls`, `block/Wall`.
- Internal since 1.458.0: `block/internal/BlockInventoryListener`, `WallFile`.
- Tests since 1.458.0: `DoubleChestHalfTest` (which way each half joins),
  `WallFileTest` (a wall's file reads back exactly, damage costs one block).
- Public since 1.163.0: `block/PlacedBlocks`.
- Internal: `block/internal/` — `BlockRuntime`, `BlockListener`, `PlacedBlockTracker`.
- Tests: `PlacedBlockTrackerTest` covers position packing, the per-chunk cap and
  what a chunk records; the persistent data itself needs a server.
- Tests: `BlockRegistryTest` covers block resolution across worlds, replacement,
  ownership, per-plugin release and the debounce.
