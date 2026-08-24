# Proposal: Built-in Configuration Panels (`net.exylia.lib.panel`)

## Intent

ExyliaCommons shipped five "editors" (`reward`, `loot`, `effect`, `namedcommand`, `potion`) that are the same editor five times: each carries Selector + Session + Registry + Clipboard + ListMenu + EditMenu + TypeSelectMenu + ActionRegistrar. `EffectEditorActionRegistrar.register()` is 612 lines, `RewardEditorActionRegistrar` 520, `LootEditorActionRegistrar` 375 — single static methods with ~25 inline lambdas each repeating the same `session == null` guard. `AbstractSelector` is 19 lines and abstracts nothing.

Consequences, all verified: six `*EditorRegistry` and seven `*Clipboard` static `ConcurrentHashMap<UUID,…>` singletons, **none** listening to `PlayerQuitEvent` — ESC leaks the session, clipboards leak for the server lifetime. Slots are hardcoded (45–53, size 54/45, `IntStream.rangeClosed(0,35)`); only `title` is configurable. Hex colours and English literals are inline in `switch` blocks. No search in any inventory UI. No confirmation and no undo: right-click deletes irreversibly, and the only Cancel discards the whole list. The potion editor addresses entries by **list index** while the other four use UUID — broken under pagination + deletion. `IconPickerAPI` is not even on the `SelectorAPI` facade.

The opportunity is not "port the editors". `net.exylia.lib.config.internal.SchemaCache`/`SchemaNode` have held, since 1.1.0 and unused, exactly the metadata a settings GUI needs:

```java
record SchemaComponent(String name, String key, Class<?> type,
                       java.lang.reflect.Type generic, List<String> comments, SchemaNode nested)
```

YAML key, Java name, declared type, generic type, the `@Comment` lines, and the nested node — with `SchemaNode.canonical()` as the canonical constructor, so a record can be rebuilt generically (read components, swap index *i*, `newInstance`) and handed to `ConfigFile.update(UnaryOperator<T>)`. A panel generated from that schema maps `int` → number button, `boolean` → toggle, enum → searchable picker, nested record → sub-panel, and turns the `@Comment` lines into lore. Doctrine already calls those comments "el manual del dueño del servidor"; today only someone opening the `.yml` reads them.

**Evidence the abstraction is right**: Commons' `EffectEditMenu` was 493 lines of `switch` over EffectType. In ExyliaLib, `EffectConfig` is already a record with 45 `@Comment`s across 8 nested records — so *the effects editor is the settings panel pointed at `EffectConfig`, with zero domain-specific editor code*. Fourteen library records (`DatabaseSettings`, `EconomySettings`, `EffectConfig`, `FormatSettings`, `HologramConfig`, `InputSettings`, `LibrarySettings`, `RedisSettings`, `SidebarConfig`, `Palette`, `PreviewSettings`, `SnapshotSettings`, `TeleportSettings`, `WizardSettings`) gain an editor for free.

## Scope

### In Scope

1. **Panel engine** — `Panels.of(plugin)` / `PluginPanels`, a `PanelSession` bound to the `UiSession` window (no static per-player maps), bounded undo stack, diff-before-save, and lifecycle release.
2. **Public schema projection** — a read-only public view over `SchemaNode`/`SchemaComponent` so a panel can be generated without leaking `config.internal`. Minimal, additive, justified below.
3. **Settings panel** — generated from any `ConfigFile<T>`'s record schema; `@Comment` lines become lore; writes through `ConfigFile.update`.
4. **Effects editor** — delivered as the settings panel pointed at `EffectConfig`. No new editor code.
5. **Generic list panel** — one editor (paginate, search, copy, paste, delete, undo, confirm, save, cancel) parameterised by a `FieldDescriptor`.
6. **Descriptors** for `RewardEntry` and `Effects.ParsedEffect`.
7. **Docs + tests** — `docs/panels.md`, behaviour tests on `FakeServer`.

### Out of Scope

