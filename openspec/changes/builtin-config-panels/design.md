# Design: Built-in Configuration Panels (`net.exylia.lib.panel`)

## Technical Approach

`panel` is a top-level module that *consumes* `ui`, exactly as `ui` consumes `item`. It adds no
new dependency and names no platform type. Two panels exist: a **settings panel** generated from a
`ConfigFile<T>`'s record schema, and a **list panel** parameterised by a `FieldDescriptor<T>`.
Everything else — session, undo, diff, clipboard, search, confirm — is engine, written once.

Three seams keep the module honest: the schema arrives as a **pure value copy** (`config.Schema`),
the record is rebuilt through **plain JDK reflection on a public class** (never through
`config.internal`), and state lives on the `UiSession` window holder (never in a static map).

## Architecture Decisions

| # | Decision | Choice | Rejected | Rationale |
|---|---|---|---|---|
| 1 | Undo bound | **20 snapshots per session**, constant, exposed as `Panels.undoLimit()` | Configurable via `PanelSettings`; unbounded | A settings snapshot is one record *reference* — the previous instance the rebuild would have discarded anyway, so it is free. A list snapshot is `List.copyOf` — N references, elements shared because they are immutable. Worst realistic case: a 500-entry reward list = 20 × 500 × 8 B ≈ **80 KB per open session**, released on close. 20 is above what anyone undoes in one sitting and below where the copy cost is visible. `PanelSettings` is rejected: this is a memory bound, not a preference. |
| 2 | Schema projection | New public `config.Schema` / `Schema.Field`, built by package-private `config/internal/SchemaProjection` | `SchemaCache` returning `Schema`; making `SchemaNode` public | `internal → public` is the direction already used (`SchemaCache` imports `config.Comment`). `SchemaProjection.of(Class<?>, String)` takes and returns only public types, so no `internal` type and no `Constructor<?>` reaches a public signature. `Schema` stays a **pure value**, so two `ConfigFile`s of one type project equal. |
| 3 | Record rebuild | `panel/internal/RecordRebuilder`, using `Class.getRecordComponents()` + `getDeclaredConstructor` | Rebuild method on `Schema`; new public `config.Records` helper | Record reflection is *public JDK API on a public class* — the panel needs no access to `SchemaNode.canonical()`. This keeps `Schema` a pure value (decision 2) and makes slice 1 and slice 3 independent. No new public API is added for it. |
| 4 | Rejected value handling | Rebuild is **pure**: it returns `Optional<T>` or a rejection reason. `ConfigFile.update` is called only at save, with an already-built record | Try/catch around `update` | `IllegalArgumentException` (type mismatch) and `InvocationTargetException` (a compact constructor threw — `ParsedEffect` on a blank name, `EffectConfig.Title`) are caught, the cause's message becomes a `Validation` failure shown to the player, and **the working copy is not mutated**. The config is never touched, because the rebuild happens before any write path exists. |
| 5 | Palette reload | **Caches nothing palette-derived. No `invalidateAll()`, no `loadPalette` hook.** Stated in `docs/reload.md` and `docs/panels.md` | Own cache + hook | Verified: `UiDefinition`/`UiItem` hold raw strings, not `Component`. The panel holds `Item` definitions (unresolved) and renders through `PluginItems.render`, whose cache `ItemCache.invalidateAll()` already drops. Adding a second cache would recreate the 1.16.0 static-effect bug. `PanelPaletteTest` proves it by reflecting over `panel` fields for a retained `Component`. **Honest limit**: a panel already on screen redraws on its next interaction — that is `ui`'s existing behaviour, and `panel` adds no new staleness. |
| 6 | Layouts | Bundled at `src/main/resources/panels/{settings,list}.yml`, refreshed once at ExyliaLib enable with `Menus.of(this).refreshBundledDirectory(ExyliaLib.class, "panels")` into `plugins/ExyliaLib/panels/` | Per-consumer-plugin layouts | Layouts are **server-wide**, owned by ExyliaLib, like `colors.yml`. Twenty consumers copying two theme files each is the problem the palette already solved. Missing/unreadable/malformed → `Layouts.BUILT_IN` (a `UiDefinition` built in Java), reported **once** via `Debug.of(ExyliaLib)`, panel still operable. |
| 7 | Threading | `open(Player)` is **any-thread** (delegates to `PluginMenus.open`, which relocates via `runAtEntity`). `openNow` is deliberately **not** exposed | Exposing `openNow` | Nothing in a panel needs a session synchronously; exposing `openNow` would export a thread precondition and break quality-bar point 2. Working copy / diff / undo / rebuild: any thread, no Bukkit. Save: `runAsync` → `ConfigFile.update` → back via `runAtEntity`. |
| 8 | Dropped from proposal | `PanelSettings`, `PanelResult` | — | `PanelSettings` had one knob (decision 1). `PanelResult` is covered by `PanelDiff` + `onSaved`. Speculative abstraction. |

