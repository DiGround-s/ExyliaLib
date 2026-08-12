# Reloading

There is no reload *system*, because none is needed: each side reloads what
it owns, and nothing else. Since 1.14.0 the library's half of that is
reachable from in-game.

## The library: `/exylialib reload`

Registered through Lamp (`io.github.revxrsal:lamp.*:4.0.0-rc.17`, the
framework the whole ecosystem standardises on), permission
`exylialib.admin`. It reloads `colors.yml` — the only state of the library a
server owner edits.

One command recolours the whole server. The chain, verified in code:

```
/exylialib reload
  → palette.reload()
      → Colors.apply(new palette)              palette tokens mean new colours
      → TextEngine cache dropped               nothing stale is re-shown
      → BoardManager.invalidateAll()           scoreboards re-send themselves
      → HologramRuntime.invalidateAll()        holograms too
```

`/exylialib` alone shows the version and the subcommand.

## A plugin: `Reloads` (since 1.15.0)

A consumer reloads **itself** and never touches the library. `Reloads`
declares what that means, as named steps:

```java
private final Reloads reloads = Reloads.of(this)
        .step("configs", () -> Configs.reloadAll(this))
        .step("debug",   () -> debug.enabled(config.get().debug()))
        .stepAlsoOnLibraryReload("menus", menus::rebuild);

@Subcommand("reload")
@CommandPermission("myplugin.admin")
public void reload(CommandSender sender) {
    reloads.run(sender);   // Reloaded 3 steps in 12ms
}
```

| Method | Contract |
| --- | --- |
| `Reloads.of(plugin)` | starts a declaration |
| `.step(name, action)` | runs on the plugin's own reload, in declaration order |
| `.stepAlsoOnLibraryReload(name, action)` | that, and also when the library reloads |
| `.run()` | runs everything, returns a `Report`, prints nothing |
| `.run(sender)` | that, plus a one-line summary to the sender and the console |
| `.stepCount()` | how many steps are declared |

`Report`: `steps()`, `failed()` (names, in order), `millis()`, `ok()`,
`describe()` — `Reloaded 3 steps in 12ms`, or
`Reloaded 2/3 steps in 12ms — failed: menus`.

**A failing step does not stop the ones after it.** It is caught, reported by
name through the debug module, and the next step runs. A half-reloaded plugin
is worse than one that says plainly which part failed.

Reloading is synchronous: reading small YAML files and re-sending packets does
not need futures or an orchestrator.

Placeholders need no re-registration — resolvers read live state.

## When the library reloads, plugins are told

The gap `Reloads` closes: a plugin that parsed something **once** and kept it
(a menu built at startup) holds the old colours after a recolour, because
nothing re-parses it.

```java
Reloads.onLibraryReload(this, menus::rebuild);
```

Runs after `/exylialib reload`. It is a **notification, not an invocation**:
the library announces that shared configuration changed, and each plugin
decides what that means. A listener that throws is reported against its own
plugin and does not stop the others. Listeners are dropped when the plugin
disables.

`.stepAlsoOnLibraryReload(...)` is the shorthand: the same action, registered
both as a step of the plugin's reload and as a library listener. An ordinary
`.step(...)` is **not** a listener — re-reading a plugin's own files is not
what a recolour means.

## Changing colours: which command?

**Only `/exylialib reload`.** Everything the library renders — scoreboards,
holograms, effects, and any `Text` built on the fly — picks up the new
palette, because `Text` re-parses after the cache drop.

The honest exception: what a plugin parsed *once* and kept as state (a GUI
built in `onEnable`, say). That is plugin state with old colours baked in;
the convention is that such things are rebuilt in the plugin's `onReload`.

## What a palette reload actually refreshes

Audited module by module, and covered by `PaletteReloadTest`. The pattern
that matters: **anything that caches a parsed component must be invalidated,
and anything that re-parses per render was never at risk.**

| Module | State it keeps | On `/exylialib reload` |
| --- | --- | --- |
| text | `TextEngine` component cache | Dropped by `Colors.apply` — every later parse uses the new colours |
| scoreboard | last rendered lines per board | `BoardManager.invalidateAll()` re-sends every board in full |
| hologram | last text sent per viewer | `HologramRuntime.invalidateAll()` re-sends every hologram |
| effect (static) | the component, drawn once and left alone | `EffectRuntime.invalidateAll()` re-parses and re-draws — **added in 1.16.0; this was silently stale before** |
| effect (dynamic) | nothing; rebuilds each cycle | Picks up new colours on its own |
| placeholder | compiled templates (structure, not colour) | Nothing to do — templates hold the raw text, and rendering goes through `Text` |
| clan / client / cooldowns / util | no rendered text | Nothing to do |
| plugin state | whatever a plugin parsed once and kept | Told through `Reloads.onLibraryReload` — the plugin rebuilds it |

### The rule for new modules

If a module holds a `Component` — or anything derived from the palette —
beyond a single render, it **must** expose an `invalidateAll()` and be called
from the palette listener in `ExyliaLib.loadPalette`. The cheap-static-path
optimisation is exactly what creates this bug: an effect drawn once and left
alone is the one that keeps last week's colours.

Anything else a module caches (clan lookups, parsed potion strings, cooldown
state) has nothing to do with the palette and is deliberately left alone.

## Why not "reload lib → reload plugin"

That order is backwards and harmful: the plugin needs nothing from the
library to reload, and the palette is everyone's — reloading it from one
consumer's command would re-send every scoreboard and hologram of every
plugin on the server. `/exyliaffa reload` recolouring the whole server would
be a bug with good intentions.
