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
import net.exylia.lib.ui.UiEntry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verified ExyliaCommons bug, made impossible.
 *
 * <p>Commons had five list editors. Four addressed a row by a UUID; the potion
 * editor addressed it by list index — {@code commons:potion_delete 1} — and so
 * deleted the wrong effect the moment the list was paginated or filtered. That is
 * the failure this class exists to keep out.
 *
 * <p>A naive delete test does not catch it. On page one, with no filter, a row's
 * index and its element agree, so index lookup and identity lookup return the
 * same thing. Three shapes separate them: a later <em>page</em>, a non-contiguous
 * <em>filter</em>, and a list <em>reordered between the draw and the click</em>.
 * The third is the strongest, because it fails even an implementation that got
 * the page arithmetic right.
 */
class ListEntryIdentityTest {

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
        plugin = FakeServer.newPlugin("ListEntryIdentityPlugin", folder.toFile());
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
    // 4.2 — delete under pagination
    // ------------------------------------------------------------------

    @Test
    @DisplayName("60 entries paginate, and a page shows fewer rows than the list holds")
    void sixtyEntriesSpanSeveralPages() {
        ListEngine<Note> engine = engine(Notes.of(60));
        engine.draw();

        assertTrue(engine.pages() >= 3,
                "the page count comes from the section's slots, so this is what the layout says: "
                        + engine.pages());
        assertTrue(engine.shown().size() < engine.entries().size(),
                "a page must be a page, or nothing below distinguishes index from identity");
    }

    @Test
    @DisplayName("deleting the second row of the last page removes the element that row carried")
    void deleteOnALaterPageRemovesTheCarriedElement() {
        Notes store = Notes.of(60);
        ListEngine<Note> engine = engine(store);
        engine.draw();
        int next = chromeSlot(ControlKind.PAGE_NEXT);
        engine.activate(next, ClickKind.LEFT);
        drawn.clear();
        engine.activate(next, ClickKind.LEFT);
        assertEquals(3, engine.page(), "the test must actually be on page three");

        int slot = rowSlots().get(1);
        Note carried = engine.entryAt(slot);
        Note atIndexOne = engine.entries().get(1);
        Note atIndexTwo = engine.entries().get(2);

        assertNotEquals(carried, atIndexOne,
                "page three's second row must not be list index 1, or the two lookups agree "
                        + "and this test proves nothing");

        deleteAt(engine, slot);

        assertFalse(engine.entries().contains(carried),
                "the element the row carried must be the one that went: " + carried);
        assertTrue(engine.entries().contains(atIndexOne),
                "the element at list index 1 must be untouched — that is the Commons bug");
        assertTrue(engine.entries().contains(atIndexTwo),
                "and so must the one at list index 2");
        assertEquals(59, engine.entries().size(), "exactly one element may go");
    }

    // ------------------------------------------------------------------
    // 4.3 — delete under a filter, and under a reorder
    // ------------------------------------------------------------------

    @Test
    @DisplayName("deleting the first row of a non-contiguous filter removes the carried element")
    void deleteUnderFilterRemovesTheCarriedElement() {
        Notes store = Notes.holding(
                new Note("a", "gamma"),
                new Note("b", "alpha one"),
                new Note("c", "delta"),
                new Note("d", "alpha two"),
                new Note("e", "epsilon"));
        ListEngine<Note> engine = engine(store);
        engine.draw();
        drawn.clear();
        engine.filter("alpha");

        assertEquals(2, engine.shown().size(), "the filter must show a non-contiguous subset");

        int slot = rowSlots().get(0);
        Note carried = engine.entryAt(slot);
        assertEquals("b", carried.id(),
                "the first shown row is list index 1, which is exactly what separates a lookup "
                        + "by carried element from a lookup by index 0 of the unfiltered list");

        deleteAt(engine, slot);

        assertTrue(engine.entries().stream().anyMatch(note -> note.id().equals("a")),
                "index 0 of the unfiltered list must be untouched: " + engine.entries());
        assertFalse(engine.entries().stream().anyMatch(note -> note.id().equals("b")),
                "and the carried element must be the one that went");
        assertEquals(4, engine.entries().size());
    }

