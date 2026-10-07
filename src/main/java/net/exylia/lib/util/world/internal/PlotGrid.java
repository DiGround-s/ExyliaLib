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
    // ponytail: one key per cell ever used, a few thousand for a busy run; the
    // cells inside firstOpenRing could be dropped and implied if that ever matters.
    private final Set<Long> taken = new HashSet<>();
    private int firstOpenRing;

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
    public synchronized int[] reserve(int sizeX, int sizeZ) {
        if (sizeX < 1 || sizeZ < 1) {
            throw new IllegalArgumentException("A plot needs a positive size, got " + sizeX + "x" + sizeZ);
        }
        int cellsX = cells(sizeX);
        int cellsZ = cells(sizeZ);
        // Starts at the first ring with a free cell: every ring inside it is full,
        // and since no cell is ever handed back, it stays full. Scanning from the
        // origin made each reserve cost every cell ever used.
        for (int ring = firstOpenRing; ; ring++) {
            // Only the ring's perimeter, in the order the full-square scan visited it.
            for (int cx = -ring; cx <= ring; cx++) {
                boolean edge = Math.abs(cx) == ring;
                for (int cz = -ring; cz <= ring; cz += edge ? 1 : 2 * ring) {
                    if (isFree(cx, cz, cellsX, cellsZ)) {
                        take(cx, cz, cellsX, cellsZ);
                        advance();
                        return new int[]{cx * CELL, cz * CELL};
                    }
                }
            }
        }
    }

    /**
     * The first ring that still has a free cell. Exposed for tests: it is what
     * keeps a reserve from rescanning every ring already used.
     *
     * @return the ring index, 0 being the origin cell
     */
    public synchronized int firstOpenRing() {
        return firstOpenRing;
    }

    /** Moves the cursor past every ring that is now full. */
    private void advance() {
        while (ringFull(firstOpenRing)) {
            firstOpenRing++;
        }
    }

    private boolean ringFull(int ring) {
        for (int cx = -ring; cx <= ring; cx++) {
            boolean edge = Math.abs(cx) == ring;
            for (int cz = -ring; cz <= ring; cz += edge ? 1 : 2 * ring) {
                if (!taken.contains(key(cx, cz))) {
                    return false;
                }
            }
        }
        return true;
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
