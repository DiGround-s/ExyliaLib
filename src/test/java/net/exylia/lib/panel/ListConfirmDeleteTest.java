package net.exylia.lib.panel;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.panel.TestDescriptors.EditMode;
import net.exylia.lib.panel.TestDescriptors.Note;
import net.exylia.lib.panel.TestDescriptors.Notes;
import net.exylia.lib.panel.internal.ControlKind;
import net.exylia.lib.panel.internal.ListEngine;
import net.exylia.lib.panel.internal.PanelPrompts;
import net.exylia.lib.panel.internal.PanelRenderer;
import net.exylia.lib.panel.internal.PanelRuntime;
import net.exylia.lib.ui.ClickKind;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting asks first, is undoable, and never half-happens.
 *
 * <p>Three promises that are one promise: a viewer must be able to get a row
 * back. The confirmation is the first chance, undo the second, and cancel the
 * third — and cancel is the one that has to be all-or-nothing, because a cancel
 * that persisted the deletions but not the edits would be worse than one that
 * persisted everything.
 *
 * <p>Save has two doors and both are tested: the panel's own button, and
 * {@link PanelSession#save()}, which a plugin holding the session can call
 * directly. A guard on only one of them is a guard the other caller walks
 * straight past — the defect a sabotage found in the settings panel.
 */
class ListConfirmDeleteTest {

    @TempDir
    Path folder;

    private Plugin plugin;
    private Player viewer;
    private PanelRenderer.DrawSink previousSink;
    private final List<Drawn> drawn = new ArrayList<>();
    private TestDescriptors.Prompts prompts;

    record Drawn(int slot, ControlKind kind, Object entry) {
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("ListConfirmDeletePlugin", folder.toFile());
        viewer = new FakePlayer("Steve").player();
        FakeServer.online(viewer);
        previousSink = PanelRenderer.sink((slot, kind, entry) -> drawn.add(new Drawn(slot, kind, entry)));
        prompts = new TestDescriptors.Prompts();
        PanelPrompts.install(prompts);
    }

    @AfterEach
    void tearDown() {
        PanelRenderer.sink(previousSink);
        PanelPrompts.install(null);
        PanelRuntime.releaseAll();
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // 4.7 — the confirmation, and what a denial leaves behind
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a delete is asked dangerously, so it is typed rather than misclicked")
    void deleteAsksDangerously() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();

        engine.activate(rowSlots().get(0), ClickKind.RIGHT);

        assertEquals(1, prompts.confirms.size(), "a delete must ask before it removes anything");
        assertEquals(List.of(true), prompts.confirmsDangerous,
                "and it must ask dangerously: removing a row a server owner configured is not "
                        + "something a misclick may do");
    }

    @Test
    @DisplayName("a denied delete leaves the working copy unchanged")
    void deniedDeleteChangesNothing() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();
        List<Note> before = engine.entries();

        prompts.confirmAnswer = false;
        engine.activate(rowSlots().get(0), ClickKind.RIGHT);

        assertEquals(before, engine.entries(), "saying no must leave everything exactly as it was");
        assertEquals(0, engine.undoDepth(),
                "and it must not be an undo step, or a viewer undoes a delete that never happened");
    }

    @Test
    @DisplayName("a delete the viewer walked away from leaves the working copy unchanged")
    void abandonedDeleteChangesNothing() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();
        List<Note> before = engine.entries();

        prompts.confirmOutcome = InputOutcome.TIMED_OUT;
        engine.activate(rowSlots().get(0), ClickKind.RIGHT);

        assertEquals(before, engine.entries(),
                "an unanswered question is not a yes — and a timeout is how most of them end");
    }

    @Test
    @DisplayName("a confirmed delete removes the row, and undo restores all five")
    void confirmedDeleteIsUndoable() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();
        Note target = engine.entryAt(rowSlots().get(2));

        prompts.confirmAnswer = true;
        engine.activate(rowSlots().get(2), ClickKind.RIGHT);
        assertEquals(4, engine.entries().size(), "the row must go");
        assertFalse(engine.entries().contains(target));

        assertTrue(engine.undo(), "and a delete must be undoable");
        assertEquals(5, engine.entries().size(), "all five must come back");
        assertTrue(engine.entries().contains(target),
                "and the restored entry must equal the deleted one, not a rebuilt lookalike");
        assertEquals(target, engine.entries().get(2),
                "in the position it was removed from");
    }

    // ------------------------------------------------------------------
    // 4.8 — diff, save, and an all-or-nothing cancel
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the diff reports exactly one addition, one removal and one change")
    void diffNamesWhatChanged() {
        Notes store = Notes.of(5);
        store.editAnswer = "edited";
        ListEngine<Note> engine = engine(store);
        engine.draw();

        // One added: copy a row and paste it.
        engine.activate(rowSlots().get(0), ClickKind.SHIFT_LEFT);
        engine.activate(chromeSlot(ControlKind.PASTE), ClickKind.LEFT);
        // One removed.
        prompts.confirmAnswer = true;
        engine.activate(rowSlots().get(4), ClickKind.RIGHT);
        // One changed.
        engine.activate(rowSlots().get(2), ClickKind.LEFT);

        PanelDiff diff = engine.diff();
        assertEquals(1, diff.added().size(), "one addition: " + diff);
        assertEquals(1, diff.removed().size(), "one removal: " + diff);
        assertEquals(1, diff.changed().size(), "one change: " + diff);
        assertFalse(diff.isEmpty());
    }

    @Test
    @DisplayName("save persists through the descriptor's write path and nothing else")
    void savePersistsThroughTheDescriptor() {
        Notes store = Notes.of(5);
        ListEngine<Note> engine = engine(store);
        engine.draw();
        prompts.confirmAnswer = true;
        Note removed = engine.entryAt(rowSlots().get(1));
        engine.activate(rowSlots().get(1), ClickKind.RIGHT);

        engine.save();
        FakeServer.tick(2);

        assertEquals(1, store.writes.size(), "exactly one write, through the descriptor");
        assertEquals(4, store.writes.get(0).size());
        assertFalse(store.writes.get(0).contains(removed),
                "and it must be the list the viewer was looking at: " + store.writes.get(0));
    }

    @Test
    @DisplayName("an unmodified list writes nothing at all")
    void anUnmodifiedListWritesNothing() {
        Notes store = Notes.of(5);
        ListEngine<Note> engine = engine(store);
        engine.draw();

        assertTrue(engine.diff().isEmpty(), "nothing was touched");
        assertFalse(engine.save(), "so nothing may be written");
        FakeServer.tick(2);
        assertEquals(0, store.writes.size(),
                "opening a list to look at it must not rewrite the owner's file: " + store.writes);
    }

    /**
     * The second door.
     *
     * <p>A plugin holding a {@link PanelSession} can call {@code save()} without
     * ever touching the panel's button. A guard that only sits on the button is
     * one this caller walks straight past — which is precisely the defect a
     * sabotage found in the settings panel, and this list has the same shape.
     */
    @Test
    @DisplayName("PanelSession.save is the same write path as the button, guard and all")
    void theSessionSaveIsGuardedToo() {
        Notes unchanged = Notes.of(5);
        ListEngine<Note> engine = engine(unchanged);
        engine.draw();

        assertFalse(engine.session().save(),
                "the session's own save must refuse an empty diff exactly as the button does");
        FakeServer.tick(2);
        assertEquals(0, unchanged.writes.size(),
                "or a plugin calling save() directly rewrites a file nobody changed: "
                        + unchanged.writes);

        Notes changed = Notes.of(5);
        ListEngine<Note> second = engine(changed);
        second.draw();
        prompts.confirmAnswer = true;
        second.activate(rowSlots().get(0), ClickKind.RIGHT);

        assertTrue(second.session().save(), "and it must write when something did change");
        FakeServer.tick(2);
        assertEquals(1, changed.writes.size(), "through the descriptor, once");
        assertEquals(4, changed.writes.get(0).size());
    }

    @Test
    @DisplayName("cancel discards deletions, pastes and edits alike")
    void cancelDiscardsEverything() {
        Notes store = Notes.of(5);
        store.editAnswer = "edited";
        ListEngine<Note> engine = engine(store);
        engine.draw();
        List<Note> opened = store.persisted();

        engine.activate(rowSlots().get(0), ClickKind.SHIFT_LEFT);
        engine.activate(chromeSlot(ControlKind.PASTE), ClickKind.LEFT);
        prompts.confirmAnswer = true;
        engine.activate(rowSlots().get(4), ClickKind.RIGHT);
        engine.activate(rowSlots().get(1), ClickKind.LEFT);
        engine.activate(rowSlots().get(2), ClickKind.LEFT);
        assertFalse(engine.diff().isEmpty(), "the cancel must have something to discard");

        engine.session().cancel();
        FakeServer.tick(2);

        assertEquals(0, store.writes.size(),
                "cancel must not persist any of it — not the deletion, not the paste, not the "
                        + "two edits: " + store.writes);
        assertEquals(opened, store.persisted(),
                "and what is stored must equal the list as it was when the panel opened");
    }

    @Test
    @DisplayName("closing the window discards the working copy, exactly as cancel does")
    void closingDiscardsToo() {
        Notes store = Notes.of(5);
        ListEngine<Note> engine = engine(store);
        engine.draw();
        prompts.confirmAnswer = true;
        engine.activate(rowSlots().get(0), ClickKind.RIGHT);

        engine.session().release();
        FakeServer.tick(2);

        assertEquals(0, store.writes.size(),
                "closing a panel is not a way to save it by accident: " + store.writes);
    }

    // ------------------------------------------------------------------
    // 4.9 — one implementation, parameterised
    // ------------------------------------------------------------------

    /**
     * The extension point, stated as a test.
     *
     * <p>{@code Note} is declared in the test sources and the library has never
     * heard of it. If every capability works over it, they work because the
     * engine is generic — not because it recognises a reward or a potion effect.
     */
    @Test
    @DisplayName("a consumer-owned record gets every capability from a descriptor alone")
    void aConsumerOwnedRecordNeedsOnlyADescriptor() {
        Notes store = Notes.of(60);
        store.editAnswer = "rewritten";
        ListEngine<Note> engine = engine(store);
        engine.draw();

        // paginate
        engine.activate(chromeSlot(ControlKind.PAGE_NEXT), ClickKind.LEFT);
        assertEquals(2, engine.page(), "paginate");
        engine.activate(chromeSlot(ControlKind.PAGE_PREVIOUS), ClickKind.LEFT);
        assertEquals(1, engine.page());

        // search
        engine.filter("note-1");
        assertTrue(engine.shown().size() < 60, "search");
        engine.filter("");

        // copy and paste
        engine.activate(rowSlots().get(0), ClickKind.SHIFT_LEFT);
        engine.activate(chromeSlot(ControlKind.PASTE), ClickKind.LEFT);
        assertEquals(61, engine.entries().size(), "paste");

        // edit
        Note edited = engine.entryAt(rowSlots().get(1));
        engine.activate(rowSlots().get(1), ClickKind.LEFT);
        assertTrue(engine.entries().stream()
                        .anyMatch(note -> note.id().equals(edited.id())
                                && note.text().equals("rewritten")),
                "edit");

        // delete
        prompts.confirmAnswer = true;
        engine.activate(rowSlots().get(2), ClickKind.RIGHT);
        assertEquals(60, engine.entries().size(), "delete");

        // undo
        assertTrue(engine.undo(), "undo");
        assertEquals(61, engine.entries().size());

        // save
        assertTrue(engine.save(), "save");
        FakeServer.tick(2);
        assertEquals(1, store.writes.size());

        // And the whole of it needed one interface implementation: no panel, no
        // menu, no session, no registry and no clipboard class was written for
        // Note, which is the requirement stated as a fact about this file.
        assertTrue(FieldDescriptor.class.isAssignableFrom(store.getClass()),
                "everything above went through one interface");
        assertEquals(List.of(FieldDescriptor.class), List.of(store.getClass().getInterfaces()),
                "and that interface is the only thing a new element type has to supply: "
                        + List.of(store.getClass().getInterfaces()));
    }

    @Test
    @DisplayName("adding a new entry uses the descriptor's own create")
    void addUsesTheDescriptorsCreate() {
        Notes store = Notes.of(3);
        ListEngine<Note> engine = engine(store);
        engine.draw();

        engine.activate(chromeSlot(ControlKind.ADD), ClickKind.LEFT);

        assertEquals(4, engine.entries().size(), "the new entry must be added");
        assertTrue(engine.entries().stream().anyMatch(note -> note.id().startsWith("created-")),
                "and it must be the descriptor's, not something the engine invented: "
                        + engine.entries());
        assertTrue(engine.undo(), "adding must be undoable like anything else");
        assertEquals(3, engine.entries().size());
    }

    @Test
    @DisplayName("an edit the viewer walked away from changes nothing")
    void anAbandonedEditChangesNothing() {
        Notes store = Notes.of(5);
        store.editMode = EditMode.CANCELLED;
        ListEngine<Note> engine = engine(store);
        engine.draw();
        List<Note> before = engine.entries();

        engine.activate(rowSlots().get(0), ClickKind.LEFT);

        assertEquals(before, engine.entries(), "a cancelled edit is not an edit");
        assertEquals(0, engine.undoDepth());
    }

    // ------------------------------------------------------------------

    private ListEngine<Note> engine(Notes store) {
        return ListEngine.forTests(plugin, viewer, store, null);
    }

    private List<Integer> rowSlots() {
        return drawn.stream()
                .filter(entry -> entry.kind() == ControlKind.ROW)
                .map(Drawn::slot)
                .toList();
    }

    private int chromeSlot(ControlKind kind) {
        return drawn.stream()
                .filter(entry -> entry.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " button was drawn: " + drawn))
                .slot();
    }
}
