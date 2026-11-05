package net.exylia.lib.block.internal;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import net.exylia.lib.task.Tasks;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The record behind {@code PlacedBlocks}: packed positions in each chunk's
 * persistent data.
 *
 * <p>A position is one {@code int}: four bits of chunk-local x, four of z and
 * twenty-four of y, so every build height fits and nothing collides. The array
 * keeps placement order, which is what lets a full chunk forget its oldest
 * entry. Read and written whole on each change of a tracked block; a chunk
 * holds a handful in practice and 4096 at most, and an untracked material
 * never reaches it.
 */
public final class PlacedBlockTracker implements Listener {

    static final int MAX_PER_CHUNK = 4096;
    private static final int[] NONE = new int[0];

    private static final Map<String, Set<Material>> BY_PLUGIN = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> CUSTOM_BY_PLUGIN = new ConcurrentHashMap<>();
    private static volatile Set<Material> tracked = Set.of();
    private static volatile Set<String> trackedCustom = Set.of();
    private static volatile NamespacedKey key;

    public PlacedBlockTracker(@NotNull Plugin library) {
        key = new NamespacedKey(library, "placed_blocks");
        // A provider places its block itself and may never fire the vanilla
        // event; its own event can fire before the block is written, so the
        // check waits a tick.
        CustomBlockIds.listenPlacements(library, block -> {
            if (trackedCustom.isEmpty()) return;
            Tasks.of(library).runAtLocationLater(block.getLocation(), 1L, () -> recordCustom(block));
        });
    }

    /** Starts recording these custom block ids for a plugin, replacing what it asked for before. */
    public static synchronized void trackCustom(@NotNull String pluginName, @NotNull Collection<String> ids) {
        Set<String> normalized = new HashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) normalized.add(normalize(id));
        }
        if (normalized.isEmpty()) {
            CUSTOM_BY_PLUGIN.remove(pluginName);
        } else {
            CUSTOM_BY_PLUGIN.put(pluginName, Set.copyOf(normalized));
        }
        rebuild();
    }

    /** An id as it is compared: lower case, with the {@code ia:} and {@code ce:} aliases spelled out. */
    static String normalize(String id) {
        String lower = id.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("ia:")) return "itemsadder:" + lower.substring(3);
        if (lower.startsWith("ce:")) return "craftengine:" + lower.substring(3);
        return lower;
    }

    private static void recordCustom(Block block) {
        Set<String> ids = trackedCustom;
        if (ids.isEmpty()) return;
        String id = CustomBlockIds.id(block);
        if (id != null && ids.contains(normalize(id))) {
            change(block, true);
        }
    }

    public static synchronized void track(@NotNull String pluginName, @NotNull Collection<Material> materials) {
        if (materials.isEmpty()) {
            BY_PLUGIN.remove(pluginName);
        } else {
            BY_PLUGIN.put(pluginName, EnumSet.copyOf(materials));
        }
        rebuild();
    }

    public static synchronized void release(@NotNull String pluginName) {
        boolean materials = BY_PLUGIN.remove(pluginName) != null;
        boolean custom = CUSTOM_BY_PLUGIN.remove(pluginName) != null;
        if (materials || custom) {
            rebuild();
        }
    }

    private static void rebuild() {
        Set<Material> union = EnumSet.noneOf(Material.class);
        BY_PLUGIN.values().forEach(union::addAll);
        tracked = union.isEmpty() ? Set.of() : union;
        Set<String> custom = new HashSet<>();
        CUSTOM_BY_PLUGIN.values().forEach(custom::addAll);
        trackedCustom = custom.isEmpty() ? Set.of() : Set.copyOf(custom);
    }

    /** Whether this block's records can matter: a tracked material, or any custom id tracked. */
    private static boolean watched(Block block) {
        return tracked.contains(block.getType()) || !trackedCustom.isEmpty();
    }

    public static boolean placed(@NotNull Block block) {
        NamespacedKey current = key;
        return current != null && indexOf(read(block.getChunk(), current), pack(block)) >= 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (tracked.contains(block.getType())) {
            change(block, true);
        } else if (!trackedCustom.isEmpty()) {
            recordCustom(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (watched(block)) {
            change(block, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    /**
     * Moves the records of pushed blocks one step along the push.
     *
     * <p>All are forgotten before any is written back, or a block moving into
     * the place another just left would be erased by that one's removal.
     */
    private void move(List<Block> blocks, BlockFace direction) {
        if ((tracked.isEmpty() && trackedCustom.isEmpty()) || blocks.isEmpty()) return;
        List<Block> recorded = new ArrayList<>(0);
        for (Block block : blocks) {
            if (watched(block) && placed(block)) {
                recorded.add(block);
            }
        }
        for (Block block : recorded) {
            change(block, false);
        }
        for (Block block : recorded) {
            change(block.getRelative(direction), true);
        }
    }

    private static void change(Block block, boolean add) {
        NamespacedKey current = key;
        if (current == null) return;
        Chunk chunk = block.getChunk();
        int[] before = read(chunk, current);
        int packed = pack(block);
        int[] after = add ? with(before, packed) : without(before, packed);
        if (after == before) return;
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        if (after.length == 0) {
            data.remove(current);
        } else {
            data.set(current, PersistentDataType.INTEGER_ARRAY, after);
        }
    }

    private static int[] read(Chunk chunk, NamespacedKey current) {
        int[] stored = chunk.getPersistentDataContainer().get(current, PersistentDataType.INTEGER_ARRAY);
        return stored == null ? NONE : stored;
    }

    private static int pack(Block block) {
        return pack(block.getX(), block.getY(), block.getZ());
    }

    static int pack(int x, int y, int z) {
        return (x & 0xF) << 28 | (z & 0xF) << 24 | (y & 0xFFFFFF);
    }

    static int indexOf(int[] positions, int packed) {
        for (int index = 0; index < positions.length; index++) {
            if (positions[index] == packed) return index;
        }
        return -1;
    }

    /** The positions with one more, dropping the oldest when full; the same array when already there. */
    static int[] with(int[] positions, int packed) {
        if (indexOf(positions, packed) >= 0) return positions;
        int drop = Math.max(0, positions.length - (MAX_PER_CHUNK - 1));
        int[] grown = Arrays.copyOfRange(positions, drop, positions.length + 1);
        grown[grown.length - 1] = packed;
        return grown;
    }

    /** The positions with one fewer; the same array when it was not there. */
    static int[] without(int[] positions, int packed) {
        int index = indexOf(positions, packed);
        if (index < 0) return positions;
        int[] shrunk = new int[positions.length - 1];
        System.arraycopy(positions, 0, shrunk, 0, index);
        System.arraycopy(positions, index + 1, shrunk, index, positions.length - index - 1);
        return shrunk;
    }
}
