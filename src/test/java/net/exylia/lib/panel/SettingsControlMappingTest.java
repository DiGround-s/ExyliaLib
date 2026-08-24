package net.exylia.lib.panel;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.config.Schema;
import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputResult;
import net.exylia.lib.panel.internal.ControlKind;
import net.exylia.lib.panel.internal.ControlMapper;
import net.exylia.lib.panel.internal.PanelRenderer;
import net.exylia.lib.panel.internal.PanelPrompts;
import net.exylia.lib.panel.internal.PanelRuntime;
import net.exylia.lib.panel.internal.SettingsEngine;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A control per component, chosen from the declared type and nothing else.
 *
 * <p>What is under test is the generated screen: which control landed in which
 * slot, in what order, carrying which documentation. It is read through the draw
 * sink rather than off an {@code ItemStack}, because a stack cannot even
 * class-initialise without a running server — that is why the seam exists.
 */
class SettingsControlMappingTest {

    @TempDir
    Path folder;

    private Plugin plugin;
    private Player viewer;
    private PanelRenderer.DrawSink previousSink;
    private final List<Drawn> drawn = new ArrayList<>();

    /** What the engine announced it drew. */
    record Drawn(int slot, ControlKind kind, Object entry) {
    }

    /** Ten constants: more than a hand-rolled picker would put on one screen. */
    enum Mode {
        ALPHA, BRAVO, CHARLIE, DELTA, ECHO, FOXTROT, GOLF, HOTEL, INDIA, JULIETT
    }

    /** One component of every supported kind, in the order the spec names them. */
    record Sample(
            @Comment("How many at once.")
            @Comment("Above eight is wasteful.")
            int amount,

            double ratio,

            boolean enabled,

            Mode mode,

            String label,

            List<String> worlds,

            Nested nested) {

        Sample() {
            this(4, 0.5, true, Mode.ALPHA, "hello", List.of("world"), new Nested());
        }

        record Nested(@Comment("Shown under the title.") String note, int weight) {
            Nested() {
                this("a note", 1);
            }
        }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("SettingsControlMappingPlugin", folder.toFile());
        FakePlayer fake = new FakePlayer("Steve");
        viewer = fake.player();
        FakeServer.online(viewer);
        previousSink = PanelRenderer.sink((slot, kind, entry) -> drawn.add(new Drawn(slot, kind, entry)));
    }

    @AfterEach
    void tearDown() {
        PanelRenderer.sink(previousSink);
        PanelPrompts.install(null);
        PanelRuntime.releaseAll();
        Configs.releaseAll();
        FakeServer.reset();
    }

    private ConfigFile<Sample> file() {
        return Configs.define(plugin, "sample", Sample.class).load();
    }

    private SettingsEngine<Sample> engine() {
        return SettingsEngine.forTests(plugin, viewer, file(), null);
    }

