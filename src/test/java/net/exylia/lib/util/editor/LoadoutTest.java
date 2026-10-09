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

    @Test
    @DisplayName("a kit moved around and split into stacks is still the kit")
    void rearrangementIgnoresPositionsAndStacks() {
        ItemStack[] kit = {new Stub("totem", 2), null, new Stub("crystal", 64)};
        ItemStack[] layout = {new Stub("crystal", 32), new Stub("totem", 1), null,
                new Stub("crystal", 32), new Stub("totem", 1)};
        assertTrue(Loadout.isRearrangement(layout, kit));
        assertEquals(List.of(), Loadout.differences(layout, kit));
    }

    @Test
    @DisplayName("a missing or extra item is named with how many")
    void differencesNameWhatIsOff() {
        ItemStack[] kit = {new Stub("totem", 2), new Stub("crystal", 64)};
        ItemStack[] layout = {new Stub("totem", 1), new Stub("crystal", 64), new Stub("dirt", 3)};
        assertEquals(List.of("-1 totem", "+3 dirt"), Loadout.differences(layout, kit));
    }

    /**
     * An item compared by name. Subclassed because {@code new ItemStack(...)}
     * needs a running server.
     */
    private static final class Stub extends ItemStack {

        private final String kind;
        private final int amount;

        Stub(String kind, int amount) {
            this.kind = kind;
            this.amount = amount;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public boolean isSimilar(ItemStack other) {
            return other instanceof Stub stub && stub.kind.equals(kind);
        }

        @Override
        public @org.jetbrains.annotations.NotNull ItemStack asOne() {
            return new Stub(kind, 1);
        }

        @Override
        public String toString() {
            return kind;
        }
    }
}
