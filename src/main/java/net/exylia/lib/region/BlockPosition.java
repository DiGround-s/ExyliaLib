package net.exylia.lib.region;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable integer block position associated with a portable world identity.
 *
 * @param world the world identity
 * @param x block x coordinate
 * @param y block y coordinate
 * @param z block z coordinate
 * @since 1.23.0
 */
public record BlockPosition(@NotNull WorldIdentity world, int x, int y, int z) {

    /** Validates the position. */
    public BlockPosition {
        Objects.requireNonNull(world, "world");
    }

    /**
     * Captures the block containing a platform location.
     *
     * @param location the source location, which must have a world
     * @return the portable block position
     */
    public static @NotNull BlockPosition from(@NotNull Location location) {
        Objects.requireNonNull(location, "location");
        World sourceWorld = Objects.requireNonNull(location.getWorld(), "location world");
        return new BlockPosition(WorldIdentity.from(sourceWorld),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * Resolves this position to a platform location on demand.
     *
     * <p>The returned location is placed at the block's minimum corner. World lookup remains the
     * caller's responsibility, preventing this value from retaining or globally resolving Bukkit
     * state.
     *
     * @param resolver resolver keyed by portable world identity
     * @return a newly allocated platform location
     * @throws IllegalArgumentException if the world cannot be resolved or resolves incorrectly
     */
    public @NotNull Location toLocation(@NotNull Function<WorldIdentity, World> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        World resolved = resolver.apply(world);
        if (resolved == null) {
            throw new IllegalArgumentException("Unable to resolve world: " + world.id());
        }
        if (!world.id().equals(resolved.getUID())) {
            throw new IllegalArgumentException("Resolver returned a world with a different UUID");
        }
        return new Location(resolved, x, y, z);
    }
}
