package net.exylia.lib.packet.internal;

import java.util.Arrays;
import java.util.Map;

/**
 * One compiled block palette: the block state id the client is sent instead of each one the server
 * sends. An array indexed by state id, so a lookup on the network thread is one bounds check and one
 * read. Pure: no server, no PacketEvents.
 */
public final class PaletteTable {

    static final PaletteTable EMPTY = new PaletteTable(new int[0], 0);

    /** state id -> id sent; identity past the highest swapped id. */
    private final int[] shown;
    private final int swapped;

    private PaletteTable(int[] shown, int swapped) {
        this.shown = shown;
        this.swapped = swapped;
    }

    /** A table from state id to shown id; pairs mapping an id to itself are dropped. */
    static PaletteTable of(Map<Integer, Integer> swaps) {
        int highest = -1;
        int count = 0;
        for (Map.Entry<Integer, Integer> swap : swaps.entrySet()) {
            if (swap.getKey() < 0 || swap.getValue() < 0) throw new IllegalArgumentException("negative state id");
            if (swap.getKey().intValue() == swap.getValue().intValue()) continue;
            highest = Math.max(highest, swap.getKey());
            count++;
        }
        if (count == 0) return EMPTY;
        int[] shown = new int[highest + 1];
        Arrays.setAll(shown, id -> id);
        for (Map.Entry<Integer, Integer> swap : swaps.entrySet()) shown[swap.getKey()] = swap.getValue();
        return new PaletteTable(shown, count);
    }

    /** The id the client is sent in place of {@code id}. */
    public int remap(int id) {
        return id >= 0 && id < shown.length ? shown[id] : id;
    }

    /** How many block states this table swaps. */
    public int swapped() {
        return swapped;
    }

    public boolean isEmpty() {
        return swapped == 0;
    }
}
