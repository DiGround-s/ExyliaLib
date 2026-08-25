# Wizard module

Walking a player through several questions, and applying nothing until they say
so. Since 1.34.0.

Entry point: `net.exylia.lib.util.wizard.Wizards`.

## Using

```java
private static final WizardKey<String> ID    = WizardKey.text("id");
private static final WizardKey<Long>   SLOTS = WizardKey.integer("slots");

private PluginWizards wizards;
private Wizard arena;

@Override
public void onEnable() {
    wizards = Wizards.of(this).using(config.get().wizard());
    arena = wizards.define("arena")
            .title("{primary}&lNEW ARENA")
            .ask(ID,    step -> step.id("Enter the arena id"))
            .ask(SLOTS, step -> step.integer("Slots").range(2L, 64L))
            .summary()
            .onFinish(this::createArena)
            .build();
}

// From a menu button:
wizards.start(player, arena, () -> openMenu(player));
```

Three objects, deliberately separate. `Wizard` is what the flow *is*, compiled
once and shared by everybody. `WizardRun` is one player's live pass through it.
`WizardValues` is what they answered, typed by `WizardKey`.

## Why this is in the library

ExyliaEvents' `EventConfigWizard` is the concrete case, and it is real code in a
sibling repo — 94 lines that ask a player two questions.

It chained `ChatInputAPI` calls by hand: `askConfigId` calls `askDisplayName`
calls `finishCreation`. Between them the half-built state lived in a
`static Map<UUID, String>`, so the flow had no object of its own — only a chain
of callbacks that each knew the next one. Three consequences follow, and all
three are in the file:

- **The cancel branch is copy-pasted into every step.** Four copies of
  `pendingEventTypes.remove(...)` plus a message, one per way out that anybody
  remembered. A way out nobody remembered — a timeout, the player quitting mid
  flow — removes nothing, so the map keeps the entry and `hasPendingWizard`
  keeps answering `true` for a flow that no longer exists.
- **There is no way back.** A player who mistyped the id in step one finds out
  at step two and has to abandon the flow and start again.
- **Something is created before the player has finished agreeing to it.** The
  menu it came from is reopened only on the success path, so a player who
  cancelled is left staring at nothing.

Here the flow is a `Wizard`, one player's pass through it is a `WizardRun` that
nothing outside the module can reach into, and every ending goes through one
cleanup path.

## Asking one thing

Most of what a setup menu needs is one question: where does this spawn go, which
area is the arena, what icon does this use. Four shortcuts cover it, and every
plugin gets the same boss bar, the same prompt placement and the same cancel
behaviour without writing the flow again.

```java
wizards.askStand(player, "{primary}&lLOBBY SPAWN " + arena.name(),
        messages.admin().pointPrompt(),
        where -> save(arena.withLobby(where)),
        () -> openArenaMenu(player));
```

| Call | The player | Answer |
| --- | --- | --- |
| `askStand(player, title, prompt, accepted, abandoned)` | stands where they mean and sneak-clicks | `Location`, facing included |
| `askPoint(...)` | clicks a block | `Location` of the block |
| `askRegion(...)` | selects a volume with the shared selector | `SelectionResult` |
| `askItem(...)` | holds an item and confirms | `ItemStack` |

`askStand` is the one for anywhere a player is later **put** — a spawn, a lobby,
a warp. A clicked block names a whole cube and carries no yaw, so an aimed pick
has to guess both, and a player spawned from one faces whatever direction the
corner happened to be.

**`abandoned` runs only when the player backed out**, never after a finish. That
is the part every hand-written version of this flow got slightly differently: a
caller that reopens its menu in both places opens it over the screen its own
`accepted` just opened. It runs on the player's thread, and not at all for a
player who is no longer there to see it.

The `title` names *what is being set*, because it is what the progress bar
draws — `LOBBY SPAWN Park (1/1)`. The `prompt` is the plugin's own text, out of
its own messages file: what the library supplies is the shape, not the wording.

For anything longer than one question — a flow with branches, a review, several
answers that build one object — declare it with `define` and keep it.

## Declaring a flow

