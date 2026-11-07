package net.exylia.lib.screen;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A click or a wheel step on a {@link Node}, handed to its {@link Node#onClick} or {@link Node#onScroll} on the
 * viewer's thread.
 *
 * @param session the screen it happened on
 * @param kind    what the player did
 * @param steps   for {@link Kind#SCROLL}: wheel steps, positive down the list; 0 otherwise
 * @param node    the {@link Node#id} of the box, or {@code null} when it has none
 * @param row     what the clicked row is about, for a box a markup screen repeated over a source (since 1.494.0):
 *                the row's {@link net.exylia.lib.ui.UiEntry#value()} from {@link ScreenMarkup#rows}, the row map
 *                from {@link ScreenMarkup#source}; {@code null} outside a repeat or for a screen built in Java
 * @param shift   whether the click was a shift-click: made while the player held sprint (since 1.496.0), since
 *                Shift itself steps back on a screen
 * @since 1.483.0
 */
public record ScreenEvent(@NotNull ScreenSession session, @NotNull Kind kind, int steps, @Nullable String node,
                          @Nullable Object row, boolean shift) {

    /** What the player did. */
    public enum Kind { LEFT, RIGHT, SCROLL }

    /** An event that is not a shift-click, as every event was before 1.496.0. */
    public ScreenEvent(@NotNull ScreenSession session, @NotNull Kind kind, int steps, @Nullable String node,
                       @Nullable Object row) {
        this(session, kind, steps, node, row, false);
    }

    /** An event with no row, as every event was before 1.494.0. */
    public ScreenEvent(@NotNull ScreenSession session, @NotNull Kind kind, int steps, @Nullable String node) {
        this(session, kind, steps, node, null, false);
    }

    /** Who did it. */
    public @NotNull Player viewer() {
        return session.viewer();
    }

    /**
     * The clicked row's object when it is a {@code type}: {@code event.row(KitEntity.class)}.
     *
     * @since 1.494.0
     */
    public <T> @NotNull Optional<T> row(@NotNull Class<T> type) {
        return type.isInstance(row) ? Optional.of(type.cast(row)) : Optional.empty();
    }
}
