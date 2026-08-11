package net.exylia.lib.scoreboard;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * A scoreboard currently shown to a player.
 *
 * <p>The handle lets the plugin that showed the board keep control of it while
 * it is up: push new values to its placeholders, ask for a re-render, or take
 * it down. Everything a board does is safe to call from any thread.
 *
 * <p>Boards stack per player: showing a second board pauses the first, and
 * stopping the second brings the first back. A plugin that keeps this handle
 * can stop exactly its own board no matter what sits on top.
 *
 * @since 1.5.0
 */
public interface Board {

    /**
     * Returns who sees this board.
     *
     * @return the viewer
     */
    @NotNull Player player();

    /**
     * Returns the config this board was shown with.
     *
     * @return the board's config
     */
    @NotNull SidebarConfig config();

    /**
     * Re-renders the board on the next refresh tick.
     *
     * <p>Rendering is still diffed: lines whose text did not change are not
     * re-sent, so calling this when a placeholder value changed costs only the
     * lines that actually differ.
     */
    void refresh();

    /**
     * Replaces the extra values placeholders can read, and re-renders.
     *
     * <p>This is how a board learns about context that is not the player: a
     * resolver reading {@code request.get("arena")} sees the arena handed in
     * here.
     *
     * @param data values resolvers can read with
     *             {@link net.exylia.lib.placeholder.Request#get}
     */
    void updateData(@NotNull Map<String, Object> data);

    /**
     * Takes the board down.
     *
     * <p>If another board was paused underneath, it comes back. Stopping twice
     * is harmless.
     */
    void stop();

    /**
     * Returns whether this board has been stopped.
     *
     * @return {@code true} once {@link #stop()} ran, the player left, or the
     * owning plugin was disabled
     */
    boolean stopped();
}
