package net.exylia.lib.util.head.internal;

import net.exylia.lib.util.head.Head;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Forty-five results drawn out of forty-eight-result pages.
 *
 * <p>The catalogue answers with a fixed page size that is not the window's, so
 * every page turn after the first straddles two of them. Getting that wrong
 * loses or repeats three heads per page, which looks like the catalogue being
 * strange rather than like a bug, so it is checked here rather than noticed
 * later.
 */
class HeadWindowTest {

    private static final int API_PAGE = 48;
    private static final int WINDOW = 45;

    /** Heads numbered from an offset, so a slice can be identified by name. */
    private static List<Head> page(int from, int count) {
        List<Head> heads = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int number = from + index;
            heads.add(new Head(number, "head-" + number, "texture-" + number, "Test"));
        }
        return heads;
    }

    private static List<String> names(List<Head> heads) {
        return heads.stream().map(Head::name).toList();
    }

    @Test
    @DisplayName("the first window is the head of the first page")
    void firstWindow() {
        List<Head> one = page(0, API_PAGE);

        List<Head> window = HeadDb.window(0, WINDOW, 1, one, one);

        assertEquals(WINDOW, window.size());
        assertEquals("head-0", window.getFirst().name());
        assertEquals("head-44", window.getLast().name());
    }

    @Test
    @DisplayName("the second window carries on across two pages, losing nothing")
    void straddlesTwoPages() {
        List<Head> one = page(0, API_PAGE);
        List<Head> two = page(API_PAGE, API_PAGE);

        List<Head> window = HeadDb.window(WINDOW, WINDOW, 1, one, two);

        assertEquals(WINDOW, window.size());
        // The three the first page still held, then the next page from its start.
        assertEquals(List.of("head-45", "head-46", "head-47", "head-48"),
                names(window).subList(0, 4));
        assertEquals("head-89", window.getLast().name());
    }

    @Test
    @DisplayName("a window starting inside the second page is offset by it")
    void startsInTheSecondPage() {
        List<Head> two = page(API_PAGE, API_PAGE);
        List<Head> three = page(API_PAGE * 2, API_PAGE);

        List<Head> window = HeadDb.window(WINDOW * 2, WINDOW, 2, two, three);

        assertEquals(WINDOW, window.size());
        assertEquals("head-90", window.getFirst().name());
        assertEquals("head-134", window.getLast().name());
    }

    @Test
    @DisplayName("the last window is short rather than padded")
    void runsOut() {
        List<Head> one = page(0, API_PAGE);
        List<Head> two = page(API_PAGE, 5);

        List<Head> window = HeadDb.window(WINDOW, WINDOW, 1, one, two);

        assertEquals(8, window.size());
        assertEquals("head-45", window.getFirst().name());
        assertEquals("head-52", window.getLast().name());
    }
}