## Consumer API

```java
// net.exylia.lib.panel
public final class Panels {
    public static PluginPanels of(Plugin plugin);
    public static Optional<PanelSession> session(Player viewer);
    public static int undoLimit();                       // 20
    public static void release(String pluginName);
    public static void releaseAll();
}

public final class PluginPanels {
    public Plugin plugin();
    public <T extends Record> SettingsPanel<T> settings(ConfigFile<T> file);
    public <T> ListPanel<T> list(FieldDescriptor<T> descriptor);
    public void close(Player viewer);
}

public final class SettingsPanel<T extends Record> {
    public SettingsPanel<T> title(String title);
    public SettingsPanel<T> onSaved(Consumer<T> action);
    public void open(Player viewer);                     // any thread
}

public final class ListPanel<T> {
    public ListPanel<T> title(String title);
    public ListPanel<T> onSaved(Consumer<List<T>> action);
    public void open(Player viewer);                     // any thread
}

public interface PanelSession {
    Player viewer();
    Plugin owner();
    boolean undo();                                      // false when empty
    int undoDepth();
    PanelDiff diff();
    void save();
    void cancel();
}

public record PanelDiff(List<String> added, List<String> removed, List<String> changed) {
    public boolean isEmpty();
}

public interface FieldDescriptor<T> {
    String label(T entry);                               // never null
    String icon(T entry);                                // material or head source
    String identity(T entry);                            // never null
    T create();
    T duplicate(T entry);                                // new identity
    CompletionStage<InputResult<T>> edit(Player viewer, T entry);
    List<T> load();
    void save(List<T> entries);                          // called on runAsync
    default boolean matches(T entry, String query) {
        return label(entry).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }
}

// net.exylia.lib.config
public record Schema(Class<?> type, List<String> comments, List<Schema.Field> fields) {
    public record Field(String name, String key, Class<?> type,
                        java.lang.reflect.Type generic,
                        List<String> comments, @Nullable Schema nested) {}
}
// ConfigFile<T> gains exactly one accessor:  @NotNull Schema schema();
```

Full use, no ordering to explain:

```java
Panels.of(this).settings(effectConfigFile).open(player);          // the whole effects editor
Panels.of(this).list(new RewardDescriptor(store)).open(player);
```

## Data Flow

    ConfigFile<T> ──schema()──→ Schema ──→ ControlMapper ──→ UiEntry[] ──→ UiSession
                                                                              │ click
                                       PanelSession ◄──── holder lookup ──────┘
                                            │
                     PanelPrompts (input) ──┴──→ RecordRebuilder ──→ Optional<T>
                                                        │ rejected → Validation to player
                                                        │ accepted → workingCopy + undo push
                                            save ──→ runAsync ──→ ConfigFile.update ──→ runAtEntity

## Test Seams (new — BLOCKING for strict TDD)

All package-private in `panel/internal` unless noted, following the named precedents.

| # | Seam | Signature | Precedent |
|---|---|---|---|
| 1 | Layout source | `panel/internal/Layouts` — `public static void install(@Nullable LayoutSource replacement)`; `interface LayoutSource { @Nullable ConfigurationSection layout(String id); }` | `Engines.install(SchematicEngine)` |
| 2 | Draw sink | `panel/internal/PanelRenderer` — `static DrawSink sink(DrawSink replacement)` returning the previous; `interface DrawSink { void drew(int slot, ControlKind kind, Object entry); }` | `ItemRenderer.components(Components)` |
| 3 | Clock | `panel/internal/PanelRuntime` — `static void setClock(LongSupplier)` / `static void resetClock()` | `Cooldowns.setClock/resetClock` |
| 4 | Report reset | `panel/internal/UnsupportedTypes` — `static void forgetReportedForTests()` | `ItemComponents.forgetReportedForTests` |
| 5 | Scripted input | `panel/internal/PanelPrompts` — `public static void install(@Nullable Prompts replacement)`; `interface Prompts { CompletionStage<InputResult<String>> text(...); CompletionStage<InputResult<Boolean>> confirm(...); <T> CompletionStage<InputResult<T>> search(...); }`. The panel **never** calls `Inputs` directly | `Engines.install` + `SnapshotCodec.setItems` |
| 6 | Test descriptor | `src/test/java/net/exylia/lib/panel/TestDescriptors.java` — test source only, a `FieldDescriptor<Note>` over `record Note(String id, String text)` | `FakeServer`/`FakePlayer` |

Seams 1–5 must exist **before** any RED test in slices 2–4. `FakePlayer`/`FakeServer.newWorld` are
never inside a measured loop (AGENTS warning).

## File Changes

