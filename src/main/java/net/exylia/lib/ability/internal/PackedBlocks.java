package net.exylia.lib.ability.internal;

import java.util.Arrays;

/**
 * Block positions packed into longs in one primitive array: no {@code Location}
 * or {@code Block} per planned block, and a cursor for the per-tick budget.
 *
 * <p>Packing is Minecraft's own: 26 bits of x, 26 of z, 12 of y, so every
 * position a world can hold fits. Not thread safe; one operation owns it.
 */
public final class PackedBlocks {

    private long[] positions;
    private int size;
    private int cursor;

    public PackedBlocks(int expected) {
        this.positions = new long[Math.max(8, expected)];
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    public static int x(long packed) {
        return (int) (packed >> 38);
    }

    public static int y(long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int z(long packed) {
        return (int) (packed << 26 >> 38);
    }

    public void add(int x, int y, int z) {
        if (size == positions.length) positions = Arrays.copyOf(positions, size * 2);
        positions[size++] = pack(x, y, z);
    }

    public int size() {
        return size;
    }

    public long get(int index) {
        return positions[index];
    }

    /** Whether the budget loop has blocks left. */
    public boolean hasNext() {
        return cursor < size;
    }

    /** The next block for the budget loop. */
    public long next() {
        return positions[cursor++];
    }

    /** How many the budget loop has not reached yet. */
    public int remaining() {
        return size - cursor;
    }

    /**
     * Hands over up to {@code budget} blocks the loop has not reached: one
     * tick's share of an area break.
     *
     * @return how many were handed over
     */
    public int drain(int budget, java.util.function.LongConsumer action) {
        int spent = 0;
        while (spent < budget && cursor < size) {
            action.accept(positions[cursor++]);
            spent++;
        }
        return spent;
    }

    /** Drops what the budget loop has not reached: an aborted operation. */
    public void clearRemaining() {
        cursor = size;
    }
}
