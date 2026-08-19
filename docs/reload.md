# Reloading

There is no reload *system*, because none is needed: each side reloads what
it owns, and nothing else. Since 1.14.0 the library's half of that is
reachable from in-game.

## The library: `/exylialib`

Registered through Lamp (`io.github.revxrsal:lamp.*:4.0.0-rc.17`, the
framework the whole ecosystem standardises on). Every subcommand sits behind
`exylialib.admin`, including the read-only ones — server admin commands are
conventionally gated by one node.

| Subcommand | What it does |
| --- | --- |
| `/exylialib` | Overview: what the command does |
| `/exylialib reload` | Reloads the library's own runtime settings |
| `/exylialib info` (since 1.35.0) | Version, platform, `config.yml` switches, and which plugins depend on the library |
| `/exylialib stats` (since 1.35.0) | Live counters from every module |
| `/exylialib export <plugin>` (since 1.36.0) | Writes that plugin's tables to a dump |
| `/exylialib import <plugin> <file> [force]` (since 1.36.0) | Reads one back; `force` **merges**, it does not replace |

### `/exylialib reload`

Reloads **five files**, not just `colors.yml`: `config.yml`
(`LibrarySettings` — debug, small text, auto-update), `colors.yml` (the
palette), `formats.yml`, `economy.yml` and `input.yml`. All five are the
library's own shared configuration, and a server owner running one reload
command means all of it — see `ExyliaLib.reloadPalette()`, whose name is a
holdover from when the palette was the only file, kept for the smaller diff.

The chain, verified in code:

```
/exylialib reload
  → LibrarySettings.reload()                   config.yml: debug, small text, auto-update
  → TextEngine.smallText(...)                  applied before the palette, so nothing renders stale
  → palette.reload()
      → Colors.apply(new palette)              palette tokens mean new colours
      → TextEngine cache dropped               nothing stale is re-shown
      → BoardManager.invalidateAll()           scoreboards re-send themselves
      → HologramRuntime.invalidateAll()        holograms too
      → EffectRuntime.invalidateAll()          static effects re-draw
      → ItemCache.invalidateAll()              rendered items are built again
  → formats.reload()                           formats.yml
  → economy.reload()                           economy.yml
  → input.reload()                             input.yml
```

### `/exylialib info` and `/exylialib stats`

Both are read-only diagnostics. Neither adds tracking to the library: `info`
reads `Platform.current()`, `LibrarySettings.get()`, and its dependent list is
the union of two signals Bukkit and the library already expose —
`Bukkit.getPluginManager().getPlugins()` (checking each enabled plugin's
`plugin.yml` for `ExyliaLib` under `depend` or `softdepend`) plus
`Debug.registeredPlugins()` (every plugin that has ever called `Debug.of(this)`,
which is nearly every consumer sooner or later, regardless of what it declared).
A plugin only needs one of the two to be listed, so calling
`Databases.of(this)` or `Menus.of(this)` without ever naming the library in
`plugin.yml` is still caught. `stats` reads the counters every module already
exposes for diagnostics —
`BoardManager.activeCount()`, `HologramRuntime.count()`, `Effects.active()`,
`Menus.registered()`, `Actions.registered()`, `Regions.registered()`,
`Databases.registered()`/`isReady()`/`engine()`, `Redis.isActive()`/`stats()`
and `Configs.loaded()`.

### `/exylialib export` and `/exylialib import` (since 1.36.0)

The transfer module behind a command, so a server owner moves a plugin's
database without anybody writing code. Both sit behind `exylialib.admin` like
the rest, and the plugin argument suggests only plugins that actually store
something — `Databases.registeredPlugins()`, so a name that is suggested is a
name that resolves.

Dumps live in one folder for the whole server, `plugins/ExyliaLib/dumps/`,
rather than one per plugin: a migration moves several plugins at once, and an
owner should be able to copy one directory. The import argument is a **file
name inside that folder**, not a path — it arrives from a chat box, and
`Path.resolve` on `../../server.properties` leaves the folder entirely.

`export` prints the tables it found **by name** before it starts. A plugin
appears to the library only once it has asked for its first repository, so one
that registers a record type lazily exports fewer tables than it owns, and the
names against what somebody expected are the only way that is visible.

`import` refuses by default when a target table already holds rows, names which
and how many, and hands back the exact command to re-run — together with the
sentence that `force` **merges rather than replaces**. It also warns when Redis
is active and the target was non-empty, which is the one case the module's
known limitation actually bites. See [transfer.md](transfer.md).

## Staying up to date (since 1.30.0)

The library updates itself. A newer release is downloaded, verified against
the SHA-256 in the manifest, and written to `plugins/update/`, which the
server applies while it discovers plugins — so the update costs one restart,
not two.

Three moments trigger a check:

| When | Why |
| --- | --- |
| Startup | Covers a server that was killed rather than stopped |
| Every 30 minutes | Covers a server that crashes before it can stop cleanly |
| Shutdown | Runs inline, so the very next start is already up to date |

Without the periodic check, a server that dies to a crash, a `kill -9` or a
host reboot never reaches `onDisable` and sits on an old jar until someone
stops it properly.

```yaml
# plugins/ExyliaLib/config.yml
auto-update: true
update-check-minutes: 30   # 0 leaves only the startup and shutdown checks
```

**Several releases before one restart is the normal case.** Each check
compares against the version *running*, not against the jar already staged,
so the newest release always wins and simply overwrites what is waiting. A
staged jar whose hash already matches is left alone rather than downloaded
again.

Polling is cheap and does not touch any rate limit. The manifest is served
from `raw.githubusercontent.com`, which is a CDN rather than the GitHub API —
the 60-requests-per-hour limit does not apply to it. Checks are conditional
on the file's ETag, so an unchanged manifest answers `304` with an empty
body: measured at 4340 bytes for a changed manifest against 0 for an
unchanged one. At 30 minutes that is 48 round trips a day.

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
| item (static) | the rendered `ItemStack`, name and lore already parsed | `ItemCache.invalidateAll()` drops it, so the next render parses again |
| item (dynamic) | nothing; only static items are held | Rendered per viewer anyway |
| placeholder | compiled templates (structure, not colour) | Nothing to do — templates hold the raw text, and rendering goes through `Text` |
| input | the prompt text, held as the string the plugin passed | Nothing to hold: a prompt is parsed when it is drawn, so the next question already uses the new palette |
| clan / client / cooldowns / util | no rendered text | Nothing to do |
| nametag | a colour and a derived team name per viewer | Nothing to do — a `NamedTextColor` is one of sixteen values the client resolves, not something the palette produces |
| combat | whether a player is tagged, and their stats | Nothing to do — numbers and booleans, expiring on their own in seconds |
| world | the detected backend | Nothing to do — no rendered text, and the Worlds plugin is not hot-swappable |
| transfer | nothing at all, between transfers | Nothing to do — rows move in storage form, which is text and numbers a codec never touched, and a transfer in flight owns only its own streams |
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
