package net.exylia.lib.packet;

import net.exylia.lib.packet.internal.Mirrors;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of a silent mirror: what is padding and what a click dirties. */
class MirrorsTest {

    /** A 41-slot player inventory shown in a 45-slot window. */
    private static final int SOURCE = 41;
    private static final int TOP = 45;

    @Test
    @DisplayName("padding is the top slots with no source behind them")
    void padding() {
        assertFalse(Mirrors.padded(0, SOURCE, TOP));
        assertFalse(Mirrors.padded(40, SOURCE, TOP));
        assertTrue(Mirrors.padded(41, SOURCE, TOP));
        assertTrue(Mirrors.padded(44, SOURCE, TOP));
        assertFalse(Mirrors.padded(45, SOURCE, TOP), "the viewer's own inventory is not padding");
        assertFalse(Mirrors.padded(26, 27, 27), "an exact fit has no padding");
    }

    @Test
    @DisplayName("a click on the top dirties that slot only")
    void topClick() {
        assertArrayEquals(new int[] {7}, Mirrors.touched(InventoryAction.PICKUP_ALL, 7, SOURCE, TOP));
        assertArrayEquals(new int[] {7}, Mirrors.touched(InventoryAction.MOVE_TO_OTHER_INVENTORY, 7, SOURCE, TOP));
    }

    @Test
    @DisplayName("a plain click on the bottom dirties nothing")
    void bottomClick() {
        assertEquals(0, Mirrors.touched(InventoryAction.PICKUP_ALL, 50, SOURCE, TOP).length);
        assertEquals(0, Mirrors.touched(InventoryAction.PLACE_ALL, 80, SOURCE, TOP).length);
    }

    @Test
    @DisplayName("shift-click from the bottom and double-click dirty every source slot, never the padding")
    void anywhere() {
        int[] all = java.util.stream.IntStream.range(0, SOURCE).toArray();
        assertArrayEquals(all, Mirrors.touched(InventoryAction.MOVE_TO_OTHER_INVENTORY, 50, SOURCE, TOP));
        assertArrayEquals(all, Mirrors.touched(InventoryAction.COLLECT_TO_CURSOR, 3, SOURCE, TOP));
        assertArrayEquals(all, Mirrors.touched(InventoryAction.COLLECT_TO_CURSOR, 60, SOURCE, TOP));
    }
}
