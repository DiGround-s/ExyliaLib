package net.exylia.lib.region.internal;

/**
 * Open-addressed set of packed block positions, held as primitive {@code long}s.
 *
 * <h2>Why not a {@code Set<BlockPosition>}</h2>
 * This is the only structure in the library that can grow to one entry per block a
 * player has ever placed, so its per-entry cost is the module's memory footprint.
 * A {@code HashSet} of boxed keys pays for a node, a boxed {@code Long}, and a
 * table slot on every block; a flat table of primitives pays for one slot. The
 * difference is roughly fifty-six bytes against sixteen at the load factor below,
 * which is the difference between a busy arena costing megabytes and costing very
 * little.
 *
 * <p>Positions are packed as 26 bits of x, 26 bits of z and 12 bits of y. That
 * covers ±33.5 million horizontally, beyond the furthest world border a server can
 * set, and the full ±2048 build height. Packing is exact and reversible, so no key
 * can collide with a different position.
 *
 * <p>Deletion shifts the probe chain backwards rather than leaving tombstones: a
 * region whose blocks are placed and broken all day would otherwise degrade into a
 * table of gravestones that only a rehash could clear.
 *
 * <p>Not thread-safe. Callers hold the instance's monitor, which is uncontended in
 * practice because a region belongs to one world and, on Folia, to one region
 * thread.
 */
final class PositionSet {

    /** Kept low: probe length is what this structure is bought for, not density. */
    private static final float LOAD_FACTOR = 0.6f;

    private long[] keys;
    private int mask;
    private int threshold;
    private int size;
    /** Zero is the empty marker and also a legal position, so it is held apart. */
    private boolean hasZero;

    PositionSet() {
        this(16);
    }

    private PositionSet(int capacity) {
        this.keys = new long[capacity];
        this.mask = capacity - 1;
        this.threshold = (int) (capacity * LOAD_FACTOR);
    }

    /** Packs a block position into one exact reversible key. */
    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | ((long) y & 0xFFFL);
    }

    /** Adds a position, returning whether it was absent. */
    boolean add(long key) {
        if (key == 0L) {
            if (hasZero) return false;
            hasZero = true;
            size++;
            return true;
        }
        int slot = slotOf(key);
        while (keys[slot] != 0L) {
            if (keys[slot] == key) return false;
            slot = (slot + 1) & mask;
        }
        keys[slot] = key;
        if (++size >= threshold) grow();
        return true;
    }

    /** Whether a position is present. */
    boolean contains(long key) {
        if (key == 0L) return hasZero;
        int slot = slotOf(key);
        long current;
        while ((current = keys[slot]) != 0L) {
            if (current == key) return true;
            slot = (slot + 1) & mask;
        }
        return false;
    }

    /** Removes a position, returning whether it was present. */
    boolean remove(long key) {
        if (key == 0L) {
            if (!hasZero) return false;
            hasZero = false;
            size--;
            return true;
        }
        int slot = slotOf(key);
        long current;
        while ((current = keys[slot]) != 0L) {
            if (current == key) {
                size--;
                shiftBack(slot);
                return true;
            }
            slot = (slot + 1) & mask;
        }
        return false;
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    private int slotOf(long key) {
        return (int) mix(key) & mask;
    }

    /**
     * Fibonacci-style avalanche.
     *
     * <p>Packed positions are anything but random in their low bits: neighbouring
     * blocks differ by one in y, which is the bottom twelve bits. Masking the raw
     * key would file a whole column into a handful of slots.
     */
    private static long mix(long key) {
        long hash = key * 0x9E3779B97F4A7C15L;
        return hash ^ (hash >>> 32);
    }

    /**
     * Closes the hole left by a removal by pulling forward every later key whose
     * ideal slot is at or before it, which keeps every probe chain unbroken
     * without ever writing a tombstone.
     */
    private void shiftBack(int hole) {
        while (true) {
            int candidate = (hole + 1) & mask;
            long current;
            while (true) {
                current = keys[candidate];
                if (current == 0L) {
                    keys[hole] = 0L;
                    return;
                }
                int ideal = slotOf(current);
                if (hole <= candidate
                        ? (hole >= ideal || ideal > candidate)
                        : (hole >= ideal && ideal > candidate)) {
                    break;
                }
                candidate = (candidate + 1) & mask;
            }
            keys[hole] = current;
            hole = candidate;
        }
    }

    private void grow() {
        long[] old = keys;
        int capacity = old.length << 1;
        keys = new long[capacity];
        mask = capacity - 1;
        threshold = (int) (capacity * LOAD_FACTOR);
        for (long key : old) {
            if (key == 0L) continue;
            int slot = slotOf(key);
            while (keys[slot] != 0L) {
                slot = (slot + 1) & mask;
            }
            keys[slot] = key;
        }
    }
}
