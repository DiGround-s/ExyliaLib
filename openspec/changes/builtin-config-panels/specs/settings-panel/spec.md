# Settings Panel Specification

## Purpose

A panel generated from a `ConfigFile<T>`'s record schema: one control per component, `@Comment` lines as lore, persistence exclusively through `ConfigFile.update`. No per-record editor code.

## Requirements

### Requirement: Controls are chosen from the declared type

The panel MUST derive controls from the projected `Schema`, in canonical-constructor order, with no per-record code. The mapping MUST be: `int`/`long`/`short`/`byte` (and boxed) → integral number control; `double`/`float` (and boxed) → decimal number control; `boolean` → toggle; enum → searchable choice over the constants; `String` → text input; `List<E>` → list sub-panel (see `list-panel`); nested record → sub-panel over the nested `Schema`. Section-level and component-level `@Comment` lines MUST render as lore on the owning control, in declaration order.

#### Scenario: Each supported type gets its control

- GIVEN a record with an `int`, a `double`, a `boolean`, an enum, a `String`, a `List<String>`, and a nested record
- WHEN the settings panel is generated
- THEN seven controls are produced in canonical-constructor order
- AND their kinds are integral number, decimal number, toggle, searchable choice, text, list sub-panel, and sub-panel

#### Scenario: Comments become lore

- GIVEN a component with two `@Comment` lines
- WHEN its control is drawn
- THEN the lore contains those two lines in declaration order

#### Scenario: Enum choice is searchable

- GIVEN an enum component with more constants than fit one page
- WHEN its control is activated
- THEN a `SearchInput` over the constants opens, not a hand-rolled picker
- AND selecting a constant sets the working copy to it

#### Scenario: Nested record opens a sub-panel

- GIVEN a nested record component
- WHEN its control is activated
- THEN a sub-panel over the nested `Schema` opens
- AND edits there are reflected in the parent working copy before save

### Requirement: Persistence goes only through `ConfigFile.update`

Save MUST rebuild the record through its canonical constructor in declared component order and hand it to `ConfigFile.update(UnaryOperator<T>)`. The panel MUST NOT write YAML, MUST NOT touch a `FileConfiguration`, and MUST NOT use any other write path. Save MUST be atomic at record level: the whole rebuilt record, or nothing.

#### Scenario: Editing one component preserves the rest

- GIVEN a record with five components
- WHEN one is edited and saved
- THEN `ConfigFile.update` receives a record whose other four equal their previous values
- AND no other write path is invoked

#### Scenario: Round-trip per supported type

- GIVEN a record with one component of each supported type
- WHEN each is edited, saved, and the file reloaded
- THEN every edited value reads back equal to what was set

#### Scenario: Cancel writes nothing

- GIVEN edits made in a settings panel
- WHEN cancel runs
- THEN `ConfigFile.update` is never called and the persisted record is unchanged

### Requirement: Unsupported component types degrade, never break

A component whose declared type has no control MUST be drawn read-only with its `@Comment` lore and a not-editable marker. It MUST be excluded from the rebuild's edit path with its existing value passed through untouched. It MUST be reported **once per server** — not once per item, not once per open; the `ItemComponents` lesson. It MUST NOT prevent opening, editing other components, or saving.

#### Scenario: Record with an unsupported component still saves

- GIVEN a record of an `int`, a `String`, and a type with no control
- WHEN the `int` is edited and saved
- THEN the rebuilt record carries the new `int`, the unchanged `String`, and the unsupported component's original value
- AND the panel opened and saved without error

#### Scenario: Report fires once per server

- GIVEN a record with an unsupported component
- WHEN the panel is opened three times by two different players
- THEN exactly one report is captured, naming the record type and the component

#### Scenario: Unsupported control is read-only

- GIVEN the unsupported component's control
- WHEN a player clicks it
- THEN the working copy is unchanged and no input request opens
- AND its lore still shows its `@Comment` lines

### Requirement: Effects editor is the settings panel pointed at `EffectConfig`

The effects editor MUST be delivered as `Panels.of(plugin).settings(effectConfigFile)` with **zero** `EffectConfig`-specific editor code. No class, branch, `switch`, or descriptor in the library MAY special-case `EffectConfig` or its nested record types. This MUST be verifiable by test, not by inspection.

#### Scenario: No EffectConfig-specific code exists

- GIVEN every class under `net.exylia.lib.panel`
- WHEN their references are scanned for `EffectConfig` and its nested record types
- THEN none is found outside test sources

#### Scenario: EffectConfig edits through the generic path

- GIVEN a `ConfigFile<EffectConfig>` with its comments across nested records
- WHEN the settings panel is generated from it
- THEN controls exist for its components and sub-panels for its nested records
- AND editing a nested value and saving persists it through `ConfigFile.update`

## Threading, Nullability, Folia

- Control generation and record rebuild MUST be pure and callable from any thread, touching no Bukkit API.
- Drawing and click handling MUST run on the viewer thread (entity thread on Folia) via `net.exylia.lib.task`; the `ConfigFile.update` write MUST NOT block it.
- A component value MAY be null only where the record permits; the panel MUST render null without throwing.
- Behaviour MUST be identical on Spigot, Paper, Purpur, and Folia.

## Test Seams

`FakeServer`/`FakePlayer` for open, click, save; `DebugCapture` for the once-per-server report. `ConfigFile.update` is already the single write seam, so a fake or in-memory `ConfigFile` suffices for round-trips. **New seams required** (for `sdd-tasks`): a `forgetReportedForTests` hook on the unsupported-type reporter — mirroring `ItemComponents.forgetReportedForTests` — so the once-per-server assertion is repeatable across test methods; plus the renderer/draw sink from `panel-engine` to assert control kinds per slot.
