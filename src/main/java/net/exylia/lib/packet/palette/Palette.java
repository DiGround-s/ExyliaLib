package net.exylia.lib.packet.palette;

import net.exylia.lib.packet.internal.PaletteTable;
import net.exylia.lib.packet.internal.Palettes;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

/**
 * Which blocks a player is shown as which others: oak planks as spruce planks, hay as dried kelp.
 * Immutable, and shared by any number of players (one per era, not one per player).
 *
 * <p>A material swap covers every state of the block, and the shown block keeps each property both
 * share: stairs keep their facing and half, slabs their type, logs their axis. A swap is accepted only
 * when the client predicts exactly what the server simulates, every state of it: the same collision
 * shape, the same friction, climbing and bouncing, no fluid and no block entity on either side, and no
 * waterlogged state shown as a block that cannot hold water. The rest are left out of the palette and
 * listed by {@link #rejected()}.
 *
 * <pre>{@code
 * Palette spruce = Palette.builder()
 *         .swap(Material.OAK_PLANKS, Material.SPRUCE_PLANKS)
 *         .swap(Material.OAK_STAIRS, Material.SPRUCE_STAIRS)
 *         .swap(Material.OAK_SLAB, Material.SPRUCE_SLAB)
 *         .worlds(Set.of("clicker"))
 *         .build();
 * }</pre>
 *
 * Build palettes once (on enable, after the worlds load), not per player: building resolves every
 * state of every block named.
 *
 * @since 1.636.0
 */
public final class Palette {

    private final PaletteTable table;
    private final Set<String> worlds;
    private final List<String> rejected;

    private Palette(PaletteTable table, Set<String> worlds, List<String> rejected) {
        this.table = table;
        this.worlds = worlds;
        this.rejected = rejected;
    }

    /** A palette from material swaps, in every world. */
    public static @NotNull Palette of(@NotNull Map<Material, Material> swaps) {
        return builder().swaps(swaps).build();
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** The worlds it applies in, by name; empty means every world. */
    public @NotNull Set<String> worlds() {
        return worlds;
    }

    /** Whether it applies in this world. */
    public boolean appliesIn(@NotNull World world) {
        return worlds.isEmpty() || worlds.contains(world.getName());
    }

    /** The swaps left out, each with the reason; empty when every swap was accepted. */
    public @NotNull List<String> rejected() {
        return rejected;
    }

    /** How many block states it swaps; {@code 0} when nothing was accepted (or PacketEvents is missing). */
    public int swappedStates() {
        return table.swapped();
    }

    /**
     * The block a player with this palette is shown in place of a real one: {@code real} itself when
     * this palette does not swap it, or when palettes do not work on this server. For drawing one area
     * in a palette with fake blocks ({@code AreaLooks#paint}). Any thread.
     *
     * @since 1.676.0
     */
    public @NotNull BlockData shown(@NotNull BlockData real) {
        return Palettes.shown(table, real);
    }

    PaletteTable table() {
        return table;
    }

    /** Collects swaps; {@link #build()} checks and resolves them. */
    public static final class Builder {

        private final Map<Material, Material> materials = new LinkedHashMap<>();
        private final Map<BlockData, Material> states = new LinkedHashMap<>();
        private Set<String> worlds = Set.of();

        private Builder() {
        }

        /** Every state of {@code from} is shown as {@code to}, shared properties kept. */
        public @NotNull Builder swap(@NotNull Material from, @NotNull Material to) {
            materials.put(from, to);
            return this;
        }

        /** This exact state is shown as {@code to}, shared properties kept; the other states are not touched. */
        public @NotNull Builder swap(@NotNull BlockData from, @NotNull Material to) {
            states.put(from.clone(), to);
            return this;
        }

        /** Adds every material swap of the map. */
        public @NotNull Builder swaps(@NotNull Map<Material, Material> swaps) {
            materials.putAll(swaps);
            return this;
        }

        /** Applies only in these worlds, by name; empty (the default) means every world. */
        public @NotNull Builder worlds(@NotNull Collection<String> names) {
            worlds = Set.copyOf(names);
            return this;
        }

        /**
         * Checks every swap and resolves it to this server's block states. Call it on the main (or
         * global) thread once the worlds are loaded.
         *
         * @throws IllegalStateException before any world is loaded
         */
        public @NotNull Palette build() {
            List<String> rejected = new ArrayList<>();
            PaletteTable table = Palettes.compile(materials, states, rejected);
            return new Palette(table, worlds, List.copyOf(rejected));
        }
    }
}
