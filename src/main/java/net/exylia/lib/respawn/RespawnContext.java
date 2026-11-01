package net.exylia.lib.respawn;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a {@link RespawnRule} is told.
 *
 * @param player        who respawns
 * @param target        where they land right now: vanilla's choice, or what the
 *                      rule before this one returned; a copy, safe to change
 * @param bedSpawn      whether vanilla is using their bed, respawn anchor or
 *                      forced spawn point rather than the world spawn
 * @param deathLocation where they died; {@code null} when the respawn did not
 *                      follow a death (leaving the End)
 * @param reason        why they respawn: {@code DEATH} unless the rule opted in
 *                      to other reasons
 * @since 1.440.0
 */
public record RespawnContext(@NotNull Player player, @NotNull Location target, boolean bedSpawn,
                             @Nullable Location deathLocation, @NotNull RespawnReason reason) {

    /** The world they died in, or {@code null} when {@link #deathLocation()} is. */
    public @Nullable World deathWorld() {
        return deathLocation == null ? null : deathLocation.getWorld();
    }
}
