package net.exylia.lib.respawn;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Decides where a player respawns.
 *
 * <p>Registered with {@link PluginRespawns#location}. Runs on the respawning
 * player's thread.
 *
 * @since 1.440.0
 */
@FunctionalInterface
public interface RespawnRule {

    /**
     * Picks the respawn location.
     *
     * @param context who respawns, where they are about to land and where they died
     * @return where they should respawn, or {@code null} to leave
     *         {@link RespawnContext#target()} as it is
     */
    @Nullable Location apply(@NotNull RespawnContext context);
}
