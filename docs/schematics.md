# Schematics

Saving a box of the world, pasting it back, and regenerating an arena between
matches.

```java
PluginSchematics schematics = Schematics.of(this);

// Setting an arena up, once.
schematics.save("arena_1", bounds, world);

// Between matches.
schematics.regenerate("arena_1", bounds, world, RegenerateOptions.defaults())
        .thenAccept(result -> { if (result.isSuccess()) reopen(); });

// Drawing the arena list: free, no disk.
boolean ready = schematics.exists("arena_1");
```

## Two engines, one format

**FastAsyncWorldEdit when it is installed and enabled. A built-in engine
otherwise.** FAWE always wins: it is faster on full rewrites and keeps every
byte of block-entity NBT. The built-in engine exists for servers where FAWE
cannot run — NeoForge or Forge hybrids with the Paper API, such as Youer or
Mohist — and it uses the Paper API alone: no NMS, no CraftBukkit, so it runs on
Paper, Folia and those hybrids alike.

`Schematics.isSupported()` is `true` with either; it is `false` only before
ExyliaLib enables or after it stops. `Schematics.unsupportedReason()` names the
bound engine, and the built-in one is announced once on the console on the
first tick.

**The choice is made late, on purpose.** ExyliaLib is `load: STARTUP` and may
enable before FAWE does. Until FAWE says it is enabled, every operation asks the
plugin manager again — one map lookup — and switches to FAWE the moment it is.
That is why `plugin.yml` has no `softdepend` on FAWE: load order would only
decide something that is already decided correctly. A FAWE that is installed but
failed to enable leaves the built-in engine bound.

**On Folia**, Folia only loads plugins that declare `folia-supported`, so a FAWE
the plugin manager knows about is a Folia build and is bound; without one the
built-in engine is used, which is Folia-safe by construction.

### The same files, both ways

Both engines read and write Sponge `.schem` files in the same folders, so an
arena saved by one is pasted by the other, and installing or removing FAWE never
means re-saving arenas.

- **Reads** Sponge versions 1, 2 and 3, gzipped or not.
- **Writes** version 2, laid out as FAWE 2.8.4's own `FastSchematicWriter` lays
  it out: a root compound *named* `Schematic`, which is what FAWE 2.8.4's reader
  is keyed by. It does not read version 3, so writing it would make the built-in
  engine's arenas unreadable the day FAWE is installed.
- **Where a paste lands** matches `FaweEngine` exactly. Relative block (0,0,0)
  lands at the paste point plus the file's paste offset: `Metadata.WEOffset*` in
  version 2 (the `Offset` tag there is the box's absolute minimum and does not
  move the paste), `Offset` in version 3. Both engines save with the origin at
  the box's minimum, so their files carry a zero offset.
- **Palettes are ints**, stored a byte per block for the usual arena, a char past
  256 states and an int past 65536 — never a short.

### How the built-in engine stays off the tick

Reading, decompressing, parsing and comparing run off the server threads. The
world is only touched on the thread that owns each chunk (`runAtLocation`), one
chunk at a time:

1. **Load** — an unloaded chunk is loaded with `getChunkAtAsync`, never
   synchronously, and held with a plugin ticket until the paste is done with it.
   Only a server whose API lacks `getChunkAtAsync` gets a synchronous load, one
   chunk per task.
2. **Snapshot** — a `ChunkSnapshot` on the owning thread, a fraction of a
   millisecond.
3. **Diff** — off-thread, only blocks whose `BlockData` differs are kept. An
   arena where 300 blocks changed costs 300 writes, air included, without
   physics.
4. **Write** — on the owning thread under a budget of **2.5 ms per 50 ms per
   thread**, shared by every chunk of every paste on that thread, continuing on
   the next tick. A full rewrite of an empty area is therefore deliberately slow
   (tens of thousands of blocks a second per thread); the usual regeneration
   finishes within a tick or two.

A paste or save fails — as `FAILED`, never a hang — if any chunk fails or if no
chunk makes progress for 60 seconds. A save writes beside the destination and
moves the file over it, so a crash halfway leaves the old arena.

### What the built-in engine keeps, and what it does not

Bukkit has no generic block-entity NBT API, so it keeps what an arena needs:

| Kept | How |
| --- | --- |
| Container contents — chests, barrels, shulker boxes, hoppers, furnaces, dispensers, droppers, brewing stands | Paper's versioned item bytes, which are vanilla item NBT plus a data version, so items FAWE saved are read back and items saved on an older server are upgraded by Paper's data fixer. A container is set to exactly what was saved, so a looted chest is refilled even though its block never changed. |
| Sign text | both sides, with colour, glow and wax, in the JSON or 1.21.5+ component form the data version calls for |
| Spawner type | the entity it spawns; delays and ranges return to the server defaults |
| Loose entities, when `copyEntities` is on | Paper's entity serialisation, with a fresh UUID; riders are saved as separate entities and not re-mounted |

