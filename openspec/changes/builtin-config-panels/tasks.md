# Tasks: Built-in Configuration Panels (`net.exylia.lib.panel`)

## Delivery Plan (guard lines and full numbers: `## Review Workload Forecast`, end of file)

~2,500 authored lines over 6 units (200 / 480 / 220 / 650 / 550 / 400). Delivery strategy `auto-chain`; recommended chain strategy `feature-branch-chain`, pending orchestrator confirmation.

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: pending
400-line budget risk: High

### Slice-2 split: **Yes, but not on the design's axis**

Design proposed 2a = runtime+session+lifecycle+seams, 2b = undo+diff+layouts. Rejected: `PanelSession` is a public interface declaring `undo()`, `undoDepth()`, `diff()` — deferring those to 2b ships stubs in a public signature. Undo and diff are pure value machinery (no Bukkit, plain JUnit, ~120 lines) and belong with the interface they complete.

Split on **layouts** instead. Decision 6 already guarantees a Java-built `Layouts.BUILT_IN` keeping a panel operable without YAML — so the pre-YAML state is *supported*, not broken, which makes YAML loading a real revertible boundary.

- **2a — engine core** (~480): seams 2/3/4/5, runtime, window-bound session, undo, diff, `BUILT_IN` only, `ExyliaLib` wiring.
- **2b — YAML layouts** (~220): seam 1, bundled `panels/*.yml`, `refreshBundledDirectory`, degrade-once report.

2a alone unblocks units 3 and 4, shortening the critical path. Cost, stated: the `panel-engine` *Layouts* requirement closes across two PRs, not one.

### Suggested Work Units

| Unit | Goal | PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|----|----------------------|-----------------|-------------------|
| 1 | Public `config.Schema` projection | PR1 | `./gradlew test --tests 'net.exylia.lib.config.Schema*Test' --tests '*PublicSignatureSweepTest'` | N/A — pure JUnit, no server, projection touches no Bukkit API (spec: config-schema-projection §Threading) | `config/Schema.java`, `config/internal/SchemaProjection.java`, one accessor on `ConfigFile` |
| 2a | Engine core: seams, session, undo, diff, lifecycle | PR2a | `./gradlew test --tests 'net.exylia.lib.panel.Panel*Test'` | `FakeServer`/`FakePlayer` + `FakeServer.liveTasks()` (e2e unavailable per `openspec/config.yaml:25`) | whole `panel/` package + `ExyliaLib` hunks |
| 2b | YAML layouts + degrade-once | PR2b | `./gradlew test --tests 'net.exylia.lib.panel.PanelLayoutFallbackTest'` | `FakeServer` + `Layouts.install` + `DebugCapture` | `resources/panels/`, `Layouts` YAML branch — revert leaves `BUILT_IN` |
| 3 | Settings panel + record rebuild | PR3 | `./gradlew test --tests 'net.exylia.lib.panel.Settings*Test' --tests '*RecordRebuilderTest' --tests '*UnsupportedComponentTest' --tests '*EffectConfigGenericPathTest'` | `FakeServer` + draw sink + `PanelPrompts.install` | `SettingsPanel`, `SettingsEngine`, `ControlMapper`, `RecordRebuilder`, `UnsupportedTypes` |
| 4 | Generic list panel | PR4 | `./gradlew test --tests 'net.exylia.lib.panel.List*Test'` | `FakeServer` + draw sink + `PanelPrompts.install` + `TestDescriptors` | `ListPanel`, `ListEngine`, `FieldDescriptor` |
| 5 | Descriptors + docs + doctrine | PR5 | `./gradlew test --tests 'net.exylia.lib.panel.*DescriptorTest' --tests '*NoNamedCommandTypeTest'` | `FakeServer` for icon render; codec round-trips are plain JUnit | `panel/descriptor/`, doc files |

