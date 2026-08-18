package net.exylia.lib.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic of paging.
 *
 * <p>Small enough to look obvious and wrong often enough to matter: the last
 * page being short, a page number surviving the list shrinking under it, a
 * slice running off the end. All three are invisible until a leaderboard has an
 * awkward number of rows.
 */
class PagesTest {

    private static List<Integer> rows(int count) {
        return IntStream.range(0, count).boxed().toList();
    }

    @Test
    @DisplayName("pages round up, and there is always one")
    void count() {
        assertEquals(1, Pages.count(0, 21), "an empty list still has a page to show");
        assertEquals(1, Pages.count(1, 21));
        assertEquals(1, Pages.count(21, 21), "an exact fit is one page, not two");
        assertEquals(2, Pages.count(22, 21));
        assertEquals(5, Pages.count(100, 21));
    }

    @Test
    @DisplayName("a list with nowhere to draw does not divide by zero")
    void noRoom() {
        assertEquals(1, Pages.count(50, 0));
        assertTrue(Pages.slice(rows(50), 1, 0).isEmpty());
    }

    @Test
    @DisplayName("a page out of range is brought back in")
    void clamping() {
        assertEquals(1, Pages.clamp(0, 50, 21), "there is no page zero");
        assertEquals(1, Pages.clamp(-5, 50, 21));
        assertEquals(3, Pages.clamp(9, 50, 21), "past the end is the last page");
        assertEquals(2, Pages.clamp(2, 50, 21));
    }

    @Test
    @DisplayName("a list that shrinks under a reader moves them to the last page")
    void shrinkingList() {
        // A leaderboard refreshing while somebody reads page five, now with
        // only two pages of entries. Page five must not be blank.
        assertEquals(2, Pages.clamp(5, 30, 21));
        assertEquals(1, Pages.clamp(5, 0, 21), "and an empty list is page one");
    }

    @Test
    @DisplayName("a page holds its own rows and no others")
    void slicing() {
        List<Integer> all = rows(50);

        assertEquals(List.of(0, 1, 2, 3, 4), Pages.slice(all, 1, 5));
        assertEquals(List.of(5, 6, 7, 8, 9), Pages.slice(all, 2, 5));
    }

    @Test
    @DisplayName("the last page is short rather than out of bounds")
    void shortLastPage() {
        List<Integer> all = rows(23);

        List<Integer> last = Pages.slice(all, 5, 5);

        assertEquals(List.of(20, 21, 22), last);
        assertEquals(3, last.size());
    }

    @Test
    @DisplayName("asking past the end gets the last page, not an error")
    void beyondTheEnd() {
        assertEquals(List.of(20, 21, 22), Pages.slice(rows(23), 99, 5));
        assertTrue(Pages.slice(List.of(), 1, 5).isEmpty());
    }

    @Test
    @DisplayName("every row appears on exactly one page")
    void everyRowAppearsOnce() {
        // The property that actually matters. An off-by-one anywhere in here
        // means a leaderboard silently missing a player.
        for (int total : new int[] {0, 1, 20, 21, 22, 43, 100}) {
            List<Integer> all = rows(total);
            List<Integer> seen = new java.util.ArrayList<>();
            for (int page = 1; page <= Pages.count(total, 21); page++) {
                seen.addAll(Pages.slice(all, page, 21));
            }
            assertEquals(all, seen, "walking every page of " + total + " rows");
        }
    }

    @Test
    @DisplayName("a page button with nowhere to go knows it")
    void reachablePages() {
        // What decides whether an arrow is drawn. The single-page case is the
        // one that matters: it is every menu on a quiet server, and drawing
        // both arrows there was two buttons that did nothing.
        assertFalse(Pages.hasPrevious(1), "there is nothing before the first page");
        assertFalse(Pages.hasNext(1, 5, 21), "one page of rows has nothing after it");
        assertFalse(Pages.hasNext(1, 0, 21), "an empty list has nowhere to go");
        assertFalse(Pages.hasNext(1, 21, 21), "an exact fit is still one page");

        assertTrue(Pages.hasNext(1, 22, 21), "one row over the edge makes a second page");
        assertTrue(Pages.hasPrevious(2));
        assertTrue(Pages.hasNext(2, 100, 21));
        assertFalse(Pages.hasNext(5, 100, 21), "the last page of five");
    }

    @Test
    @DisplayName("a page number left behind by a shrinking list still hides the arrow")
    void reachableAfterShrinking() {
        // A leaderboard emptying under somebody on page three: the rows are
        // gone, so forward has to be refused even though the page number says
        // otherwise.
        assertFalse(Pages.hasNext(3, 4, 21));
    }

    @Test
    @DisplayName("a slot on a page maps back to its row")
    void indexOf() {
        assertEquals(0, Pages.indexOf(1, 21, 0));
        assertEquals(20, Pages.indexOf(1, 21, 20));
        assertEquals(21, Pages.indexOf(2, 21, 0), "the first slot of page two");
        assertEquals(42, Pages.indexOf(3, 21, 0));
    }
}