**Not kept:** every other block entity's data — banner patterns, head owners,
lectern books, beehive occupants, command block commands. Their block states
are pasted; their data is not. A block state the server does not know (a block
removed since the file was saved, a mod that is not installed) is left as it was
and reported once per paste. Block states are not run through a data fixer, so a
schematic from a much older version may paste some states as unknown. Biomes
are never copied, as with FAWE.

**Boxes are capped** at 2<sup>26</sup> blocks (a 512×256×512 box) for both save
and paste; past that the built-in engine refuses and FAWE is the answer.

**The gap.** A chunk is compared with its snapshot, not with itself at the
moment of writing, so a block changed in the fraction of a second between the
two survives the paste. Regenerate an arena when nobody is building in it.

### Why ExyliaCommons' fallback is still not the answer

ExyliaCommons carried a hand-written fallback whose every problem was silent:
block-entity NBT was lost, palettes past 32767 states were truncated by a
`short[]`, and blocks were applied one at a time on the main thread. The
built-in engine is the opposite on each point: what it keeps and loses is
written down above, palettes are ints, and writes are diffed, chunked and
budgeted. It is still second to FAWE, which is why FAWE wins whenever it is
there.

FAWE is `compileOnly` and is never shaded. It is a plugin: a second copy of
WorldEdit on one server is a classloading accident waiting to happen.

## What it does not do

**Entities, by default.** Loose entities are opt-in:

```java
schematics.save("plot_3", bounds, world, true);   // armour stands are the build
```

Off by default because that is what an arena wants: copying them would restore
the dropped swords of the last match along with the walls. It is what
ExyliaCommons did too, so a migrated plugin behaves identically.

**Biomes.** Never copied. They belong to the world, not to the build, and a
schematic pasted elsewhere should not drag its climate along.

## Three answers, not a boolean

```java
schematics.paste("arena_1", origin).thenAccept(result -> {
    if (result.isSuccess()) {
        startMatch();
    } else if (result.outcome() == SchematicOutcome.NOT_FOUND) {
        admin.sendMessage("Save the arena first.");
    }
    // FAILED and UNSUPPORTED are already on the console.
});
```

| Outcome | Means |
| --- | --- |
| `SUCCESS` | the whole operation completed |
| `NOT_FOUND` | there is no such schematic; nothing went wrong |
| `UNSUPPORTED` | no engine — the library is not running |
| `FAILED` | it was attempted and did not finish; `reason()` says why |

ExyliaCommons answered all four with `false`, so a menu that greys out a button,
a command that explains itself and a restart loop that should stop retrying all
had to guess which one they had.

## Every future completes

Nothing here completes exceptionally, and nothing here can hang. Every stage is
guarded, including against `Error` — a FAWE version whose API moved throws
`NoClassDefFoundError`, which a `catch (Exception)` does not see.

That is the ExyliaCommons bug this fixes most directly: it chained
`getChunkAtAsync().thenAccept(...)` with no `exceptionally`, so one chunk that
failed to load left the caller waiting on a future that would never complete.
The arena never regenerated and nothing said why.

A plugin being disabled completes whatever it still has in flight as `FAILED`,
naming the schematic, rather than leaving a promise its own scheduler can no
longer keep.

## `exists()` never touches the disk

This is a contract, not an implementation detail.

Roughly twenty call sites in the ecosystem are menu renders. ExyliaCommons
answered each with `File.exists()` — one stat syscall per slot per redraw, on
the thread that also runs the game. Here the names are read once, off the main
thread, when a plugin first asks for its schematics, and kept in step by `save`
and `delete`.

Two consequences worth knowing:

- A file created or deleted **behind the module's back** is not noticed until
  the next restart. A server owner dropping a `.schem` in by hand has to reload.
- Between a plugin enabling and its first listing finishing, `exists()` answers
  `false` for everything. `isIndexed()` says whether that moment has passed.

Neither costs anything real: an operation asked for anyway reads the disk and
answers `NOT_FOUND` honestly.

## Where files live

| | |
| --- | --- |
| written to | `<plugin data folder>/schematics/` |
| also read from | `<plugin data folder>/schematics/regions/` |

The second is where ExyliaCommons wrote them, and those files are the arenas on
production right now, so they are still read and can still be pasted without
re-saving. A name present in both resolves to the new folder, which is what
makes a re-save an upgrade rather than a second copy nobody reads. `delete`
removes both — leaving the old copy would make a deleted arena come back at the
next restart.

