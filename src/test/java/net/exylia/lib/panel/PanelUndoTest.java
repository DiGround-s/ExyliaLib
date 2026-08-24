package net.exylia.lib.panel;

import net.exylia.lib.panel.internal.WorkingCopy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The working copy, its bounded history, and what undo means when there is
 * nothing left to undo.
 *
 * <p>Plain JUnit with no server: a working copy touches no Bukkit API at all,
 * which is the whole reason undo ships with the interface that declares it
 * rather than being deferred behind a stub.
 */
class PanelUndoTest {

    @Test
    @DisplayName("undo restores the previous value, and a third undo is a no-op")
    void undoRestoresThePreviousValue() {
        WorkingCopy<String> copy = WorkingCopy.of("original");

        copy.edit("first");
        copy.edit("second");
        assertEquals("second", copy.current());
        assertEquals(2, copy.undoDepth());

        assertTrue(copy.undo(), "undoing a committed edit must report that it happened");
        assertEquals("first", copy.current());

        assertTrue(copy.undo(), "the second undo must reach the original");
        assertEquals("original", copy.current());
        assertEquals(0, copy.undoDepth());

        assertFalse(copy.undo(), "undo with nothing left must be a no-op, not an error");
        assertEquals("original", copy.current(),
                "a no-op undo must leave the working copy exactly where it was");
    }

    @Test
    @DisplayName("the undo stack is bounded, discarding the oldest without throwing")
    void undoStackIsBounded() {
        int bound = Panels.undoLimit();
        WorkingCopy<Integer> copy = WorkingCopy.of(0);

        for (int edit = 1; edit <= bound + 5; edit++) {
            copy.edit(edit);
        }

        assertEquals(bound, copy.undoDepth(),
                "exactly the bound must remain after overflowing it");
        assertEquals(bound + 5, copy.current(), "the newest edit is still the current one");

        // Unwinding the whole stack reaches the oldest snapshot still held,
        // not the original: the five oldest were discarded, and that is the
        // documented cost of the bound.
        for (int undo = 0; undo < bound; undo++) {
            assertTrue(copy.undo(), "undo " + undo + " must succeed within the bound");
        }
        assertEquals(0, copy.undoDepth());
        assertFalse(copy.undo());
        assertEquals(5, copy.current(),
                "the oldest surviving snapshot is the fifth edit; the five before it were discarded");
    }

    @Test
    @DisplayName("the bound is twenty, the documented maximum")
    void boundIsTwenty() {
        assertEquals(20, Panels.undoLimit());
    }

    @Test
    @DisplayName("a snapshot is independent of later edits")
    void snapshotsAreIndependentOfLaterEdits() {
        WorkingCopy<List<String>> copy = WorkingCopy.of(List.of("a"));

        copy.edit(List.of("a", "b"));
        copy.edit(List.of("a", "b", "c"));

        assertTrue(copy.undo());
        assertEquals(List.of("a", "b"), copy.current());
        assertTrue(copy.undo());
        assertEquals(List.of("a"), copy.current(),
                "the first snapshot must still hold what it held when it was taken");
    }

    @Test
    @DisplayName("the original is remembered whatever the history does to it")
    void originalSurvivesTheBound() {
        WorkingCopy<Integer> copy = WorkingCopy.of(0);
        for (int edit = 1; edit <= Panels.undoLimit() + 5; edit++) {
            copy.edit(edit);
        }

        assertEquals(0, copy.original(),
                "what was on disk when the panel opened is what cancel and diff compare against, "
                        + "so it cannot be a snapshot the bound is allowed to discard");
    }

    @Test
    @DisplayName("releasing drops the history so nothing outlives the screen")
    void releaseDropsTheHistory() {
        WorkingCopy<String> copy = WorkingCopy.of("original");
        copy.edit("first");
        copy.edit("second");
        assertEquals(2, copy.undoDepth());

        copy.release();

        assertEquals(0, copy.undoDepth(), "a released panel must hold no snapshots");
        assertFalse(copy.undo(), "undo after release must be a no-op");
    }
}
