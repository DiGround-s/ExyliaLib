package net.exylia.lib.overlay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a slot of the lower inventory is.
 *
 * <p>Three numberings describe the same forty-one places, and every bug this
 * module can have that is not a protocol bug is one of them read as another:
 * a button drawn in the wrong slot, a click credited to the wrong button, or
 * — the one that matters — a slot believed to be the player's when it is the
 * overlay's, which is how a drawn item becomes a real one.
 */
class OverlaySlotsTest {

    @Test
    @DisplayName("the player's own screen maps hotbar, storage, armour and off-hand")
    void playerMenu() {
        // The hotbar is last on screen and first in the inventory, which is the
        // reversal every one of these conversions exists for.
        assertEquals(0, OverlaySlots.fromPlayerMenu(36));
        assertEquals(8, OverlaySlots.fromPlayerMenu(44));
        assertEquals(9, OverlaySlots.fromPlayerMenu(9));
        assertEquals(35, OverlaySlots.fromPlayerMenu(35));
        assertEquals(OverlaySlots.HELMET, OverlaySlots.fromPlayerMenu(5));
        assertEquals(OverlaySlots.BOOTS, OverlaySlots.fromPlayerMenu(8));
        assertEquals(OverlaySlots.OFFHAND, OverlaySlots.fromPlayerMenu(45));
    }

    @Test
    @DisplayName("the crafting area is nobody's slot")
    void craftingArea() {
        for (int slot = 0; slot <= 4; slot++) {
            assertEquals(-1, OverlaySlots.fromPlayerMenu(slot));
        }
        assertEquals(-1, OverlaySlots.fromPlayerMenu(-1));
        assertEquals(-1, OverlaySlots.fromPlayerMenu(46));
    }

    @Test
    @DisplayName("every index survives the round trip through the player's screen")
    void roundTrip() {
        for (int index = 0; index < OverlaySlots.SIZE; index++) {
            int slot = OverlaySlots.toPlayerMenu(index);
            assertTrue(slot >= 0, "index " + index + " has no slot");
            assertEquals(index, OverlaySlots.fromPlayerMenu(slot));
        }
    }

    @Test
    @DisplayName("a container window puts storage first and the hotbar last")
    void container() {
        int chest = 54;
        assertEquals(9, OverlaySlots.fromContainer(chest, chest));
        assertEquals(35, OverlaySlots.fromContainer(chest + 26, chest));
        assertEquals(0, OverlaySlots.fromContainer(chest + 27, chest));
        assertEquals(8, OverlaySlots.fromContainer(chest + 35, chest));
    }

    @Test
    @DisplayName("a slot inside the container itself belongs to nobody below it")
    void insideContainer() {
        assertEquals(-1, OverlaySlots.fromContainer(0, 54));
        assertEquals(-1, OverlaySlots.fromContainer(53, 54));
        // Past the hotbar: no armour and no off-hand in a container window, so
        // reading one as the off-hand would draw a staff tool onto a chest.
        assertEquals(-1, OverlaySlots.fromContainer(54 + 36, 54));
    }

    @Test
    @DisplayName("worn slots can be written by name")
    void names() {
        assertEquals(List.of(OverlaySlots.HELMET), OverlaySlots.parse("helmet"));
        assertEquals(List.of(0, 1, 2, OverlaySlots.OFFHAND), OverlaySlots.parse("0-2,offhand"));
        assertEquals(-1, OverlaySlots.byName("elytra"));
    }

    @Test
    @DisplayName("a slot that is not a name or a number is a mistake, not an empty list")
    void badSlot() {
        assertThrows(IllegalArgumentException.class, () -> OverlaySlots.parse("hemlet"));
    }

    @Test
    @DisplayName("only the forty-one places are valid, and five of them are worn")
    void bounds() {
        assertTrue(OverlaySlots.isValid(0));
        assertTrue(OverlaySlots.isValid(OverlaySlots.SIZE - 1));
        assertFalse(OverlaySlots.isValid(-1));
        assertFalse(OverlaySlots.isValid(OverlaySlots.SIZE));
        assertFalse(OverlaySlots.isWorn(OverlaySlots.STORAGE_LAST));
        assertTrue(OverlaySlots.isWorn(OverlaySlots.BOOTS));
        assertTrue(OverlaySlots.isWorn(OverlaySlots.OFFHAND));
    }
}
