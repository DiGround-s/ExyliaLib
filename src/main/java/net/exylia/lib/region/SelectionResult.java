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
 * <h2>The shape that was picked (since 1.312.0)</h2>
 * {@link #shape()} is what the player saw and confirmed: a box, a full-height rectangle, a
 * polygon, a circle or a sphere, depending on the {@link SelectionMode} and the
 * {@link SelectionHeight}. A plugin that stores more than boxes reads that.
 *
 * <p>A plugin that only stores boxes keeps reading {@link #first()}, {@link #second()} and
 * {@link #cuboid()}, and keeps working whatever the player picked: for a
 * {@link SelectionMode#CUBOID box} they are the clicked corners, as they always were, and for
 * every other mode they are the corners of the box around the shape, so {@link #cuboid()} holds
 * every block the shape does.
 *
 * @param world  selected world identity
 * @param first  the left-clicked corner, or the lowest corner of the box around the shape
 * @param second the right-clicked corner, or the highest corner of the box around the shape
 * @param shape  the shape the player confirmed
 * @since 1.23.0
 */
public record SelectionResult(@NotNull WorldIdentity world,
                              @NotNull BlockPosition first,
                              @NotNull BlockPosition second,
                              @NotNull RegionShape shape) {

    /** Validates that the authoritative result world matches the first corner. */
    public SelectionResult {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(shape, "shape");
        if (!world.equals(first.world())) {
            throw new IllegalArgumentException("Selection world must match the first corner world");
        }
    }

    /**
     * A box between two corners, the only result there was before modes.
     *
     * @param world  selected world identity
     * @param first  exact left-clicked block position
     * @param second exact right-clicked block position
     */
    public SelectionResult(@NotNull WorldIdentity world, @NotNull BlockPosition first,
                           @NotNull BlockPosition second) {
        this(world, first, second, Cuboid.blocks(first.x(), first.y(), first.z(),
                second.x(), second.y(), second.z()));
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