    /**
     * The scenario no index-based implementation can pass by accident.
     *
     * <p>A correct page calculation still resolves the wrong element when the
     * list moves under it. Reordering between the draw and the click is the only
     * shape that separates "resolved the right index" from "resolved the right
     * element", which is why the specification asks for it by name.
     */
    @Test
    @DisplayName("reordering the backing list between draw and click still resolves the carried element")
    void reorderingBetweenDrawAndClickStillResolvesTheCarriedElement() {
        ListEngine<Note> engine = engine(Notes.of(10));
        engine.draw();

        int slot = rowSlots().get(3);
        Note carried = engine.entryAt(slot);
        assertEquals("id-4", carried.id(), "the fourth row, before anything moved");

        engine.reorderForTests(ListEntryIdentityTest::reversed);
        assertNotEquals(carried, engine.entries().get(3),
                "the reorder must actually move something under that row, or the scenario is "
                        + "vacuous and an index lookup would pass it too");

        deleteAt(engine, slot);

        assertFalse(engine.entries().contains(carried),
                "the click must resolve what the row carried, not what now sits at its index: "
                        + engine.entries());
        assertEquals(9, engine.entries().size());
    }

    @Test
    @DisplayName("editing under a reorder changes the carried element, not the one at its index")
    void editingUnderReorderChangesTheCarriedElement() {
        Notes store = Notes.of(10);
        store.editAnswer = "rewritten";
        ListEngine<Note> engine = engine(store);
        engine.draw();

        int slot = rowSlots().get(3);
        Note carried = engine.entryAt(slot);

        engine.reorderForTests(ListEntryIdentityTest::reversed);
        Note atIndex = engine.entries().get(3);
        assertNotEquals(carried.id(), atIndex.id(), "the reorder must move something under the row");

        engine.activate(slot, ClickKind.LEFT);

        assertTrue(engine.entries().stream()
                        .anyMatch(note -> note.id().equals(carried.id())
                                && note.text().equals("rewritten")),
                "the carried element must be the one that changed: " + engine.entries());
        assertTrue(engine.entries().stream()
                        .anyMatch(note -> note.id().equals(atIndex.id())
                                && note.text().equals(atIndex.text())),
                "and the element that moved into its index must be untouched");
    }

    // ------------------------------------------------------------------
    // Rows carry their element, and that is what a click reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every drawn row is announced as a UiEntry carrying its element")
    void everyDrawnRowCarriesItsElement() {
        ListEngine<Note> engine = engine(Notes.of(10));
        engine.draw();

        List<Drawn> rows = drawn.stream().filter(entry -> entry.kind() == ControlKind.ROW).toList();
        assertEquals(10, rows.size(), "ten entries, ten rows");
        for (Drawn row : rows) {
            assertTrue(row.entry() instanceof UiEntry,
                    "a row must be announced as the UiEntry it was drawn from, which is what "
                            + "carries the element: " + row.entry());
            UiEntry carried = (UiEntry) row.entry();
            assertTrue(carried.value() instanceof Note,
                    "and its value must be the element the row is about: " + carried.value());
            assertEquals(carried.value(), engine.entryAt(row.slot()),
                    "what the engine resolves for a slot must be exactly what it drew there");
        }
    }

    @Test
    @DisplayName("a slot the panel drew no row in resolves to nothing")
    void anUndrawnSlotResolvesToNothing() {
        ListEngine<Note> engine = engine(Notes.of(3));
        engine.draw();

        int unused = rowSlots().get(2) + 1;
        assertNull(engine.entryAt(unused),
                "a slot with no row is not an empty row — it is not a row, and a click there "
                        + "must resolve to nothing rather than to a neighbour");
        assertFalse(engine.activate(unused, ClickKind.RIGHT), "and clicking it must mean nothing");
        assertEquals(3, engine.entries().size());
    }

    // ------------------------------------------------------------------
    // 4.15 — the structural half: no slot maps to an index anywhere
    // ------------------------------------------------------------------

