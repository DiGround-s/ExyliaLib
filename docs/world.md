# World module

Creating and deleting worlds through the Worlds plugin
(net.thenextlvl.worlds), on a server where Bukkit's own `WorldCreator` cannot be
driven safely from an arbitrary thread — which on Folia is every thread.
Since 1.36.0.

Entry point: `net.exylia.lib.util.world.Worlds`.

```java
if (Worlds.isAvailable()) {
    Worlds.create(Key.key("myplugin", "arena"), "arena_1").thenAccept(world -> {
        if (world == null) {
            return; // the plugin declined; fall back to a vanilla path
        }
        // the world is loaded and ready
    });
}
```

## API

| Method | Contract |
| --- | --- |
| `isAvailable()` | whether a compatible Worlds is installed and bound |
| `backendName()` | `"Worlds 4.x"`, `"Worlds 4.0.x"`, `"Worlds 3.x"` or `"none"` — diagnostics |
| `create(key, name)` | void world (no terrain, no structures); the same as `create(key, name, true)` |
| `create(key, name, voidWorld)` | `false` leaves the world for your own generator |
| `delete(world)` | `true` on success |

`key` is a `net.kyori.adventure.key.Key` identifying the level; `name` is the
legacy world folder and name it is exposed under.

## Behavior

- **Nothing throws.** Without a compatible Worlds installed, `isAvailable()` is
  `false`, `backendName()` is `"none"`, `create` completes with `null` and
  `delete` with `false`. A failure inside the plugin is logged and collapses to
  the same answer, so a caller can fall back to a vanilla Bukkit path.
  `isAvailable()` is what tells "Worlds is not usable here" apart from "Worlds
  tried and refused this particular world".
- **One probe per server run.** The backend is detected at first use — deferred,
  so it happens after the Worlds plugin has enabled — and remembered, negative
  answers included. A server without Worlds pays the classloading once.
- **Detection is by capability, not by version string.** Each backend is
  constructed in turn, newest generation first, and the first one that binds
  every member it needs wins. A Worlds release that renamed a method is turned
  away during that probe rather than throwing mid-creation.
- **Two generations, two backends.** 3.12.x (MC 1.21.x) puts the void in a
  preset and creates through a `WorldsProvider` service; 4.x (MC 26.x) puts it
  in a flat generator type and creates through `WorldsAccess`. They are not the
  same shape, so they do not share a reflective path.
  `Level.Builder#legacyName` only exists from 4.1.0, which is what
  `"Worlds 4.0.x"` reports.
- **Everything is reflection, and it has to be.** 4.x ships as Java 25 bytecode
  while this library targets Java 21, so it cannot go on the compile classpath at
  all. No Gradle dependency is added for this module.

## Two reflection constraints

Both were paid for with a real failure:

- **Classes load through the Worlds plugin's own classloader.** Paper gives each
  plugin its own, so a plain `Class.forName` reports "not installed" on a server
  that has it installed.
- **Members resolve with `MethodHandles.Lookup`, never `Class#getMethod`.**
  `getMethod` makes the JVM resolve the descriptor of *every* method on the
  class, and `WorldsProvider` declares
  `default GroupProvider groupProvider()` — a return type from the separate,
  optional PerWorlds plugin. On a server without PerWorlds, asking for an
  unrelated method throws `NoClassDefFoundError`. `findVirtual`/`findStatic`
  resolve only the one descriptor asked for.

## Threads

Every method is safe from any thread and none of them blocks. The futures
complete on whichever thread the Worlds plugin finishes on, so a caller that
then touches the game hops back first:

```java
Worlds.create(key, "arena_1").thenAccept(world -> {
    if (world != null) {
        tasks.runAtLocation(world.getSpawnLocation(), () -> setUpArena(world));
    }
});
```

## Reload

Nothing derived from the palette is cached here, so the module has no
`invalidateAll()` and is deliberately absent from `ExyliaLib.loadPalette`. What
it does remember is the detected backend, dropped by
`WorldsBackendDetector.reset()` — internal, for a reload flow that has to pick up
a Worlds plugin enabled after the first probe.

## Source and tests

- Public: `util/world/Worlds.java`.
- Internal: `util/world/internal/` (`WorldsBackend`, `WorldsBackendDetector`,
  `WorldsReflection`, `Worlds3Backend`, `Worlds4Backend`).
- Tests: `src/test/java/net/exylia/lib/util/world/WorldsTest.java` — the
  degradation contract, which is the path every server without Worlds takes.
