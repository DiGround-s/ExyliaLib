package net.exylia.lib.spatial;

import org.jetbrains.annotations.NotNull;

/**
 * A press on a {@link Hotspot}: which button, whether the player was sneaking, and the cell it landed
 * on ({@code 0} on a plain rectangle, the cell index on a {@link Hotspot#cells cell picker}, {@code -1}
 * for a click on nothing).
 *
 * <p>Left is the click every player can make anywhere: a swing in the air reaches the server, a
 * right-click in the air with an empty hand does not. Right arrives on a block, an entity, or with the
 * overlay hotbar's leave item in hand.
 *
 * @since 1.406.0
 */
public record SpatialClick(@NotNull Kind kind, boolean sneaking, int cell) {

    /** Which button. */
    public enum Kind { LEFT, RIGHT }

    public boolean left() {
        return kind == Kind.LEFT;
    }

    public boolean right() {
        return kind == Kind.RIGHT;
    }
}
