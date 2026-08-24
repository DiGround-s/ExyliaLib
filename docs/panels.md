# Panels

Configuration panels: a record edited on screen, with bounded undo and a diff
before anything is written.

> **Status.** This page documents the engine core shipped in 1.50.0 — the
> session, the working copy, undo, the diff, and release — and the **settings
> panel** built on it. The list panel is documented separately.

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

---

# The settings panel

A configuration editor **generated from a config record's schema**, with no
per-record code:

```java
Panels.of(this).settings(effectConfigFile).open(player);
```

That line is the entire effects editor. There is nothing to register, nothing to
describe, and no screen to keep in step with the record — a record this library
has never seen edits exactly as well as one it ships.

## The API

```java
public final class PluginPanels {
    public <T extends Record> SettingsPanel<T> settings(ConfigFile<T> file);
}

public final class SettingsPanel<T extends Record> {
    public SettingsPanel<T> title(String title);       // Exylia text notation
    public SettingsPanel<T> onSaved(Consumer<T> action);
    public void open(Player viewer);                   // any thread
}
```

`open` is safe from anywhere; it relocates itself onto the thread that owns the
player. There is deliberately **no `openNow`** — see *Threads* above.

## One control per component, chosen from the declared type

| Declared type | Control |
| --- | --- |
| `int`, `long`, `short`, `byte` (and boxed) | integral number |
| `double`, `float` (and boxed) | decimal number |
| `boolean` | toggle |
| enum | searchable choice over the constants |
| `String` | text input |
| `List<E>` | list sub-panel |
| nested record | sub-panel over the nested schema |
| anything else | read-only (see below) |

Controls appear in **canonical-constructor order**, so reading order on screen is
declaration order in the record.

Chosen from the *declared* type, never from the value: a `long` holding zero is
still a number control, and a null `String` is still text. Deciding from the
value would make a screen change shape as it is edited.

The mapping is a **table**, not control flow. `ControlMapper` takes a
`Schema.Field` and returns a `ControlKind`; there is no `switch` over any domain
type anywhere, and adding a supported type is one entry. `SettingsControlMappingTest`
asserts the mapper's whole public input is the projection, so a branch that
learned something about a particular record fails the build.

An enum is offered through the input module's **`SearchInput`**, not a
hand-rolled picker — an enum can be longer than a screen, and the input module
already solved paging, filtering and every transport.

## `@Comment` lines become the lore

The comment lines above a component are its tooltip, in declaration order:

```java
record Settings(
        @Comment("Connections kept open.")
        @Comment("Rule of thumb: cores × 2.")
        int poolSize) { }
```

Those two lines are already the server owner's manual by doctrine. Until now, the
only way anybody read them was by opening the `.yml`.

## Persistence goes only through `ConfigFile.update`

Save rebuilds the whole record through its canonical constructor, in declared
component order, and hands the **finished record** to
`ConfigFile.update(UnaryOperator<T>)`.

The panel never writes YAML and never touches a `FileConfiguration`. Migrations,
key pruning and comment preservation stay owned by the `config` module, which is
the only thing that knows how to keep them.

Save is atomic at record level: the whole rebuilt record, or nothing. Editing one
component of five leaves the other four equal to what they were, because they are
carried through the rebuild rather than re-read.

An empty diff **writes nothing at all** — opening a panel to look at something
must not rewrite the owner's file. `cancel` discards the working copy; a save
afterwards never reaches `update`.

## The rebuild is pure, and it happens first

`RecordRebuilder` returns a record **or a rejection**, and never throws for a bad
value. Two things can go wrong and both are answers:

- a value of the wrong type — `IllegalArgumentException`
- a compact constructor that refused — `InvocationTargetException`

A compact constructor is allowed to say no. `Effects.ParsedEffect` throws on a
blank name; `EffectConfig.Title` has one. The cause's message becomes a
player-facing `Validation` failure — "an effect needs a name", not
"InvocationTargetException" — **the working copy is not mutated, and the config
file is never opened**.

That ordering is the point. Wrapping `ConfigFile.update` in a try/catch would put
the refusal on the wrong side of the decision to write; doing it first means
there is no write path to unwind, because none has been entered.

Both entry points are guarded independently: the panel's own save button, and a
plugin calling `PanelSession.save()` on a session it is holding. A guard on only
one is a guard the other caller walks straight past.

`RecordRebuilder` uses `java.lang.reflect` — `Class.getRecordComponents()` plus
the canonical constructor, public JDK API on a public class. It never reaches
into `config.internal`, which is what keeps `Schema` a pure value and let the
schema projection and the panel ship as independent slices.