`define(id)` returns a `WizardBuilder`. Do this once, when the plugin reads its
configuration, and keep the `Wizard` in a field: compiling per command re-runs
every validation and allocates every lambda for a flow that has not changed
since the server started.

```java
Wizard arena = wizards.define("arena")
        .title("{primary}&lNEW ARENA")
        .ask(ID,    step -> step.id("Enter the arena id"))
        .ask(KIND,  step -> step.choice("Game type", List.of("koth", "conquest")))
        .pick(SPAWN, "Click the spawn block")
        .region(AREA, "Select the arena bounds")
        .hand(ICON, "Hold the icon and confirm")
        .when(KIND, kind -> kind.equals("koth"),
              branch -> branch.ask(POINTS, step -> step.integer("Capture points")
                                                      .range(1L, 5L)))
        .ask(NAME, step -> step.text("Display name"))
        .summary()
        .progress(true)
        .onFinish(this::createArena)
        .onCancel(outcome -> debug.log("Arena wizard ended as " + outcome))
        .build();
```

### Step kinds

| Call | What the player does | Answer type |
| --- | --- | --- |
| `ask(key, step -> ...)` | Answers a question through the `input` module | whatever the request produces |
| `pick(key, prompt)` | Clicks a block, either button | `Location` |
| `region(key, prompt[, options])` | Selects a volume with the shared block selector | `SelectionResult` |
| `hand(key, prompt)` | Holds an item and confirms | `ItemStack` |
| `when(key, predicate, branch)` | Nothing — it decides which steps apply | — |

The lambda of an `ask` receives a `WizardStep.Prompt`, a factory already bound to
whichever player is running the flow. That is what lets one definition serve
everybody: the lambda is the recipe, followed once per run. It offers `text`,
`id`, `slug`, `integer`, `decimal`, `amount`, `duration`, `flag`, `confirm`,
`choice`, `search` and `player()` — the whole `input` vocabulary except `form`,
which is left out on purpose: a form asks several things in one window, which is
what a wizard already is.

`when` nests. An inner predicate sees everything answered before it, including
what the outer branch collected.

### Flow-level calls

| Call | Effect |
| --- | --- |
| `title(raw)` | Named in the progress bar and above the review. Raw library notation, so `{primary}` and `&l` work. Defaults to the id |
| `summary()` | Review and confirm before anything is applied |
| `progress(boolean)` | The boss bar, on or off. Defaults to `WizardSettings.progress()` |
| `onFinish(Consumer<WizardValues>)` | What to create. Runs exactly once, only on `COMPLETED` |
| `onCancel(Consumer<WizardOutcome>)` | Optional, for saying something specific per ending |
| `build()` | Compiles it |

### Wiring mistakes fail at load, not on the server

Each of these is a `WizardException` thrown while the plugin reads its
configuration, naming the key that is wrong:

- two steps declared under the same key — the second answer would overwrite the
  first and the review could only show one of them;
- a `when` guarded by a key nothing asks for *before* that point — a branch is
  decided against the answers collected so far, so it could never apply, and a
  step that quietly never happens is the hardest kind of bug to see;
- a branch with no steps, a flow with no steps, a blank prompt, a blank title, a
  blank id, a key with no name.

Everything a *player* can cause — a typo, a cancel, walking away until the run
times out — is a `WizardOutcome`, never an exception.

## Typed answers

A `WizardKey<T>` is a name and a type together, declared once as a constant and
used at both ends. The alternative is the `Map<String, Object>` of half-built
state `EventConfigWizard` kept, where reading a field was a cast and a guess and
a key spelled `minPlayers` going in and `min_players` coming out compiled
perfectly and failed on the server.

| Factory | Type |
| --- | --- |
| `WizardKey.text(name)` | `String` |
| `WizardKey.integer(name)` | `Long` |
| `WizardKey.decimal(name)` | `BigDecimal` |
| `WizardKey.flag(name)` | `Boolean` |
| `WizardKey.duration(name)` | `Duration` |
| `WizardKey.location(name)` | `Location` |
| `WizardKey.region(name)` | `SelectionResult` |
| `WizardKey.item(name)` | `ItemStack` |
| `WizardKey.of(name, type)` | anything |

