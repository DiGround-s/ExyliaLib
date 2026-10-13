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
     * it along with its mount, so nothing has to be sent while it moves. The
     * lines keep their stack and the offset is measured from where the vehicle
     * carries its passengers.
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
     * <p>On a hologram with pages, these are the first page's; the other
     * pages stay as they are.
     *
     * @param lines the new lines, top first
     */
    void lines(@NotNull java.util.List<String> lines);

    /**
     * Replaces every page at once, keeping the page each viewer is on when it
     * still exists.
     *
     * @param pages one list of lines per page, the first page first; an empty
     *              list is one empty page
     * @since 1.297.0
     */
    default void pages(@NotNull java.util.List<java.util.List<String>> pages) {
        lines(pages.isEmpty() ? java.util.List.of() : pages.getFirst());
    }

    /**
     * Returns how many pages it has.
     *
     * @return 1 for a hologram without pages
     * @since 1.297.0
     */
    default int pageCount() {
        return 1;
    }

    /**
     * Returns the page a player is on.
     *
     * @param player the viewer
     * @return the page, counted from 0
     * @since 1.297.0
     */
    default int page(@NotNull Player player) {
        return 0;
    }

    /**
     * Turns a player to a page. A page past either end is clamped to it.
     *
     * <p>Safe from any thread; the player sees it on the next frame.
     *
     * @param player the viewer
     * @param page   the page, counted from 0
     * @since 1.297.0
     */
    default void page(@NotNull Player player, int page) {
    }

    /**
     * Turns a player to the next page, from the last back to the first.
     *
     * @param player the viewer
     * @since 1.297.0
     */
    default void nextPage(@NotNull Player player) {
        page(player, (page(player) + 1) % Math.max(1, pageCount()));
    }

    /**
     * Turns a player to the previous page, from the first round to the last.
     *
     * @param player the viewer
     * @since 1.297.0
     */
    default void previousPage(@NotNull Player player) {
        page(player, Math.floorMod(page(player) - 1, Math.max(1, pageCount())));
    }

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
     * Runs code when a player clicks the hologram, on that player's thread.
     *
     * <p>Setting a handler makes the hologram clickable even when its config
     * lists no commands; both run when it has both. The config's
     * {@code click.cooldown} applies.
     *
     * @param handler told who clicked, or {@code null} to stop listening
     * @since 1.287.0
     */
    default void onClick(@Nullable java.util.function.Consumer<Player> handler) {
        onClick(handler == null ? null
                : (java.util.function.BiConsumer<Player, net.exylia.lib.interaction.Click>)
                        (player, click) -> handler.accept(player));
    }

    /**
     * Runs code when a player clicks the hologram, told which button and
     * whether they were sneaking, on that player's thread.
     *
     * <p>Replaces a handler set with either {@code onClick}. Setting one makes
     * the hologram clickable even when its config lists no click lines; both
     * run when it has both. The config's {@code click.cooldown} applies.
     *
     * <pre>{@code
     * hologram.onClick((player, click) -> {
     *     if (click.isShift()) editor.open(player);
     * });
     * }</pre>
     *
     * @param handler told who clicked and how, or {@code null} to stop listening
     * @since 1.291.0
     */
    default void onClick(@Nullable java.util.function.BiConsumer<Player, net.exylia.lib.interaction.Click> handler) {
    }

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
