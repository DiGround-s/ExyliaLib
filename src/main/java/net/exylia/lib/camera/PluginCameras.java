package net.exylia.lib.camera;

import net.exylia.lib.camera.internal.CameraRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One plugin's view of the camera module.
 *
 * <p>Obtained from {@link Cameras#of(Plugin)} and held for as long as the
 * plugin is loaded.
 *
 * @since 1.172.0
 */
public final class PluginCameras {

    private final Plugin plugin;

    PluginCameras(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Shows a player themselves, from outside.
     *
     * <p>The shot is worked out around where they are standing and which way
     * they are facing at this moment, so the same file reads the same whichever
     * direction they happen to be pointing.
     *
     * @param player who is filmed, and who watches
     * @param shot   the choreography
     * @return the handle, or {@code null} when there is nothing to play
     */
    public @Nullable CameraHandle play(@NotNull Player player, @NotNull CameraShot shot) {
        return CameraRuntime.play(plugin, shot, player.getLocation(), List.of(player));
    }

    /**
     * Shows several players one shot of one spot.
     *
     * <p>The camera is one entity and everybody looks through the same one, so
     * a celebration filmed for four people costs what it costs for one. What
     * that also means is that everybody sees the identical framing: two players
     * who should each see themselves from over their own shoulder are two calls,
     * not one.
     *
     * @param players who watches
     * @param shot    the choreography
     * @param subject where it films, and which way that faces
     * @return the handle, or {@code null} when there is nothing to play
     */
    public @Nullable CameraHandle play(@NotNull List<Player> players, @NotNull CameraShot shot,
                                       @NotNull Location subject) {
        return CameraRuntime.play(plugin, shot, subject, players);
    }

    /**
     * Ends whatever this player is looking through, whoever started it.
     *
     * <p>Not only this plugin's: a player is looking through one camera at a
     * time, and the thing a caller has is a player who needs their eyes back.
     *
     * @param player whose view to give back
     */
    public void stop(@NotNull Player player) {
        CameraRuntime.stop(player);
    }

    /**
     * Whether this player is looking through a camera right now.
     *
     * @param player the player
     * @return {@code true} while a shot is playing for them
     */
    public boolean isFilming(@NotNull Player player) {
        return CameraRuntime.isFilming(player);
    }

    /** Whether a camera can be shown at all on this server. */
    public boolean isSupported() {
        return CameraRuntime.isSupported();
    }
}
