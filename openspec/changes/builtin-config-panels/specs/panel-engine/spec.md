# Panel Engine Specification

## Purpose

Plugin-scoped panel runtime: entry point, window-bound session, bounded undo, diff before save, YAML-themable layouts, and release of everything a panel owns.

## Requirements

### Requirement: Plugin-scoped entry point

`Panels.of(Plugin)` MUST return a `PluginPanels` owned by that plugin, mirroring `Menus.of`/`Items.of`/`Inputs.of`. `PluginPanels` MUST expose `settings(ConfigFile<T>)` and `list(FieldDescriptor<T>)`, each returning a builder whose `open(Player)` shows the panel. `Panels` MUST expose `session(Player)`, `release(String)`, `releaseAll()`. No call order beyond `of → settings|list → open` MAY be required.

#### Scenario: Two plugins get separate registries

- GIVEN plugins `A` and `B` each open a panel for the same player
- WHEN `Panels.release("A")` runs
- THEN A's panel closes and B's registry is untouched

### Requirement: Session state lives on the window, never in a static map

State (working copy, undo stack, page, filter, clipboard) MUST live on a `PanelSession` bound to the open `UiSession`, resolved through the window holder. The module MUST NOT contain any static map or cache keyed by `UUID` or `Player`. A click MUST be validated against what the session drew, not the slot the client sent.

#### Scenario: No static per-player state exists

- GIVEN every class in `net.exylia.lib.panel` and its `internal` package
- WHEN static fields are reflected over
- THEN none is a `Map`, `Collection`, or `Cache` keyed by `UUID` or `Player`

#### Scenario: A foreign window is not ours

- GIVEN a player with a panel open who then opens a plain chest
- WHEN a click arrives in the chest
- THEN no panel session resolves and the panel module ignores it

### Requirement: Working copy, bounded undo, diff before save

A panel MUST edit a working copy and MUST NOT mutate persisted state before save. Each committed edit MUST push a snapshot onto a per-session undo stack bounded by a documented maximum; overflow MUST discard the oldest and MUST NOT throw. Undo on an empty stack MUST be a no-op. Save MUST diff original against working copy and MUST NOT write when the diff is empty. Cancel MUST discard the whole working copy and MUST NOT partially persist.

#### Scenario: Undo restores the previous value

- GIVEN two committed edits
- WHEN undo runs twice
- THEN the working copy equals its original state
- AND a third undo is a no-op

#### Scenario: Undo stack is bounded

- GIVEN a bound of N
- WHEN N + 5 edits are committed
- THEN exactly N snapshots remain and no exception is raised

#### Scenario: Empty diff writes nothing

- GIVEN a panel where no value changed
- WHEN save runs
- THEN no write reaches the store and the diff is empty

#### Scenario: Cancel persists nothing

- GIVEN three edits not yet saved
- WHEN cancel runs
- THEN persisted state equals the state before the panel opened

### Requirement: Layouts load from bundled YAML and degrade, never break

Layouts MUST ship as bundled YAML refreshed via `PluginMenus.refreshBundledDirectory`, so owners retheme slots, colours, and titles without recompiling. Slots and sizes MUST NOT be hardcoded in engine control flow. A missing, unreadable, or malformed layout MUST fall back to a built-in default, MUST be reported once, and MUST still open a usable panel.

#### Scenario: Owner's slot edit is honoured

- GIVEN a bundled layout with save at slot 49
- WHEN the owner sets slot 45 and the directory is refreshed
- THEN the next panel draws save at slot 45

#### Scenario: Malformed layout degrades

- GIVEN a layout containing invalid YAML
- WHEN a panel opens
- THEN the built-in default is used, exactly one problem is reported, and the panel is operable

#### Scenario: Missing layout degrades identically

- GIVEN the layout file is absent
- WHEN a panel opens
- THEN the built-in default is used and one problem is reported

### Requirement: Nothing survives its owner

Closing a panel MUST release its session and MUST cancel any `ActionExecution` with delayed steps it started. `PlayerQuitEvent` MUST release that player's session. Plugin disable MUST close that plugin's panels before its tasks are dropped and MUST release every session it owns. Afterwards the module MUST hold zero references to player, session, or working copy.

#### Scenario: Quit releases the session

- GIVEN a player with an open panel
- WHEN `PlayerQuitEvent` is dispatched
- THEN `Panels.session(player)` is empty and that owner's session count is zero

#### Scenario: Plugin disable closes windows before dropping tasks

- GIVEN a panel with a scheduled delayed action step
- WHEN the owning plugin is disabled
- THEN the window closes, the step is cancelled, and no task remains live

#### Scenario: Close cancels delayed steps

- GIVEN a panel that started an execution with a delayed step
- WHEN the player closes the panel before the delay elapses
- THEN the execution is cancelled and the step never runs

### Requirement: Palette reload contract

Quality-bar point 8 MUST be satisfied explicitly. If the engine retains a rendered `Component` or palette-derived value beyond one render, it MUST expose `invalidateAll()`, MUST be hooked into `ExyliaLib.loadPalette` alongside `BoardManager`/`HologramRuntime`/`EffectRuntime`/`ItemCache`, and MUST be covered by `PaletteReloadTest`. Otherwise it MUST render per open, and `docs/reload.md` and `docs/panels.md` MUST state that `panel` caches nothing palette-derived. Silence MUST NOT be acceptable.

#### Scenario: Palette reload leaves no stale colour

- GIVEN an open panel drawn with the current palette
- WHEN the palette is reloaded
- THEN the next render uses the new palette
- AND either `invalidateAll()` was invoked from `loadPalette`, or a test asserts no palette-derived value is retained between renders

## Threading, Nullability, Folia

- Open, close, draw, redraw: viewer thread — entity thread on Folia — scheduled via `net.exylia.lib.task` (`runAtEntity`). No `BukkitRunnable`, `Bukkit.getScheduler()`, `new Thread`, or private `ExecutorService`.
- Config writes and I/O: `runAsync`, returning to the viewer thread before touching the game.
- Working copy, diff, undo bookkeeping: any thread, no Bukkit API.
- `Panels.session(Player)` returns `Optional`; no public panel API returns `null`.
- No platform-specific or PacketEvents type MAY be named; the library MUST load on pure Spigot.

## Test Seams

`FakeServer`/`FakePlayer` cover open, close, quit, disable, and `liveTasks()`; `DebugCapture` covers once-per-server reports. **New seams required** (for `sdd-tasks`): a layout source seam to inject a malformed or missing layout; a renderer/draw sink so tests can assert which slot carried which control; a clock or step-counter seam if a timing-dependent redraw is introduced.