`WizardValues` is what `onFinish` receives, and what a branch predicate is
evaluated against.

```java
values.get(ID);              // typed; throws naming the key if nothing collected it
values.getOr(POINTS, 0L);    // the right accessor for anything behind a `when`
values.has(POINTS);          // whether the branch ran at all
values.asMap();              // read-only, in the order the player answered
```

Reading an answer nobody collected is a `WizardException` naming it *and listing
what was collected* — not a `null` that becomes a `NullPointerException` three
lines later inside the plugin's own creation code. The typed convenience readers
(`getText`, `getLong`, `getDecimal`, `getBoolean`, `getDuration`, `getLocation`,
`getRegion`, `getItem`) all go through the same check. `getLocation` and
`getItem` hand back a copy, so a caller may mutate it freely.

Insertion order is preserved, because it is the order the player answered in and
therefore the order the review lists.

## Contracts

- **One wizard per player, across every plugin.** A second flow ends the first as
  `REPLACED`. This is deliberately the same rule `InputRuntime` already enforces
  for a single active request, for the same reason: a wizard *is* a chain of
  inputs, so two live flows would each be waiting on that player's one input
  slot — the second question asked would answer the first flow's step and the
  first would then answer the second's. They would trade answers and neither
  would produce anything a plugin could use.
- **Nothing is applied until the player confirms.** With a `summary()`,
  `onFinish` runs exactly once, after the review, and only on `COMPLETED`. A run
  that was cancelled, timed out, disconnected, replaced or failed never reaches
  it. That is the guarantee that lets a plugin do all of its creating in one
  place instead of accumulating half-objects step by step. Without a `summary()`
  the flow applies as soon as the last answer arrives.
- **`afterwards` runs on every ending, and never for an offline player.** The
  `Runnable` passed to `start` runs however the flow ended — confirmed,
  cancelled, timed out, replaced, shut down, failed — so a menu that opened one
  is reopened either way. It runs a tick later, on the thread that owns the
  player, so the window the last question was asked in has already closed. It is
  skipped entirely for a player who is no longer online.
- **One cleanup path, not a branch per step.** Every ending claims the run's
  terminal slot atomically and goes through the same release, which lets go of
  four things a player can feel: the pending question, the block selector, the
  progress bar, and the player's one wizard slot. (The run's own timeout task is
  cancelled there too.) Each part is guarded on its own, so a boss bar that will
  not stop cannot stop the selector from being released. Whoever gets there
  first wins and every other path does nothing, which is what makes `cancel()`
  safe to call from a quit handler, a menu close and a command in the same tick.
- **The block selector is the reason that path is single.** `beginSelection` is
  one selector per player *server-wide*, and it refuses while somebody owns it.
  The other three things a run holds are its own — a leaked bar is this plugin's
  bar, a leaked question is this plugin's question, a leaked wizard slot only
  blocks this library's wizards. A leaked selector leaves that player unable to
  select a block for **any** plugin — a WorldGuard claim, another arena setup, a
  shop region — until they reconnect. It is the only leak in the module a player
  can carry out of the plugin that caused it. A region step that cannot claim
  the selector because another plugin already owns it ends the run as `REPLACED`
  with the current owner named in the console, rather than silently competing
  for the player's clicks.
- **The way back lives in the review, and is a `confirm` plus a `choice` and
  nothing else.** Denying the summary offers the list of collected answers;
  picking one asks it again and returns to the review. Built from those two
  requests on purpose: it asks for no control that any transport lacks, so it
  works identically in a native dialog, a Bedrock form, an anvil, a menu and in
  chat. A bespoke review screen would have had to be written five times and four
  of them would have rotted.
- **Redo rounds are capped.** `WizardSettings.maxRedos()` bounds them; exceeding
  the cap ends the run as `CANCELLED` rather than looping. Without it a player
  could deny, change, deny, change forever, holding the flow, the selector and
  the wizard slot — the run timeout would eventually end it, but only after
  minutes of a flow nobody is going to finish.
