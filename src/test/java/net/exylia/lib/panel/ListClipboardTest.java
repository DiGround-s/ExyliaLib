package net.exylia.lib.panel;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
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

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A clipboard is something a screen holds, and it dies with the screen.
 *
 * <p>The alternative — a {@code static Map<UUID, T>} — is the shape this module
 * exists to make impossible. It survives the panel, survives the player, and
 * survives the plugin, so the copy somebody made an hour ago is still holding a
 * reference to an object nobody can reach any more.
 *
 * <p>Paste is likewise not "insert the same row again": where the descriptor
 * defines an identity, the pasted entry must get a new one, or the list holds
 * two rows that address each other's deletes.
 */
class ListClipboardTest {

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
        plugin = FakeServer.newPlugin("ListClipboardPlugin", folder.toFile());
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
    // 4.6 — paste produces a distinct entry
    // ------------------------------------------------------------------

    @Test
    @DisplayName("copy then paste yields two entries with matching payloads and different identities")
    void pasteProducesADistinctEntry() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();

        Note copied = engine.entryAt(rowSlots().get(2));
        engine.activate(rowSlots().get(2), ClickKind.SHIFT_LEFT);
        assertTrue(engine.hasClipboard(), "the copy must land somewhere the paste can read");

        engine.activate(chromeSlot(ControlKind.PASTE), ClickKind.LEFT);

        List<Note> matching = engine.entries().stream()
                .filter(note -> note.text().equals(copied.text()))
                .toList();
        assertEquals(2, matching.size(),
                "a paste must add a row, not replace one: " + engine.entries());
        assertEquals(matching.get(0).text(), matching.get(1).text(), "the payloads must match");
        assertNotEquals(matching.get(0).id(), matching.get(1).id(),
                "and the identities must differ, or the two rows address each other's deletes — "
                        + "which is the same class of bug as addressing a row by index");
        assertEquals(6, engine.entries().size());
    }

    @Test
    @DisplayName("pasting twice yields three rows, each with its own identity")
    void pastingTwiceKeepsGivingNewIdentities() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();

        Note copied = engine.entryAt(rowSlots().get(0));
        engine.activate(rowSlots().get(0), ClickKind.SHIFT_LEFT);
        engine.activate(chromeSlot(ControlKind.PASTE), ClickKind.LEFT);
        engine.activate(chromeSlot(ControlKind.PASTE), ClickKind.LEFT);

        List<String> identities = engine.entries().stream()
                .filter(note -> note.text().equals(copied.text()))
                .map(Note::id)
                .toList();
        assertEquals(3, identities.size(), "three rows with that payload: " + engine.entries());
        assertEquals(3, List.copyOf(new java.util.LinkedHashSet<>(identities)).size(),
                "and three distinct identities, or the second paste reused the first's: "
                        + identities);
    }

    @Test
    @DisplayName("copy does not change the list, and a paste is undoable")
    void copyChangesNothingAndPasteIsUndoable() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();
        List<Note> before = engine.entries();

        engine.activate(rowSlots().get(1), ClickKind.SHIFT_LEFT);
        assertEquals(before, engine.entries(), "copying is a read; it must change nothing");
        assertEquals(0, engine.undoDepth(), "and it must not be an undo step");

        engine.activate(chromeSlot(ControlKind.PASTE), ClickKind.LEFT);
        assertEquals(6, engine.entries().size());
        assertTrue(engine.undo(), "a paste is an edit, so it must be undoable");
        assertEquals(before, engine.entries(), "and undoing it must restore the list exactly");
    }

    // ------------------------------------------------------------------
    // 4.6 — an empty clipboard is a no-op, not an error
    // ------------------------------------------------------------------

    @Test
    @DisplayName("pasting with an empty clipboard changes nothing and raises nothing")
    void pasteWithAnEmptyClipboardIsANoOp() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();
        List<Note> before = engine.entries();

        assertFalse(engine.hasClipboard(), "nothing has been copied yet");
        boolean handled = engine.activate(chromeSlot(ControlKind.PASTE), ClickKind.LEFT);

        assertFalse(handled, "an empty paste must report that it did nothing");
        assertEquals(before, engine.entries(), "and it must change nothing");
        assertEquals(0, engine.undoDepth(),
                "and it must not push an undo step, or a viewer undoes a paste that never was");
    }

    // ------------------------------------------------------------------
    // 4.6 — the clipboard dies with the session
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the clipboard is gone after the panel closes")
    void clipboardDiesOnClose() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();
        engine.activate(rowSlots().get(0), ClickKind.SHIFT_LEFT);
        assertTrue(engine.hasClipboard());

        engine.session().release();

        assertFalse(engine.hasClipboard(),
                "a clipboard is something a screen holds; nothing may survive the screen");
    }

    @Test
    @DisplayName("the clipboard is gone after the viewer quits")
    void clipboardDiesOnQuit() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();
        engine.activate(rowSlots().get(0), ClickKind.SHIFT_LEFT);
        assertTrue(engine.hasClipboard());

        PanelRuntime.forget(viewer.getUniqueId());

        assertFalse(engine.hasClipboard(),
                "a player who left is holding nothing, and their session must say so");
        assertFalse(engine.session().isOpen());
    }

    @Test
    @DisplayName("the clipboard is gone after the owning plugin is disabled")
    void clipboardDiesOnDisable() {
        ListEngine<Note> engine = engine(Notes.of(5));
        engine.draw();
        engine.activate(rowSlots().get(0), ClickKind.SHIFT_LEFT);
        assertTrue(engine.hasClipboard());

        PanelRuntime.release(plugin.getName());

        assertFalse(engine.hasClipboard(),
                "a plugin that is going away takes its clipboards with it");
        assertFalse(engine.session().isOpen());
    }

    /**
     * The structural half of the promise.
     *
     * <p>The three assertions above prove the clipboard is empty after each
     * ending, but an implementation could satisfy them and still keep a static
     * map it merely cleared. What must be true is that no such map exists to
     * clear — the same rule {@code PanelNoStaticStateTest} enforces on the
     * engine, applied to the field the clipboard lives in.
     */
    @Test
    @DisplayName("no static field of the list engine could hold a clipboard per player")
    void theClipboardIsNotAStaticMap() {
        List<String> offenders = new ArrayList<>();
        for (Field field : ListEngine.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            if (Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)) {
                offenders.add(field.getName() + " : " + type.getName());
            }
        }
        assertEquals(List.of(), offenders,
                "a clipboard in a static map outlives the panel, the player and the plugin — "
                        + "which is the shape this module exists to make impossible");
        assertTrue(ListEngine.class.getDeclaredFields().length > 0,
                "the sweep must have something to read, or it passes for the wrong reason");
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
