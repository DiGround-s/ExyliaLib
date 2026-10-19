package net.exylia.lib.region.internal;

import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.Cylinder;
import net.exylia.lib.region.HorizontalBounds;
import net.exylia.lib.region.HorizontalCylinder;
import net.exylia.lib.region.Polygon;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.SelectionHeight;
import net.exylia.lib.region.SelectionMode;
import net.exylia.lib.region.Sphere;
import net.exylia.lib.region.VerticalBounds;
import net.exylia.lib.region.WorldIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each mode makes of the clicks, how big it says it is, and what it draws.
 */
class SelectionSketchTest {

    private static final WorldIdentity WORLD = new WorldIdentity(UUID.randomUUID(), "world");

    @Test
    @DisplayName("a circle reaches the block clicked on its edge, between the clicked heights")
    void circle() {
        RegionShape shape = SelectionSketch.shapeOf(twoClicks(SelectionMode.CIRCLE, SelectionHeight.NORMAL,
                at(0, 64, 0), at(5, 70, 0)));

        Cylinder cylinder = assertInstanceOf(Cylinder.class, shape);
        assertEquals(5.5, cylinder.radius());
        assertEquals(64, cylinder.minY());
        assertEquals(71, cylinder.maxY());
        assertTrue(cylinder.contains(5.5, 66, 0.5), "the edge block");
        assertFalse(cylinder.contains(6.5, 66, 0.5));

        RegionShape full = SelectionSketch.shapeOf(twoClicks(SelectionMode.CIRCLE, SelectionHeight.FULL,
                at(0, 64, 0), at(0, 64, -3)));
        assertEquals(new HorizontalCylinder(0.5, 0.5, 3.5), full);
    }

    @Test
    @DisplayName("a sphere is centred in the middle of its centre block")
    void sphere() {
        RegionShape shape = SelectionSketch.shapeOf(twoClicks(SelectionMode.SPHERE, SelectionHeight.NORMAL,
                at(10, 64, 10), at(10, 67, 10)));

        assertEquals(new Sphere(10.5, 64.5, 10.5, 3.5), shape);
    }

    @Test
    @DisplayName("an outline is a shape from its third point, spanning the clicked heights")
    void outline() {
        List<BlockPosition> two = List.of(at(0, 60, 0), at(6, 62, 0));
        assertNull(SelectionSketch.shapeOf(outline(SelectionHeight.NORMAL, two)));

        List<BlockPosition> three = List.of(at(0, 60, 0), at(6, 62, 0), at(0, 61, 6));
        Polygon prism = assertInstanceOf(Polygon.class, SelectionSketch.shapeOf(outline(SelectionHeight.NORMAL, three)));
        assertEquals(new VerticalBounds(60, 63), prism.vertical());
        assertNull(assertInstanceOf(Polygon.class,
                SelectionSketch.shapeOf(outline(SelectionHeight.FULL, three))).vertical());
    }

    @Test
    @DisplayName("every shape counts exactly the blocks it contains")
    void sizesMatchContainment() {
        List<RegionShape> shapes = List.of(
                SelectionSketch.twoPointShape(SelectionMode.CIRCLE, SelectionHeight.NORMAL, at(0, 0, 0), at(7, 3, 2)),
                SelectionSketch.twoPointShape(SelectionMode.CIRCLE, SelectionHeight.FULL, at(0, 0, 0), at(4, 0, 4)),
                SelectionSketch.twoPointShape(SelectionMode.SPHERE, SelectionHeight.NORMAL, at(0, 0, 0), at(6, 1, 0)),
                SelectionSketch.twoPointShape(SelectionMode.CUBOID, SelectionHeight.NORMAL, at(0, 0, 0), at(4, 2, 3)),
                SelectionSketch.outlineShape(SelectionHeight.NORMAL,
                        List.of(at(0, 0, 0), at(9, 2, 1), at(4, 1, 8))));
        for (RegionShape shape : shapes) {
            assertEquals(bruteForce(shape), SelectionSketch.sizeOf(shape), shape.toString());
        }
    }

    @Test
    @DisplayName("a box is drawn as its twelve edges, and a column as a ring with four posts")
    void boxLines() {
        List<Line> box = SelectionSketch.lines(twoClicks(SelectionMode.CUBOID, SelectionHeight.NORMAL,
                at(0, 0, 0), at(4, 4, 4)), null, 70.0);
        assertEquals(12, box.size());
        assertTrue(box.stream().allMatch(line -> line.tone() == Line.Tone.EDGE));

        List<Line> column = SelectionSketch.lines(twoClicks(SelectionMode.CUBOID, SelectionHeight.FULL,
                at(0, 0, 0), at(4, 90, 4)), null, 70.0);
        assertEquals(8, column.size());
        // Drawn around the viewer's height, not the clicked ones.
        assertTrue(column.stream().allMatch(line -> line.y1() >= 69.0 && line.y2() <= 72.0));
    }

    @Test
    @DisplayName("with one corner set, the box to the cursor is drawn as a ghost")
    void ghostBox() {
        List<Line> lines = SelectionSketch.lines(twoClicks(SelectionMode.CUBOID, SelectionHeight.NORMAL,
                at(0, 0, 0), null), at(5, 2, 5), 70.0);

        assertEquals(12, lines.stream().filter(line -> line.tone() == Line.Tone.EDGE).count(), "the corner block");
        assertEquals(12, lines.stream().filter(line -> line.tone() == Line.Tone.GHOST).count(), "the box to come");
    }

