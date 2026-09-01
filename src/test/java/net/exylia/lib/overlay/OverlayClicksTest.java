package net.exylia.lib.overlay;

import net.exylia.lib.overlay.internal.OverlayClicks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
