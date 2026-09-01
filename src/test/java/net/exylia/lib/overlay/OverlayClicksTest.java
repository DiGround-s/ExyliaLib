package net.exylia.lib.overlay;

import net.exylia.lib.overlay.internal.OverlayClicks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a click is allowed to do while an overlay is on.
 *
 * <p>The security boundary of the module. The client is looking at items the
 * server does not have; every move it asks for would be answered from the ones
 * the server does have, so a click that gets through either moves an item the
 * player cannot see or writes one it can see into a slot that is real.
 */
class OverlayClicksTest {

    private static boolean full(boolean scatters, boolean ownScreen,
                                boolean inPlayerRegion, boolean owned) {
        return OverlayClicks.refuses(OverlayLock.FULL, scatters, ownScreen, inPlayerRegion, owned);
    }

    private static boolean owned(boolean scatters, boolean ownScreen,
                                 boolean inPlayerRegion, boolean owned) {
        return OverlayClicks.refuses(OverlayLock.OWNED, scatters, ownScreen, inPlayerRegion, owned);
    }

    @Test
    @DisplayName("a full lock refuses everything on the player's own screen")
    void fullRefusesOwnScreen() {
        assertTrue(full(false, true, true, true));
        assertTrue(full(false, true, true, false));
        // The crafting grid is not one of the forty-one, and is still a way to
        // move an item out from under the overlay.
        assertTrue(full(false, true, false, false));
    }

    @Test
    @DisplayName("a full lock leaves a menu's own buttons alone")
    void fullAllowsTheContainer() {
        assertFalse(full(false, false, false, false));
    }

    @Test
    @DisplayName("a full lock refuses the rows below an open menu")
    void fullRefusesBelowAContainer() {
        assertTrue(full(false, false, true, false));
    }

    @Test
    @DisplayName("an owned lock refuses only the overlay's own slots")
    void ownedRefusesOnlyItsOwn() {
        assertTrue(owned(false, true, true, true));
        assertFalse(owned(false, true, true, false));
        assertFalse(owned(false, false, false, false));
    }

    private static OverlayClicks.WorldPress press(boolean draws, boolean owned, boolean realEmpty) {
        return OverlayClicks.worldPress(draws, owned, realEmpty, false);
    }

    @Test
    @DisplayName("a slot the overlay draws answers a press in the world")
    void drawnSlotsPress() {
        assertEquals(OverlayClicks.WorldPress.PRESS, press(true, true, true));
        assertEquals(OverlayClicks.WorldPress.PRESS, press(true, true, false));
    }

    @Test
    @DisplayName("an empty hand under hide_rest still reaches the world")
    void emptyHandPasses() {
        // hide_rest owns all forty-one slots. Deciding on ownership alone left
        // a staff member unable to open a door, a chest, or anything else a
        // right click does, because every slot was "the overlay's".
        assertEquals(OverlayClicks.WorldPress.PASS, press(false, true, true));
    }

    @Test
    @DisplayName("a real item hidden under an undrawn slot is not usable")
    void hiddenItemIsRefused() {
        assertEquals(OverlayClicks.WorldPress.REFUSE, press(false, true, false));
    }

    @Test
    @DisplayName("a slot the overlay does not own is the player's")
    void unownedSlotsPass() {
        assertEquals(OverlayClicks.WorldPress.PASS, press(false, false, false));
        assertEquals(OverlayClicks.WorldPress.PASS, press(false, false, true));
    }

    @Test
    @DisplayName("a bound empty hand answers whether or not a real item is under it")
    void boundEmptyHandAlwaysPresses() {
        // The point of binding it: a tool that works on one hotbar slot and
        // not the next, because of what the wearer happens to be carrying
        // there, is worse than one that never works.
        assertEquals(OverlayClicks.WorldPress.PRESS,
                OverlayClicks.worldPress(false, true, true, true));
        assertEquals(OverlayClicks.WorldPress.PRESS,
                OverlayClicks.worldPress(false, true, false, true));
    }

    @Test
    @DisplayName("a bound empty hand claims nothing outside the overlay")
    void boundEmptyHandStopsAtTheOverlay() {
        assertEquals(OverlayClicks.WorldPress.PASS,
                OverlayClicks.worldPress(false, false, true, true));
    }

    @Test
    @DisplayName("a click the server aims is refused whatever slot it started from")
    void scatteringIsAlwaysRefused() {
        // A shift-click, a number key, an off-hand swap, a double-click and a
        // drag all land where the server decides, and that may be a slot the
        // overlay draws. Refusing only the slot it started from is the hole.
        assertTrue(owned(true, false, false, false));
        assertTrue(full(true, false, false, false));
    }
}
