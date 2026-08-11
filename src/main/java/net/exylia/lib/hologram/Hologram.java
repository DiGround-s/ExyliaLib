package net.exylia.lib.hologram;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A hologram that is currently showing.
 *
 * <p>The handle is how the plugin that created it keeps control: move it, push
 * new values into its placeholders, restrict who sees it, or take it down.
 * Every method is safe to call from any thread.
 *
 * @since 1.6.0
 */
public interface Hologram {

    /**
     * Returns the name this hologram was created under.
     *
     * @return the id, unique per plugin
     */
    @NotNull String id();

    /**
     * Returns where it is standing, offsets included.
     *
     * @return a copy of its location
     */
    @NotNull Location location();

    /**
     * Moves it.
     *
     * <p>Viewers are told to move it rather than being sent a new hologram, so
     * a hologram that follows something does not flicker.
     *
     * @param location where to move it to
     */
    void moveTo(@NotNull Location location);

    /**
     * Makes the hologram ride an entity, keeping the configured offset.
     *
     * <p>The cheapest way to have a hologram follow something: the client moves
     * it along with its mount, so nothing has to be sent while it moves.
     *
     * @param entity what to ride, or {@code null} to detach and stay put
     */
    void attachTo(@Nullable Entity entity);

    /**
     * Replaces the text, ignoring what config said.
     *
     * <p>For holograms whose content is decided in code. Placeholders in the
     * lines still resolve.
     *
     * @param lines the new lines, top first
     */
    void lines(@NotNull java.util.List<String> lines);

    /**
     * Re-renders on the next refresh tick.
     *
     * <p>Still diffed: only what actually changed reaches the client.
     */
    void refresh();

    /**
     * Replaces the extra values placeholders can read, and re-renders.
     *
     * @param data values resolvers can read with
     *             {@link net.exylia.lib.placeholder.Request#get}
     */
    void updateData(@NotNull Map<String, Object> data);

    /**
     * Limits who can see it.
     *
     * <p>Applied on top of the view distance: a player who fails this never
     * receives the hologram at all.
     *
     * @param filter decides per player, or {@code null} for everybody
     */
    void visibleIf(@Nullable java.util.function.Predicate<Player> filter);

    /**
     * Returns whether a player is currently being shown this hologram.
     *
     * @param player the player
     * @return {@code true} when the player has it on screen
     */
    boolean isViewing(@NotNull Player player);

    /**
     * Returns how many players have it on screen.
     *
     * @return the viewer count
     */
    int viewerCount();

    /**
     * Takes it down for everybody.
     *
     * <p>Removing it twice is harmless.
     */
    void remove();

    /**
     * Returns whether this hologram has been removed.
     *
     * @return {@code true} once it is gone
     */
    boolean removed();
}