- **Changing an answer a branch depends on re-resolves the flow.** Redoing a key
  that some `when` is guarded on re-walks the whole definition from the top
  against the current answers. Answers belonging to steps that no longer apply
  are dropped; steps that now apply are asked *before* the review returns.
  Answers survive by name, so everything still reachable keeps what the player
  already typed. Nested branches fall and wake with their parent. Re-resolution
  adds no round of its own, so it does not buy the player extra denials.
  This was a real bug: going straight back to the review was wrong in both
  directions and silently — a player who changed KOTH to CONQUEST handed
  `onFinish` a `points` that kind of event does not have, and CONQUEST to KOTH
  handed it answers with a required one missing. Neither showed up until the
  plugin's own creation code read a field. Redoing a key nothing branches on —
  by far the common case, a mistyped display name — still goes straight back to
  the review, and re-asks nothing else.
- **Everything hops to the thread that owns the player.** Input futures and
  selection stages complete on unspecified threads; nothing arriving from them
  touches Bukkit before a `runAtEntity` hop. `onFinish`, `onCancel` and
  `afterwards` all run there, so they are safe to touch the game from. Correct
  on Folia with no branching.
- **Nothing outlives its run.** Quitting, the owning plugin disabling, the server
  stopping and the run timeout each end it, and each releases everything it
  held. Disabling a plugin ends its runs *before* its scheduler goes away,
  because ending a run schedules on it.
- **A definition holds nothing about anybody running it.** `Wizard` is immutable
  and safe to read from any thread; a run's mutable state lives in a session
  nothing outside the module can reach.

## Outcomes

`WizardOutcome` names every way a run can end. Exactly one is delivered exactly
once per run.

| Outcome | What happened |
| --- | --- |
| `COMPLETED` | Every step was answered and the summary confirmed. The only outcome that applies anything |
| `CANCELLED` | The player chose to stop: the cancel word, the cancel button, closing the window, declining a `hand` step, or exceeding the redo cap |
| `TIMED_OUT` | Nobody answered before the run's own timeout ran out |
| `DISCONNECTED` | The player left the server |
| `REPLACED` | A newer wizard for the same player took over — or a region step could not claim the player's block selector because another plugin owns it |
| `SHUT_DOWN` | The owning plugin was disabled, or the server is stopping |
| `FAILED` | Something in the definition threw: a branch predicate, a step that could not be built, the finish callback. Reported to the console against the owning plugin |

`REPLACED` is its own outcome rather than a cancel because the two mean different
things to a caller: a cancel is the player saying no, a replace is a plugin
changing its mind, and reopening a menu on a replace is how two screens end up
fighting each other. `SHUT_DOWN` is distinct so a caller does not try to save
what it was collecting while its own plugin is being torn down.

Two helpers: `hasValues()` is true only for `COMPLETED`; `byPlayer()` is true for
`CANCELLED` and `DISCONNECTED`.

## Inspecting a run

`start` returns a `WizardRun`, a view rather than the state.

```java
WizardRun run = wizards.start(player, arena, () -> menu.open(player));

run.player();      // who
run.wizard();      // what
run.stepIndex();   // how many steps they have answered; counts only steps reached
run.stepCount();   // how many this run expects — an upper bound while branches are undecided
run.isFinished();  // whether a terminal outcome has been delivered
run.cancel();      // ends it; true when this call is the one that ended it
```

`stepCount()` can only fall as a branch is skipped, never rise past the
definition's own upper bound, so the progress bar never jumps backwards.
`cancel()` is safe from any thread and safe to call twice.

Server-wide, from `Wizards`: `isRunning(player)`, `running(player)` (an
`Optional<WizardRun>`, for a caller that wants to end it rather than just know
about it), and `active()`. Per plugin, from `PluginWizards`: `endAll()`.

Ask `isRunning` before anything that would fight a live flow — another wizard, a
menu that takes over the screen, a teleport that moves the player away from the
block they were told to click.

## Configuration

`WizardSettings` nests in a plugin's own config record, and is applied with
`Wizards.of(this).using(...)`. It affects runs started after that call: a live
run keeps the settings it began with, so a reload cannot move a deadline a
player is already racing.

