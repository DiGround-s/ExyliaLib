package net.exylia.lib.packet.internal;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Tilts the camera of the players near a point, the way taking a hit does.
 *
 * <p>It is the hurt animation sent to a player about themselves: one packet,
 * no damage, no sound, no knockback, nothing on the server. The client plays
 * its own tilt, so how hard it tilts is the viewer's own "Damage Tilt"
 * accessibility setting and not ours to scale; a player who turned it off sees
 * nothing, which is exactly what that setting is for.
 *
 * <p>Sent through {@link Player#sendHurtAnimation(float)}, which is that packet
 * and nothing else on Spigot, Paper and Folia alike, so it needs no
 * PacketEvents and is safe from any thread.
 */
@ApiStatus.Internal
public final class ScreenShake {

    private ScreenShake() {
    }

    /**
     * Tilts every listed player within {@code radius} blocks of {@code origin}.
     *
     * @param origin  where the blow came from; each camera tilts away from it
     * @param players who may be shaken; whoever left or is elsewhere is skipped
     * @param radius  how far from the origin, in blocks
     * @return how many players were shaken
     */
    public static int shake(@NotNull Location origin, @NotNull Collection<? extends Player> players,
                            double radius) {
        World world = origin.getWorld();
        if (world == null || radius <= 0.0) {
            return 0;
        }
        double limit = radius * radius;
        int shaken = 0;
        for (Player player : players) {
            if (!player.isOnline()) {
                continue;
            }
            Location at = player.getLocation();
            // The world first: distanceSquared throws across worlds.
            if (at == null || !world.equals(at.getWorld()) || at.distanceSquared(origin) > limit) {
                continue;
            }
            player.sendHurtAnimation(towards(at, origin));
            shaken++;
        }
        return shaken;
    }

    /**
     * Which way the blow came from, relative to where the player is looking.
     *
     * <p>0 is straight ahead and 90 is to their right, which is what the hurt
     * animation expects. Standing on the origin itself reads as ahead.
     */
    static float towards(@NotNull Location player, @NotNull Location origin) {
        double dx = origin.getX() - player.getX();
        double dz = origin.getZ() - player.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return 0f;
        }
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return (float) (((yaw - player.getYaw()) % 360.0 + 360.0) % 360.0);
    }
}
