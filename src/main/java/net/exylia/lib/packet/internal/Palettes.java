package net.exylia.lib.packet.internal;

import net.exylia.lib.packet.palette.Palette;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is shown the world through which block palette. The packet side is {@link PalettePackets}; the
 * public face is {@code net.exylia.lib.packet.palette}.
 */
public final class Palettes {

    /** One viewer's palette: the plugin that set it, the worlds it applies in, the table. */
    record Active(String plugin, Palette palette, Set<String> worlds, PaletteTable table) {
    }

    /** Chunks resent per tick by {@link #refresh}, nearest first. */
    private static final int CHUNKS_PER_TICK = 32;

    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();

    private Palettes() {
    }

    /** Whether swaps can be compiled and applied here: PacketEvents is installed. */
    public static boolean supported() {
        return PacketRuntime.isAvailable();
    }

    /**
     * Compiles swaps into a table for this server, adding to {@code rejected} each swap refused and why.
     * An empty table when PacketEvents is missing.
     */
    public static PaletteTable compile(Map<Material, Material> materials, Map<BlockData, Material> states,
                                       List<String> rejected) {
        if (!supported()) {
            rejected.add("PacketEvents is not installed: nothing is swapped");
            return PaletteTable.EMPTY;
        }
        if (Bukkit.getWorlds().isEmpty()) throw new IllegalStateException("Build palettes once the worlds are loaded");
        Location at = new Location(Bukkit.getWorlds().getFirst(), 0, 64, 0);
        return PaletteStates.compile(materials, states, at, rejected);
    }

    public static void set(Plugin plugin, Player viewer, Palette palette, Set<String> worlds, PaletteTable table) {
        ACTIVE.put(viewer.getUniqueId(), new Active(plugin.getName(), palette, Set.copyOf(worlds), table));
    }

    /** Takes the viewer's palette off; {@code true} when they had one. */
    public static boolean clear(Player viewer) {
        return ACTIVE.remove(viewer.getUniqueId()) != null;
    }

    public static @Nullable Palette paletteOf(Player viewer) {
        Active active = ACTIVE.get(viewer.getUniqueId());
        return active == null ? null : active.palette();
    }

    // ---- what the packet listener asks: lock-free, any thread ----

    static boolean any() {
        return !ACTIVE.isEmpty();
    }

    /** The table this viewer's packets go through in the world they are in, or {@code null}. */
    static @Nullable PaletteTable tableFor(Player viewer) {
        Active active = ACTIVE.get(viewer.getUniqueId());
        if (active == null || active.table().isEmpty()) return null;
        if (!active.worlds().isEmpty() && !active.worlds().contains(viewer.getWorld().getName())) return null;
        return active.table();
    }

    // ---- lifecycle ----

    static void forget(UUID viewer) {
        ACTIVE.remove(viewer);
    }

    /** A plugin is disabled: its palettes come off and the world is sent again as it is. */
    static void release(String pluginName) {
        for (Map.Entry<UUID, Active> entry : ACTIVE.entrySet()) {
            if (!entry.getValue().plugin().equals(pluginName) || !ACTIVE.remove(entry.getKey(), entry.getValue())) continue;
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) refresh(viewer);
        }
    }

    static void shutdown() {
        ACTIVE.clear();
    }

    // ---- resending ----

    /**
     * Sends again every chunk the viewer holds, nearest first, so what they see follows the palette they
     * have now. Each chunk is resent on its own region with {@link World#refreshChunk}, which the server
     * sends to everyone holding it, light and block entities included.
     */
    public static void refresh(Player viewer) {
        Plugin lib = PacketRuntime.lib();
        if (lib == null || !lib.isEnabled() || Bukkit.isStopping()) return;
        TaskScheduler tasks = Tasks.of(lib);
        tasks.runAtEntity(viewer, () -> {
            World world = viewer.getWorld();
            int cx = viewer.getLocation().getBlockX() >> 4;
            int cz = viewer.getLocation().getBlockZ() >> 4;
            List<Long> chunks = new ArrayList<>(viewer.getSentChunkKeys());
            chunks.sort(Comparator.comparingLong(key -> {
                long dx = (int) (long) key - cx;
                long dz = (int) ((long) key >> 32) - cz;
                return dx * dx + dz * dz;
            }));
            // ponytail: every player holding a chunk gets it again, not only this viewer; a per-viewer
            // resend needs the chunk packet built by hand (light included) and is worth it only if
            // palette changes become frequent in crowded places.
            for (int i = 0; i < chunks.size(); i++) {
                long key = chunks.get(i);
                int x = (int) key;
                int z = (int) (key >> 32);
                Location at = new Location(world, (x << 4) + 8, 0, (z << 4) + 8);
                long delay = i / CHUNKS_PER_TICK;
                Runnable resend = () -> world.refreshChunk(x, z);
                if (delay == 0) tasks.runAtLocation(at, resend);
                else tasks.runAtLocationLater(at, delay, resend);
            }
        });
    }
}
