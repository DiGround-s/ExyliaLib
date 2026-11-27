package net.exylia.lib.packet.area;

import net.exylia.lib.packet.FakeBlocks;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.packet.palette.Palette;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How each player sees an area, by name: a locked section drawn as wild ground, a base drawn in the
 * materials of its era, a fence around what is not open yet. Fake blocks kept for that one viewer
 * ({@link FakeBlocks#keep}), grouped by area so one area can be redrawn, cleared or revealed ring by
 * ring without touching the others. Nothing changes on the server.
 * <pre>{@code
 * AreaLooks looks = AreaLooks.of(this);              // once, on enable
 * looks.show(player, "mine", wildGround);            // replaces what "mine" looked like
 * looks.paint(player, "base", world, box, era);      // the real blocks there, through a palette
 * looks.reveal(player, "mine", gateX, gateZ, 5, 4, () -> grow(player));
 * }</pre>
 * <p>What each viewer was shown is forgotten when they leave or change world (the client reloads its
 * chunks then). Only swap a block for one of the same shape: the server still collides with the real one.
 *
 * @since 1.676.0
 */
public final class AreaLooks implements Listener {

    private final TaskScheduler tasks;
    private final FakeBlocks fake;
    /** viewer -> area -> positions kept for it. */
    private final Map<UUID, Map<String, Set<Location>>> shown = new ConcurrentHashMap<>();

    private AreaLooks(Plugin plugin) {
        this.tasks = Tasks.of(plugin);
        this.fake = Packets.of(plugin).fakeBlocks();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** A plugin's area looks. Create one per plugin (it listens for quits), on enable. */
    public static @NotNull AreaLooks of(@NotNull Plugin plugin) {
        return new AreaLooks(plugin);
    }

    /**
     * Shows a viewer an area as these blocks and keeps them shown, replacing what the area looked like
     * before: positions it no longer covers go back to the truth. Any thread.
     */
    public void show(@NotNull Player viewer, @NotNull String area, @NotNull Map<Location, BlockData> blocks) {
        Map<String, Set<Location>> areas = shown.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>());
        Set<Location> before = areas.remove(area);
        if (before != null) {
            before.removeAll(blocks.keySet());
            if (!before.isEmpty()) fake.clear(viewer, before);
        }
        if (blocks.isEmpty()) return;
        Set<Location> now = ConcurrentHashMap.newKeySet();
        now.addAll(blocks.keySet());
        areas.put(area, now);
        fake.keep(viewer, blocks);
    }

    /**
     * Shows a viewer the real blocks of a box through a palette: every block the palette swaps is kept
     * as its swap, the rest stays real. The box is read chunk by chunk on the thread that owns each chunk
     * (Folia safe), then shown at once. Any thread; reads the world, so keep the box to one section.
     *
     * @return {@code false}, and nothing read, when the palette swaps nothing here
     */
    public boolean paint(@NotNull Player viewer, @NotNull String area, @NotNull World world,
                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ, @NotNull Palette palette) {
        if (palette.swappedStates() == 0 || !palette.appliesIn(world)) return false;
        Map<Location, BlockData> out = new ConcurrentHashMap<>();
        List<int[]> chunks = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) chunks.add(new int[]{cx, cz});
        AtomicInteger left = new AtomicInteger(chunks.size());
        for (int[] chunk : chunks) {
            tasks.runAtLocation(new Location(world, chunk[0] << 4, minY, chunk[1] << 4), () -> {
                int x0 = Math.max(minX, chunk[0] << 4), x1 = Math.min(maxX, (chunk[0] << 4) + 15);
                int z0 = Math.max(minZ, chunk[1] << 4), z1 = Math.min(maxZ, (chunk[1] << 4) + 15);
                for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = minY; y <= maxY; y++) {
                    BlockData real = world.getBlockData(x, y, z);
                    BlockData drawn = palette.shown(real);
                    if (drawn != real) out.put(new Location(world, x, y, z), drawn);
                }
                if (left.decrementAndGet() == 0) tasks.runAtEntity(viewer, () -> show(viewer, area, out));
            });
        }
        return true;
    }

    /** Puts an area back to the truth for a viewer. Any thread. */
    public void clear(@NotNull Player viewer, @NotNull String area) {
        Map<String, Set<Location>> areas = shown.get(viewer.getUniqueId());
        Set<Location> before = areas == null ? null : areas.remove(area);
        if (before != null && !before.isEmpty()) fake.clear(viewer, before);
    }

    /** Puts every area this plugin drew back to the truth for a viewer. Any thread. */
    public void clear(@NotNull Player viewer) {
        Map<String, Set<Location>> areas = shown.remove(viewer.getUniqueId());
        if (areas == null) return;
        Set<Location> all = new HashSet<>();
        areas.values().forEach(all::addAll);
        if (!all.isEmpty()) fake.clear(viewer, all);
    }

    /** The areas a viewer is shown a look of now. */
    public @NotNull Set<String> areas(@NotNull Player viewer) {
        Map<String, Set<Location>> areas = shown.get(viewer.getUniqueId());
        return areas == null ? Set.of() : Set.copyOf(areas.keySet());
    }

    /**
     * Takes an area's look down ring by ring for one viewer, nearest {@code (fromX, fromZ)} first:
     * {@code steps} rings, {@code ticksApart} ticks apart, on the viewer's thread. {@code then} runs on
     * that thread after the last ring (at once when the area shows nothing), unless they left.
     */
    public void reveal(@NotNull Player viewer, @NotNull String area, int fromX, int fromZ, int steps,
                       long ticksApart, @Nullable Runnable then) {
        Map<String, Set<Location>> areas = shown.get(viewer.getUniqueId());
        Set<Location> kept = areas == null ? null : areas.get(area);
        if (kept == null || kept.isEmpty()) {
            if (then != null) tasks.runAtEntity(viewer, then);
            return;
        }
        List<List<Location>> rings = rings(new ArrayList<>(kept), fromX, fromZ, Math.max(1, steps));
        for (int i = 0; i < rings.size(); i++) {
            boolean last = i == rings.size() - 1;
            List<Location> ring = rings.get(i);
            tasks.runAtEntityLater(viewer, 1L + i * Math.max(0L, ticksApart), () -> {
                if (!viewer.isOnline()) return;
                Set<Location> still = areas.get(area);
                if (still != null) {
                    List<Location> gone = new ArrayList<>(ring);
                    gone.retainAll(still);
                    still.removeAll(gone);
                    if (!gone.isEmpty()) fake.clear(viewer, gone);
                    if (still.isEmpty()) areas.remove(area, still);
                }
                if (last && then != null) then.run();
            });
        }
    }

    /**
     * Splits positions into {@code steps} rings by their flat distance from a point, nearest first: the
     * order {@link #reveal} clears them in. Every position lands in exactly one ring; a ring may be empty.
     */
    public static @NotNull List<List<Location>> rings(@NotNull Collection<Location> blocks, int fromX, int fromZ, int steps) {
        List<List<Location>> out = new ArrayList<>();
        for (int i = 0; i < steps; i++) out.add(new ArrayList<>());
        Map<Location, Double> distance = new HashMap<>();
        double far = 0;
        for (Location b : blocks) {
            double dx = b.getBlockX() - fromX, dz = b.getBlockZ() - fromZ;
            double d = Math.sqrt(dx * dx + dz * dz);
            distance.put(b, d);
            far = Math.max(far, d);
        }
        for (Location b : blocks) out.get((int) Math.min(steps - 1, distance.get(b) / (far + 1) * steps)).add(b);
        return out;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }

    /** The fake blocks are forgotten on a world change: so is what they were. */
    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }
}
