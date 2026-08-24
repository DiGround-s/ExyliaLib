# Panels

Configuration panels: a record edited on screen, with bounded undo and a diff
before anything is written.

> **Status.** This page documents the engine core shipped in 1.50.0 — the
> session, the working copy, undo, the diff, and release. The generated
> settings and list panels build on it.

```java
Panels.of(this).settings(effectConfigFile).open(player);
```

## Why a panel is not a menu

A menu draws what a plugin tells it to draw. A panel *owns an edit*: it holds a
working copy, remembers what it replaced, and decides whether anything needs
writing. Those three things are the whole module, and none of them belongs in
`ui`.

The rule that makes it safe is that **nothing reaches the config file until
save**. Every edit goes into a working copy in memory, so cancel is free, undo
is possible, and a panel opened just to look at something writes nothing at all.

## The API

```java
public final class Panels {
    public static PluginPanels of(Plugin plugin);
    public static Optional<PanelSession> session(Player viewer);
    public static int undoLimit();                    // 20
    public static void release(String pluginName);
    public static void releaseAll();
    public static int registered();
}

public final class PluginPanels {
    public Plugin plugin();
    public Optional<PanelSession> session(Player viewer);
    public void close(Player viewer);
    public int open();
}

public interface PanelSession {
    Player viewer();
    Plugin owner();
    boolean undo();          // false when there is nothing left
    int undoDepth();
    PanelDiff diff();
    boolean save();          // false when nothing changed
    void cancel();
    void close();
    boolean isOpen();
}

public record PanelDiff(List<String> added, List<String> removed, List<String> changed) {
    public static final PanelDiff EMPTY;
    public boolean isEmpty();
    public int size();
}
```

There is no call order to explain. `of` → open → the session answers. That is
the whole contract; if a sequence needed documenting, the API would be wrong.

## The working copy

Every committed edit pushes a snapshot. The stack is bounded at
`Panels.undoLimit()` — **20** — and overflow discards the *oldest* rather than
throwing: somebody making their twenty-first edit is doing normal work, and
refusing it would be a bug dressed as a limit.

The bound is cheap because a snapshot is a **reference**, not a deep copy.
Records are immutable, so the previous value is the instance the next edit would
have discarded anyway; a list snapshot is a `List.copyOf` whose elements are
shared. The realistic worst case — twenty snapshots of a five-hundred entry
reward list — is roughly **80 KB while the panel is open**, released on close.

Twenty is a memory bound, not a preference, which is why it is a constant rather
than a setting. It is above what anybody undoes in one sitting and below where
the copying starts to show.

`undo()` on an empty stack returns `false` and changes nothing. It is a no-op,
never an error.

## Diff before save

`save()` compares what the panel opened with against what it holds now. **An
empty diff writes nothing** — not an empty write, no write at all:

```java
PanelDiff diff = session.diff();
if (!diff.isEmpty()) {
    player.sendMessage("Changing: " + String.join(", ", diff.changed()));
}
```

`save()` returns whether anything was actually written, so a caller can tell
"saved" from "there was nothing to save".

The three lists name **components, not values**. A diff is something a player is
shown before they confirm, and a value could be a password, a serialised
inventory, or a megabyte of list. The names are sorted, so two opens read the
same rather than appearing to reshuffle.

`cancel()` discards the working copy entirely. Nothing is partially persisted:
what is on disk is what was there when the panel opened.

## Threads

| What | Where |
| --- | --- |
| `open` | any thread — it relocates itself onto the thread that owns the player |
| `undo`, `undoDepth`, `diff` | any thread; no Bukkit API is touched |
| `save`, `cancel`, `close` | the thread that owns the viewer, which is where a click handler already is |
| config write | `runAsync`, returning to the viewer's thread before touching the game |

There is deliberately **no `openNow`**. Nothing about a panel needs a session
synchronously, and exposing one would export a thread precondition into an API
whose whole point is that there is nothing to get right.

No `BukkitRunnable`, no `Bukkit.getScheduler()`, no `new Thread`, no private
executor: everything goes through `net.exylia.lib.task`, so the module behaves
identically on Spigot, Paper and Folia.

## Nothing survives its owner

