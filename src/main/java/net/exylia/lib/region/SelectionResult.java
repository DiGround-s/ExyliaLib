package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Immutable result of a completed block selection.
 *
 * <p>The two corners retain their exact selected coordinates and order. The result world is the
 * first corner's world. Options may permit a second corner from another world; in that case the
 * cuboid still normalizes the two exact coordinate triples in the result world.
 *
 * @param world selected world identity
 * @param first exact left-clicked block position
 * @param second exact right-clicked block position
 * @since 1.23.0
 */
public record SelectionResult(@NotNull WorldIdentity world,
                              @NotNull BlockPosition first,
                              @NotNull BlockPosition second) {

    /** Validates that the authoritative result world matches the first corner. */
    public SelectionResult {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (!world.equals(first.world())) {
            throw new IllegalArgumentException("Selection world must match the first corner world");
        }
    }

    /**
     * Returns a normalized cuboid preserving every inclusively selected block.
     *
     * @return minimum-inclusive, maximum-exclusive cuboid for the selected blocks
     */
    public @NotNull Cuboid cuboid() {
        return Cuboid.blocks(first.x(), first.y(), first.z(), second.x(), second.y(), second.z());
    }
}
