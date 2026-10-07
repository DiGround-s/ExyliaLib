package net.exylia.lib.util.world;

import net.exylia.lib.util.world.internal.PlotGrid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlotGridTest {

    private static final int GAP = 64;

    @Test
    void firstPlotStartsAtTheOrigin() {
        int[] corner = new PlotGrid(GAP).reserve(10, 10);
        assertEquals(0, corner[0]);
        assertEquals(0, corner[1]);
    }

    @Test
    void plotsNeverOverlapAndKeepTheirGap() {
        PlotGrid grid = new PlotGrid(GAP);
        int[][] sizes = {{10, 10}, {200, 40}, {33, 300}, {64, 64}, {1, 1}, {500, 500}, {70, 20}};
        List<int[]> plots = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            for (int[] size : sizes) {
                int[] corner = grid.reserve(size[0], size[1]);
                assertEquals(0, Math.floorMod(corner[0], 16), "chunk aligned");
                assertEquals(0, Math.floorMod(corner[1], 16), "chunk aligned");
                plots.add(new int[]{corner[0], corner[1], size[0], size[1]});
            }
        }
        for (int i = 0; i < plots.size(); i++) {
            for (int j = i + 1; j < plots.size(); j++) {
                int[] a = plots.get(i);
                int[] b = plots.get(j);
                boolean apartX = a[0] + a[2] + GAP <= b[0] || b[0] + b[2] + GAP <= a[0];
                boolean apartZ = a[1] + a[3] + GAP <= b[1] || b[1] + b[3] + GAP <= a[1];
                assertTrue(apartX || apartZ, "plots " + i + " and " + j + " are closer than the gap");
            }
        }
    }

    @Test
    void coordinatesStayCompact() {
        PlotGrid grid = new PlotGrid(GAP);
        int far = 0;
        for (int i = 0; i < 400; i++) {
            int[] corner = grid.reserve(60, 60);
            far = Math.max(far, Math.max(Math.abs(corner[0]), Math.abs(corner[1])));
        }
        // 400 plots of 2x2 cells fit in a 40x40-cell square: about 1280 blocks out.
        assertTrue(far <= 1400, "plots spread to " + far);
    }

    @Test
    void placesExactlyWhereTheFullSquareScanDid() {
        PlotGrid grid = new PlotGrid(GAP);
        Reference reference = new Reference(GAP);
        int[][] sizes = {{10, 10}, {200, 40}, {33, 300}, {64, 64}, {1, 1}, {500, 500}, {70, 20}, {1, 1}, {1, 1}};
        for (int round = 0; round < 30; round++) {
            for (int[] size : sizes) {
                int[] expected = reference.reserve(size[0], size[1]);
                int[] actual = grid.reserve(size[0], size[1]);
                assertEquals(expected[0], actual[0], "x of plot " + round + "/" + size[0] + "x" + size[1]);
                assertEquals(expected[1], actual[1], "z of plot " + round + "/" + size[0] + "x" + size[1]);
            }
        }
    }

    @Test
    void skipsRingsThatAreAlreadyFull() {
        // Gap 0 and one block: one cell per plot, so ring r is full after (2r+1)^2 plots.
        PlotGrid grid = new PlotGrid(0);
        for (int i = 0; i < 9 * 9; i++) {
            grid.reserve(1, 1);
        }
        assertEquals(5, grid.firstOpenRing(),
                "rings 0-4 are full; a reserve that starts below 5 rescans every cell ever used");
        grid.reserve(1, 1);
        assertEquals(5, grid.firstOpenRing(), "ring 5 still has room");
    }

    /** The original algorithm: every cell of the square, every ring from the origin. */
    private static final class Reference {
        private final int gap;
        private final Set<Long> taken = new HashSet<>();

        Reference(int gap) {
            this.gap = gap;
        }

        int[] reserve(int sizeX, int sizeZ) {
            int cellsX = (sizeX + gap + PlotGrid.CELL - 1) / PlotGrid.CELL;
            int cellsZ = (sizeZ + gap + PlotGrid.CELL - 1) / PlotGrid.CELL;
            for (int ring = 0; ; ring++) {
                for (int cx = -ring; cx <= ring; cx++) {
                    for (int cz = -ring; cz <= ring; cz++) {
                        if (Math.max(Math.abs(cx), Math.abs(cz)) != ring || !free(cx, cz, cellsX, cellsZ)) {
                            continue;
                        }
                        for (int x = cx; x < cx + cellsX; x++) {
                            for (int z = cz; z < cz + cellsZ; z++) {
                                taken.add(((long) x << 32) | (z & 0xFFFFFFFFL));
                            }
                        }
                        return new int[]{cx * PlotGrid.CELL, cz * PlotGrid.CELL};
                    }
                }
            }
        }

        private boolean free(int cx, int cz, int cellsX, int cellsZ) {
            for (int x = cx; x < cx + cellsX; x++) {
                for (int z = cz; z < cz + cellsZ; z++) {
                    if (taken.contains(((long) x << 32) | (z & 0xFFFFFFFFL))) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
