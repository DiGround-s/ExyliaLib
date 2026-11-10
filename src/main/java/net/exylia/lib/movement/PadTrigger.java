package net.exylia.lib.movement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Set;

/**
 * What sets a {@link JumpPad} off: one block, or every block of some
 * materials.
 *
 * @since 1.559.0
 */
public sealed interface PadTrigger {

    /**
     * One block, by world name and coordinates: the plate a player stands in,
     * or the block they stand on.
     */
    record At(@NotNull String world, int x, int y, int z) implements PadTrigger {
        public At {
            Objects.requireNonNull(world, "world");
        }
    }

    /**
     * Any block of {@code pad}, standing on a block of {@code below} (any block
     * when {@code below} is empty): {@code materials(plates, Set.of(SLIME_BLOCK))}
     * is every pressure plate laid on slime.
     */
    record Materials(@NotNull Set<Material> pad, @NotNull Set<Material> below) implements PadTrigger {
        public Materials {
            pad = Set.copyOf(pad);
            below = Set.copyOf(below);
            if (pad.isEmpty()) throw new IllegalArgumentException("A material pad needs at least one material.");
        }
    }

    /** The block a location is in. */
    static @NotNull PadTrigger at(@NotNull Location location) {
        Objects.requireNonNull(location.getWorld(), "the location has no world");
        return new At(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** Every block of these materials, on any block. */
    static @NotNull PadTrigger materials(@NotNull Set<Material> pad) {
        return new Materials(pad, Set.of());
    }

    /** Every block of these materials laid on one of {@code below}. */
    static @NotNull PadTrigger materials(@NotNull Set<Material> pad, @NotNull Set<Material> below) {
        return new Materials(pad, below);
    }

    /** Every pressure plate, laid on one of {@code below} (any block when empty). */
    static @NotNull PadTrigger plates(@NotNull Set<Material> below) {
        return new Materials(Tag.PRESSURE_PLATES.getValues(), below);
    }
}