`feature-branch-chain` bases: PR1 = tracker; PR2a = PR1; PR2b/PR3/PR4 = PR2a (siblings — if a child diff shows a sibling's changes, rebase); PR5 = PR4.

**Threat matrix**: N/A per `design.md:178` — no routing, shell, subprocess, VCS, or process boundary. No threat RED tasks emitted.

**Version**: `@since 1.50.0` (placeholder, confirm at release; `build.gradle` is `1.49.5`). **No task edits `build.gradle`** — reserved release input per AGENTS.

**Path convention**: paths below are relative to `src/main/java/net/exylia/lib/` for production and `src/test/java/net/exylia/lib/` for tests, except where written in full.

---

## Unit 1 — `config-schema-projection` (~200 lines, depends on nothing)

- [x] 1.1 RED: create `config/SchemaProjectionTest.java` — canonical-constructor field order, `@Key` rename (`key` vs `name`), `@Comment` lines in declaration order, non-null `nested` for a nested record. Fails: no `Schema` type.
- [x] 1.2 RED: add to `SchemaProjectionTest` — component `poolSize` with no `@Key` projects `key == "pool-size"` and empty `comments`.
- [x] 1.3 RED: add to `SchemaProjectionTest` — `List<String>` component has `type == List.class` and `generic` carrying the `String` argument.
- [x] 1.4 RED: add to `SchemaProjectionTest` — `fields()` and `comments()` throw `UnsupportedOperationException` on mutation.
- [x] 1.5 RED: add to `SchemaProjectionTest` — two `ConfigFile`s of one record type holding different values project **equal** `Schema`s; and a `Schema` taken before reload is unchanged after it.
- [x] 1.6 GREEN: create `config/Schema.java` — `record Schema(Class<?> type, List<String> comments, List<Field> fields)` + nested `record Field(String name, String key, Class<?> type, Type generic, List<String> comments, @Nullable Schema nested)`. Compact constructors `List.copyOf`.
- [x] 1.7 GREEN: create `config/internal/SchemaProjection.java` — package-private `static Schema of(Class<?>, String)` mapping `SchemaNode`/`SchemaComponent`. Only public types in its signature; no `Constructor<?>` escapes.
- [x] 1.8 GREEN: modify `config/ConfigFile.java` — add exactly one accessor `@NotNull Schema schema()`. Change no existing signature.
- [x] 1.9 RED: create `config/PublicSignatureSweepTest.java` — reflect every public constructor, return, and parameter of `net.exylia.lib.config` (later `.panel`); assert no `.internal` package appears and no `Constructor` is reachable from `Schema`/`Field`.
- [x] 1.10 GREEN: make 1.9 pass; assert `SchemaNode` and `SchemaComponent` remain package-private.
- [x] 1.11 REFACTOR: extract kebab-case conversion to one place shared with the existing key resolution; no duplicated casing rule.
- [x] 1.12 Doc: update `docs/config.md` — `schema()` accessor plus a `Schema`/`Schema.Field` section. Extract signatures with `grep -n "    public" .../config/Schema.java` first; write against output, never memory.
- [x] 1.13 Javadoc: `Schema`, `Schema.Field`, `ConfigFile.schema()` — English, `@since 1.50.0`, stating any-thread purity, immutability, and that `nested()` is the only nullable accessor.
- [x] 1.14 **Sabotage**: change the kebab fallback to return the raw Java name; confirm 1.2 fails. Change `List.copyOf` to a bare assignment; confirm 1.4 fails. Restore both; record in the PR body.
- [x] 1.15 Verify: `./gradlew clean build` green, zero warnings.

## Unit 2a — `panel-engine` core (~480 lines, depends on 1 for wiring)

Seams first — RED cannot be written without them.

- [x] 2a.1 SEAM: create `panel/internal/PanelRenderer.java` — `interface DrawSink { void drew(int slot, ControlKind kind, Object entry); }` + `static DrawSink sink(DrawSink)` returning the previous. Precedent: `ItemRenderer.components`.
- [x] 2a.2 SEAM: create `panel/internal/ControlKind.java` — the enum the sink reports.
- [x] 2a.3 SEAM: create `panel/internal/PanelRuntime.java` — `static void setClock(LongSupplier)` / `resetClock()`. Precedent: `Cooldowns.setClock`.
- [x] 2a.4 SEAM: create `panel/internal/UnsupportedTypes.java` — `static void forgetReportedForTests()`. Precedent: `ItemComponents.forgetReportedForTests`.
- [x] 2a.5 SEAM: create `panel/internal/PanelPrompts.java` — `interface Prompts { text(...); confirm(...); <T> search(...); }` returning `CompletionStage<InputResult<…>>`, plus `public static void install(@Nullable Prompts)`. The engine calls **only** this, never `Inputs`.
- [x] 2a.6 SEAM: create `panel/internal/Layouts.java` with `BUILT_IN` — a `UiDefinition` built in Java. YAML loading and `install` land in 2b.
- [x] 2a.7 RED: create `panel/PanelNoStaticStateTest.java` — reflect every static field in `panel` and `panel.internal`; fail if any is a `Map`, `Collection`, or `Cache` keyed by `UUID` or `Player`.
- [x] 2a.8 RED: create `panel/PanelLifecycleTest.java` — quit releases the session (`Panels.session(player)` empty, owner count zero); `Panels.release("A")` closes A, leaves B untouched; a click in a plain chest resolves no session.
- [x] 2a.9 RED: extend `PanelLifecycleTest` — plugin disable closes the window **before** tasks are dropped, cancels a delayed `ActionExecution` step, and leaves `FakeServer.liveTasks()` empty; closing early cancels the step so it never runs.
- [x] 2a.10 RED: create `panel/PanelUndoTest.java` — two edits then two undos restore the original, a third undo is a no-op; `N + 5` edits leave exactly `Panels.undoLimit()` snapshots, no exception.
- [x] 2a.11 RED: create `panel/PanelDiffTest.java` — one added/removed/changed reports exactly `1/1/1`; an unchanged copy yields `isEmpty()` and **no write reaches the store**; cancel leaves persisted state as at open.
- [x] 2a.12 RED: create `panel/PanelPaletteTest.java` — reflect `panel` instance and static fields for a retained `Component` or palette-derived value; assert none is held between renders. This is how quality-bar point 8 is *said*, not left silent (decision 5).
- [x] 2a.13 GREEN: create `panel/internal/UndoStack.java` — bounded ring, oldest discarded on overflow, never throws.
- [x] 2a.14 GREEN: create `panel/internal/Diff.java` and public `panel/PanelDiff.java` — `record PanelDiff(List<String> added, List<String> removed, List<String> changed)` with `isEmpty()`.
- [x] 2a.15 GREEN: create `panel/internal/Session.java` — working copy, undo stack, page, filter, clipboard; resolved through the `UiSession` window holder, never a static map. A click validates against what the session drew.
- [x] 2a.16 GREEN: complete `PanelRuntime` — per-plugin registries, `forget(Player)`, `release(String)`, `releaseAll()`.
- [x] 2a.17 GREEN: create public `panel/Panels.java` (`of`, `session`, `undoLimit()`=20, `release`, `releaseAll`), `PluginPanels.java` (`plugin`, `settings`, `list`, `close`), `PanelSession.java` (`viewer`, `owner`, `undo`, `undoDepth`, `diff`, `save`, `cancel`).
- [x] 2a.18 GREEN: modify `ExyliaLib.java` — `PanelRuntime.forget` in `onPlayerQuit`; `Panels.release` in `onPluginDisable` **before** `Menus.release`; `Panels.releaseAll()` in `onDisable` before `Menus.releaseAll()` (line 391). Add **no** `loadPalette` hook (decision 5).
- [x] 2a.19 REFACTOR: confirm `open(Player)` delegates to `PluginMenus.open` (any-thread, relocates via `runAtEntity`) and that `openNow` is **not** exposed (decision 7). No `BukkitRunnable`, `Bukkit.getScheduler()`, `new Thread`, or private `ExecutorService`.
- [x] 2a.20 Doc: add a `panel` row to the palette-reload table in `docs/reload.md` (after line 225) reading, in substance: *no rendered text is kept — controls are `Item` definitions rendered through `PluginItems.render`, whose `ItemCache.invalidateAll()` already drops them; nothing to do*. Silence is not acceptable per spec `panel-engine` §Palette reload contract.
- [x] 2a.21 Javadoc: `Panels`, `PluginPanels`, `PanelSession`, `PanelDiff` — English, `@since 1.50.0`, with the usage example, the any-thread contract on `open`, and the 20-snapshot undo bound and its rationale.
- [x] 2a.22 **Sabotage**: remove the undo cap → `PanelUndoTest` must fail. Resolve the session from a `Map<UUID, Session>` instead of the window holder → `PanelNoStaticStateTest` must fail. Drop tasks before closing windows on disable → `PanelLifecycleTest` must fail. Restore all three; record in the PR body.
- [x] 2a.23 Verify: extend `PublicSignatureSweepTest` (from 1.9) to cover `net.exylia.lib.panel`; `./gradlew clean build` green, zero warnings.

## Unit 2b — YAML layouts (~220 lines, depends on 2a)

- [ ] 2b.1 SEAM: add to `panel/internal/Layouts.java` — `interface LayoutSource { @Nullable ConfigurationSection layout(String id); }` + `public static void install(@Nullable LayoutSource)`. Precedent: `Engines.install`.
- [ ] 2b.2 RED: create `panel/PanelLayoutFallbackTest.java` — a `LayoutSource` returning invalid YAML opens on `BUILT_IN`, reports **exactly one** problem via `DebugCapture`, stays operable.
- [ ] 2b.3 RED: extend it — an **absent** layout degrades identically, with one report; and opening three times still reports once.
- [ ] 2b.4 RED: extend it — a layout declaring save at slot 49, re-read with the owner's edit to slot 45, draws save at slot 45 on the next open (asserted through the draw sink, seam 2). No slot or size is hardcoded in engine control flow.
- [ ] 2b.5 GREEN: create `resources/panels/settings.yml` and `resources/panels/list.yml` — slots, sizes, titles, colours; palette tokens only, never inline hex (AGENTS §Texto y color).
- [ ] 2b.6 GREEN: implement YAML loading in `Layouts` on top of `LayoutSource`, falling back to `BUILT_IN` on missing, unreadable, or malformed input, reported once via `Debug.of(ExyliaLib)`.
- [ ] 2b.7 GREEN: modify `ExyliaLib.java` — `Menus.of(this).refreshBundledDirectory(ExyliaLib.class, "panels")` at enable, into `plugins/ExyliaLib/panels/`.
- [ ] 2b.8 **Sabotage**: let malformed YAML propagate instead of falling back → `PanelLayoutFallbackTest` must fail. Report per open instead of once → 2b.3 must fail. Restore; record in the PR body.
- [ ] 2b.9 Verify: `./gradlew clean build` green, zero warnings.

## Unit 3 — `settings-panel` (~650 lines, depends on 1 and 2a)

- [x] 3.1 RED: create `panel/RecordRebuilderTest.java` — rebuilding through the canonical constructor in declared order preserves untouched components; a type mismatch returns a rejection carrying the cause's message and **does not mutate** the working copy.
- [x] 3.2 RED: extend it — a compact constructor that throws (`InvocationTargetException`, e.g. a blank-name `ParsedEffect`) is caught, surfaced as a `Validation` failure, and leaves the working copy intact. `ConfigFile.update` is never reached (decision 4).
- [x] 3.3 RED: create `panel/SettingsControlMappingTest.java` — a record of `int`, `double`, `boolean`, enum, `String`, `List<String>`, nested record yields **seven** controls in canonical order: integral / decimal / toggle / searchable choice / text / list sub-panel / sub-panel, read via the draw sink (seam 2).
- [x] 3.4 RED: extend it — two `@Comment` lines render as lore in declaration order; an enum control opens a `SearchInput` over the constants (asserted via `PanelPrompts.install`, seam 5) and selecting one sets the working copy; a nested record opens a sub-panel whose edits reach the parent working copy before save.
- [x] 3.5 RED: create `panel/UnsupportedComponentTest.java` — a record of `int`, `String`, and an uncontrollable type opens, saves the edited `int`, keeps the `String`, and **passes the unsupported value through untouched**.
- [x] 3.6 RED: extend it — clicking the unsupported control changes nothing and opens no input request, while its `@Comment` lore still shows; and three opens by two players yield **exactly one** report naming record type and component, repeatable across methods via `UnsupportedTypes.forgetReportedForTests()` (seam 4).
- [x] 3.7 RED: create `panel/EffectConfigGenericPathTest.java` — scan every class under `net.exylia.lib.panel` for a reference to `EffectConfig` or its nested record types; assert none outside test sources.
- [x] 3.8 RED: extend it — a `ConfigFile<EffectConfig>` generates controls and nested sub-panels, and editing a nested value then saving persists through `ConfigFile.update`.
- [x] 3.9 RED: add a round-trip case per supported type — edit, save, reload, read back equal; and a cancel case where `ConfigFile.update` is **never** called.
- [x] 3.10 GREEN: create `panel/internal/RecordRebuilder.java` — `Class.getRecordComponents()` + `getDeclaredConstructor`, pure, returning `Optional<T>` or a rejection reason. Plain JDK reflection on a public class; never touches `config.internal` or `SchemaNode.canonical()` (decision 3).
- [x] 3.11 GREEN: create `panel/internal/ControlMapper.java` — declared type → `ControlKind`, driven by unit 1's `Schema`, with **no** per-record code and no `switch` over any domain type.
- [x] 3.12 GREEN: complete `panel/internal/UnsupportedTypes.java` — read-only control, not-editable marker, `@Comment` lore retained, excluded from the edit path, reported once per server.
- [x] 3.13 GREEN: create `panel/internal/SettingsEngine.java` and public `panel/SettingsPanel.java` (`title`, `onSaved`, `open`).
- [x] 3.14 GREEN: wire save — `runAsync` → `ConfigFile.update(UnaryOperator<T>)` → back via `runAtEntity`. No YAML write, no `FileConfiguration`, no other write path. Atomic at record level.
- [x] 3.15 REFACTOR: assert `ControlMapper` reads only `Schema`, so adding a supported type touches one mapping table and nothing else.
- [x] 3.16 Javadoc: `SettingsPanel` — English, `@since 1.50.0`, with the `Panels.of(this).settings(file).open(player)` example, the any-thread `open` contract, and the unsupported-type degrade rule.
- [x] 3.17 **Sabotage**: swallow the rebuild rejection and write anyway → `RecordRebuilderTest` must fail. Report the unsupported type per open → 3.6 must fail. Restore; record in the PR body.
- [x] 3.18 Verify: `./gradlew clean build` green, zero warnings.

## Unit 4 — `list-panel` (~550 lines, depends on 2a — **not** on 3)

- [x] 4.1 SEAM: create `panel/TestDescriptors.java` (test source only) — a `FieldDescriptor<Note>` over `record Note(String id, String text)`. Proves list behaviour independently of either built-in descriptor.
- [x] 4.2 RED: create `panel/ListEntryIdentityTest.java` — 60 entries over three pages; deleting the second row on page 3 removes the element **that row carried**, leaving list indices 1 and 2 untouched. The verified Commons potion bug.
- [x] 4.3 RED: extend it — deleting the first row of a non-contiguous search result removes the carried element, not index 0 of the unfiltered list; and reordering the backing list between draw and click still resolves through `UiKeys.ENTRY`.
- [x] 4.4 RED: create `panel/ListSearchTest.java` — 20 entries, a search matching 3 shows 3 rows and restores 20 on clear, the working copy holding 20 throughout; next-page redraws list slots only, leaving unrelated slots un-re-sent.
- [x] 4.5 RED: extend it — a search matching nothing draws the **pagination filler stating why**, distinct from the background filler (`AGENTS.md` §Menus: the three fillers are three things).
- [x] 4.6 RED: create `panel/ListClipboardTest.java` — copy then paste yields two entries with matching payloads and **different identities**; paste with an empty clipboard is a no-op, not an error; the clipboard is gone after close, quit, and plugin disable.
- [x] 4.7 RED: create `panel/ListConfirmDeleteTest.java` — delete routes through `ConfirmInput.dangerous()` (`input/ConfirmInput.java:36`), scripted via `PanelPrompts.install`; denial leaves the working copy unchanged; confirm-then-undo restores all 5 entries, the restored one equal to the deleted.
- [x] 4.8 RED: add a save/cancel case — the diff reports exactly one addition, one removal, one change and persists only through the descriptor's write path; an unmodified list writes nothing; cancel discards deletions, pastes, and edits alike.
- [x] 4.9 RED: add the extension-point case — `TestDescriptors`' consumer-owned record gets paginate, search, copy, paste, delete, undo, save, cancel with **no additional class**.
- [x] 4.10 GREEN: create public `panel/FieldDescriptor.java` — `label`, `icon`, `identity`, `create`, `duplicate`, `edit`, `load`, `save`, default `matches`. `label` and `identity` never return null.
- [x] 4.11 GREEN: create `panel/internal/ListEngine.java` — one generic implementation, entries carried through `UiKeys.ENTRY`, page numbers taken from the `UiSection`, never from the caller.
- [x] 4.12 GREEN: create public `panel/ListPanel.java` (`title`, `onSaved`, `open`).
- [x] 4.13 GREEN: route search through the existing `SearchInput` and confirmation through `ConfirmInput.dangerous()` — both via `PanelPrompts`, neither reimplemented. Filter affects the view only, never the working copy.
- [x] 4.14 GREEN: session-scoped clipboard on `Session` (unit 2a) — no static map, dies with the session.
- [x] 4.15 REFACTOR: assert no slot number, page number, or list index is used as entry identity anywhere in `ListEngine`.
- [x] 4.16 Javadoc: `ListPanel`, `FieldDescriptor` — English, `@since 1.50.0`, stating that descriptor callbacks for identity, creation, and copying are pure and any-thread, and that persistence runs off the viewer thread.
- [x] 4.17 **Sabotage**: resolve the delete target by list index instead of `UiKeys.ENTRY` → `ListEntryIdentityTest` must fail. Let the filter mutate the working copy → `ListSearchTest` must fail. Restore; record in the PR body.
- [x] 4.18 Verify: `./gradlew clean build` green, zero warnings.

## Unit 5 — descriptors, docs, doctrine (~400 lines, depends on 4)

- [ ] 5.1 RED: create `panel/RewardDescriptorTest.java` — editing an amount preserves the id (`toBuilder()`), copy-paste yields matching payloads with different ids (`copy()`), and a `RewardCodec`-written list saved unedited produces a **byte-identical** stored string.
- [ ] 5.2 RED: create `panel/ParsedEffectDescriptorTest.java` — `SPEED:1:300|JUMP_BOOST:2:120` saved unedited parses to the same two effects in the same order; deleting an entry on a later page removes the carried effect; an unresolvable effect name survives untouched in the saved string.
- [ ] 5.3 RED: create `panel/NoNamedCommandTypeTest.java` — scan public and internal library source for a `NamedCommand` type; assert none exists.
- [ ] 5.4 RED: add a descriptor-surface case — neither built-in exposes pagination, search, clipboard, or undo behaviour, and neither is privileged over a consumer-supplied descriptor.
- [ ] 5.5 GREEN: create `panel/descriptor/RewardDescriptor.java` — reuses `toBuilder()`, `copy()`, `displayName()`, `resolvedIcon()`; persists through `RewardCodec`'s existing stored form. Reimplements no rendering, chance/weight semantics, or codec.
- [ ] 5.6 GREEN: create `panel/descriptor/ParsedEffectDescriptor.java` — produces the existing `NAME:amplifier:duration|…` string form; addresses entries by carried value only.
- [ ] 5.7 Doc: create `docs/panels.md` — English, written against signatures extracted with `grep -n "    public" src/main/java/net/exylia/lib/panel/*.java` (never from memory). Cover `Panels`/`PluginPanels`/`SettingsPanel`/`ListPanel`/`PanelSession`/`PanelDiff`/`FieldDescriptor`, the any-thread `open` contract, the 20-snapshot undo bound, layout retheming, **and the explicit statement that `panel` caches nothing palette-derived** (quality-bar point 8).
- [ ] 5.8 Doc: add the named-command **example** to `docs/panels.md` — a consumer-owned record of three strings plus one `FieldDescriptor`, requiring no library change. This is the whole delivery of named commands (proposal §Out of Scope).
- [ ] 5.9 Doc: add a `panel` row to the index table in `docs/README.md`, matching the existing column shape, `1.50.0` in the version column.
- [ ] 5.10 Doc: add a `panel` row to the module table in `AGENTS.md` §"Module map" — public API `panel/Panels`, `PluginPanels`, `SettingsPanel`, `ListPanel`, `PanelSession`, `PanelDiff`, `FieldDescriptor`; Internal `panel/internal/`; Doc `docs/panels.md`; Since `1.50.0`. Add the six new seams to §"Injectable seams for tests (don't remove them)".
- [ ] 5.11 Doc: add a `## Paneles` doctrine section to `AGENTS.md` in the voice of the existing sections — state that panel state lives on the window and never in a static map, that entries are addressed by their carried value, that layouts are server-wide and owned by ExyliaLib, and that the effects editor is the settings panel with zero `EffectConfig` code.
- [ ] 5.12 Verify: run the class-isolation sweep from `AGENTS.md` §"Artifact inspection" over the built classes and assert **no** `panel` class references Folia (`threadedregions`), PacketEvents, or FAWE types:
      `for f in $(find build/classes -name "*.class" -path "*panel*"); do javap -c -p "$f" | grep -qE "threadedregions|packetevents|fastasyncworldedit" && echo "$f"; done` — output must be empty.
- [ ] 5.13 Verify: correct the stale line `openspec/config.yaml:34` — replace `current_status: blocked by existing compile failure in ExyliaLib.loadFormats()` with a passing status. `./gradlew compileJava` exits 0; the recorded blocker does not exist and would send `sdd-verify` chasing a phantom.
- [ ] 5.14 Verify: raise `openspec/config.yaml:8` `review_budget_lines` and the `rules.tasks` auto-chain threshold only if the maintainer confirms — otherwise leave both at 400 and note that this session ran at 800 by explicit override.
- [ ] 5.15 **Sabotage**: change `RewardDescriptor` to write a field the old codec omits by default → 5.1's byte-compatibility case must fail. Have `ParsedEffectDescriptor` drop an unresolvable effect name → 5.2 must fail. Restore; record in the PR body.
- [ ] 5.16 Verify: `./gradlew clean build` green with zero warnings; `./gradlew test` passes; publish with `publishToMavenLocal` and compile a throwaway consumer against `Panels.of(plugin).settings(file).open(player)` to confirm the API is comfortable in real use (`AGENTS.md` §Verification).

---

## Review Workload Forecast

**Estimated changed lines per work unit**

| Unit | Slice | Est. lines |
|------|-------|-----------:|
| 1 | `config-schema-projection` | ~200 |
| 2a | panel engine core (seams, session, undo, diff, lifecycle) | ~480 |
| 2b | YAML layouts + degrade-once | ~220 |
| 3 | `settings-panel` | ~650 |
| 4 | `list-panel` | ~550 |
| 5 | descriptors + docs + doctrine | ~400 |
| | **Total** | **~2,500** |

Chained PRs recommended: Yes
Chain strategy: pending
400-line budget risk: High
800-line budget risk: Low
Decision needed before apply: No

**Budget note**: this session's review budget is **800 lines**, an explicit override of `openspec/config.yaml:8` (400). Reported against 800, every unit clears it and no `size:exception` is required. Reported against the standing 400, units 2a, 3, 4, and 5 exceed it — so the chain is what makes this reviewable either way.

**Recommended PR slicing and dependency order**

```
PR1  unit 1   schema projection        base: tracker branch
PR2a unit 2a  engine core              base: PR1
PR2b unit 2b  YAML layouts             base: PR2a
PR3  unit 3   settings panel           base: PR2a
PR4  unit 4   list panel               base: PR2a
PR5  unit 5   descriptors + docs       base: PR4
```

True dependency DAG: `1 → 2a → {2b ‖ 3 ‖ 4} → 5`. PR2b, PR3, and PR4 are siblings off PR2a and may be reviewed in parallel; PR5 needs PR4 only. If review capacity is serial, land them in the listed order — that ordering is also a valid linear chain.

Recommended chain strategy: **feature-branch-chain**. Only the tracker merges to main, which matches the coordinated minor release this module needs (`@since 1.50.0`) and keeps the `build.gradle` version bump — reserved release input — a single deliberate act at the end rather than something a mid-chain merge to main could trigger. Awaiting the orchestrator's confirmation.