| File | Action | Description |
|---|---|---|
| `config/Schema.java` | Create | Public value projection + `Schema.Field` |
| `config/ConfigFile.java` | Modify | One added accessor `schema()`. No existing signature changes. Not a documented extension point, so no third-party implementor is stranded |
| `config/internal/SchemaProjection.java` | Create | `SchemaNode` → `Schema`; only public types in its signature |
| `panel/{Panels,PluginPanels,SettingsPanel,ListPanel,PanelSession,PanelDiff,FieldDescriptor}.java` | Create | Public API |
| `panel/internal/{PanelRuntime,Session,Layouts,PanelRenderer,PanelPrompts,UnsupportedTypes,ControlMapper,ControlKind,RecordRebuilder,UndoStack,Diff,ListEngine,SettingsEngine}.java` | Create | Engine |
| `panel/descriptor/{RewardDescriptor,ParsedEffectDescriptor}.java` | Create | The two shipped descriptors |
| `resources/panels/{settings,list}.yml` | Create | Bundled layouts |
| `ExyliaLib.java` | Modify | `refreshBundledDirectory` at enable; `Panels.release` in `onPluginDisable` **before** `Menus.release`; `PanelRuntime.forget` in `onPlayerQuit`; `releaseAll` on shutdown. **No** `loadPalette` hook (decision 5) |
| `docs/panels.md`, `docs/README.md`, `docs/config.md`, `docs/reload.md`, `AGENTS.md` | New/Modify | Module doc, index row, schema accessor, reload exemption row, doctrine section |

## Class Isolation (quality bar 6)

`panel` names no PacketEvents, Folia, or FAWE type. The retitle packet path stays confined in
`ui/internal/TitlePackets`, which `panel` reaches only through `UiSession`. `RecordRebuilder` uses
`java.lang.reflect` only — JDK, not platform. Verified by the documented `javap` sweep; the library
loads on pure Spigot.

## Work-Unit Split

Forecast revised from the proposal: the rebuild moved out of `config` into `panel` (decision 3), so
slice 1 shrinks and slice 3 grows; the five seams add to slice 2. **Total ≈ 2,500 lines.**

| # | Slice | Lines | Depends on | Test files | Seams used |
|---|---|---:|---|---|---|
| 1 | `config-schema-projection` | ~200 | — | `config/SchemaProjectionTest`, `config/PublicSignatureSweepTest` | none (plain JUnit) |
| 2 | `panel-engine` | ~700 | 1 (wiring only) | `panel/PanelLifecycleTest`, `PanelUndoTest`, `PanelDiffTest`, `PanelLayoutFallbackTest`, `PanelNoStaticStateTest`, `PanelPaletteTest` | 1, 2, 3, `FakeServer`, `DebugCapture` |
| 3 | `settings-panel` | ~650 | 1, 2 | `panel/SettingsControlMappingTest`, `RecordRebuilderTest`, `UnsupportedComponentTest`, `EffectConfigGenericPathTest` | 2, 4, 5 |
| 4 | `list-panel` | ~550 | 2 (**not** 3) | `panel/ListEntryIdentityTest`, `ListSearchTest`, `ListClipboardTest`, `ListConfirmDeleteTest` | 2, 5, 6 |
| 5 | `descriptors + docs` | ~400 | 4 | `panel/RewardDescriptorTest`, `ParsedEffectDescriptorTest`, `NoNamedCommandTypeTest` | 6, plain JUnit codecs |

Order: **1 → 2 → {3 ‖ 4} → 5.** Slices 3 and 4 are independent of each other and may be sequenced
either way; 4 does not require the schema projection.

## Testing Strategy

| Layer | What | How |
|---|---|---|
| Unit | Projection equality, immutability, kebab fallback, generic recovery; rebuild accept/reject; diff; undo bound | Plain JUnit, no server |
| Unit | Control mapping per declared type; unsupported passthrough; report once | Draw sink (seam 2) + `DebugCapture` + `forgetReportedForTests` (seam 4) |
| Integration | Open/click/save/cancel/quit/disable; delete under pagination and under filter; clipboard death; layout fallback | `FakeServer`/`FakePlayer`, `Layouts.install` (1), `PanelPrompts.install` (5), `FakeServer.liveTasks()` |
| Guard | No `internal` type in a public signature; no static `Map`/`Cache` keyed by `UUID`/`Player`; no `EffectConfig` reference in `panel`; no `NamedCommand` type | Reflection/classpath sweeps |
| Sabotage | Break the identity resolution to index-based → `ListEntryIdentityTest` must fail; remove the undo cap → `PanelUndoTest` must fail; swallow the rebuild rejection → `RecordRebuilderTest` must fail | Manual, recorded per slice |

## Threat Matrix

N/A — no routing, shell, subprocess, VCS/PR automation, executable-file classification, or
process-integration boundary. The only I/O is `ConfigFile.update` (an existing, already-guarded
write path) and `refreshBundledDirectory` (existing, path-escape guarded at `PluginMenus:180`).

## Migration / Rollout

No migration required. Purely additive; no existing behaviour is routed through `panel`, so not
opening a panel is the off switch. `build.gradle` version is untouched.

## Open Questions

None blocking. All seven open decisions are closed above.
