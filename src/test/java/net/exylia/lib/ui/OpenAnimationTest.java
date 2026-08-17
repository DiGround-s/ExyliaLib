package net.exylia.lib.ui;

import net.exylia.lib.ui.internal.OpenAnimationAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order slots appear in when a menu opens.
 *
 * <p>Pure arithmetic over a grid, which is why it is worth testing: the failure
 * mode is a menu missing slots, and that only shows up on a live server.
 */
class OpenAnimationTest {

    /**
     * Frames worked out afresh, never from the cache.
     *
     * <p>A test that reads a cached answer cannot fail when the arithmetic
     * behind it breaks, which makes it worse than no test at all.
     */
    private static List<List<Integer>> frames(String type, int size) {
        return OpenAnimationAccess.uncached(UiAnimationSpec.of(type), size);
    }

    /** Every slot appears exactly once, which is the property that matters. */
    private static void coversEverySlotOnce(List<List<Integer>> frames, int size) {
        List<Integer> seen = new ArrayList<>();
        for (List<Integer> frame : frames) {
            seen.addAll(frame);
        }
        assertEquals(size, seen.size(), "every slot appears once: " + seen);
        assertEquals(size, seen.stream().distinct().count(), "no slot appears twice");
        for (int slot : seen) {
            assertTrue(slot >= 0 && slot < size, "slot " + slot + " is outside the menu");
        }
    }

    /** Every name a file may write, in the spelling it would write it. */
    private static final List<String> EVERY_ANIMATION = List.of(
            "slide_left", "slide_top", "cascade", "center_out", "random", "spiral",
            "spiral_out", "checkerboard", "wave_horizontal", "wave_vertical", "corners",
            "snake", "rows_alternate", "columns_alternate", "explosion", "typewriter");

    @Test
    @DisplayName("every animation covers every slot exactly once, at every chest size")
    void everyAnimationCoversEverySlot() {
        // The failure mode is a menu that opens with slots missing, or a slot
        // drawn twice and so left showing whatever was underneath. Neither is
        // visible until a live server, so it is checked here for all seventeen
        // shapes across all six chest sizes.
        for (String animation : EVERY_ANIMATION) {
            for (int size : new int[] {9, 18, 27, 36, 45, 54}) {
                List<List<Integer>> frames = frames(animation, size);
                assertFalse(frames.isEmpty(), animation + " at " + size + " must animate");
                coversEverySlotOnce(frames, size);
            }
        }
    }

    @Test
    @DisplayName("no animation produces an empty frame, which would be a wasted tick")
    void noEmptyFrames() {
        for (String animation : EVERY_ANIMATION) {
            for (List<Integer> frame : frames(animation, 54)) {
                assertFalse(frame.isEmpty(), animation + " produced a frame with no slots");
            }
        }
    }

    @Test
    @DisplayName("a name nobody implemented draws the menu at once")
    void unknownAnimation() {
        // Decoration must never cost the menu. The loader reports the name; the
        // drawing simply happens immediately.
        assertTrue(frames("no_such_animation", 54).isEmpty());
        assertTrue(frames("none", 54).isEmpty());
    }

    @Test
    @DisplayName("the cache answers exactly what the arithmetic would")
    void cacheAgreesWithArithmetic() {
        for (String animation : EVERY_ANIMATION) {
            assertEquals(OpenAnimationAccess.uncached(UiAnimationSpec.of(animation), 54),
                    OpenAnimationAccess.frames(UiAnimationSpec.of(animation), 54),
                    animation + " must not change once cached");
        }
    }

    @Test
    @DisplayName("every name the library claims to know, it can draw")
    void knownNamesAllDraw() {
        // The loader warns using this list, so a name on it that draws nothing
        // would send an admin chasing a working config.
        for (String animation : EVERY_ANIMATION) {
            assertTrue(OpenAnimationAccess.isKnown(animation), animation + " should be known");
            assertFalse(frames(animation, 54).isEmpty(), animation + " is known but draws nothing");
        }
        assertTrue(OpenAnimationAccess.isKnown("none"), "none is a real choice");
        assertFalse(OpenAnimationAccess.isKnown("no_such_animation"));
    }

    @Test
    @DisplayName("random is stable, so it can be cached and looks the same for everyone")
    void randomIsStable() {
        // Seeded by size rather than by clock. A clock-seeded shuffle would be
        // uncacheable and would animate differently for each player.
        assertEquals(frames("random", 54), frames("random", 54));
    }

    @Test
    @DisplayName("typewriter reveals one slot at a time, in reading order")
    void typewriterOrder() {
        List<List<Integer>> frames = frames("typewriter", 27);

        assertEquals(27, frames.size(), "one frame per slot");
        assertEquals(List.of(0), frames.getFirst());
        assertEquals(List.of(26), frames.getLast());
    }

    @Test
    @DisplayName("snake doubles back on every other row")
    void snakeOrder() {
        List<Integer> order = new ArrayList<>();
        frames("snake", 27).forEach(order::addAll);

        // First row left to right, second row right to left.
        assertEquals(0, order.get(0));
        assertEquals(8, order.get(8));
        assertEquals(17, order.get(9), "the second row starts at its right end");
        assertEquals(9, order.get(17), "and finishes at its left");
    }

