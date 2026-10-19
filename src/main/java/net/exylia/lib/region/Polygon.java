package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A region drawn as a polygon on the ground, either between two heights or through the whole
 * column of the world.
 *
 * <pre>{@code
 * Polygon plaza = Polygon.prism(List.of(
 *         Polygon.Vertex.block(0, 0), Polygon.Vertex.block(20, 0),
 *         Polygon.Vertex.block(30, 15), Polygon.Vertex.block(5, 25)), 60, 90);
 *
 * Polygon claim = Polygon.columns(vertices);   // every y, like UnboundedYRectangle
 * }</pre>
 *
 * <h2>A polygon of blocks</h2>
 * Containment is decided per block column: a point belongs to the polygon when the centre of the
 * block column it stands in is inside the outline or exactly on it. That is what an admin who
 * clicked the corner blocks means — the corner blocks are part of the area, and so is every block
 * the outline runs through — and it is why {@link Vertex#block(int, int)} puts a vertex in the
 * centre of a block. Two points in the same column always get the same answer, so a player
 * standing on the edge of a claim is never half in it.
 *
 * <h2>Any outline</h2>
 * The vertices are joined in order and the last is joined back to the first. The outline may
 * cross itself: the even-odd rule decides what is inside, so a figure eight is two areas and
 * the knot in the middle is outside. That is what lets a freehand selection be stored as it was
 * drawn.
 *
 * @param vertices the outline in order, at least three, at most {@link #MAX_VERTICES}
 * @param vertical the heights it spans, or {@code null} to contain every y
 * @since 1.312.0
 */
public record Polygon(@NotNull List<Vertex> vertices, @Nullable VerticalBounds vertical)
        implements RegionShape {

    /**
     * The most vertices one polygon may have.
     *
     * <p>Containment walks every edge, and region lookups ask it on every block a player crosses.
     * A thousand edges is still well under a microsecond, and far past anything a hand draws.
     */
    public static final int MAX_VERTICES = 1024;

    /**
     * How close to an edge a point counts as on it.
     *
     * <p>Block centres and block-centred vertices meet exactly in floating point; the tolerance
     * only absorbs the rounding of the division that finds where an edge crosses a row.
     */
    private static final double EDGE_TOLERANCE = 1.0E-7;

    /** Validates the outline and copies it. */
    public Polygon {
        Objects.requireNonNull(vertices, "vertices");
        vertices = List.copyOf(vertices);
        if (vertices.size() < 3) {
            throw new IllegalArgumentException("A polygon needs at least three vertices");
        }
        if (vertices.size() > MAX_VERTICES) {
            throw new IllegalArgumentException("A polygon may have at most " + MAX_VERTICES + " vertices");
        }
    }

    /**
     * A polygon between two heights.
     *
     * @param vertices the outline in order
     * @param minY     the lowest y, inclusive
     * @param maxY     the highest y, exclusive
     * @return the polygon
     */
    public static @NotNull Polygon prism(@NotNull List<Vertex> vertices, double minY, double maxY) {
        return new Polygon(vertices, new VerticalBounds(minY, maxY));
    }

    /**
     * A polygon through every y, the way a claim covers its land.
     *
     * @param vertices the outline in order
     * @return the polygon
     */
    public static @NotNull Polygon columns(@NotNull List<Vertex> vertices) {
        return new Polygon(vertices, null);
    }

    @Override
    public boolean contains(double x, double y, double z) {
        if (vertical != null && (y < vertical.minY() || y >= vertical.maxY())) {
            return false;
        }
        return containsColumn((int) Math.floor(x), (int) Math.floor(z));
    }

    /**
     * Whether one block column is part of this polygon, whatever the height.
     *
     * @param blockX the column's x
     * @param blockZ the column's z
     * @return whether its centre is inside the outline or on it
     */
    public boolean containsColumn(int blockX, int blockZ) {
        double px = blockX + 0.5;
        double pz = blockZ + 0.5;
        boolean inside = false;
        int count = vertices.size();
        for (int index = 0, previous = count - 1; index < count; previous = index++) {
            Vertex from = vertices.get(previous);
            Vertex to = vertices.get(index);
            if (onEdge(from, to, px, pz)) {
                return true;
            }
            if (crosses(from, to, pz) && px < crossingX(from, to, pz)) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * How many block columns the polygon covers.
     *
     * <p>Exactly the columns {@link #containsColumn} says yes to, counted a row at a time: each
     * row sorts the places the outline crosses it once instead of asking every edge about every
     * block, so the cost follows the area rather than the area times the vertices.
     *
     * @return the number of columns
     */
    public long columnCount() {
        HorizontalBounds bounds = horizontalBounds();
        int minX = (int) bounds.minX();
        int maxX = (int) bounds.maxX() - 1;
        int width = maxX - minX + 1;
        int count = vertices.size();
        double[] crossings = new double[count];
        BitSet onOutline = new BitSet(width);
        long total = 0L;
        for (int row = (int) bounds.minZ(); row < (int) bounds.maxZ(); row++) {
            double pz = row + 0.5;
            int crossed = 0;
            onOutline.clear();
            for (int index = 0, previous = count - 1; index < count; previous = index++) {
                Vertex from = vertices.get(previous);
                Vertex to = vertices.get(index);
                if (crosses(from, to, pz)) {
                    crossings[crossed++] = crossingX(from, to, pz);
                }
                markOutline(from, to, pz, minX, maxX, onOutline);
            }
            Arrays.sort(crossings, 0, crossed);
            int passed = 0;
            for (int column = minX; column <= maxX; column++) {
                double px = column + 0.5;
                // The same comparison containsColumn makes, so the two can never disagree.
                while (passed < crossed && !(px < crossings[passed])) {
                    passed++;
                }
                if (((crossed - passed) & 1) == 1 || onOutline.get(column - minX)) {
                    total++;
                }
            }
        }
        return total;
    }

    @Override
    public @NotNull HorizontalBounds horizontalBounds() {
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Vertex vertex : vertices) {
            minX = Math.min(minX, vertex.x());
            minZ = Math.min(minZ, vertex.z());
            maxX = Math.max(maxX, vertex.x());
            maxZ = Math.max(maxZ, vertex.z());
        }
        // Whole blocks: a column belongs by its centre, so the bounds run from the block the
        // lowest vertex is in to the far side of the block the highest one is in.
        return new HorizontalBounds(Math.floor(minX), Math.floor(maxX) + 1.0,
                Math.floor(minZ), Math.floor(maxZ) + 1.0);
    }

    @Override
    public @NotNull Optional<VerticalBounds> verticalBounds() {
        return Optional.ofNullable(vertical);
    }

    /**
     * Whether an edge crosses the row at {@code pz}, by the half-open rule that keeps a vertex
     * on the row from being counted twice.
     */
    private static boolean crosses(Vertex from, Vertex to, double pz) {
        return (from.z() > pz) != (to.z() > pz);
    }

    /** Where an edge that {@link #crosses} the row meets it. */
    private static double crossingX(Vertex from, Vertex to, double pz) {
        return (to.x() - from.x()) * (pz - from.z()) / (to.z() - from.z()) + from.x();
    }

    /** Whether a point lies on an edge, ends included. */
    private static boolean onEdge(Vertex from, Vertex to, double px, double pz) {
        if (Math.abs(from.z() - to.z()) <= EDGE_TOLERANCE) {
            return Math.abs(pz - from.z()) <= EDGE_TOLERANCE
                    && px >= Math.min(from.x(), to.x()) - EDGE_TOLERANCE
                    && px <= Math.max(from.x(), to.x()) + EDGE_TOLERANCE;
        }
        if (pz < Math.min(from.z(), to.z()) || pz > Math.max(from.z(), to.z())) {
            return false;
        }
        double x = from.x() + (pz - from.z()) * (to.x() - from.x()) / (to.z() - from.z());
        return Math.abs(px - x) <= EDGE_TOLERANCE;
    }

    /** Marks the columns of one row an edge runs through, by the same test as {@link #onEdge}. */
    private static void markOutline(Vertex from, Vertex to, double pz, int minX, int maxX, BitSet marks) {
        if (Math.abs(from.z() - to.z()) <= EDGE_TOLERANCE) {
            if (Math.abs(pz - from.z()) > EDGE_TOLERANCE) {
                return;
            }
            int first = Math.max(minX, (int) Math.ceil(Math.min(from.x(), to.x()) - EDGE_TOLERANCE - 0.5));
            int last = Math.min(maxX, (int) Math.floor(Math.max(from.x(), to.x()) + EDGE_TOLERANCE - 0.5));
            if (first <= last) {
                marks.set(first - minX, last - minX + 1);
            }
            return;
        }
        if (pz < Math.min(from.z(), to.z()) || pz > Math.max(from.z(), to.z())) {
            return;
        }
        double x = from.x() + (pz - from.z()) * (to.x() - from.x()) / (to.z() - from.z());
        int column = (int) Math.floor(x);
        if (column >= minX && column <= maxX && Math.abs(column + 0.5 - x) <= EDGE_TOLERANCE) {
            marks.set(column - minX);
        }
    }

    /**
     * One corner of the outline, on the horizontal plane.
     *
     * @param x the x coordinate
     * @param z the z coordinate
     */
    public record Vertex(double x, double z) {

        /** Validates finite coordinates. */
        public Vertex {
            if (!Double.isFinite(x) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Polygon vertices must be finite");
            }
        }

        /**
         * The vertex in the centre of a block column, which is where a clicked block puts it.
         *
         * @param blockX the block's x
         * @param blockZ the block's z
         * @return the vertex
         */
        public static @NotNull Vertex block(int blockX, int blockZ) {
            return new Vertex(blockX + 0.5, blockZ + 0.5);
        }
    }
}