A panel ends five ways — save, cancel, the viewer closing the window, the viewer
leaving the server, the owning plugin being disabled — and all five go through
**one** release path. That is a deliberate design choice: a cleanup branch per
ending is how the leak gets in, because the ending nobody remembered is the one
that leaks.

Release gives back everything the panel took, including any `ActionExecution`
with delayed steps a button started. Releasing twice is harmless; the terminal
state is claimed atomically.

Plugin disable releases panels **before** menus and **before** the task module
drops the plugin's tasks. Ordered the other way, closing the window first would
strand the session that owns the working copy, and cancelling the scheduler
first would leave the delayed steps with nothing left to cancel them.

## No static map keyed by player

State lives on the **window**, resolved through its inventory holder — never in
a `Map<UUID, Session>`. This is not stylistic. A player with a chest open on top
of a panel is looking at the chest; a map still says "this player has a panel",
so a click in the chest would be handed to it. The holder knows what a window
is; the player does not.

`PanelNoStaticStateTest` sweeps every compiled field in `panel` and
`panel.internal` and fails on any collection or cache keyed by `UUID` or
`Player`. It also asserts that the sweep examined the production classes and
that its detector detects — an absence assertion that examined nothing passes
for the wrong reason.

## Palette reload

**The panel module caches nothing derived from the palette.** It has no
`invalidateAll()` and no hook in `ExyliaLib.loadPalette`, and that is a decision
rather than an omission.

What it holds is layouts and item *definitions*, which carry raw text such as
`{primary}&lSAVE`. What that resolves to is decided when the panel is drawn, and
drawing goes through `PluginItems.render`, whose `ItemCache.invalidateAll()` the
palette listener already calls. Adding a second cache here would recreate the
1.16.0 static-effect bug, where a permanent boss bar kept last week's colours
because it was drawn once and never re-parsed.

`PanelPaletteTest` keeps that true by failing on any retained `Component`, and
proves the built-in layout still carries its palette tokens rather than resolved
hex. See [reload.md](reload.md).

**Honest limit**: a panel already on screen redraws on its next interaction.
That is `ui`'s existing behaviour, and `panel` adds no new staleness.

## Test seams

Package-internal, following the established precedents. They are not decoration:
without them the tests could not exist, because `Bukkit.createInventory` returns
nothing and `ItemStack` cannot even class-initialise without a running server.

| Seam | What it replaces | Precedent |
| --- | --- | --- |
| `PanelRenderer.sink(DrawSink)` | reading an `ItemStack` to learn which control landed in which slot | `ItemRenderer.components` |
| `PanelPrompts.install(Prompts)` | every input transport at once — the panel never calls `Inputs` directly | `Engines.install` |
| `PanelRuntime.setClock/resetClock` | the system clock, so time moves without sleeping | `Cooldowns.setClock` |
| `UnsupportedTypes.forgetReportedForTests()` | the once-per-server memory, so "reported once" is assertable twice | `ItemComponents.forgetReportedForTests` |
| `Layouts` / `Layouts.BUILT_IN` | the layout file, so a missing or malformed one is testable | `Engines.install` |
| `Session.forTests(...)` | a real window, which no test can open | `FakeServer` / `FakePlayer` |

**`PanelPrompts` is a rule, not a convenience.** The engine never calls `Inputs`
directly. Every question a panel asks goes through that seam, which is what lets
a test script the answers and assert what the panel *did* with them.

## Layouts

Slots, sizes, titles and colours come from layout files owned by ExyliaLib, the
same way `colors.yml` is — a server themes every panel once, rather than twenty
consumers shipping two theme files each. **Slots and sizes are never written into
engine control flow.**

`Layouts.BUILT_IN` is the answer of last resort: a complete, usable panel built
in Java. An owner who deletes every layout file still gets a working screen,
which is what makes theming optional rather than load-bearing. A missing,
unreadable or malformed layout falls back to it, is reported **once** via
`Debug.of(ExyliaLib)`, and still opens.

## Unsupported components

A component whose declared type has no control is drawn read-only and **passed
through untouched** — never dropped. A value nobody can see is a value nobody
notices losing.

It is reported **once per server, per type**, not once per item and not once per
open. Opening a settings panel renders every component, so reporting per control
put eighteen identical lines in the console for a single screen. Which types
this library has no control for is a fact about the library, not an incident of
the panel that happened to open. That is the `ItemComponents` lesson, applied
before it could be re-learned.