    /**
     * The behavioural scenarios above prove the current implementation resolves
     * by element. This proves the <em>shape</em> that made the Commons bug
     * possible does not exist: a field mapping a slot number to a list index.
     * An implementation can be rewritten and still be correct; it cannot be
     * rewritten around a field that is not there.
     */
    @Test
    @DisplayName("no field of the engine maps a slot to a list index")
    void noFieldMapsASlotToAnIndex() {
        List<String> offenders = new ArrayList<>();
        for (Field field : ListEngine.class.getDeclaredFields()) {
            if (mapsSlotToIndex(field.getGenericType())) {
                offenders.add(field.getName() + " : " + field.getGenericType());
            }
        }
        assertEquals(List.of(), offenders,
                "a slot that remembers an index is the Commons potion bug with a different "
                        + "name: the index goes stale the moment the list is filtered, paged "
                        + "or reordered, and the slot does not");
    }

    @Test
    @DisplayName("the engine does map a slot to the row it drew, so the sweep read something")
    void theSweepReadTheFieldThatMatters() {
        List<String> rowMaps = new ArrayList<>();
        for (Field field : ListEngine.class.getDeclaredFields()) {
            if (mapsSlotTo(field.getGenericType(), UiEntry.class)) {
                rowMaps.add(field.getName());
            }
        }
        assertEquals(1, rowMaps.size(),
                "exactly one field must map a slot to the row drawn there — without it the "
                        + "sweep above passes because the engine has no slot map at all, which "
                        + "is a very different and much worse thing: " + declaredFields());
        assertTrue(ListEngine.class.getDeclaredFields().length >= 4,
                "and the sweep must have read the whole engine: " + declaredFields());
    }

    @Test
    @DisplayName("the detector recognises a slot-to-index map when it is shown one")
    void detectorRecognisesTheBannedShape() throws NoSuchFieldException {
        assertTrue(mapsSlotToIndex(Banned.class.getDeclaredField("byIndex").getGenericType()),
                "a Map<Integer, Integer> is exactly the shape that is forbidden");
        assertFalse(mapsSlotToIndex(Banned.class.getDeclaredField("byRow").getGenericType()),
                "and a Map<Integer, UiEntry> is the shape that is required, so the detector "
                        + "must not confuse the two");
    }

    /** The shape the rule forbids, so the detector can be shown to detect it. */
    @SuppressWarnings("unused")
    private static final class Banned {
        Map<Integer, Integer> byIndex;
        Map<Integer, UiEntry> byRow;
    }

    private static boolean mapsSlotToIndex(Type type) {
        return mapsSlotTo(type, Integer.class);
    }

    /** Whether a declared type is a {@code Map<Integer, value>}. */
    private static boolean mapsSlotTo(Type type, Class<?> value) {
        if (!(type instanceof ParameterizedType parameterized)
                || !(parameterized.getRawType() instanceof Class<?> raw)
                || !Map.class.isAssignableFrom(raw)) {
            return false;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        return arguments.length == 2 && arguments[0] == Integer.class && arguments[1] == value;
    }

    private static List<String> declaredFields() {
        return java.util.Arrays.stream(ListEngine.class.getDeclaredFields())
                .map(field -> field.getName() + " : " + field.getGenericType())
                .toList();
    }

    // ------------------------------------------------------------------

    private ListEngine<Note> engine(Notes store) {
        return ListEngine.forTests(plugin, viewer, store, null);
    }

    /** Right-clicks a row and confirms the dangerous question it asks. */
    private void deleteAt(ListEngine<Note> engine, int slot) {
        prompts.confirmAnswer = true;
        engine.activate(slot, ClickKind.RIGHT);
    }

    private static List<Note> reversed(List<Note> entries) {
        List<Note> copy = new ArrayList<>(entries);
        Collections.reverse(copy);
        return copy;
    }

    /** The slots holding rows, in draw order. */
    private List<Integer> rowSlots() {
        return drawn.stream()
                .filter(entry -> entry.kind() == ControlKind.ROW)
                .map(Drawn::slot)
                .toList();
    }

    /** A chrome slot from everything drawn so far. */
    private int chromeSlot(ControlKind kind) {
        return drawn.stream()
                .filter(entry -> entry.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " button was drawn: " + drawn))
                .slot();
    }
}
