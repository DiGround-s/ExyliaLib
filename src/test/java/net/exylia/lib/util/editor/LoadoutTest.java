package net.exylia.lib.util.editor;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout every screen that shows a loadout reads.
 *
 * <p>What this is guarding is the bug it was written for: the editor, the
 * preview and the code that hands a kit over each decided for themselves what
 * position five meant, and they disagreed.
 */
class LoadoutTest {

    @Test
    @DisplayName("every position belongs to exactly one part")
    void partsCoverTheLayout() {
        assertEquals(Loadout.Part.HELMET, Loadout.partOf(0));
        assertEquals(Loadout.Part.BOOTS, Loadout.partOf(3));
        assertEquals(Loadout.Part.OFFHAND, Loadout.partOf(Loadout.OFFHAND));
        assertEquals(Loadout.Part.STORAGE, Loadout.partOf(Loadout.STORAGE_START));
        assertEquals(Loadout.Part.STORAGE, Loadout.partOf(Loadout.HOTBAR_START - 1));
        assertEquals(Loadout.Part.HOTBAR, Loadout.partOf(Loadout.HOTBAR_START));
        assertEquals(Loadout.Part.HOTBAR, Loadout.partOf(Loadout.SIZE - 1));
        assertNull(Loadout.partOf(-1));
        assertNull(Loadout.partOf(Loadout.SIZE));
    }

    @Test
    @DisplayName("the rows count from their own start")
    void offsetsAreRelative() {
        assertEquals(0, Loadout.offsetIn(Loadout.storage(0)));
        assertEquals(26, Loadout.offsetIn(Loadout.storage(26)));
        assertEquals(8, Loadout.offsetIn(Loadout.hotbar(8)));
        assertEquals(-1, Loadout.offsetIn(Loadout.OFFHAND));
        assertEquals(Loadout.SIZE, Loadout.STORAGE_START + Loadout.STORAGE_COUNT
                + Loadout.HOTBAR_COUNT);
    }

    @Test
    @DisplayName("the grid has one slot per position, and none twice")
    void editorSlotsAreOnePerPosition() {
        List<Integer> slots = Loadout.editorSlots();
        assertEquals(Loadout.SIZE, slots.size());
        assertEquals(Loadout.SIZE, Set.copyOf(slots).size());
        assertEquals(0, slots.get(0));
        assertEquals(4, slots.get(4));
        assertEquals(9, slots.get(5));
        assertEquals(44, slots.get(Loadout.SIZE - 1));
        assertTrue(slots.stream().noneMatch(slot -> slot > 44));
    }

    @Test
    @DisplayName("an empty tail is not part of the loadout")
    void trimDropsTheEmptyTail() {
        List<ItemStack> cleared = Arrays.asList(null, null, null);
        assertTrue(Loadout.trim(cleared).isEmpty());
        assertTrue(Loadout.trim(List.of()).isEmpty());
    }

    @Test
    @DisplayName("reading past the end is nothing there, not a mistake")
    void readingPastTheEndIsEmpty() {
        assertNull(Loadout.at(List.of(), 40));
        assertNull(Loadout.at(null, 0));
        assertNull(Loadout.at(List.of(), -1));
    }
}