Files are `<name>.schem` in either folder.

## Names are validated

A schematic name becomes a filename, so it is checked before it is concatenated
onto a path. Letters, digits, `.`, `_` and `-`, up to 128 characters, no `..`,
no leading dot.

ExyliaCommons had no check at all, so `../../plugins/Other/config` was a valid
schematic name and an empty one was a file called `.schem` that every plugin
shared. A refused name is a `FAILED` result and a console line naming the plugin
whose config holds it — never an exception that takes down the menu reading it.

## Regenerating

```java
schematics.regenerate("arena_1", bounds, world, RegenerateOptions.defaults());
```

Three stages, in this order and no other:

1. **Clear** — non-player entities inside the bounds are removed, while the old
   blocks are still there.
2. **Paste** — the blocks go back, air included, so what the last match built is
   cleared rather than pasted around.
3. **Rescue** — anyone the new blocks buried is moved up to the nearest air.

Rescuing first would put a player back inside a wall that had not been placed
yet. Both the first and the third are switches:

```java
RegenerateOptions.defaults()
        .clearEntities(false)       // the armour stands are the build
        .moveTrappedPlayers(false); // nobody is in there
```

Both default to on, because both describe damage a regeneration does if nobody
asks for them, and a caller that has not thought about it wants the arena that
works rather than the one that suffocates whoever stood in it.

## Threading

Every method is safe from any thread, and none of them blocks.

| Work | Where it runs |
| --- | --- |
| reading and writing files, clipboards, diffing chunks | `runAsync` |
| snapshotting and writing chunks (built-in engine) | `runAtLocation`, under the tick budget |
| reading the entities in a box | `runAtLocation` |
| moving a trapped player | `runAtEntity` |
| `exists()`, `names()`, `isIndexed()` | the calling thread, from memory |

Futures complete on whichever thread finished the work, so a caller that then
touches the game hops back through `Tasks.of(plugin).runAtLocation(...)` first.

## What is held in memory

With FAWE: loaded clipboards, up to **8**, dropped after 10 idle minutes. With
the built-in engine: parsed schematics up to **128 MB** in total, weighed by
their size rather than counted, dropped after 10 idle minutes and re-read when
the file on disk changes.

Deliberately small. A clipboard is not a cache entry like a parsed component: a
200×80×200 arena is millions of block states, tens of megabytes live.
ExyliaCommons held fifty, which on a practice server with large arenas is most
of a heap spent on schematics nobody is pasting. Eight is the working set of a
server mid-rotation, and anything past that costs one file read on a thread that
is already off the main one.

`weakValues()` was considered and rejected: it would make the hit rate depend on
GC timing, so it would be least predictable exactly when the heap is under
pressure — which is when a re-read is cheapest to afford.

## The palette

This module caches nothing derived from the colour palette, so it has **no**
`invalidateAll()` and is deliberately outside `ExyliaLib.loadPalette`. Nothing
it holds is a component: a clipboard is block data, and the only text it ever
handles is a filename.

Declared rather than left unsaid, because point 8 of the quality bar asks for it
either way.

## Cleanup

| When | What happens |
| --- | --- |
| a plugin is disabled | its outstanding futures complete as `FAILED`, its index is dropped |
| the server stops | the same, plus every clipboard is released |

Released **before** the task module, because a paste in flight is a chain of
tasks on that plugin's own scheduler: cancelling the scheduler first would leave
the stage that completes the future unable to run.

## Where the code is

| | |
| --- | --- |
| API | `net.exylia.lib.schematic` — `Schematics`, `PluginSchematics`, `SchematicResult`, `SchematicOutcome`, `RegenerateOptions` |
| Internal | `net.exylia.lib.schematic.internal` — `SchematicRuntime`, `SchematicStore`, `SchematicNames`, `Bounds`, `Engines`, `SchematicEngine`, `FaweEngine`; the built-in engine: `NativeEngine`, `SpongeSchematic`, `Nbt`, `ChunkDiff`, `TickBudget`, `NativeCodec` |
| Tests | `src/test/java/net/exylia/lib/schematic/internal/` — `SchematicTest` (the module), `SpongeSchematicTest` (the format, against FAWE's own NBT streams), `NativeEngineTest` (diff, budget, binding) |

`FaweEngine` is the only class that names FastAsyncWorldEdit types — `Engines`
never mentions it outside one guarded call, not even in an `instanceof` — so a
server without it never loads that one — verified in bytecode, the same way PacketEvents
and Folia are. `SchematicEngine` is the seam a test installs to exercise every
decision the module makes with no FAWE and no server.
