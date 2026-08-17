package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * What to do with a click, decided before anything is run.
 *
 * <p>The security boundary of the menu system, kept apart from the listener so
 * it can be reasoned about and tested on its own. Every case here is a way a
 * player takes an item out of a menu that was not meant to give them one, and
 * each has been a real duplication bug in some plugin or other.
 *
 * <p>Nothing in here reads the item the client claims to have clicked. A packet
 * carries a slot number and a click type; the server already knows what it drew.
 *
 * @since 1.22.0
 */
public final class ClickPolicy {

    private ClickPolicy() {
    }

    /** What should happen to a click. */
    public enum Decision {
        /** Cancel it and run whatever the slot is bound to. */
        BUTTON,
        /** Leave it alone: the slot belongs to the player. */
        ALLOW,
        /** Cancel it and do nothing else. */
        CANCEL,
        /** Not our window. */
        IGNORE
    }

    /**
     * Decides what a click means.
     *
     * @param ours       whether the top inventory is a menu of ours
     * @param inTop      whether the click landed in the top inventory
     * @param shiftClick whether it was a shift-click
     * @param rawSlot    the slot, as the client numbered it
     * @param inputSlots the slots the menu lets the player use
     * @return what to do
     */
    public static @NotNull Decision decide(boolean ours, boolean inTop, boolean shiftClick,
                                           int rawSlot, @NotNull Set<Integer> inputSlots) {
        if (!ours) {
            return Decision.IGNORE;
        }
        if (!inTop) {
            // A shift-click from below throws the item upwards, so it is a
            // click on the menu even though the clicked inventory is not. This
            // is the one every plugin forgets, and the one that duplicates
            // items into a menu that then closes.
            if (shiftClick) {
                return inputSlots.isEmpty() ? Decision.CANCEL : Decision.ALLOW;
            }
            // Their own inventory, ordinary click. Not our business.
            return Decision.ALLOW;
        }
        if (inputSlots.contains(rawSlot)) {
            return Decision.ALLOW;
        }
        return Decision.BUTTON;
    }

    /**
     * Whether a drag should be refused.
     *
     * <p>A drag touches several slots at once, so it is allowed only when every
     * slot it lands on belongs to the player. Anything else would let somebody
     * paint an item across a row of buttons.
     *
     * @param topSize    how many slots the menu has
     * @param touched    the slots the drag covers
     * @param inputSlots the slots the menu lets the player use
     * @return whether to cancel it
     */
    public static boolean refuseDrag(int topSize, @NotNull Iterable<Integer> touched,
                                     @NotNull Set<Integer> inputSlots) {
        for (int slot : touched) {
            if (slot < topSize && !inputSlots.contains(slot)) {
                return true;
            }
        }
        return false;
    }
}
