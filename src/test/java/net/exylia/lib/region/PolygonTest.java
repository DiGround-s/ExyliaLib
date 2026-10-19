package net.exylia.lib.region;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A polygon of blocks: what it contains, how many columns it says it has, and how it is stored.
 */
class PolygonTest {

    @Test
    @DisplayName("the clicked corner blocks and every block on the outline are inside")
    void cornersAndEdgesAreInside() {
        // A right triangle through the centres of (0,0), (10,0) and (0,10).
        Polygon triangle = Polygon.columns(List.of(
                Polygon.Vertex.block(0, 0), Polygon.Vertex.block(10, 0), Polygon.Vertex.block(0, 10)));

        assertTrue(triangle.containsColumn(0, 0), "corner");
        assertTrue(triangle.containsColumn(10, 0), "corner");
        assertTrue(triangle.containsColumn(5, 0), "on the bottom edge");
        assertTrue(triangle.containsColumn(5, 5), "on the slanted edge");
        assertTrue(triangle.containsColumn(2, 3), "inside");
        assertFalse(triangle.containsColumn(6, 6), "past the slanted edge");
        assertFalse(triangle.containsColumn(-1, 0), "outside");

        // Any point of a column answers for the whole column.
        assertTrue(triangle.contains(0.01, 64, 0.99));
        assertTrue(triangle.contains(10.99, -30, 0.01));
    }

    @Test
    @DisplayName("a square of block centres is the square of blocks, corners included")
    void squareCountsWholeBlocks() {
        Polygon square = Polygon.prism(List.of(
                Polygon.Vertex.block(0, 0), Polygon.Vertex.block(4, 0),
                Polygon.Vertex.block(4, 4), Polygon.Vertex.block(0, 4)), 60, 63);

        assertEquals(25L, square.columnCount());
        assertEquals(new HorizontalBounds(0, 5, 0, 5), square.horizontalBounds());
        assertTrue(square.contains(2.5, 62.9, 2.5));
        assertFalse(square.contains(2.5, 63.0, 2.5), "the top is exclusive");
        assertFalse(square.contains(2.5, 59.9, 2.5));
    }

    @Test
    @DisplayName("the column count is exactly what containment says, for any outline")
    void countAgreesWithContainment() {
        // Random outlines, self-crossing ones included, against a brute force
        // walk of their bounds. The count is a scanline and containment asks
        // every edge; this is what keeps the two from ever drifting apart.
        Random random = new Random(7L);
        for (int round = 0; round < 200; round++) {
            int count = 3 + random.nextInt(9);
            List<Polygon.Vertex> vertices = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                vertices.add(Polygon.Vertex.block(random.nextInt(31) - 15, random.nextInt(31) - 15));
            }
            Polygon polygon = Polygon.columns(vertices);
            HorizontalBounds bounds = polygon.horizontalBounds();
            long brute = 0L;
            for (int x = (int) bounds.minX(); x < (int) bounds.maxX(); x++) {
                for (int z = (int) bounds.minZ(); z < (int) bounds.maxZ(); z++) {
                    if (polygon.containsColumn(x, z)) {
                        brute++;
                    }
                }
            }
            assertEquals(brute, polygon.columnCount(), "round " + round + ": " + vertices);
        }
    }

    @Test
    @DisplayName("a crossed outline follows the even-odd rule")
    void figureEight() {
        // A bow tie: two triangles meeting at the middle.
        Polygon bowTie = Polygon.columns(List.of(
                Polygon.Vertex.block(0, 0), Polygon.Vertex.block(10, 10),
                Polygon.Vertex.block(10, 0), Polygon.Vertex.block(0, 10)));

        assertTrue(bowTie.containsColumn(1, 5), "left wing");
        assertTrue(bowTie.containsColumn(9, 5), "right wing");
        assertFalse(bowTie.containsColumn(5, 1), "the gap below the knot");
    }

    @Test
    @DisplayName("an outline needs three points and no more than the cap")
    void vertexLimits() {
        assertThrows(IllegalArgumentException.class, () -> Polygon.columns(List.of(
                Polygon.Vertex.block(0, 0), Polygon.Vertex.block(1, 1))));
        List<Polygon.Vertex> tooMany = new ArrayList<>();
        for (int index = 0; index <= Polygon.MAX_VERTICES; index++) {
            tooMany.add(Polygon.Vertex.block(index, index % 2));
        }
        assertThrows(IllegalArgumentException.class, () -> Polygon.columns(tooMany));
        assertThrows(IllegalArgumentException.class, () -> new Polygon.Vertex(Double.NaN, 0));
    }

    @Test
    @DisplayName("a polygon survives being stored, with and without heights")
    void codecRoundTrip() {
        List<Polygon.Vertex> vertices = List.of(
                Polygon.Vertex.block(0, 0), Polygon.Vertex.block(8, 2), Polygon.Vertex.block(3, 9));
        WorldIdentity world = new WorldIdentity(UUID.randomUUID(), "world");
        for (Polygon shape : List.of(Polygon.prism(vertices, -10, 40), Polygon.columns(vertices))) {
            RegionSnapshot region = new RegionSnapshot(RegionId.parse("test:plaza"), "Test", world, shape, 0,
                    PolicySet.empty());

            RegionData data = RegionCodec.encode(region, List.of());
            assertEquals(RegionData.ShapeType.POLYGON, data.shapeType());
            assertEquals(shape, RegionCodec.decode(data, List.of()).shape());
        }
    }

    @Test
    @DisplayName("stored polygon data with a broken length is refused")
    void malformedData() {
        assertThrows(IllegalArgumentException.class, () -> new RegionData(RegionData.CURRENT_FORMAT_VERSION,
                "test:plaza", "Test", UUID.randomUUID(), "world", RegionData.ShapeType.POLYGON,
                new double[] {0, 0, 0, 1, 1, 2, 2}, 0, Map.of()));
    }
}
