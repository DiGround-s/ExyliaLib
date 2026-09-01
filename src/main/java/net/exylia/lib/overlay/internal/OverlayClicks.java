package net.exylia.lib.overlay.internal;

import net.exylia.lib.overlay.OverlayLock;
import org.jetbrains.annotations.NotNull;

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
     * What to do with a press in the world: a right click, a left click on a
     * block, or a click on an entity.
     *
     * @since 1.81.3
     */
    public enum WorldPress {

        /** The overlay draws the held slot, so the press is the overlay's. */
        PRESS,

        /**
         * The overlay covers the held slot but draws nothing there, and the
         * player really is holding something. The client is being shown an
         * empty hand, so letting the press through would use an item that is
         * not on the player's screen.
         */
        REFUSE,

        /**
         * Neither: the client and the server agree that the hand is empty, or
         * the slot is not the overlay's at all.
         */
        PASS
    }

    /**
     * Decides what a press in the world does.
     *
     * <p>An overlay with {@code hide_rest} owns every slot, because drawing
     * air over the ones it has no item for is the point. Deciding a world
     * press on ownership alone therefore refuses every right click a staff
     * member makes with an empty hand — no doors, no chests, and no silent
     * container inspection, which is the one thing a staff mode most wants.
     * What matters for a press is whether the overlay <em>draws</em> the slot;
     * ownership only decides whether a real item is hiding under it.
     *
     * @param draws     whether the overlay draws an item in the held slot
     * @param owned     whether the slot is the overlay's at all
     * @param realEmpty whether the player's real slot is empty
     * @return what to do with the press
     * @since 1.81.3
     */
    public static @NotNull WorldPress worldPress(boolean draws, boolean owned, boolean realEmpty) {
        if (draws) {
            return WorldPress.PRESS;
        }
        if (!owned || realEmpty) {
            return WorldPress.PASS;
        }
        return WorldPress.REFUSE;
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