## Sub-panels share the parent's working copy

A nested record is not a second panel with a second working copy. Entering one
pushes a frame; every edit rebuilds the nested record and writes it straight into
the parent's component, all the way to the root.

So leaving a sub-panel has nothing to gather, and losing one loses nothing. Inside
a sub-panel, cancel is a **way back**, not a way out — leaving the whole panel from
a nested screen would throw away edits made two levels up without saying so.

## Components with no control

A component whose declared type this library cannot edit — a `UUID`, a
`BigDecimal` — is:

- **drawn read-only**, with its `@Comment` lore and a line saying why
- **passed through a save untouched**, by identity: not rebuilt, not defaulted,
  not dropped
- **reported once per server**, per type

and it never prevents opening, editing the other components, or saving.

Clicking it does nothing and opens no input request. A value nobody can see is a
value nobody notices losing, and refusing a whole screen over one field would
lose the other twelve.

**Once per server, not once per item.** That is the `ItemComponents` lesson,
applied before it could be re-learned: opening a settings panel renders every
component, so reporting per drawn control put eighteen identical lines in the
console for a single screen. Which types this library has no control for is a
fact about the library, not an incident of the panel that happened to open.

`UnsupportedComponentTest` proves the silence is the *memory* and not a component
that quietly stopped being drawn: it forgets, reopens, and asserts both that every
component is still drawn and that the line comes back.

## The effects editor is this panel, and that is testable

```java
Panels.of(this).settings(effectConfigFile).open(player);
```

`EffectConfig` is already a record with 45 `@Comment` lines across six nested
records. It gets a sub-panel per nested record, a control per component, and its
comments on screen — with **zero** `EffectConfig`-specific code in the library.

`EffectConfigGenericPathTest` enforces that from **compiled bytecode**, not from
source text: a reference is what actually couples two classes, and an import can
be absent while a fully-qualified name inline does the coupling anyway. It sweeps
every class under `panel` and `panel.internal` and fails if any constant pool
names `EffectConfig` or one of its nested records.

Three guards, because an absence assertion that examined nothing passes for the
wrong reason: the assertion itself, a non-vacuity check naming the classes the
sweep must have read, and a detector check fed a class that *does* reference the
banned type.

That test is the evidence the abstraction is right. One branch there, and the next
config record needs its own screen too.

## Threads (settings panel)

| What | Where |
| --- | --- |
| `SettingsPanel.open` | any thread — relocates via `runAtEntity` |
| control mapping, rebuild, diff, undo | any thread; no Bukkit API |
| drawing, click handling | the thread that owns the viewer |
| the write | `runAsync` → `ConfigFile.update` → back via `runAtEntity` |

`onSaved` runs on the viewer's thread with the record that was persisted, so it is
safe to touch the game from it. It is not run when nothing changed, because
nothing was written.

# The list panel

One paginated editor for every element type, parameterised by a
`FieldDescriptor<T>`. ExyliaCommons had five of these — rewards, potions,
commands, messages, items — copy-pasted from each other and drifting apart.
Here there is one, and adding an element type is one interface implementation.

```java
Panels.of(this).list(new WarpDescriptor(store)).open(player);

Panels.of(this).list(new WarpDescriptor(store))
        .title("{primary}&lWARPS")
        .onSaved(warps -> reloadSigns(warps))
        .open(player);
```

