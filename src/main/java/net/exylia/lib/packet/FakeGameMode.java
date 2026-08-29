package net.exylia.lib.packet;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Making one client believe it is a spectator.
 *
 * <p>Sends the game-mode change and the abilities of a spectator — flying,
 * invulnerable, no-clip camera — without calling {@code setGameMode}, so the
 * server keeps the real mode and every other plugin's checks keep working.
 *
 * <h2>Limits</h2>
 * The server still simulates the real game mode: the player collides, can be
 * hit, and interacts with blocks as before. The client draws a spectator, but
 * the server moves a survival player. Pair with {@link Visibility} to hide
 * them and {@code setCollidable(false)} to stop them being pushed. Any
 * server-side game-mode change resends the truth and undoes this.
 *
 * @since 1.75.0
 */
public interface FakeGameMode {

    /**
     * Shows the player their client as a spectator, or back to their real mode.
     *
     * @param player the player
     * @param enabled {@code true} to fake spectator, {@code false} to restore
     */
    void spectator(@NotNull Player player, boolean enabled);

    /**
     * Returns whether this plugin is faking spectator for the player.
     *
     * @param player the player
     * @return {@code true} while faked
     */
    boolean isSpectator(@NotNull Player player);
}