- **Loot editor** — deferred by the user; no loot module exists in ExyliaLib.
- **A `NamedCommand` domain type.** `NamedCommand` has **0 occurrences** in ExyliaLib. Commons' type was three strings (`id`, `name`, `command`). Introducing a domain module for three strings violates "sin abstracción especulativa" and the quality bar's point 1 ("problema real y repetido"). Named commands ship as a **documented example** of the generic list panel over a consumer-owned record. If a second real consumer appears, promoting it is a later, cheap change.
- Item-builder panel, `/exylialib panels` browser, diagnostics panel — future work.
- Registry/colour/icon pickers beyond what `SearchInput` already provides.

## Capabilities

`openspec/specs/` is currently empty (only `config.yaml` exists), so every capability below is new.

### New Capabilities
- `config-schema-projection`: public, read-only description of a config record's schema (key, Java name, declared/generic type, `@Comment` lines, nested node) without exposing `config.internal`.
- `panel-engine`: window-bound panel sessions, bounded undo, diff-before-save, YAML-themable layouts, plugin-scoped lifecycle.
- `settings-panel`: a panel generated from a `ConfigFile<T>` schema that edits and persists the record.
- `list-panel`: a generic paginated list editor with search, clipboard, delete, undo, save/cancel, parameterised by a field descriptor.
- `panel-field-descriptors`: descriptors binding `RewardEntry` and `Effects.ParsedEffect` to the list panel.

### Modified Capabilities
None. This change is purely additive; no existing module's public API changes except the additive schema projection (item 2), which introduces new types and does not alter existing signatures.

## Approach

**Placement: `net.exylia.lib.panel`, sibling of `ui`, not inside it.** Verified: `ui` imports only `action`, `command`, `debug`, `effect`, `item`, `task`, `text` — and **not** `config`, `input`, or `util.reward`. Putting editors in `ui` would invert that and make `ui` import half the repo. This is the same reasoning AGENTS.md already records for `item` living outside `ui` ("SpecialsV3, PracticeCore, Shields y SurvivalCore lo usan sin abrir ninguna GUI"). `util/panel` is rejected: `util` is documented as "utilidades auto-contenidas… no dependen entre sí", and a panel depends on `ui`, `input`, `config`, `item`, `action` and `text` at once. `panel` is a top-level module that *consumes* `ui`, exactly as `ui` consumes `item`.

**Schema projection.** A new public `config.Schema` / `Schema.Field` record pair, produced by a single new method on `ConfigFile` (or `Configs`), mapping `SchemaNode` → `Schema` at call time. `internal` types stay internal; the projection is a copy, so `internal` remains free to change per the "Estructura" rule.

**Generic rebuild.** Read the record's components via the canonical constructor's parameter order, substitute index *i*, `newInstance`, hand the result to `ConfigFile.update`. All of it inside `panel/internal`.

**Unsupported types.** A component whose type has no editor is rendered **read-only** with its `@Comment` lore and a "not editable here" note, reported once via `Debug`/`Problems`, and **excluded from the rebuild path** — its existing value is passed through untouched. The panel and the config must survive an unsupported field. This mirrors the item module's "una parte ilegible se reporta y se salta".

**Reuse, do not rewrite.** Search is `SearchInput` + `internal/SearchTransport` (682 L, DIALOG > BEDROCK > ANVIL_SEARCH > MENU > CHAT ladder). Multi-field edit is `FormInput`/`FormField`/`FormKey`/`FormValues`. Confirmation is `ConfirmInput.dangerous()`. Row identity is `UiKeys.ENTRY` — the exact seam that removes Commons' static per-player maps and the potion editor's index bug (entries are addressed by their carried value, never by list index). Rewards reuse `RewardEntry.toBuilder()` (preserves id), `copy()` (new id), `preview()`, `resolvedIcon()` — whose Javadoc already says it was designed for an editor menu.

**Themable layouts.** Panel layouts ship as bundled YAML resources and are refreshed at startup with `PluginMenus.refreshBundledDirectory(Class, String)` (added 1.49.3), so owners retheme slots and colours without recompiling the library. That is what replaces Commons' hardcoded 45–53.

### Quality bar (AGENTS.md, all 8 points)

