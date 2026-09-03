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

        /**
         * The press is the overlay's: it draws the held slot, or it binds
         * what an empty-looking hand does.
         */
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
     * <p>An overlay that binds its empty hand answers a blank slot itself.
     * That is decided before the real item is looked at, on purpose: a tool
     * that works or not depending on what the wearer happens to be carrying
     * in that slot is worse than one that never works.
     *
     * @param draws     whether the overlay draws an item in the held slot
     * @param owned     whether the slot is the overlay's at all
     * @param realEmpty whether the player's real slot is empty
     * @param emptyHand whether the overlay binds this press on a blank slot
     * @return what to do with the press
     * @since 1.81.3
     */
    public static @NotNull WorldPress worldPress(boolean draws, boolean owned, boolean realEmpty,
                                                 boolean emptyHand) {
        if (draws) {
            return WorldPress.PRESS;
        }
        if (!owned) {
            return WorldPress.PASS;
        }
        if (emptyHand) {
            return WorldPress.PRESS;
        }
        return realEmpty ? WorldPress.PASS : WorldPress.REFUSE;
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
        // A scattering click is refused wherever it could take an item out
        // from under the overlay, which is anywhere in the player's own half.
        //
        // Only there, though. The overlay stays up over a menu, and a
        // shift-click on a menu button is how half of them are used: refusing
        // every scattering click regardless of where it started would cancel
        // the packet, the menu's own click event would never fire, and every
        // shift-click action in every menu on the server would stop working
        // for anybody on duty. A menu button moves nothing, because the menu
        // cancels the click itself.
        if (scatters && (ownScreen || inPlayerRegion)) {
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
