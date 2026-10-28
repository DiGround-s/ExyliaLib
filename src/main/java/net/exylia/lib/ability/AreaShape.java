package net.exylia.lib.ability;

import net.exylia.lib.ability.internal.AreaPlanner;
import org.jetbrains.annotations.NotNull;

/**
 * Which blocks around a broken one an {@link AreaBreak} takes.
 *
 * <pre>{@code
 * AreaShape.plane(1, 0);   // 3x3, facing the breaker
 * AreaShape.cube(1);       // 3x3x3
 * AreaShape.vein(64);      // the connected blocks of the same material, 64 at most
 * }</pre>
 *
 * @since 1.385.0
 */
public final class AreaShape {

    /** The most blocks any shape plans, whatever it is asked for. */
    public static final int LIMIT = 4096;

    private final AreaPlanner.Kind kind;
    private final int radius;
    private final int depth;
    private final int max;

    private AreaShape(AreaPlanner.Kind kind, int radius, int depth, int max) {
        this.kind = kind;
        this.radius = Math.max(0, radius);
        this.depth = Math.max(0, depth);
        this.max = Math.max(0, Math.min(LIMIT, max));
    }

    /** A cube {@code 2 * radius + 1} wide, centred on the block. */
    public static @NotNull AreaShape cube(int radius) {
        return new AreaShape(AreaPlanner.Kind.CUBE, radius, 0, LIMIT);
    }

    /**
     * A square {@code 2 * radius + 1} wide across the way the breaker looks,
     * {@code depth + 1} layers deep along it: a 3x3 tunnel is {@code plane(1, 0)}.
     */
    public static @NotNull AreaShape plane(int radius, int depth) {
        return new AreaShape(AreaPlanner.Kind.PLANE, radius, depth, LIMIT);
    }

    /** The connected blocks of the same material, at most {@code max}: veins and trees. */
    public static @NotNull AreaShape vein(int max) {
        return new AreaShape(AreaPlanner.Kind.VEIN, 0, 0, max);
    }

    AreaPlanner.Kind kind() {
        return kind;
    }

    int radius() {
        return radius;
    }

    int depth() {
        return depth;
    }

    int max() {
        return max;
    }

    @Override
    public String toString() {
        return "AreaShape[" + kind + ", radius " + radius + ", depth " + depth + ", max " + max + "]";
    }
}