    @Test
    @DisplayName("a polygon draws its path, the closing edge, a marker per point and a bar to the cursor")
    void polygonLines() {
        SelectionSketch.State state = new SelectionSketch.State(SelectionMode.POLYGON, SelectionHeight.FULL,
                null, null, List.of(at(0, 0, 0), at(8, 0, 0), at(8, 0, 8)), false);

        List<Line> lines = SelectionSketch.lines(state, at(0, 0, 8), 64.0);

        assertEquals(5, count(lines, Line.Tone.EDGE), "two edges and three posts");
        assertEquals(1, count(lines, Line.Tone.CLOSING));
        assertEquals(3, count(lines, Line.Tone.ANCHOR));
        assertEquals(1, count(lines, Line.Tone.GHOST));
    }

    @Test
    @DisplayName("a bar points its display's length along the line it draws")
    void barsFaceTheirLine() {
        double[][] directions = {{1, 0, 0}, {0, 0, 1}, {0, 1, 0}, {-3, 4, 12}, {2, -1, -2}};
        for (double[] direction : directions) {
            Line line = Line.between(0, 0, 0, direction[0], direction[1], direction[2], Line.Tone.EDGE);
            double length = line.length();
            float[] axis = LineRenderer.facing(line, length).apply(new float[] {0f, 0f, 1f});
            assertEquals(direction[0] / length, axis[0], 1.0E-5);
            assertEquals(direction[1] / length, axis[1], 1.0E-5);
            assertEquals(direction[2] / length, axis[2], 1.0E-5);
        }
    }

    @Test
    @DisplayName("a bar is clipped to the box around the viewer, and one wholly inside comes back as itself")
    void clipping() {
        Line inside = Line.between(1, 1, 1, 2, 2, 2, Line.Tone.EDGE);
        assertTrue(inside == inside.clip(0, 0, 0, 10, 10, 10));

        Line across = Line.between(-10, 5, 5, 20, 5, 5, Line.Tone.EDGE);
        Line clipped = across.clip(0, 0, 0, 10, 10, 10);
        assertEquals(0.0, clipped.x1(), 1.0E-9);
        assertEquals(10.0, clipped.x2(), 1.0E-9);

        assertNull(Line.between(20, 20, 20, 30, 30, 30, Line.Tone.EDGE).clip(0, 0, 0, 10, 10, 10));
        assertNull(Line.marker(11, 5, 5, Line.Tone.ANCHOR).clip(0, 0, 0, 10, 10, 10));
    }

    @Test
    @DisplayName("every saved shape has an outline, a full-height one drawn at the viewer's height")
    void savedShapes() {
        for (RegionShape shape : List.of(
                SelectionSketch.twoPointShape(SelectionMode.SPHERE, SelectionHeight.NORMAL, at(0, 0, 0), at(9, 0, 0)),
                new HorizontalCylinder(0, 0, 50),
                SelectionSketch.outlineShape(SelectionHeight.FULL, List.of(at(0, 0, 0), at(9, 0, 0), at(0, 0, 9))))) {
            List<Line> lines = OutlineLines.of(shape, 100.0, Line.Tone.EDGE);
            assertFalse(lines.isEmpty(), shape.toString());
            if (shape.verticalBounds().isEmpty()) {
                assertTrue(lines.stream().allMatch(line -> Math.min(line.y1(), line.y2()) >= 99.0), shape.toString());
            }
        }
    }

    private static long count(List<Line> lines, Line.Tone tone) {
        return lines.stream().filter(line -> line.tone() == tone).count();
    }

    /** Every block around the shape, asked one by one; one layer for a shape with no heights. */
    private static long bruteForce(RegionShape shape) {
        HorizontalBounds horizontal = shape.horizontalBounds();
        Optional<VerticalBounds> vertical = shape.verticalBounds();
        int lowest = vertical.map(bounds -> (int) Math.floor(bounds.minY()) - 1).orElse(0);
        int highest = vertical.map(bounds -> (int) Math.ceil(bounds.maxY())).orElse(0);
        long total = 0L;
        for (int x = (int) Math.floor(horizontal.minX()) - 1; x <= (int) Math.ceil(horizontal.maxX()); x++) {
            for (int z = (int) Math.floor(horizontal.minZ()) - 1; z <= (int) Math.ceil(horizontal.maxZ()); z++) {
                for (int y = lowest; y <= highest; y++) {
                    if (shape.contains(x + 0.5, y + 0.5, z + 0.5)) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    private static SelectionSketch.State twoClicks(SelectionMode mode, SelectionHeight height,
                                                   BlockPosition first, BlockPosition second) {
        return new SelectionSketch.State(mode, height, first, second, List.of(), false);
    }

    private static SelectionSketch.State outline(SelectionHeight height, List<BlockPosition> points) {
        return new SelectionSketch.State(SelectionMode.POLYGON, height, null, null, points, false);
    }

    private static BlockPosition at(int x, int y, int z) {
        return new BlockPosition(WORLD, x, y, z);
    }
}
