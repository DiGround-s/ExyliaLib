package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a palette's swaps into state ids, as this server numbers them. Names PacketEvents: reached only
 * after {@link PacketRuntime#isAvailable()} said yes.
 */
final class PaletteStates {

    private PaletteStates() {
    }

    static PaletteTable compile(Map<Material, Material> materials, Map<BlockData, Material> states,
                                Location at, List<String> rejected) {
        Map<Integer, Integer> swaps = new HashMap<>();
        for (Map.Entry<Material, Material> swap : materials.entrySet()) {
            Material from = swap.getKey();
            Material to = swap.getValue();
            if (from == to) continue;
            String why = PaletteRules.refuse(from, to);
            Map<Integer, Integer> these = new HashMap<>();
            if (why == null) {
                int first = id(from.createBlockData());
                StateType type = WrappedBlockState.getByGlobalId(first, false).getType();
                // A block's states are numbered one after another: walk out from its default state.
                int low = first;
                while (low > 1 && type.equals(WrappedBlockState.getByGlobalId(low - 1, false).getType())) low--;
                for (int id = low; why == null; id++) {
                    WrappedBlockState state = WrappedBlockState.getByGlobalId(id, false);
                    if (id > first && !type.equals(state.getType())) break;
                    why = add(SpigotConversionUtil.toBukkitBlockData(state), id, to, at, these);
                }
            }
            if (why == null) swaps.putAll(these);
            else rejected.add(from + " -> " + to + ": " + why);
        }
        for (Map.Entry<BlockData, Material> swap : states.entrySet()) {
            BlockData from = swap.getKey();
            String why = from.getMaterial() == swap.getValue() ? null : PaletteRules.refuse(from.getMaterial(), swap.getValue());
            Map<Integer, Integer> these = new HashMap<>();
            if (why == null) why = add(from, id(from), swap.getValue(), at, these);
            if (why == null) swaps.putAll(these);
            else rejected.add(from.getAsString() + " -> " + swap.getValue() + ": " + why);
        }
        return PaletteTable.of(swaps);
    }

    /** Adds one state's swap, its shared properties carried over; the reason when it may not be. */
    /** The block a table shows in place of {@code real}: {@code real} itself when it is not swapped. */
    static BlockData shown(PaletteTable table, BlockData real) {
        int id = SpigotConversionUtil.fromBukkitBlockData(real).getGlobalId();
        int to = table.remap(id);
        return to == id ? real : SpigotConversionUtil.toBukkitBlockData(WrappedBlockState.getByGlobalId(to, false));
    }

    private static String add(BlockData from, int fromId, Material to, Location at, Map<Integer, Integer> into) {
        BlockData shown = to.createBlockData();
        from.copyTo(shown);
        String why = PaletteRules.refuse(from, shown, at);
        if (why == null) into.put(fromId, id(shown));
        return why;
    }

    private static int id(BlockData data) {
        return SpigotConversionUtil.fromBukkitBlockData(data).getGlobalId();
    }
}