    @Test
    @DisplayName("spiral_out is spiral backwards")
    void spiralOutIsSpiralReversed() {
        List<Integer> inward = new ArrayList<>();
        frames("spiral", 54).forEach(inward::addAll);
        List<Integer> outward = new ArrayList<>();
        frames("spiral_out", 54).forEach(outward::addAll);

        assertEquals(inward.getFirst(), outward.getLast());
        assertEquals(inward.getLast(), outward.getFirst(),
                "the middle of the spiral is where the outward one starts");
    }

    @Test
    @DisplayName("checkerboard draws the light squares before the dark ones")
    void checkerboardOrder() {
        List<Integer> order = new ArrayList<>();
        frames("checkerboard", 54).forEach(order::addAll);

        // Slot 0 is light, slot 1 is dark. Every light square must come first.
        int lastLight = 0;
        int firstDark = order.size();
        for (int index = 0; index < order.size(); index++) {
            int slot = order.get(index);
            boolean light = (slot / 9 + slot % 9) % 2 == 0;
            if (light) {
                lastLight = Math.max(lastLight, index);
            } else {
                firstDark = Math.min(firstDark, index);
            }
        }
        assertTrue(lastLight < firstDark, "the two colours must not interleave");
    }

    @Test
    @DisplayName("columns_alternate walks in from both edges")
    void columnsAlternateOrder() {
        List<List<Integer>> frames = frames("columns_alternate", 54);

        assertTrue(frames.getFirst().contains(0), "the left column is first");
        assertTrue(frames.get(1).contains(8), "then the right one");
        assertEquals(9, frames.size(), "one frame per column");
    }

    @Test
    @DisplayName("a dashed or camel-cased name is the same animation")
    void namesAreForgiving() {
        // An admin should not have to guess the punctuation.
        assertEquals(frames("center_out", 54), frames("center-out", 54));
        assertEquals(frames("center_out", 54), frames("centerOut", 54));
        assertEquals(frames("rows_alternate", 54), frames("rows", 54));
    }

    @Test
    @DisplayName("center_out covers a full chest exactly once")
    void centerOutCoversEverything() {
        // 247 deployed menus ask for this one.
        coversEverySlotOnce(frames("center_out", 54), 54);
    }

    @Test
    @DisplayName("center_out starts in the middle and ends at the corners")
    void centerOutOrder() {
        List<List<Integer>> frames = frames("center_out", 54);

        assertTrue(frames.size() > 1, "an animation needs more than one frame");
        // Row 2, column 4 is the middle of a six-row chest.
        assertTrue(frames.getFirst().contains(22), "the middle appears first: " + frames.getFirst());
        assertTrue(frames.getLast().contains(0) || frames.getLast().contains(53),
                "a corner appears last: " + frames.getLast());
    }

    @Test
    @DisplayName("rows_alternate covers a full chest exactly once")
    void rowsAlternateCoversEverything() {
        coversEverySlotOnce(frames("rows_alternate", 54), 54);
    }

    @Test
    @DisplayName("rows_alternate goes top, bottom, top, bottom")
    void rowsAlternateOrder() {
        List<List<Integer>> frames = frames("rows_alternate", 54);

        assertEquals(6, frames.size(), "one frame per row");
        assertEquals(0, frames.get(0).getFirst(), "the top row first");
        assertEquals(45, frames.get(1).getFirst(), "then the bottom");
        assertEquals(9, frames.get(2).getFirst(), "then the second from the top");
    }

    @Test
    @DisplayName("an odd number of rows does not draw the middle one twice")
    void oddRowCount() {
        // Three rows: top, bottom, middle. The naive two-pointer walk emits the
        // middle row twice when the pointers meet.
        coversEverySlotOnce(frames("rows_alternate", 27), 27);
        assertEquals(3, frames("rows_alternate", 27).size());
    }

    @Test
    @DisplayName("every chest size is covered exactly once")
    void everySize() {
        for (int size = 9; size <= 54; size += 9) {
            coversEverySlotOnce(frames("center_out", size), size);
            coversEverySlotOnce(frames("rows_alternate", size), size);
        }
    }

    @Test
    @DisplayName("a shape nobody implemented shows the menu at once")
    void unknownShape() {
        // An animation is decoration; the menu is the point. A name the library
        // does not know must not leave a player staring at an empty window.
        assertTrue(frames("spiral_inwards", 54).isEmpty());
        assertTrue(frames("none", 54).isEmpty());
    }

    @Test
    @DisplayName("no animation and no size are both nothing to do")
    void nothingToDo() {
        assertTrue(OpenAnimationAccess.frames(null, 54).isEmpty());
        assertTrue(frames("center_out", 0).isEmpty());
    }

    @Test
    @DisplayName("the same shape and size is worked out once")
    void framesAreCached() {
        // 81 menus asking the same question should not be 81 answers. This one
        // deliberately goes through the cache: it is what it is testing.
        assertSame(OpenAnimationAccess.frames(UiAnimationSpec.of("center_out"), 54),
                OpenAnimationAccess.frames(UiAnimationSpec.of("center_out"), 54));
        assertFalse(OpenAnimationAccess.frames(UiAnimationSpec.of("center_out"), 54)
                == OpenAnimationAccess.frames(UiAnimationSpec.of("center_out"), 27));
    }
}
