package net.exylia.lib.overlay.internal;

import net.exylia.lib.overlay.OverlayLock;

/**
 * Whether a click has to be refused, decided apart from the packet it came in.
 *
 * <p>The security boundary of the overlay module, kept here rather than in
 * {@link OverlayPackets} so it can be reasoned about and tested without a
 * server and without PacketEvents. Every case is a way an item the server does
 * not have becomes one it does.
 *
 * <p>Nothing here reads the item the client claims to have clicked. A click
 * carries a slot and a kind; what is drawn there is something the server
 * already knows.
 */
public final class OverlayClicks {

    private OverlayClicks() {
    }

    /**
     * Decides whether to refuse a click.
     *
     * @param lock           how much of the inventory the overlay freezes
     * @param scatters       whether the click moves an item to a slot the
     *                       server picks rather than the player — a
     *                       shift-click, a number key, an off-hand swap, a
     *                       double-click, a drag
     * @param ownScreen      whether the click was in the player's own
     *                       inventory screen rather than in a container
     * @param inPlayerRegion whether it landed on one of the player's own slots
     * @param owned          whether that slot is one the overlay draws
     * @return whether the click must not reach the server
     */
    public static boolean refuses(OverlayLock lock, boolean scatters, boolean ownScreen,
                                  boolean inPlayerRegion, boolean owned) {
        // Where it started does not decide it: the server picks where it lands,
        // and that may be a slot we own whatever slot it came from.
        if (scatters) {
            return true;
        }
        if (lock == OverlayLock.FULL) {
            // On the player's own screen even the crafting grid is a way to
            // move an item out from under the overlay, so nothing there moves.
            return ownScreen || inPlayerRegion;
        }
        return owned;
    }
}