    // ------------------------------------------------------------------
    // 3.3 — one control per component, in canonical order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("seven components yield seven controls in canonical-constructor order")
    void everySupportedTypeGetsItsControl() {
        engine().draw();

        List<Drawn> controls = controlsOnly();
        assertEquals(7, controls.size(),
                "one control per component, no more and no fewer: " + kinds(controls));
        assertEquals(
                List.of(ControlKind.INTEGER, ControlKind.DECIMAL, ControlKind.TOGGLE,
                        ControlKind.CHOICE, ControlKind.TEXT, ControlKind.LIST,
                        ControlKind.SUB_PANEL),
                kinds(controls),
                "the kind must follow the declared type, in the order the record declares them");
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6),
                controls.stream().map(Drawn::slot).toList(),
                "and they must land in consecutive slots, which is what makes reading order obvious");
    }

    @Test
    @DisplayName("every control names the component it edits")
    void everyControlNamesItsComponent() {
        engine().draw();

        assertEquals(List.of("amount", "ratio", "enabled", "mode", "label", "worlds", "nested"),
                controlsOnly().stream()
                        .map(entry -> ((SettingsEngine.Control) entry.entry()).field().name())
                        .toList());
    }

    @Test
    @DisplayName("the chrome row is drawn as well as the controls")
    void chromeIsDrawnToo() {
        engine().draw();

        List<ControlKind> chrome = drawn.stream().map(Drawn::kind)
                .filter(kind -> kind == ControlKind.SAVE || kind == ControlKind.CANCEL
                        || kind == ControlKind.UNDO)
                .toList();
        assertTrue(chrome.contains(ControlKind.SAVE), "a panel must offer a way to write: " + chrome);
        assertTrue(chrome.contains(ControlKind.CANCEL), "and a way out: " + chrome);
        assertTrue(chrome.contains(ControlKind.UNDO), "and a way back: " + chrome);
    }

    // ------------------------------------------------------------------
    // 3.4 — comments become lore
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two @Comment lines become lore in declaration order")
    void commentsBecomeLoreInDeclarationOrder() {
        engine().draw();

        SettingsEngine.Control amount = control("amount");
        assertEquals(List.of("How many at once.", "Above eight is wasteful."),
                amount.field().comments(),
                "the projection must carry both lines");
        assertTrue(amount.item().lore().containsAll(
                        List.of("How many at once.", "Above eight is wasteful.")),
                "and the drawn control must show them: " + amount.item().lore());
        assertTrue(amount.item().lore().indexOf("How many at once.")
                        < amount.item().lore().indexOf("Above eight is wasteful."),
                "in the order they were declared, which is the order the owner wrote them in");
    }

    @Test
    @DisplayName("an undocumented component still draws, with no invented lore")
    void undocumentedComponentDrawsWithoutInventedLore() {
        engine().draw();

        SettingsEngine.Control ratio = control("ratio");
        assertEquals(List.of(), ratio.field().comments());
        assertTrue(ratio.item().lore().stream().noneMatch(line -> line.contains("How many")),
                "a component with no comment must not borrow another's: " + ratio.item().lore());
    }

    // ------------------------------------------------------------------
    // 3.4 — an enum opens a searchable choice
    // ------------------------------------------------------------------

    @Test
    @DisplayName("activating an enum control opens a search over its constants and sets the working copy")
    void enumControlOpensASearchOverItsConstants() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.searchAnswer = Mode.HOTEL;
        PanelPrompts.install(prompts);

        SettingsEngine<Sample> engine = engine();
        engine.draw();
        engine.activate(slotOf("mode"));

        assertEquals(1, prompts.searches.size(),
                "an enum must be offered through the input module's search, not a hand-rolled picker");
        assertEquals(List.of(Mode.values()), prompts.searches.get(0),
                "over every constant, so a ten-constant enum does not need a picker per page");
        assertEquals(Mode.HOTEL, engine.value("mode"),
                "and choosing one must reach the working copy");
    }

    @Test
    @DisplayName("cancelling the search leaves the working copy alone")
    void cancellingTheSearchChangesNothing() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.searchOutcome = InputOutcome.CANCELLED;
        PanelPrompts.install(prompts);

        SettingsEngine<Sample> engine = engine();
        engine.draw();
        engine.activate(slotOf("mode"));

        assertEquals(Mode.ALPHA, engine.value("mode"),
                "a player who backed out of a picker chose nothing");
        assertEquals(0, engine.undoDepth(),
                "and nothing may be pushed onto undo for an edit that did not happen");
    }

    @Test
    @DisplayName("a toggle flips without asking anything")
    void toggleFlipsWithoutAPrompt() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        PanelPrompts.install(prompts);

        SettingsEngine<Sample> engine = engine();
        engine.draw();
        engine.activate(slotOf("enabled"));

        assertEquals(false, engine.value("enabled"), "true must become false");
        assertEquals(0, prompts.texts.size() + prompts.searches.size(),
                "asking a player to confirm a toggle is asking them to click twice for nothing");
    }

    @Test
    @DisplayName("a text control asks for text and commits what came back")
    void textControlAsksAndCommits() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.textAnswer = "goodbye";
        PanelPrompts.install(prompts);

        SettingsEngine<Sample> engine = engine();
        engine.draw();
        engine.activate(slotOf("label"));

        assertEquals(1, prompts.texts.size());
        assertEquals("goodbye", engine.value("label"));
    }

    @Test
    @DisplayName("a number control rejects text that is not a number and keeps the old value")
    void numberControlRejectsNonNumbers() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.textAnswer = "eight";
        PanelPrompts.install(prompts);

        SettingsEngine<Sample> engine = engine();
        engine.draw();
        engine.activate(slotOf("amount"));

        assertEquals(4, engine.value("amount"),
                "a value that is not a number must not reach the working copy");
    }

    @Test
    @DisplayName("a number control commits a number, as the declared type rather than as text")
    void numberControlCommitsTheDeclaredType() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.textAnswer = "9";
        PanelPrompts.install(prompts);

        SettingsEngine<Sample> engine = engine();
        engine.draw();
        engine.activate(slotOf("amount"));

        assertEquals(9, engine.value("amount"));
        assertSame(Integer.class, engine.value("amount").getClass(),
                "an int component holds an int, not the string a player typed");
    }

    @Test
    @DisplayName("a decimal control keeps the fraction")
    void decimalControlKeepsTheFraction() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.textAnswer = "0.75";
        PanelPrompts.install(prompts);

        SettingsEngine<Sample> engine = engine();
        engine.draw();
        engine.activate(slotOf("ratio"));

        assertEquals(0.75, engine.value("ratio"));
    }

    // ------------------------------------------------------------------
    // 3.4 — a nested record opens a sub-panel
    // ------------------------------------------------------------------

    @Test
    @DisplayName("activating a nested record shows that record's own controls")
    void nestedRecordOpensASubPanel() {
        SettingsEngine<Sample> engine = engine();
        engine.draw();
        int nested = slotOf("nested");
        drawn.clear();

        engine.activate(nested);

        List<Drawn> controls = controlsOnly();
        assertEquals(List.of(ControlKind.TEXT, ControlKind.INTEGER), kinds(controls),
                "the sub-panel must be generated from the nested schema, not from the parent's");
        assertEquals(List.of("note", "weight"),
                controls.stream()
                        .map(entry -> ((SettingsEngine.Control) entry.entry()).field().name())
                        .toList());
    }

    @Test
    @DisplayName("an edit in a sub-panel reaches the parent working copy before save")
    void subPanelEditsReachTheParentWorkingCopy() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.textAnswer = "edited";
        PanelPrompts.install(prompts);

        SettingsEngine<Sample> engine = engine();
        engine.draw();
        engine.activate(slotOf("nested"));
        drawn.clear();
        engine.draw();
        engine.activate(slotOf("note"));

        Object nested = engine.rootValue("nested");
        assertNotNull(nested);
        assertEquals(new Sample.Nested("edited", 1), nested,
                "the parent must already hold the rebuilt nested record, so save has nothing left to gather");
    }

    @Test
    @DisplayName("a sub-panel's comments come from the nested record")
    void subPanelCommentsComeFromTheNestedRecord() {
        SettingsEngine<Sample> engine = engine();
        engine.draw();
        int nested = slotOf("nested");
        drawn.clear();
        engine.activate(nested);

        SettingsEngine.Control note = control("note");
        assertEquals(List.of("Shown under the title."), note.field().comments());
        assertTrue(note.item().lore().contains("Shown under the title."));
    }

    @Test
    @DisplayName("leaving a sub-panel returns to the parent's controls")
    void leavingASubPanelReturnsToTheParent() {
        SettingsEngine<Sample> engine = engine();
        engine.draw();
        engine.activate(slotOf("nested"));
        drawn.clear();
        engine.draw();

        engine.activate(chromeSlot(ControlKind.CANCEL));

        drawn.clear();
        engine.draw();
        assertEquals(7, controlsOnly().size(),
                "cancel inside a sub-panel is a way back, not a way out of the whole panel");
    }

    // ------------------------------------------------------------------
    // 3.15 — the mapping reads a Schema and nothing else
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the mapper's whole input is a Schema.Field, so a record it never saw maps the same")
    void mappingReadsOnlyTheSchema() {
        for (java.lang.reflect.Method method : ControlMapper.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                assertTrue(parameter == Schema.Field.class || parameter == ControlKind.class,
                        "ControlMapper." + method.getName() + " takes " + parameter.getName()
                                + ". Its whole input must be the projection, or the mapping has "
                                + "learned something about a particular record");
            }
        }
    }

    @Test
    @DisplayName("adding a supported type is one table entry, so every listed type maps without a branch")
    void everySupportedTypeMapsThroughTheSameTable() {
        // Driven from types rather than from a record, so this fails the moment
        // the mapping stops being a lookup and starts being control flow.
        assertEquals(ControlKind.INTEGER, ControlMapper.kindOf(field(int.class)));
        assertEquals(ControlKind.INTEGER, ControlMapper.kindOf(field(Long.class)));
        assertEquals(ControlKind.INTEGER, ControlMapper.kindOf(field(short.class)));
        assertEquals(ControlKind.INTEGER, ControlMapper.kindOf(field(byte.class)));
        assertEquals(ControlKind.DECIMAL, ControlMapper.kindOf(field(double.class)));
        assertEquals(ControlKind.DECIMAL, ControlMapper.kindOf(field(Float.class)));
        assertEquals(ControlKind.TOGGLE, ControlMapper.kindOf(field(boolean.class)));
        assertEquals(ControlKind.TOGGLE, ControlMapper.kindOf(field(Boolean.class)));
        assertEquals(ControlKind.TEXT, ControlMapper.kindOf(field(String.class)));
        assertEquals(ControlKind.CHOICE, ControlMapper.kindOf(field(Mode.class)),
                "an enum is recognised by shape, since no table could list every one");
        assertEquals(ControlKind.LIST, ControlMapper.kindOf(field(List.class)));
        assertEquals(ControlKind.UNSUPPORTED, ControlMapper.kindOf(field(java.util.UUID.class)),
                "and a type with no entry is unsupported rather than an exception");
    }

    @Test
    @DisplayName("a nested record is a sub-panel whatever its own type is")
    void nestedRecordIsASubPanelRegardlessOfType() {
        Schema nested = new Schema(Sample.Nested.class, List.of(), List.of());
        Schema.Field field = new Schema.Field("nested", "nested", Sample.Nested.class,
                Sample.Nested.class, List.of(), nested);

        assertEquals(ControlKind.SUB_PANEL, ControlMapper.kindOf(field),
                "structure beats the type table: a record is a sub-panel because it is a record");
    }

    private static Schema.Field field(Class<?> type) {
        return new Schema.Field("value", "value", type, type, List.of(), null);
    }

    // ------------------------------------------------------------------
    // 3.9 — round-trip per supported type, and cancel writes nothing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every supported type survives edit, save and reload equal to what was set")
    void everySupportedTypeRoundTrips() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        PanelPrompts.install(prompts);

        ConfigFile<Sample> file = file();
        SettingsEngine<Sample> engine = SettingsEngine.forTests(plugin, viewer, file, null);
        engine.draw();

        prompts.textAnswer = "9";
        engine.activate(slotOf("amount"));
        prompts.textAnswer = "0.75";
        engine.activate(slotOf("ratio"));
        engine.activate(slotOf("enabled"));
        prompts.searchAnswer = Mode.HOTEL;
        engine.activate(slotOf("mode"));
        prompts.textAnswer = "goodbye";
        engine.activate(slotOf("label"));

        assertTrue(engine.save(), "five edits are worth writing");
        FakeServer.tick(2);
        file.reload();

        Sample persisted = file.get();
        assertEquals(9, persisted.amount(), "an int must read back as the int that was set");
        assertEquals(0.75, persisted.ratio(), "a double must keep its fraction across the file");
        assertEquals(false, persisted.enabled(), "a toggle must read back flipped");
        assertEquals(Mode.HOTEL, persisted.mode(), "an enum must read back as the constant chosen");
        assertEquals("goodbye", persisted.label(), "and text as the text typed");
        assertEquals(List.of("world"), persisted.worlds(),
                "while a component nobody touched reads back as it was");
        assertEquals(new Sample.Nested("a note", 1), persisted.nested(),
                "including a nested record nobody opened");
    }

    @Test
    @DisplayName("a nested edit survives save and reload too")
    void nestedEditRoundTrips() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.textAnswer = "changed";
        PanelPrompts.install(prompts);

        ConfigFile<Sample> file = file();
        SettingsEngine<Sample> engine = SettingsEngine.forTests(plugin, viewer, file, null);
        engine.draw();
        engine.activate(slotOf("nested"));
        drawn.clear();
        engine.draw();
        engine.activate(slotOf("note"));

        assertTrue(engine.save());
        FakeServer.tick(2);
        file.reload();

        assertEquals(new Sample.Nested("changed", 1), file.get().nested());
    }

    @Test
    @DisplayName("cancel discards the edits, and a save afterwards never reaches ConfigFile.update")
    void cancelWritesNothing() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.textAnswer = "9";
        PanelPrompts.install(prompts);

        FakeConfigFile<Sample> file = FakeConfigFile.of(plugin, "cancelled", new Sample());
        SettingsEngine<Sample> engine = SettingsEngine.forTests(plugin, viewer, file, null);
        engine.draw();
        engine.activate(slotOf("amount"));
        assertEquals(9, engine.value("amount"), "the edit must have landed before cancel undoes it");

        engine.activate(chromeSlot(ControlKind.CANCEL));

        assertEquals(4, engine.value("amount"), "cancel must put back what the panel opened with");
        assertFalse(engine.save(), "and there must be nothing left worth writing");
        FakeServer.tick(2);
        assertEquals(0, file.updates(),
                "ConfigFile.update must never be reached: what is on disk is what was there "
                        + "when the panel opened");
    }

    @Test
    @DisplayName("a save with nothing changed writes nothing at all")
    void unchangedSaveWritesNothing() {
        FakeConfigFile<Sample> file = FakeConfigFile.of(plugin, "unchanged", new Sample());
        SettingsEngine<Sample> engine = SettingsEngine.forTests(plugin, viewer, file, null);
        engine.draw();

        assertFalse(engine.save());
        FakeServer.tick(2);
        assertEquals(0, file.updates(),
                "opening a panel to look at something must not rewrite the owner's file");
    }

    @Test
    @DisplayName("undo takes back an edit, and a save afterwards writes nothing")
    void undoTakesBackTheEdit() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.textAnswer = "9";
        PanelPrompts.install(prompts);

        FakeConfigFile<Sample> file = FakeConfigFile.of(plugin, "undone", new Sample());
        SettingsEngine<Sample> engine = SettingsEngine.forTests(plugin, viewer, file, null);
        engine.draw();
        engine.activate(slotOf("amount"));
        assertEquals(1, engine.undoDepth(), "a committed edit must be takeable back");

        engine.activate(chromeSlot(ControlKind.UNDO));

        assertEquals(4, engine.value("amount"));
        assertFalse(engine.save());
        assertEquals(0, file.updates());
    }

    @Test
    @DisplayName("onSaved is run with the new record, on the viewer's thread")
    void onSavedReceivesTheNewRecord() {
        ScriptedPrompts prompts = new ScriptedPrompts();
        prompts.textAnswer = "9";
        PanelPrompts.install(prompts);

        List<Sample> saved = new ArrayList<>();
        FakeConfigFile<Sample> file = FakeConfigFile.of(plugin, "notified", new Sample());
        SettingsEngine<Sample> engine = SettingsEngine.forTests(plugin, viewer, file, saved::add);
        engine.draw();
        engine.activate(slotOf("amount"));
        engine.save();
        FakeServer.tick(4);

        assertEquals(1, saved.size(), "whoever asked to be told must be told exactly once");
        assertEquals(9, saved.get(0).amount());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<Drawn> controlsOnly() {
        return drawn.stream()
                .filter(entry -> entry.entry() instanceof SettingsEngine.Control)
                .toList();
    }

    private List<ControlKind> kinds(List<Drawn> entries) {
        return entries.stream().map(Drawn::kind).toList();
    }

    private SettingsEngine.Control control(String component) {
        return controlsOnly().stream()
                .map(entry -> (SettingsEngine.Control) entry.entry())
                .filter(control -> control.field().name().equals(component))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no control was drawn for " + component));
    }

    private int slotOf(String component) {
        return controlsOnly().stream()
                .filter(entry -> ((SettingsEngine.Control) entry.entry()).field().name().equals(component))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no control was drawn for " + component))
                .slot();
    }

    private int chromeSlot(ControlKind kind) {
        return drawn.stream()
                .filter(entry -> entry.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " button was drawn"))
                .slot();
    }

    /** Answers every question the panel asks, without a transport. */
    static final class ScriptedPrompts implements PanelPrompts.Prompts {

        final List<String> texts = new ArrayList<>();
        final List<List<?>> searches = new ArrayList<>();

        String textAnswer = "";
        Object searchAnswer;
        InputOutcome searchOutcome;
        InputOutcome textOutcome;

        @Override
        public CompletionStage<InputResult<String>> text(org.bukkit.plugin.Plugin plugin,
                                                          Player viewer, String prompt) {
            texts.add(prompt);
            return CompletableFuture.completedFuture(textOutcome != null
                    ? InputResult.ended(textOutcome)
                    : InputResult.completed(textAnswer));
        }

        @Override
        public CompletionStage<InputResult<Boolean>> confirm(org.bukkit.plugin.Plugin plugin,
                                                              Player viewer, String prompt,
                                                              boolean dangerous) {
            return CompletableFuture.completedFuture(InputResult.completed(true));
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> CompletionStage<InputResult<T>> search(org.bukkit.plugin.Plugin plugin,
                                                          Player viewer, String prompt,
                                                          List<T> choices, Function<T, String> label) {
            searches.add(List.copyOf(choices));
            return CompletableFuture.completedFuture(searchOutcome != null
                    ? InputResult.ended(searchOutcome)
                    : InputResult.completed((T) searchAnswer));
        }
    }
}
