package net.exylia.lib.overlay;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * How much of the player's own inventory an overlay freezes.
 *
 * <p>An overlay draws items the server does not have. The danger is not that
 * the player sees them; it is that the player <em>moves</em> them, because the
 * only way a client can move an item is to tell the server about a slot, and
 * the server's answer is written from the items it really has. Every such
 * message is refused rather than answered, and this says how widely.
 *
 * @since 1.79.0
 */
public enum OverlayLock {

    /**
     * Nothing in the player's inventory moves.
     *
     * <p>The default, and the right one for a staff mode: the real inventory
     * is hidden underneath, and a player who cannot see what they are moving
     * cannot move it on purpose.
     */
    FULL,

    /**
     * Only the slots the overlay draws are frozen.
     *
     * <p>For an overlay that adds a few buttons to a hotbar the player is
     * still meant to use. Shift-clicks, number keys and off-hand swaps are
     * still refused whatever slot they start from, because each of them moves
     * an item to a slot chosen by the server rather than by the player, and
     * that slot may be one of ours.
     */
    OWNED;

    /**
     * Reads a lock as written, defaulting to the safe one.
     *
     * @param name the value, or {@code null}
     * @return the lock; {@link #FULL} for anything unrecognised
     */
    public static @NotNull OverlayLock byName(String name) {
        if (name == null) {
            return FULL;
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "OWNED", "SLOTS", "OWNED_ONLY" -> OWNED;
            default -> FULL;
        };
    }
}
