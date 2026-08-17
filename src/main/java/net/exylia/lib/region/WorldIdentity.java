package net.exylia.lib.region;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Portable identity of a world without retaining a platform world object.
 *
 * <p>The UUID is authoritative. The exact world name is retained only as a fallback for display,
 * diagnostics, and legacy migration.
 *
 * @param id the authoritative world UUID
 * @param fallbackName the exact fallback world name
 * @since 1.23.0
 */
public record WorldIdentity(@NotNull UUID id, @NotNull String fallbackName) {

    /** Validates the world identity. */
    public WorldIdentity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fallbackName, "fallbackName");
        if (fallbackName.isBlank()) {
            throw new IllegalArgumentException("Fallback world name cannot be blank");
        }
    }

    /**
     * Captures a world identity without retaining the supplied world.
     *
     * @param world the platform world
     * @return its portable identity
     */
    public static @NotNull WorldIdentity from(@NotNull World world) {
        Objects.requireNonNull(world, "world");
        return new WorldIdentity(world.getUID(), world.getName());
    }
}
