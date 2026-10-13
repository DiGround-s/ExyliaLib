package net.exylia.lib.interaction;

import org.jetbrains.annotations.NotNull;

/**
 * How a player clicked something in the world: an NPC, a hologram, anything a plugin draws.
 *
 * <p>Left is an attack, right is a use, and either can be done while sneaking. Configuration
 * filters on these with the same prefixes menus use: {@code left:}, {@code right:},
 * {@code shift_left:}, {@code shift_right:}, plus {@code shift:} for either while sneaking and
 * {@code any:} (or no prefix) for every click.
 *
 * @since 1.290.0
 */
public enum Click {
    LEFT,
    RIGHT,
    SHIFT_LEFT,
    SHIFT_RIGHT;

    /**
     * The click a packet describes.
     *
     * @param attack   whether it was an attack (left) rather than a use (right)
     * @param sneaking whether the player was sneaking
     * @return the click
     */
    public static @NotNull Click of(boolean attack, boolean sneaking) {
        if (attack) {
            return sneaking ? SHIFT_LEFT : LEFT;
        }
        return sneaking ? SHIFT_RIGHT : RIGHT;
    }

    /** Whether this is an attack, sneaking or not. */
    public boolean isLeft() {
        return this == LEFT || this == SHIFT_LEFT;
    }

    /** Whether this is a use, sneaking or not. */
    public boolean isRight() {
        return this == RIGHT || this == SHIFT_RIGHT;
    }

    /** Whether the player was sneaking. */
    public boolean isShift() {
        return this == SHIFT_LEFT || this == SHIFT_RIGHT;
    }
}
