package net.exylia.lib.api.practice;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where things are in one copy of an arena.
 *
 * <p>From {@link PracticeService#arenaLayout} it describes the original arena;
 * inside an {@link ArenaLease} it describes the copy that was reserved, which
 * may stand somewhere else entirely. Every location is a copy, so changing one
 * changes nothing in practice.
 *
 * @param spawn1         the first team's spawn
 * @param spawn2         the second team's spawn
 * @param spectatorSpawn where spectators stand, or {@code null} when the admin
 *                       set none
 * @param min            the lowest corner of the playable region
 * @param max            the highest corner of the playable region
 * @since 3.5.0
 */
public record ArenaLayout(
        @NotNull Location spawn1,
        @NotNull Location spawn2,
        @Nullable Location spectatorSpawn,
        @NotNull Location min,
        @NotNull Location max) {
}
