package net.exylia.lib.packet;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Making one client draw itself as a spectator.
 *
 * <p>Sends the game-mode change and the abilities of a spectator — flying,
 * invulnerable, no hand, no HUD — without calling {@code setGameMode}, so the
 * server keeps the real mode and every other plugin's checks keep working.
 * A camera view for a cutscene, a replay, or a lobby that should look empty.
 *
 * <h2>This is not no-clip</h2>
 * The server still simulates the real game mode: the player collides with
 * blocks, can be hit, and interacts with the world as before. The client draws
 * a spectator, the server moves a survival player, and the two disagree the
 * moment the player flies into a wall — they stop dead, or the server drags
 * them back. Staff who must pass through blocks need the real thing:
 *
 * <pre>{@code
 * player.setGameMode(GameMode.SPECTATOR); // walls stop mattering
 * }</pre>
 *
 * <p>Pair this with {@link Visibility} to hide them and
 * {@code setCollidable(false)} to stop them being pushed. Any server-side
 * game-mode change resends the truth and undoes it.
 *
 * @since 1.75.0
 */
public interface FakeGameMode {

    /**
     * Shows the player their client as a spectator, or back to their real mode.
     *
     * <p>A camera, not no-clip: see the class documentation.
     *
     * @param player  the player
     * @param enabled {@code true} to draw a spectator, {@code false} to restore
     * @since 1.78.0
     */
    void cameraView(@NotNull Player player, boolean enabled);

    /**
     * Returns whether this plugin is drawing the player as a spectator.
     *
     * @param player the player
     * @return {@code true} while faked
     * @since 1.78.0
     */
    boolean isCameraView(@NotNull Player player);

    /**
     * Shows the player their client as a spectator.
     *
     * @param player  the player
     * @param enabled {@code true} to draw a spectator, {@code false} to restore
     * @deprecated the name reads as the game mode, which this is not: the
     *         server keeps moving the real player, so walls still stop them.
     *         Use {@link #cameraView} for a camera, or
     *         {@code setGameMode(GameMode.SPECTATOR)} for no-clip.
     */
    @Deprecated
    default void spectator(@NotNull Player player, boolean enabled) {
        cameraView(player, enabled);
    }

    /**
     * Returns whether this plugin is drawing the player as a spectator.
     *
     * @param player the player
     * @return {@code true} while faked
     * @deprecated use {@link #isCameraView}.
     */
    @Deprecated
    default boolean isSpectator(@NotNull Player player) {
        return isCameraView(player);
    }
}
