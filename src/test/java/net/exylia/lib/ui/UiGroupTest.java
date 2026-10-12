package net.exylia.lib.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a group's visible items land: centred, with a gap while they fit
 * with one, side by side once they do not.
 */
class UiGroupTest {

    private static final List<Integer> ROW = List.of(19, 20, 21, 22, 23, 24, 25);

    private static List<Integer> slots(int visible, int spacing) {
        UiGroup group = new UiGroup("row", ROW, List.of(), spacing);
        List<Integer> items = java.util.stream.IntStream.range(0, visible).boxed().toList();
        return List.copyOf(group.place(items).keySet());
    }

    @Test
    @DisplayName("auto spacing keeps a gap between items while they fit with one")
    void spacedWhileTheyFit() {
        assertEquals(List.of(22), slots(1, UiGroup.AUTO));
        assertEquals(List.of(21, 23), slots(2, UiGroup.AUTO));
        assertEquals(List.of(20, 22, 24), slots(3, UiGroup.AUTO));
        assertEquals(List.of(19, 21, 23, 25), slots(4, UiGroup.AUTO));
    }

    @Test
    @DisplayName("past that, items sit side by side, still centred")
    void contiguousWhenCrowded() {
        assertEquals(List.of(20, 21, 22, 23, 24), slots(5, UiGroup.AUTO));
        assertEquals(List.of(19, 20, 21, 22, 23, 24), slots(6, UiGroup.AUTO));
        assertEquals(ROW, slots(7, UiGroup.AUTO));
    }

    @Test
    @DisplayName("more items than slots draws only what fits; none draws nothing")
    void overflowAndEmpty() {
        assertEquals(ROW, slots(9, UiGroup.AUTO));
        assertEquals(List.of(), slots(0, UiGroup.AUTO));
    }

    @Test
    @DisplayName("a fixed spacing is kept when it fits and dropped when it does not")
    void fixedSpacing() {
        assertEquals(List.of(21, 22, 23), slots(3, 0));
        assertEquals(List.of(19, 22, 25), slots(3, 2));
        assertEquals(List.of(20, 21, 22, 23), slots(4, 2), "4 with a gap of 2 cannot fit 7");
    }

    @Test
    @DisplayName("hidden members leave no hole: the visible ones close ranks in file order")
    void hiddenItemsPack() {
        UiGroup group = new UiGroup("row", ROW, List.of(), UiGroup.AUTO);
        List<String> all = List.of("land", "people", "flags", "teleport", "rent", "market", "danger");
        // "people" and "rent" switched off.
        List<String> visible = all.stream().filter(id -> !id.equals("people") && !id.equals("rent")).toList();

        Map<Integer, String> placed = group.place(visible);

        assertEquals(Map.of(20, "land", 21, "flags", 22, "teleport", 23, "market", 24, "danger"), placed);
        assertEquals(List.of("land", "flags", "teleport", "market", "danger"), List.copyOf(placed.values()));
    }
}
