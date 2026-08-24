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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Searching narrows what is <em>shown</em>, never what is <em>held</em>.
 *
 * <p>The distinction is the whole requirement. A filter that removed entries
 * from the working copy would make clearing the search a data-loss event and
 * would make saving after a search write a truncated list — silently, because
 * the screen would look exactly right.
 *
 * <p>Paging is likewise the section's arithmetic and not the caller's: the list
 * already knows how many rows it has and which page it is on, so asking the
 * caller for a page number would be asking them to recompute what the section
 * already computed.
 */
class ListSearchTest {

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
        plugin = FakeServer.newPlugin("ListSearchPlugin", folder.toFile());
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
    // 4.4 — the filter is a view, and the working copy is untouched
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a search matching 3 of 20 shows 3 rows and restores 20 on clear")
    void searchFiltersTheViewAndClearingRestoresIt() {
        ListEngine<Note> engine = engine(twentyWithThreeMatching());
        engine.draw();
        assertEquals(20, rowCount(), "all twenty fit on one page before anything is filtered");

        drawn.clear();
        engine.filter("target");
        assertEquals(3, rowCount(), "only the matches may be drawn: " + shownLabels(engine));
        assertEquals(20, engine.entries().size(),
                "and the working copy must still hold twenty — a filter that removed entries "
                        + "would make clearing the search a data-loss event");

        drawn.clear();
        engine.filter("");
        assertEquals(20, rowCount(), "clearing must restore the full view intact");
        assertEquals(20, engine.entries().size());
    }

    @Test
    @DisplayName("the working copy holds twenty through filter, page and clear alike")
    void theWorkingCopyIsNeverTouchedByAFilter() {
        ListEngine<Note> engine = engine(twentyWithThreeMatching());
        engine.draw();

        List<Note> before = engine.entries();
        engine.filter("target");
        assertEquals(before, engine.entries(), "filtering must not reorder or remove anything");
        engine.filter("note");
        assertEquals(before, engine.entries());
        engine.filter("");
        assertEquals(before, engine.entries());
        assertEquals(0, engine.undoDepth(),
                "and a filter is not an edit: nothing may be pushed onto undo, or a viewer "
                        + "would undo a search instead of the change they made");
    }

    @Test
    @DisplayName("saving after a search writes the whole list, not the filtered view")
    void savingAfterASearchWritesEveryEntry() {
        Notes store = twentyWithThreeMatching();
        ListEngine<Note> engine = engine(store);
        engine.draw();
        engine.filter("target");

        // One real change, so the diff is not empty and a write actually happens.
        engine.activate(rowSlots().get(0), ClickKind.LEFT);
        engine.save();
        FakeServer.tick(2);

        assertEquals(1, store.writes.size(), "exactly one write");
        assertEquals(20, store.writes.get(0).size(),
                "a filtered screen must not persist a truncated list — the seventeen entries "
                        + "nobody was looking at are still theirs: " + store.writes.get(0));
    }

    @Test
    @DisplayName("the search question goes through PanelPrompts, and the panel never calls Inputs")
    void searchIsAskedThroughTheSeam() {
        Notes store = twentyWithThreeMatching();
        ListEngine<Note> engine = engine(store);
        engine.draw();
        prompts.searchAnswer = store.load().get(4);

        engine.activate(chromeSlot(ControlKind.SEARCH), ClickKind.LEFT);

        assertEquals(1, prompts.searches.size(),
                "the search must be asked through the prompts seam, which is what makes it the "
                        + "existing SearchInput rather than a second one written here");
        assertEquals(20, prompts.searches.get(0).size(),
                "and it must be offered every entry, not the page the viewer happens to see");
    }

    // ------------------------------------------------------------------
    // 4.4 — paging redraws the list and nothing else
    // ------------------------------------------------------------------

    @Test
    @DisplayName("page numbers come from the section, so paging past the end goes nowhere")
    void pageNumbersComeFromTheSection() {
        ListEngine<Note> engine = engine(Notes.of(60));
        engine.draw();
        assertEquals(1, engine.page(), "a list opens on its first page");

        int next = chromeSlot(ControlKind.PAGE_NEXT);
        for (int click = 0; click < 10; click++) {
            engine.activate(next, ClickKind.LEFT);
        }
        assertEquals(engine.pages(), engine.page(),
                "paging past the last page must clamp rather than run off the end");
        assertTrue(engine.pages() >= 3, "sixty entries must actually paginate: " + engine.pages());
    }

