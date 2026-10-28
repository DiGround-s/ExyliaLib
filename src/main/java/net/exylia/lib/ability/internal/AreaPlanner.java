package net.exylia.lib.ability.internal;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/**
 * Which blocks an area break takes, worked out once, before anything breaks.
 *
 * <p>Pure: it reads materials through a {@link TypeView}, so the whole plan is
 * tested without a world. No recursion: a vein is a bounded breadth-first walk
 * over a primitive queue, never a call per block.
 */
public final class AreaPlanner {

    /** The shapes it plans. */
    public enum Kind { CUBE, PLANE, VEIN }

    /** A world's materials by position; {@code World#getType(int, int, int)} live. */
    @FunctionalInterface
    public interface TypeView {
        @NotNull Material type(int x, int y, int z);
    }

    private AreaPlanner() {
        throw new AssertionError("No instances.");
    }

    /**
     * Plans an area around an origin, the origin itself left out (its own
     * event breaks it).
     *
     * @param kind      the shape
     * @param radius    cube and plane: blocks from the origin, so 1 is 3 wide
     * @param depth     plane: extra layers along the look axis, so 0 is one layer
     * @param max       the most blocks planned, whatever the shape
     * @param ox        origin x
     * @param oy        origin y
     * @param oz        origin z
     * @param lx        look axis x: -1, 0 or 1, exactly one axis non-zero
     * @param ly        look axis y
     * @param lz        look axis z
     * @param view      the materials
     * @param breakable whether a material may go (air, liquids, unbreakables and the blacklist answer no)
     * @param out       where the plan goes
     * @return how many were planned
     */
    public static int plan(@NotNull Kind kind, int radius, int depth, int max, int ox, int oy, int oz,
                           int lx, int ly, int lz, @NotNull TypeView view,
                           @NotNull Predicate<Material> breakable, @NotNull PackedBlocks out) {
        int cap = Math.max(0, max);
        int r = Math.max(0, radius);
        switch (kind) {
            case CUBE -> {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if ((dx | dy | dz) == 0) continue;
                            if (out.size() >= cap) return out.size();
                            take(ox + dx, oy + dy, oz + dz, view, breakable, out);
                        }
                    }
                }
            }
            case PLANE -> {
                // Two axes across the look axis; layers go along it, away from the breaker.
                // Looking up or down spans x and z; along x spans y and z; along z spans x and y.
                int ax = lx != 0 ? 0 : 1, ay = lx != 0 ? 1 : 0, az = 0;
                int bx = 0, by = lz != 0 ? 1 : 0, bz = lz != 0 ? 0 : 1;
                for (int layer = 0; layer <= Math.max(0, depth); layer++) {
                    for (int a = -r; a <= r; a++) {
                        for (int b = -r; b <= r; b++) {
                            int x = ox + lx * layer + ax * a + bx * b;
                            int y = oy + ly * layer + ay * a + by * b;
                            int z = oz + lz * layer + az * a + bz * b;
                            if (x == ox && y == oy && z == oz) continue;
                            if (out.size() >= cap) return out.size();
                            take(x, y, z, view, breakable, out);
                        }
                    }
                }
            }
            case VEIN -> vein(ox, oy, oz, cap, view, breakable, out);
        }
        return out.size();
    }

    private static void take(int x, int y, int z, TypeView view, Predicate<Material> breakable, PackedBlocks out) {
        if (breakable.test(view.type(x, y, z))) out.add(x, y, z);
    }

    /** Same material as the origin, 26 neighbours, breadth first, bounded by {@code cap}. */
    private static void vein(int ox, int oy, int oz, int cap, TypeView view, Predicate<Material> breakable,
                             PackedBlocks out) {
        Material ore = view.type(ox, oy, oz);
        if (!breakable.test(ore) || cap == 0) return;
        LongSet seen = new LongSet(cap * 4);
        seen.add(PackedBlocks.pack(ox, oy, oz));
        // The queue is the plan itself: index i is expanded after index i-1.
        int head = -1;
        int cx = ox, cy = oy, cz = oz;
        while (true) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if ((dx | dy | dz) == 0) continue;
                        int x = cx + dx, y = cy + dy, z = cz + dz;
                        if (!seen.add(PackedBlocks.pack(x, y, z))) continue;
                        if (view.type(x, y, z) != ore) continue;
                        out.add(x, y, z);
                        if (out.size() >= cap) return;
                    }
                }
            }
            head++;
            if (head >= out.size()) return;
            long next = out.get(head);
            cx = PackedBlocks.x(next);
            cy = PackedBlocks.y(next);
            cz = PackedBlocks.z(next);
        }
    }

    /** An open-addressing set of longs: the vein's visited set without boxing. */
    static final class LongSet {
        private static final long EMPTY = Long.MIN_VALUE;
        private long[] slots;
        private int size;

        LongSet(int expected) {
            int capacity = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
            slots = new long[capacity];
            java.util.Arrays.fill(slots, EMPTY);
        }

        /** @return whether it was not there yet */
        boolean add(long value) {
            if (size * 2 >= slots.length) grow();
            int mask = slots.length - 1;
            int index = (int) (mix(value) & mask);
            while (slots[index] != EMPTY) {
                if (slots[index] == value) return false;
                index = (index + 1) & mask;
            }
            slots[index] = value;
            size++;
            return true;
        }

        private void grow() {
            long[] old = slots;
            slots = new long[old.length * 2];
            java.util.Arrays.fill(slots, EMPTY);
            size = 0;
            for (long value : old) {
                if (value != EMPTY) add(value);
            }
        }

        private static long mix(long value) {
            long h = value * 0x9E3779B97F4A7C15L;
            return h ^ (h >>> 32);
        }
    }
}
