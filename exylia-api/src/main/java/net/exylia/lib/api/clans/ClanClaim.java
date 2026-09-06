package net.exylia.lib.api.clans;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * One rectangle of land a clan owns.
 *
 * <p>Edges are inclusive block coordinates and the claim spans the full height
 * of the world, so a point is inside it whenever its X and Z fall in range.
 *
 * @param id     the claim id
 * @param clanId who owns it
 * @param world  the world it is in
 * @param minX   west edge, inclusive
 * @param minZ   north edge, inclusive
 * @param maxX   east edge, inclusive
 * @param maxZ   south edge, inclusive
 * @param baseY  the height the claim was made at
 * @since 1.0.0
 */
public record ClanClaim(
        @NotNull String id,
        @NotNull String clanId,
        @NotNull String world,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        int baseY) {

    /**
     * Whether a column of blocks falls inside this claim.
     *
     * @param x block X
     * @param z block Z
     * @return {@code true} when the column is claimed
     */
    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /**
     * Whether a location falls inside this claim, world included.
     *
     * @param location the location to test
     * @return {@code true} when the location is inside
     */
    public boolean contains(@NotNull Location location) {
        return location.getWorld() != null
                && location.getWorld().getName().equals(world)
                && contains(location.getBlockX(), location.getBlockZ());
    }
}
