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
 * AreaShape.column(8);     // a line 8 long, origin included, the way the breaker looks
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
    private final boolean vertical;

    private AreaShape(AreaPlanner.Kind kind, int radius, int depth, int max) {
        this(kind, radius, depth, max, false);
    }

    private AreaShape(AreaPlanner.Kind kind, int radius, int depth, int max, boolean vertical) {
        this.kind = kind;
        this.radius = Math.max(0, radius);
        this.depth = Math.max(0, depth);
        this.max = Math.max(0, Math.min(LIMIT, max));
        this.vertical = vertical;
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

    /**
     * A line {@code length} blocks long, the origin included, going away from
     * the breaker along the way they look (down or up past 45 degrees of
     * pitch, else the horizontal facing): {@code column(8)} digs 7 past the
     * broken block.
     *
     * @since 1.387.0
     */
    public static @NotNull AreaShape column(int length) {
        return column(length, false);
    }

    /**
     * {@link #column(int)}, or with {@code vertical} always straight down,
     * or straight up when the breaker looks above the horizon.
     *
     * @since 1.387.0
     */
    public static @NotNull AreaShape column(int length, boolean vertical) {
        return new AreaShape(AreaPlanner.Kind.PLANE, 0, Math.max(1, length) - 1, LIMIT, vertical);
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

    /** Whether the look axis is forced onto y. */
    boolean vertical() {
        return vertical;
    }

    @Override
    public String toString() {
        return "AreaShape[" + kind + ", radius " + radius + ", depth " + depth + ", max " + max + (vertical ? ", vertical" : "") + "]";
    }
}