| # | Requirement | How this change satisfies it |
|---|---|---|
| 1 | Real, repeated problem | Five duplicated editors in Commons; 14 annotated records in the lib with no editor. Not speculative. |
| 2 | Small, obvious API | `Panels.of(plugin).settings(configFile).open(player)` and `.list(descriptor).open(player)`. No call ordering to explain. |
| 3 | English Javadoc + `docs/panels.md` | Written against extracted signatures, in the same commit, per the anti-hallucination rules. |
| 4 | **Folia** | No `BukkitRunnable`, no `Bukkit.getScheduler()`. All scheduling via `net.exylia.lib.task`; player-bound work uses `runAtEntity`. Config file writes go through `Configs`/`runAsync`. |
| 5 | **No leaks** | Zero static per-player maps. State lives on the session, found by the window holder (per the `ui` rule), released on close, on `PlayerQuitEvent`, and on plugin disable — which cancels any `ActionExecution` with delayed steps before dropping tasks. |
| 6 | **Class isolation** | No platform-specific types introduced. Any packet path (retitle) is already confined in `ui/internal/TitlePackets`; `panel` names no PacketEvents type. Verifiable with the documented `javap` sweep. |
| 7 | Behaviour tests | Rebuild-a-record, unsupported-type passthrough, undo bounds, diff correctness, delete-under-pagination identity, session release on quit/disable — on `FakeServer`/`FakePlayer`, with deliberate-sabotage checks. |
| 8 | **Palette reload** | Any panel that caches a rendered `Component` beyond one render exposes `invalidateAll()` and is hooked into `ExyliaLib.loadPalette` alongside `BoardManager`, `HologramRuntime`, `EffectRuntime`, `ItemCache`. If the engine renders per-open and caches nothing palette-derived, that is documented explicitly in `docs/panels.md` and `docs/reload.md` — the rule requires saying it, not omitting it. |

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `src/main/java/net/exylia/lib/panel/` | New | Public API: `Panels`, `PluginPanels`, `PanelSession`, `FieldDescriptor`, `PanelResult`, `PanelSettings`. |
| `src/main/java/net/exylia/lib/panel/internal/` | New | Engine, settings generator, list panel, record rebuild, undo stack, diff. |
| `src/main/java/net/exylia/lib/config/` | Modified (additive) | New public `Schema`/`Schema.Field` projection + one accessor. No existing signature changes. |
| `src/main/java/net/exylia/lib/config/internal/SchemaNode.java` | Modified | Node → projection mapping only. Stays package-private. |
| `src/main/java/net/exylia/lib/ExyliaLib.java` | Modified | `init`/`forget`/`release` for panels; palette hook if item 8 applies. |
| `src/main/resources/panels/` | New | Bundled YAML layouts refreshed via `refreshBundledDirectory`. |
| `src/test/java/net/exylia/lib/panel/` | New | Behaviour tests. |
| `docs/panels.md`, `docs/README.md`, `docs/config.md`, `docs/reload.md` | New/Modified | Module doc, index row, schema projection, reload table. |
| `AGENTS.md` | Modified | New `### Panels` doctrine section + module map row. |
| `build.gradle` | **Unchanged** | Version is release input, coordinated separately. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| **Reflective record rebuild** corrupts or drops a config value | Med | Rebuild only through `SchemaNode.canonical()` in declared component order; unsupported components pass their existing value through untouched; write only via `ConfigFile.update`; round-trip tests per supported type + a "record with an unsupported component survives an edit" test. |
| Unsupported component type breaks the panel | Med | Rendered read-only, reported **once** (not per open — same lesson as `ItemComponents`' per-server reporting), never fatal. |
| Leaking `config.internal` through the projection | Med | Projection is a copy of plain public records; `SchemaNode` stays package-private; a test asserts no `internal` type appears in a public `panel`/`config` signature. |
| Palette-cache bug (the 1.16.0 static-effect class of bug) | Med | Point 8 is a hard gate: either `invalidateAll()` + `loadPalette` hook, or an explicit documented "caches nothing palette-derived", covered by `PaletteReloadTest`. |
| Size overrun / reviewer burnout | **High** | Forecast below; auto-chain into 5 slices. |
| Scope creep back toward Commons' five editors | Med | Loot and `NamedCommand` are explicitly out; descriptors limited to two real types. |
| Bundled YAML drifts from engine expectations | Low | `refreshBundledDirectory` runs at startup; an unreadable layout falls back to a built-in default and is reported, never blocking the panel. |

## Rollback Plan

The change is additive and isolated, so rollback is deletion, not migration:

1. **Full revert** — `git revert` the slice commits in reverse order. No consumer plugin can depend on `panel` before its release, so nothing breaks.
2. **Partial revert** — slices are independent by construction. Dropping `list-panel` + descriptors leaves the engine and settings panel working; dropping everything but the schema projection leaves a small, useful additive API.
3. **Runtime kill** — no existing behaviour is routed through `panel`. Not opening a panel is the off switch; no config flag or migration is needed.
4. **Config safety** — panels write only through `ConfigFile.update`, which already writes the file from the record. A reverted panel leaves valid YAML behind; there is no panel-specific on-disk format to unwind.
5. **Compatibility** — public config keys and existing module APIs are untouched, so a revert cannot strand a `.yml`. `build.gradle` version is not modified here, so no published tag is implicated (per the "el versionado es inmutable" rule).

## Dependencies

- No new external dependencies. Everything reuses `config`, `ui`, `input`, `item`, `action`, `text`, `task`, `debug`, `util/reward`, `effect`.
- Requires `PluginMenus.refreshBundledDirectory` (present since 1.49.3).
- Versioning target: **minor bump from 1.49.4** (a new public module). `build.gradle` is **not** edited by this change.

## Size Forecast and Work-Unit Split

Honest forecast: **~2,000–2,600 authored changed lines**, well past the 800-line session budget. Auto-chain into five slices, each independently verifiable and under budget:

| # | Slice | Est. lines | Contents |
|---|-------|-----------:|----------|
| 1 | `config-schema-projection` | ~250 | Public `Schema`/`Schema.Field`, mapping, `docs/config.md`, tests. Independently useful. |
| 2 | `panel-engine` | ~600 | `Panels`/`PluginPanels`/`PanelSession`, undo stack, diff, lifecycle, YAML layout loading, `ExyliaLib` wiring. |
| 3 | `settings-panel` | ~600 | Schema-driven panel, record rebuild, unsupported-type passthrough, `EffectConfig` proof (no domain code). |
| 4 | `list-panel` | ~550 | Generic paginated editor: search, clipboard, delete, undo, save/cancel. |
| 5 | `descriptors + docs` | ~400 | `RewardEntry` and `ParsedEffect` descriptors, named-command example, `docs/panels.md`, `docs/README.md`, `docs/reload.md`, `AGENTS.md`. |

## Success Criteria

- [ ] `Panels.of(plugin).settings(configFile).open(player)` edits and persists any of the 14 annotated records without per-record code.
- [ ] The effects editor is delivered with **zero** `EffectConfig`-specific editor code (contrast: Commons' 493-line `switch`).
- [ ] A record containing an unsupported component type still opens, still saves, and preserves that component's value; the report fires **once**, not per open.
- [ ] No static per-player map exists in `panel`; a test proves session state is gone after close, quit, and plugin disable.
- [ ] Entries are addressed by their carried value (`UiKeys.ENTRY`), never by list index; a delete-under-pagination test proves the Commons potion bug cannot recur.
- [ ] Search, undo, confirm-on-destructive, and diff-before-save work in the list panel; `SearchInput` is reused, not reimplemented.
- [ ] Panel layouts load from bundled YAML and reflect an owner's slot/colour edit after `refreshBundledDirectory`.
- [ ] Point 8 satisfied: either `invalidateAll()` is hooked into `ExyliaLib.loadPalette` and covered by `PaletteReloadTest`, or `docs/reload.md` states explicitly that `panel` caches nothing palette-derived.
- [ ] `./gradlew clean build` is green with zero warnings; `./gradlew test` passes; sabotage checks confirm the tests actually fail when the logic is broken.
- [ ] `docs/panels.md` exists, is English, and is written against extracted signatures.
- [ ] No existing public API signature changed; `build.gradle` version untouched.