The viewer gets pagination, search, add, copy, paste, edit, delete, undo, save
and cancel. **No panel, menu, session, registry or clipboard class is written for
a new element type** — that requirement is stated as a test
(`ListConfirmDeleteTest`, "a consumer-owned record gets every capability from a
descriptor alone") over a record declared in the test sources, which the library
has never heard of.

## The API

```java
public final class ListPanel<T> {
    public ListPanel<T> title(String title);
    public ListPanel<T> onSaved(Consumer<List<T>> action);
    public void open(Player viewer);                 // any thread
}

public interface FieldDescriptor<T> {
    String label(T entry);                           // never null, never blank
    String icon(T entry);                            // material or head source
    String identity(T entry);                        // never null, never blank
    T create();
    T duplicate(T entry);                            // a NEW identity
    CompletionStage<InputResult<T>> edit(Player viewer, T entry);
    List<T> load();
    void save(List<T> entries);                      // called off the viewer thread

    default boolean matches(T entry, String query);  // searches label()
}
```

`PluginPanels` gains one method: `<T> ListPanel<T> list(FieldDescriptor<T>)`.

## Rows are addressed by what they carry, never by index

Every drawn row is a `UiEntry` carrying its element, and every operation — edit,
copy, delete — resolves its target from that carried value. No slot number, page
number or list index is ever entry identity.

This is not tidiness. It is the verified Commons bug: four of its five editors
addressed a row by a UUID, and the potion editor addressed it by list index
(`commons:potion_delete 1`), so pagination plus a deletion removed the wrong
effect.

`ListEntryIdentityTest` proves it in three shapes, because **a naive delete test
catches none of them** — on page one with no filter, a row's index and its
element agree:

| Shape | Why it separates the two lookups |
| --- | --- |
| a later **page** | the row's page index is not its list index |
| a non-contiguous **filter** | the first shown row is not list index 0 |
| a **reorder between draw and click** | fails even an implementation whose page arithmetic is right |

The third is the strongest and the specification asks for it by name. A plugin
editing the same list from a command while a panel is open does exactly this,
which is why `reorderForTests` is a seam rather than a contrivance.

There is a structural guard as well: no field of `ListEngine` may be a
`Map<Integer, Integer>`, and exactly one must be a `Map<Integer, UiEntry>` — with
a detector test fed both shapes, so the sweep cannot pass by recognising nothing.

## Search filters the view, never the working copy

A search narrows what is drawn. The working copy is untouched, which is what
makes clearing a search free — and what stops a save after a search from
persisting a **truncated list**, silently, because the screen would look exactly
right.

Searching reuses the existing `SearchInput` through the `PanelPrompts` seam. The
engine never calls `Inputs` directly, which is both why a test can script the
answer and why there is no second search implementation to keep in step.

A filter is not an undo step either: a viewer taking back their last change gets
the change back, not the search.

**An empty result explains itself.** The three fillers are three things
(`AGENTS.md` §Menús): the background is decoration, and this one *says why*. A
viewer whose search matched nothing has a search to clear; one whose list is
genuinely empty has an entry to add, and the two read differently on purpose —
somebody who cannot tell them apart clears a search that was not the problem.

## Copy, paste, delete, undo

- **Copy** is not an edit. Nothing about the list changed, so nothing is pushed
  onto undo: a viewer who copies and then undoes wants their last real change
  back, not the copy taken away.
- **Paste** inserts `duplicate(entry)`, never the entry itself. Where the
  descriptor defines an identity the pasted row gets a new one, or the two rows
  address each other's deletes — the same class of bug as addressing a row by
  index.
- **Paste with an empty clipboard is a no-op**, not an error. Pressing paste
  before copying is an ordinary thing a person does.
- **Delete asks through `ConfirmInput.dangerous()`** — typed rather than clicked.
  An unanswered question is not a yes, and a timeout is how most confirmations
  end.
- **Everything is undoable**, bounded at `Panels.undoLimit()` (20).

**The clipboard dies with the session.** It is a field of `Session`, not a
`static Map<UUID, T>` — that shape survives the panel, the player *and* the
plugin, so a copy made an hour ago still pins an object nobody can reach.
`ListClipboardTest` asserts it is gone after close, after quit and after the
owning plugin is disabled, **separately**, plus a structural check that no static
collection exists on the engine to be cleared in the first place.

## Diff before save, all-or-nothing cancel

The diff names **entries**, not components: a list panel edits one component, so
a component diff could only ever say "one thing changed". Entries are paired by
`identity()`, which is what makes an edit distinguishable from a removal plus an
addition — the identity survives an edit and a new row's does not.

Save persists only through `FieldDescriptor.save`, once, off the viewer's thread,
with the whole list. A save whose diff is empty writes nothing at all.

**Both save doors carry the guard.** The panel's own button and a plugin calling
`PanelSession.save()` take the same path, and both are asserted independently.
That is not belt-and-braces: a sabotage on the settings panel found exactly this
hole, where a guard sat on one door and the other caller walked past it.

Cancel discards the **whole** working copy — deletions, pastes and edits alike.
So does closing the window, quitting, and the owning plugin being disabled.

## Threads (list panel)

| What | Where |
| --- | --- |
| `ListPanel.open` | any thread — relocates via `runAtEntity` |
| `label`, `icon`, `identity`, `create`, `duplicate`, `matches` | **any thread, must be pure** — they run while filtering and drawing |
| `load` | once, as the panel opens, on the viewer's thread |
| `edit` | the viewer's thread; answers whenever it likes, hence the stage |
| filtering, paging arithmetic, diff, undo | any thread; no Bukkit API |
| drawing, click handling | the thread that owns the viewer |
| `save` | `runAsync`, then back via `runAtEntity` for `onSaved` |

A descriptor is a description, not a session: one instance may serve every viewer
and every open panel, and it is never told a panel closed. Anything per-viewer
belongs to the panel, which gives it back when the screen goes.
