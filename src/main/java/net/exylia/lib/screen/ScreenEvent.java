package net.exylia.lib.screen;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A click or a wheel step on a {@link Node}, handed to its {@link Node#onClick} or {@link Node#onScroll} on the
 * viewer's thread.
 *
 * @param session the screen it happened on
 * @param kind    what the player did
 * @param steps   for {@link Kind#SCROLL}: wheel steps, positive down the list; 0 otherwise
 * @param node    the {@link Node#id} of the box, or {@code null} when it has none
 * @since 1.483.0
 */
public record ScreenEvent(@NotNull ScreenSession session, @NotNull Kind kind, int steps, @Nullable String node) {

    /** What the player did. */
    public enum Kind { LEFT, RIGHT, SCROLL }

    /** Who did it. */
    public @NotNull Player viewer() {
        return session.viewer();
    }
}