```yaml
wizard:
  timeout-seconds: 300                          # the whole run, not one question
  max-redos: 3                                  # denials of the review before it gives up
  progress: true                                # the boss bar
  progress-text: '{primary}%title% {muted}(%step%/%steps%)'
```

`%step%` is the question they are on, `%steps%` how many there are, `%title%` the
name of the flow.

Values are clamped in the record's compact constructor:
`timeout-seconds` is floored at **30** (a run shorter than one question's own
default limit would end the flow while the player was still reading the first
prompt); `max-redos` is clamped to **0–20**, and zero keeps its meaning — a
review that may be denied but never edited is still a review, and denying it
simply cancels; a blank or missing `progress-text` falls back to the default
rather than drawing an empty bar.

**The run needs a timeout of its own** even though every question already times
out: a player who answers one question every fifty seconds never trips any
single timeout and can hold the flow — and the selector, and the bar, and the
one-wizard-per-player slot — indefinitely.

## Design

No benchmark exists for this module, so no numbers are claimed here. What is
claimed is the shape:

- **A definition is compiled once and shared.** Every validation runs at load;
  starting a run allocates a session and walks a list.
- **One boss bar per run, reused across steps**, updated in place — not one
  display per step. A flow that turns the bar off draws nothing at all.
- **One Bukkit listener for every wizard on the server**, registered against the
  library itself, not one per run and not one per plugin. A handler per session
  is how a plugin ends up with a thousand listeners after an evening of players
  opening menus, and how a listener belonging to a finished flow answers
  somebody else's click. The listener holds no policy: it finds the player's
  session and hands it the event, because only the session knows which step it
  is on.
- **Both mouse buttons answer a `pick`.** A player told to "click the spawn
  block" reaches for whichever one they habitually use, and a prompt that
  accepts only one looks broken to half the server. The event is cancelled when
  it is consumed, so the left click does not start breaking the block and the
  right click does not open the chest.
- **A redo that cannot change which steps apply takes the cheap path** straight
  back to the review; only a guard key triggers a re-walk.
- **Items and locations are stored as copies**, taken at the moment they are
  answered. The live stack changes the moment the player moves it, and would
  usually be air by the time the summary is confirmed.

## Reload

The module caches **nothing** derived from the palette. Every prompt, review line
and progress bar is built through `Text` at the moment it is shown, and a
`Wizard` keeps its title as raw text rather than as a parsed component. A palette
reload is therefore picked up by whatever is drawn next with no help from this
module.

It has no `invalidateAll()` and is deliberately absent from the palette-reload
chain in `ExyliaLib.loadPalette`. That is a declared position, not an omission —
see `docs/reload.md` for the modules that do hook in and why.

## Source and tests

- Public: `util/wizard/` — `Wizards`, `PluginWizards`, `Wizard`, `WizardBuilder`
  (and `WizardBuilder.Branch`), `WizardStep` (and `WizardStep.Prompt`),
  `WizardKey`, `WizardValues`, `WizardRun`, `WizardOutcome`, `WizardResult`,
  `WizardSettings`, `WizardException`.
- Internal: `util/wizard/internal/` — `WizardRuntime` (the server-wide registry
  and everything that can end a run), `WizardSession` (one player's pass, the
  single cleanup path, branch re-resolution), `WizardListener` (the module's one
  Bukkit listener).
- Wired in `ExyliaLib`: `WizardRuntime.init` on enable, `WizardRuntime.forget`
  on quit, `Wizards.release(name)` on plugin disable, `Wizards.releaseAll()` on
  shutdown.
- Tests: `WizardDefinitionTest` (what the builder refuses), `WizardFlowTest`
  (the happy path, typed answers, with and without a summary),
  `WizardStepKindTest` (pick, hand, answer order), `WizardRedoTest` (branch
  re-resolution in both directions, nested branches, the redo cap),
  `WizardEndingTest` (every ending, `afterwards` on all of them, ending exactly
  once), `WizardRegionReleaseTest` (the selector given back on each of five
  endings, asserted by another plugin retaking it), `WizardFailureTest` (a
  throwing predicate, finish callback or step builder), `WizardProgressTest`
  (the bar never passes its total, and the total never rises),
  `WizardValueTypesTest` (keys, values, results, settings clamping).
