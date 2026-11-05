package net.exylia.lib.packet.internal;

import net.exylia.lib.packet.BlockMasks;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One plugin's {@link BlockMasks}, over one index every plugin shares (a client sees one block per
 * position, whoever masked it). {@link MaskPackets} rewrites the packets; this class decides who is
 * near and sends the swaps.
 */
final class Masks implements BlockMasks {

    /** One masked block: who masked it, what the far players see, and how near is near. */
    record Mask(String plugin, BlockData shown, double distance) {
    }

    /** Blocks past the reveal distance a revealed player may wander before the mask comes back. */
    private static final double HYSTERESIS = 2.0;
    /** Ticks between checks of who is near. */
    private static final long PASS_TICKS = 10L;

    /** world -> chunk key -> packed position -> mask. */
    private static final Map<UUID, Map<Long, Map<Long, Mask>>> WORLDS = new ConcurrentHashMap<>();
    /** viewer -> packed positions in their current world they are sent as they are. */
    private static final Map<UUID, Set<Long>> REVEALED = new ConcurrentHashMap<>();
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static volatile double farthest;
    private static volatile TaskHandle pass;

    private final Plugin plugin;

    Masks(Plugin plugin) {
        this.plugin = plugin;
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    // ---- what the packet listener asks: lock-free, any thread ----

    static boolean any() {
        return COUNT.get() > 0;
    }

    static @Nullable Map<Long, Mask> chunk(UUID world, int chunkX, int chunkZ) {
        Map<Long, Map<Long, Mask>> chunks = WORLDS.get(world);
        if (chunks == null) return null;
        Map<Long, Mask> here = chunks.get(chunkKey(chunkX, chunkZ));
        return here == null || here.isEmpty() ? null : here;
    }

    /** The mask this viewer is sent at a position instead of the real block, or {@code null}. */
    static @Nullable Mask hiddenAt(UUID viewer, UUID world, int x, int y, int z) {
        Map<Long, Mask> here = chunk(world, x >> 4, z >> 4);
        if (here == null) return null;
        long packed = PacketRuntime.pack(x, y, z);
        Mask mask = here.get(packed);
        if (mask == null) return null;
        Set<Long> revealed = REVEALED.get(viewer);
        return revealed != null && revealed.contains(packed) ? null : mask;
    }

    static @Nullable Set<Long> revealedOf(UUID viewer) {
        return REVEALED.get(viewer);
    }

    /** A viewer left the server or the world: their client dropped every chunk with it. */
    static void forget(UUID viewer) {
        REVEALED.remove(viewer);
    }

    // ---- BlockMasks ----

    @Override
    public void mask(@NotNull Location at, @NotNull BlockData shown, double revealDistance) {
        World world = at.getWorld();
        if (world == null) return;
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        long packed = PacketRuntime.pack(x, y, z);
        double distance = Math.max(0, revealDistance);
        Mask mask = new Mask(plugin.getName(), shown, distance);
        Mask old = WORLDS.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(x >> 4, z >> 4), ignored -> new ConcurrentHashMap<>())
                .put(packed, mask);
        if (old == null) COUNT.incrementAndGet();
        if (distance > farthest) farthest = distance;
        ensurePass();
        if (old != null && old.shown().equals(shown)) return;
        // The players who already have the chunk: near ones keep the truth they have, far ones get the mask.
        Location block = new Location(world, x, y, z);
        PacketSink out = PacketRuntime.sink();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getWorld().equals(world)) continue;
            double d2 = distanceSquared(viewer.getLocation(), x, y, z);
            if (d2 <= distance * distance) {
                REVEALED.computeIfAbsent(viewer.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(packed);
            } else if (out != null && d2 <= viewRange() && !isRevealed(viewer.getUniqueId(), packed)) {
                send(out, viewer, Map.of(block, shown));
            }
        }
    }

    @Override
    public void unmask(@NotNull Location at) {
        World world = at.getWorld();
        if (world == null) return;
        remove(world, at.getBlockX(), at.getBlockY(), at.getBlockZ(), true);
    }

    @Override
    public void unmaskAll() {
        String name = plugin.getName();
        for (Map.Entry<UUID, Map<Long, Map<Long, Mask>>> byWorld : WORLDS.entrySet()) {
            World world = Bukkit.getWorld(byWorld.getKey());
            for (Map<Long, Mask> here : byWorld.getValue().values()) {
                for (Map.Entry<Long, Mask> entry : here.entrySet()) {
                    if (!name.equals(entry.getValue().plugin())) continue;
                    long packed = entry.getKey();
                    if (world == null) {
                        if (here.remove(packed) != null) COUNT.decrementAndGet();
                    } else {
                        remove(world, (int) (packed >> 38), (int) (packed << 52 >> 52), (int) (packed << 26 >> 38), true);
                    }
                }
            }
        }
    }

    @Override
    public boolean isMasked(@NotNull Location at) {
        Mask mask = maskAt(at);
        return mask != null && mask.plugin().equals(plugin.getName());
    }

    @Override
    public boolean isRevealed(@NotNull Player viewer, @NotNull Location at) {
        return maskAt(at) != null && isRevealed(viewer.getUniqueId(),
                PacketRuntime.pack(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
    }

    private static @Nullable Mask maskAt(Location at) {
        if (at.getWorld() == null) return null;
        Map<Long, Mask> here = chunk(at.getWorld().getUID(), at.getBlockX() >> 4, at.getBlockZ() >> 4);
        return here == null ? null : here.get(PacketRuntime.pack(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
    }

    private static boolean isRevealed(UUID viewer, long packed) {
        Set<Long> revealed = REVEALED.get(viewer);
        return revealed != null && revealed.contains(packed);
    }

    private void remove(World world, int x, int y, int z, boolean restore) {
        Map<Long, Mask> here = chunk(world.getUID(), x >> 4, z >> 4);
        long packed = PacketRuntime.pack(x, y, z);
        if (here == null || here.remove(packed) == null) return;
        COUNT.decrementAndGet();
        List<Player> restoring = new ArrayList<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getWorld().equals(world)) continue;
            Set<Long> revealed = REVEALED.get(viewer.getUniqueId());
            boolean saw = revealed != null && revealed.remove(packed);
            if (!saw && restore && distanceSquared(viewer.getLocation(), x, y, z) <= viewRange()) restoring.add(viewer);
        }
        if (!restoring.isEmpty()) sendReal(restoring, List.of(new Location(world, x, y, z)));
    }

    // ---- who is near ----

    private static void ensurePass() {
        if (pass != null) return;
        Plugin lib = PacketRuntime.lib();
        if (lib == null) return;
        synchronized (Masks.class) {
            if (pass == null) pass = Tasks.of(lib).runAsyncTimer(PASS_TICKS, PASS_TICKS, Masks::pass);
        }
    }

    /** Stops the checks; the library is going down. */
    static void shutdown() {
        TaskHandle running = pass;
        if (running != null) running.cancel();
        pass = null;
        WORLDS.clear();
        REVEALED.clear();
        COUNT.set(0);
        farthest = 0;
    }

    /**
     * Reveals the masks each player came within reach of, and masks again the ones they left. Reads
     * memory and player positions only; the real blocks are read on their own regions.
     */
    static void pass() {
        PacketSink out = PacketRuntime.sink();
        Plugin lib = PacketRuntime.lib();
        if (out == null || lib == null || COUNT.get() == 0 && REVEALED.isEmpty()) return;
        int reach = (int) Math.ceil(farthest / 16.0);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            World world = viewer.getWorld();
            Map<Long, Map<Long, Mask>> chunks = WORLDS.get(world.getUID());
            Location at = viewer.getLocation();
            UUID id = viewer.getUniqueId();

            Set<Long> revealed = REVEALED.get(id);
            if (revealed != null && !revealed.isEmpty()) {
                Map<Location, BlockData> hide = new HashMap<>();
                for (Iterator<Long> it = revealed.iterator(); it.hasNext(); ) {
                    long packed = it.next();
                    int x = (int) (packed >> 38);
                    int y = (int) (packed << 52 >> 52);
                    int z = (int) (packed << 26 >> 38);
                    Mask mask = chunks == null ? null : chunks.getOrDefault(chunkKey(x >> 4, z >> 4), Map.of()).get(packed);
                    if (mask == null) {
                        it.remove();
                        continue;
                    }
                    double leave = mask.distance() + HYSTERESIS;
                    if (distanceSquared(at, x, y, z) > leave * leave) {
                        it.remove();
                        hide.put(new Location(world, x, y, z), mask.shown());
                    }
                }
                if (!hide.isEmpty()) send(out, viewer, hide);
            }

            if (chunks == null || chunks.isEmpty()) continue;
            int cx = at.getBlockX() >> 4;
            int cz = at.getBlockZ() >> 4;
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    Map<Long, Mask> here = chunks.get(chunkKey(cx + dx, cz + dz));
                    if (here == null || here.isEmpty()) continue;
                    List<Location> reveal = null;
                    for (Map.Entry<Long, Mask> entry : here.entrySet()) {
                        long packed = entry.getKey();
                        if (revealed != null && revealed.contains(packed)) continue;
                        int x = (int) (packed >> 38);
                        int y = (int) (packed << 52 >> 52);
                        int z = (int) (packed << 26 >> 38);
                        double distance = entry.getValue().distance();
                        if (distanceSquared(at, x, y, z) > distance * distance) continue;
                        if (revealed == null) {
                            revealed = REVEALED.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet());
                        }
                        revealed.add(packed);
                        if (reveal == null) reveal = new ArrayList<>();
                        reveal.add(new Location(world, x, y, z));
                    }
                    if (reveal != null) sendReal(List.of(viewer), reveal);
                }
            }
        }
    }

    // ---- sending ----

    private static void send(PacketSink out, Player viewer, Map<Location, BlockData> blocks) {
        SectionGroups.group(blocks.keySet()).forEach((section, positions) -> out.blocks(viewer, section, positions, blocks));
    }

    /**
     * Sends blocks of one chunk as they are, with their block entities, read on the chunk's own region.
     * Run by the library, which outlives the plugin unmasking on its way out; nothing is sent while the
     * library or the server is going down: Folia disables plugins from its shutdown thread, which
     * owns every region but has no region scheduler to queue on.
     */
    private static void sendReal(List<Player> viewers, List<Location> blocks) {
        Plugin lib = PacketRuntime.lib();
        if (lib == null || !lib.isEnabled() || Bukkit.isStopping()) return;
        Tasks.of(lib).runAtLocation(blocks.getFirst(), () -> {
            for (Location at : blocks) {
                Block block = at.getBlock();
                BlockData data = block.getBlockData();
                BlockState state = block.getState(false);
                for (Player viewer : viewers) {
                    if (!viewer.isOnline() || !viewer.getWorld().equals(at.getWorld())) continue;
                    viewer.sendBlockChange(at, data);
                    if (state instanceof TileState tile) viewer.sendBlockUpdate(at, tile);
                }
            }
        });
    }

    private static double distanceSquared(Location from, int x, int y, int z) {
        double dx = from.getX() - (x + 0.5);
        double dy = from.getY() - (y + 0.5);
        double dz = from.getZ() - (z + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Squared blocks within which a client may hold the chunk. */
    private static double viewRange() {
        double blocks = (Bukkit.getViewDistance() + 1) * 16.0;
        return blocks * blocks;
    }
}
