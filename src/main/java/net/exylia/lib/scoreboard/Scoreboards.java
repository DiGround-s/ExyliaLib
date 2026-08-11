package net.exylia.lib.scoreboard;

import net.exylia.lib.scoreboard.internal.BoardManager;
import net.exylia.lib.scoreboard.internal.SidebarLibrary;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

/**
 * Entry point of the scoreboard module.
 *
 * <p>A scoreboard is declared in config, not in code: the plugin says <em>what
 * happened</em> ("show this player the FFA board") and the server owner decides
 * what that looks like. One call is the whole lifecycle:
 *
 * <pre>{@code
 * // player entered the FFA arena
 * Scoreboards.show(this, player, config.get().scoreboards().ffa());
 *
 * // the arena changed: push the new context to the same board
 * Scoreboards.get(player).ifPresent(board ->
 *         board.updateData(Map.of("arena", arena)));
 *
 * // player left the arena
 * Scoreboards.hide(player);
 * }</pre>
 *
 * <h2>Stacking</h2>
 * Boards stack per player, across plugins. Showing a board pauses the one the
 * player already had; when it goes away, the previous board comes back on its
 * own. A lobby board, an event board and a death screen therefore compose
 * without any of the three plugins knowing about the others. A plugin that
 * keeps the returned {@link Board} can stop exactly its own board with
 * {@link Board#stop()} even when it is not the visible one.
 *
 * <h2>Cost</h2>
 * Templates are compiled once, when the board is shown. Every refresh resolves
 * placeholders, compares each rendered line with what the player already has,
 * and sends only the lines that changed: a board whose values did not move
 * costs a few string comparisons and zero packets. Lines without placeholders
 * are never re-rendered. All of it runs off the main thread.
 *
 * <h2>Threading</h2>
 * Refreshing happens on an async driver, so resolvers used in a scoreboard
 * must be safe off the main thread, which is what
 * {@link net.exylia.lib.placeholder.Placeholders.Group#async()} declares. The
 * methods here are safe to call from any thread.
 *
 * <h2>Compatibility</h2>
 * The board rides on the same packet-level scoreboard library ExyliaCommons
 * uses, and {@link SidebarConfig} reads the same YAML keys, so a server moving
 * to an ExyliaLib-based plugin keeps its scoreboard files untouched.
 *
 * @since 1.5.0
 */
public final class Scoreboards {

    private Scoreboards() {
        throw new AssertionError("No instances.");
    }

    /**
     * Shows a board to a player.
     *
     * <p>If the player already had one, it is paused underneath and comes back
     * when this one goes away.
     *
     * @param plugin the plugin the board belongs to; its boards disappear when
     *               it is disabled
     * @param player the viewer
     * @param config what the board looks like, usually from a config record
     * @return the shown board; a disabled config returns a no-op board, so the
     * result is never {@code null}
     */
    public static @NotNull Board show(@NotNull Plugin plugin, @NotNull Player player,
                                      @NotNull SidebarConfig config) {
        return BoardManager.show(plugin, player, config, null);
    }

    /**
     * Shows a board, with extra values its placeholders can read.
     *
     * <pre>{@code
     * Scoreboards.show(this, player, config.get().arena(), Map.of("arena", arena));
     * }</pre>
     *
     * A resolver then reads it with {@code request.get("arena")}.
     *
     * @param plugin the plugin the board belongs to
     * @param player the viewer
     * @param config what the board looks like
     * @param data   values resolvers can read with
     *               {@link net.exylia.lib.placeholder.Request#get}
     * @return the shown board, never {@code null}
     */
    public static @NotNull Board show(@NotNull Plugin plugin, @NotNull Player player,
                                      @NotNull SidebarConfig config,
                                      @NotNull Map<String, Object> data) {
        return BoardManager.show(plugin, player, config, data);
    }

    /**
     * Takes down the board currently visible to a player.
     *
     * <p>If another board was paused underneath, it comes back. To take down a
     * specific board regardless of what sits on top, stop it through its
     * {@link Board#stop()} handle instead.
     *
     * @param player the viewer
     * @return {@code true} when there was a board to take down
     */
    public static boolean hide(@NotNull Player player) {
        return BoardManager.hide(player);
    }

    /**
     * Returns the board currently visible to a player.
     *
     * @param player the viewer
     * @return the visible board, empty when the player has none
     */
    public static @NotNull Optional<Board> get(@NotNull Player player) {
        return BoardManager.get(player);
    }

    /**
     * Returns whether a player has any board, visible or paused.
     *
     * @param player the viewer
     * @return {@code true} when the player has at least one board
     */
    public static boolean has(@NotNull Player player) {
        return BoardManager.has(player);
    }

    /**
     * Returns whether the server version has a scoreboard packet adapter.
     *
     * <p>When {@code false} every method here keeps working and boards simply
     * stay invisible, so a caller never has to branch on this.
     *
     * @return {@code true} when boards actually reach the client
     */
    public static boolean isSupported() {
        return SidebarLibrary.isSupported();
    }
}
