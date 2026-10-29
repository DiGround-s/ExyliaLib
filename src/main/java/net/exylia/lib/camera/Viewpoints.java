package net.exylia.lib.camera;

import net.exylia.lib.camera.internal.CameraRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Where a player is seeing the world from: the camera they are looking
 * through, or else their own body.
 *
 * <h2>Why every range check asks this</h2>
 * Packet things (holograms, NPCs, displays) are drawn for whoever is near
 * them. While a camera films a place 100 blocks from the viewer's body,
 * "near" by the body would leave every hologram in the shot undrawn, unless
 * somebody else happened to stand there. So the library's per-viewer range
 * checks use this instead of {@link Player#getLocation()}: while a shot plays
 * it is the camera's current position (in the viewer's world), afterwards the
 * body again. The trackers re-run their passes on their own clocks, so what is
 * drawn follows the camera as it moves and cuts, and falls back when it ends.
 *
 * <p>Real server entities are tracked by the body, which no packet can change:
 * a plugin filming far from the body moves the body along (see
 * {@link WorldShot}).
 *
 * @since 1.397.0
 */
public final class Viewpoints {

    private Viewpoints() {
    }

    /**
     * Where a player sees from now.
     *
     * @param player the viewer
     * @return a fresh location: the camera's position and heading while they
     *         look through one, else {@link Player#getLocation()}
     */
    public static @NotNull Location of(@NotNull Player player) {
        double[] camera = CameraRuntime.viewpoint(player.getUniqueId());
        if (camera == null) {
            return player.getLocation();
        }
        return new Location(player.getWorld(), camera[0], camera[1], camera[2], (float) camera[3], (float) camera[4]);
    }

    /**
     * Whether a player is seeing from somewhere other than their body.
     *
     * @param player the viewer
     * @return {@code true} while they look through a camera
     */
    public static boolean isElsewhere(@NotNull Player player) {
        return CameraRuntime.viewpoint(player.getUniqueId()) != null;
    }
}
