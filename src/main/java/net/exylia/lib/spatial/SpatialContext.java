package net.exylia.lib.spatial;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One open spatial menu, as its sources, actions and preview see it: who looks at it, what they have
 * chosen, which page each list is on, and the menu's own state.
 *
 * <p>State set here is the menu's alone and goes when it closes. Every value set is also a
 * {@code %key%} placeholder in the layout's TEXT lines and titles. Used from the viewer's thread,
 * where every callback runs.
 *
 * @since 1.406.0
 */
public interface SpatialContext {

    @NotNull Player viewer();

    /** The menu's id, as registered. */
    @NotNull String menu();

    /** A value of this menu's state, or {@code null}. */
    @Nullable Object get(@NotNull String key);

    /** A value of this menu's state, or {@code fallback} when it is unset or of another type. */
    <T> @NotNull T get(@NotNull String key, @NotNull T fallback);

    /** Sets a value of this menu's state, or removes it with {@code null}. Redraws nothing by itself. */
    void set(@NotNull String key, @Nullable Object value);

    /** The page a source's list is on, from 0. */
    int page(@NotNull String source);

    /** Moves a source's list to a page (wrapping round either end) and redraws it. */
    void page(@NotNull String source, int page);

    /** The id of the entry chosen in a source, or {@code null}. */
    @Nullable String selected(@NotNull String source);

    /** Chooses an entry in a source by id ({@code null} for none); its marker glides there on the next redraw. */
    void select(@NotNull String source, @Nullable String entry);

    /**
     * Reads every source, the TEXT lines and the preview again and redraws what changed: equal
     * entries keep what they drew, a moved selection glides, nothing pops in again.
     */
    void refresh();

    /** Closes the menu, deflating it. */
    void close();
}
