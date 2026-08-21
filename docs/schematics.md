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

## One engine, on purpose

FastAsyncWorldEdit and nothing else. ExyliaCommons carried a second,
hand-written engine as a fallback, and every one of its problems was silent:

- **Block-entity NBT was lost.** A SurvivalGames arena regenerated with empty
  loot chests, a spawner became a plain block, a sign lost its text.
- **Palettes past 32767 states were truncated**, because block states were
  packed into a `short[]`.
- **Blocks were applied one at a time**, so a regeneration was one client packet
  per block.

FastAsyncWorldEdit is installed on production, so the fallback is not ported.
Keeping it would mean maintaining a second, worse answer to a question that is
already answered — and choosing between them at runtime, which is what made the
NBT loss so hard to see.

## What is needed

FastAsyncWorldEdit. Without it `Schematics.isSupported()` is `false` and every
operation completes with `UNSUPPORTED` rather than throwing — the same
degradation as holograms without PacketEvents. The reason is printed once at
startup rather than once per refused call, because it is a fact about the server
rather than about the request.

`Schematics.unsupportedReason()` is the sentence to show an admin.

FAWE is `compileOnly` and is never shaded. It is a plugin: a second copy of
WorldEdit on one server is a classloading accident waiting to happen.

## What it does not do

**Folia.** FastAsyncWorldEdit does not support region threading, so on Folia
`isSupported()` is `false` with that as its reason. The server starts, every
other module keeps working, and this one does nothing — loudly. Everything
outside the engine is already scheduled the Folia way (`runAtLocation` for
blocks, `runAtEntity` for players), so the day FAWE supports it, this is one
line.

**Entities, by default.** A schematic carries its block entities for free — that
is the point above — but loose entities are opt-in:

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
| `UNSUPPORTED` | no engine — FAWE absent, unbindable, or Folia |
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
| reading and writing files, clipboards | `runAsync` |
| reading the entities in a box | `runAtLocation` |
| moving a trapped player | `runAtEntity` |
| `exists()`, `names()`, `isIndexed()` | the calling thread, from memory |

Futures complete on whichever thread finished the work, so a caller that then
touches the game hops back through `Tasks.of(plugin).runAtLocation(...)` first.

## What is held in memory

Loaded clipboards, up to **8**, dropped after 10 idle minutes.

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
| Internal | `net.exylia.lib.schematic.internal` — `SchematicRuntime`, `SchematicStore`, `SchematicNames`, `Bounds`, `Engines`, `SchematicEngine`, `FaweEngine` |
| Tests | `src/test/java/net/exylia/lib/schematic/internal/SchematicTest.java` |

`FaweEngine` is the only class that names FastAsyncWorldEdit types, so a server
without it never loads that one — verified in bytecode, the same way PacketEvents
and Folia are. `SchematicEngine` is the seam a test installs to exercise every
decision the module makes with no FAWE and no server.
