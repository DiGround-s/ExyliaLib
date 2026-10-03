package net.exylia.lib.util.world.internal;

import java.util.HashSet;
import java.util.Set;

/**
 * Hands out non-overlapping rectangles of the temporary world.
 *
 * <p>The world is cut into square cells of {@link #CELL} blocks, so every plot
 * starts on a chunk boundary. A plot takes as many cells as its size plus the
 * gap needs, which leaves at least that much empty space between any two
 * plots. Cells are searched ring by ring outwards from the origin, so the
 * coordinates in use stay as small as the number of plots allows.
 *
 * <p>A cell is never handed out twice in one run. What a released plot leaves
 * behind is never seen again, so a new plot always lands on untouched void
 * and nobody has to clear anything. The world is wiped on every start.
 *
 * <p>Thread-safe.
 */
public final class PlotGrid {

    /** Side of a cell, in blocks. A multiple of 16, so plots are chunk aligned. */
    public static final int CELL = 64;

    private final int gap;
    private final Set<Long> taken = new HashSet<>();

    /**
     * @param gap the minimum empty space between two plots, in blocks
     */
    public PlotGrid(int gap) {
        this.gap = Math.max(0, gap);
    }

    /**
     * Reserves room for a plot.
     *
     * @param sizeX the plot's size along X, in blocks
     * @param sizeZ the plot's size along Z, in blocks
     * @return the plot's minimum corner, as {@code {x, z}} in blocks
     */
    // ponytail: linear ring scan from the origin, O(cells in use) per call; keep a
    // free-ring cursor if a single run ever holds tens of thousands of plots.
    public synchronized int[] reserve(int sizeX, int sizeZ) {
        if (sizeX < 1 || sizeZ < 1) {
            throw new IllegalArgumentException("A plot needs a positive size, got " + sizeX + "x" + sizeZ);
        }
        int cellsX = cells(sizeX);
        int cellsZ = cells(sizeZ);
        for (int ring = 0; ; ring++) {
            for (int cx = -ring; cx <= ring; cx++) {
                for (int cz = -ring; cz <= ring; cz++) {
                    if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) {
                        continue;
                    }
                    if (isFree(cx, cz, cellsX, cellsZ)) {
                        take(cx, cz, cellsX, cellsZ);
                        return new int[]{cx * CELL, cz * CELL};
                    }
                }
            }
        }
    }

    private int cells(int size) {
        return (size + gap + CELL - 1) / CELL;
    }

    private boolean isFree(int cx, int cz, int cellsX, int cellsZ) {
        for (int x = cx; x < cx + cellsX; x++) {
            for (int z = cz; z < cz + cellsZ; z++) {
                if (taken.contains(key(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void take(int cx, int cz, int cellsX, int cellsZ) {
        for (int x = cx; x < cx + cellsX; x++) {
            for (int z = cz; z < cz + cellsZ; z++) {
                taken.add(key(x, z));
            }
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
