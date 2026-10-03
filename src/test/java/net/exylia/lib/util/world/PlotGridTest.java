package net.exylia.lib.util.world;

import net.exylia.lib.util.world.internal.PlotGrid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