    @Test
    @DisplayName("paging redraws the list slots and does not re-send unrelated slots")
    void pagingRedrawsOnlyTheList() {
        ListEngine<Note> engine = engine(Notes.of(60));
        engine.draw();
        int next = chromeSlot(ControlKind.PAGE_NEXT);
        List<Integer> firstPageRows = rowSlots();
        assertFalse(firstPageRows.isEmpty(), "there must be rows to redraw");

        drawn.clear();
        engine.activate(next, ClickKind.LEFT);

        assertEquals(firstPageRows, rowSlots(),
                "the same slots must be redrawn, with different entries in them");
        List<ControlKind> chrome = drawn.stream()
                .map(Drawn::kind)
                .filter(kind -> kind != ControlKind.ROW)
                .toList();
        assertEquals(List.of(), chrome,
                "a page change changes the list and nothing else: re-sending the buttons is "
                        + "packets for items that are identical. Re-sent: " + chrome);
    }

    @Test
    @DisplayName("paging shows different entries, so the redraw is not vacuous")
    void pagingActuallyChangesWhatIsShown() {
        ListEngine<Note> engine = engine(Notes.of(60));
        engine.draw();
        List<Note> firstPage = engine.shown();

        engine.activate(chromeSlot(ControlKind.PAGE_NEXT), ClickKind.LEFT);

        assertNotEquals(firstPage, engine.shown(),
                "page two must show different entries, or the assertion above passes because "
                        + "nothing happened");
    }

    // ------------------------------------------------------------------
    // 4.5 — an empty result explains itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a search matching nothing draws the pagination filler, not the background one")
    void anEmptyResultDrawsTheExplainingFiller() {
        ListEngine<Note> engine = engine(twentyWithThreeMatching());
        engine.draw();

        drawn.clear();
        engine.filter("nothing matches this");

        assertEquals(0, rowCount(), "nothing matches, so nothing may be drawn as a row");
        List<Drawn> fillers = drawn.stream()
                .filter(entry -> entry.kind() == ControlKind.EMPTY)
                .toList();
        assertFalse(fillers.isEmpty(),
                "an empty list must say why it is empty: the pagination filler is a different "
                        + "thing from the background, and treating it as background leaves the "
                        + "viewer with no explanation. Drawn: " + drawn);
        assertNotEquals(ControlKind.BACKGROUND, fillers.get(0).kind(),
                "and it must be distinct from the background filler");
    }

    @Test
    @DisplayName("the empty filler says the search is why, and goes away when it matches again")
    void theEmptyFillerIsAboutTheSearch() {
        ListEngine<Note> engine = engine(twentyWithThreeMatching());
        engine.draw();

        engine.filter("nothing matches this");
        assertTrue(engine.emptyReason().toLowerCase(java.util.Locale.ROOT).contains("search"),
                "the reason must name the search, since a list that is empty because it is "
                        + "empty is a different situation: " + engine.emptyReason());

        drawn.clear();
        engine.filter("target");
        assertEquals(3, rowCount(), "the rows must come back");
        assertEquals(0, drawn.stream().filter(entry -> entry.kind() == ControlKind.EMPTY).count(),
                "and the explanation must go with them");
    }

    @Test
    @DisplayName("a list that is empty on its own says so differently from one a search emptied")
    void anEmptyListAndAnEmptiedSearchReadDifferently() {
        ListEngine<Note> empty = engine(new Notes(List.of()));
        empty.draw();
        String withoutSearch = empty.emptyReason();

        ListEngine<Note> filtered = engine(twentyWithThreeMatching());
        filtered.draw();
        filtered.filter("nothing matches this");

        assertNotEquals(withoutSearch, filtered.emptyReason(),
                "an empty list and a search that matched nothing are two different situations, "
                        + "and a viewer who cannot tell them apart clears a search that was not "
                        + "the problem");
    }

    // ------------------------------------------------------------------

    private ListEngine<Note> engine(Notes store) {
        return ListEngine.forTests(plugin, viewer, store, null);
    }

    /** Twenty entries, of which exactly three carry the word searched for. */
    private static Notes twentyWithThreeMatching() {
        List<Note> notes = new ArrayList<>(20);
        for (int index = 1; index <= 20; index++) {
            boolean matches = index == 3 || index == 11 || index == 19;
            notes.add(new Note("id-" + index, (matches ? "target " : "plain ") + index));
        }
        return new Notes(notes);
    }

    private long rowCount() {
        return drawn.stream().filter(entry -> entry.kind() == ControlKind.ROW).count();
    }

    private List<Integer> rowSlots() {
        return drawn.stream()
                .filter(entry -> entry.kind() == ControlKind.ROW)
                .map(Drawn::slot)
                .toList();
    }

    private static List<String> shownLabels(ListEngine<Note> engine) {
        return engine.shown().stream().map(Note::text).toList();
    }

    private int chromeSlot(ControlKind kind) {
        return drawn.stream()
                .filter(entry -> entry.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " button was drawn: " + drawn))
                .slot();
    }
}
