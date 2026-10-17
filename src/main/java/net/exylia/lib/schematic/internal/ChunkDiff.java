package net.exylia.lib.schematic.internal;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Which blocks of one chunk a paste actually has to write.
 *
 * <p>Generic over the block value so the decision is tested with strings and
 * no server: in production {@code T} is {@code BlockData}, read from a
 * {@code ChunkSnapshot} captured on the chunk's own thread and compared here,
 * off it. An arena where three hundred blocks changed costs three hundred
 * writes, not the whole box.
 *
 * <p>A change is two ints: the position packed as
 * {@code localX | localZ << 4 | (y - minY) << 8} and the palette id to write.
 * Twelve bits of height is more than the 4064 blocks a world may span.
 */
final class ChunkDiff {

    private ChunkDiff() {
        throw new AssertionError("No instances.");
    }

    /** What a chunk currently holds, at chunk-local x and z. */
    @FunctionalInterface
    interface Live<T> {
        T at(int localX, int y, int localZ);
    }

    /**
     * Compares a schematic with one chunk.
     *
     * @param schematic the schematic
     * @param palette   the schematic's palette resolved to block values; a
     *                  {@code null} slot is a state the server does not know,
     *                  and the block is left alone
     * @param chunkX    the chunk
     * @param chunkZ    the chunk
     * @param originX   where relative block (0,0,0) lands in the world
     * @param originY   where it lands
     * @param originZ   where it lands
     * @param minY      the world's lowest block, inclusive
     * @param maxY      the world's height limit, exclusive
     * @param live      what the chunk holds now
     * @return the changes, two ints each
     */
    static <T> int @NotNull [] diff(SpongeSchematic schematic, T[] palette, int chunkX, int chunkZ,
                                    int originX, int originY, int originZ, int minY, int maxY,
                                    Live<T> live) {
        int fromX = Math.max(originX, chunkX << 4);
        int toX = Math.min(originX + schematic.width() - 1, (chunkX << 4) + 15);
        int fromZ = Math.max(originZ, chunkZ << 4);
        int toZ = Math.min(originZ + schematic.length() - 1, (chunkZ << 4) + 15);
        int fromY = Math.max(originY, minY);
        int toY = Math.min(originY + schematic.height() - 1, maxY - 1);
        int[] changes = new int[64];
        int size = 0;
        for (int y = fromY; y <= toY; y++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int x = fromX; x <= toX; x++) {
                    int state = schematic.stateAt(schematic.index(x - originX, y - originY, z - originZ));
                    T target = palette[state];
                    if (target == null || target.equals(live.at(x & 15, y, z & 15))) {
                        continue;
                    }
                    if (size == changes.length) {
                        changes = Arrays.copyOf(changes, size * 2);
                    }
                    changes[size++] = (x & 15) | (z & 15) << 4 | (y - minY) << 8;
                    changes[size++] = state;
                }
            }
        }
        return Arrays.copyOf(changes, size);
    }

    /** One long per chunk, for grouping block entities and entities by chunk. */
    static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
