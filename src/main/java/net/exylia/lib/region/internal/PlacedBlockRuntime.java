package net.exylia.lib.region.internal;

import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.RegionId;
import net.exylia.lib.region.RegionSnapshot;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which blocks inside a region a player put there, and when the temporary ones go.
 *
 * <h2>What this is and is not</h2>
 * The region module states what a region declares and never cancels an event on its
 * behalf; enforcing {@code player_build_only} stays the consumer's decision, made
 * with {@code PluginRegions.placedByPlayer}. What no consumer can hold on its own is
 * the <em>answer</em>: block ownership is per-region state that outlives any one
 * event, and every plugin keeping its own copy would mean every plugin paying for
 * the same table and getting a different answer where regions overlap. So the
 * library owns the record, and acting on it is still the caller's move.
 *
 * <p>{@code temporary_blocks} is the exception, and deliberately so: nothing about
 * it is a decision. A block placed in a region that declares it disappears after the
 * declared number of seconds, and there is no event to cancel and no consumer choice
 * to preserve. The library owns the whole behaviour.
 *
 * <h2>Cost when nothing uses it</h2>
 * {@link #tracking()} is a single volatile read, recomputed only when a region
 * revision is published. A server whose regions never declare either policy pays
 * that read per block place and break and nothing else — no map lookup, no query, no
 * allocation.
 *
 * <h2>Lifetime</h2>
 * The record for a region lives exactly as long as the region does. Unregistering
 * it, replacing it without the policy, or releasing its plugin all publish a new
 * revision, and {@link #onPublish} drops everything that revision no longer names.
 * There is no separate expiry, no periodic scan of the table, and nothing to leak
 * once the region is gone.
 *
 * <h2>The expiry sweeper</h2>
 * One shared timer for the whole server rather than a scheduled task per block: a
 * region's blocks all carry the same lifetime, so they expire in the order they were
 * placed and a plain queue per region has the earliest at its head. The sweeper
 * starts when the first temporary block is placed and cancels itself when the last
 * one is gone, so an idle server holds no timer at all.
 */
public final class PlacedBlockRuntime {

    /** One second. Finer than the granularity anybody sets these in. */
    private static final long SWEEP_PERIOD_TICKS = 20L;

    private static final ConcurrentMap<RegionId, PositionSet> PLACED = new ConcurrentHashMap<>();
    private static final ConcurrentMap<RegionId, ArrayDeque<Temporary>> EXPIRING =
            new ConcurrentHashMap<>();
    /** What keeps the sweeper alive; an empty queue is left in place, not removed. */
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final Object SWEEPER_LOCK = new Object();

    private static volatile Plugin libraryPlugin;
    private static volatile boolean tracking;
    private static TaskHandle sweeper;

    private PlacedBlockRuntime() {
    }

    /** Initializes the scheduling owner used by the expiry sweeper. */
    public static void init(@NotNull Plugin plugin) {
        libraryPlugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Whether any registered region declares a policy that needs block ownership.
     *
     * <p>The gate the block listener reads before doing anything at all.
     *
     * @return {@code true} when at least one region declares {@code player_build_only}
     *         or {@code temporary_blocks}
     */
    public static boolean tracking() {
        return tracking;
    }

    /** Whether one region declares a policy that requires recording its blocks. */
    public static boolean tracks(@NotNull RegionSnapshot region) {
        return region.policySet().explicit(CommonRegionPolicies.PLAYER_BUILD_ONLY).orElse(false)
                || region.policySet().explicit(CommonRegionPolicies.TEMPORARY_BLOCKS).orElse(false);
    }

    /**
     * Rearms the gate and forgets regions the new revision no longer tracks.
     *
     * <p>Called from the publication of a region revision, which is where a region is
     * unregistered, replaced, or has the policy turned off.
     */
    static void onPublish(@NotNull RegionIndex index) {
        Set<RegionId> live = null;
        for (RegionSnapshot region : index.all()) {
            if (!tracks(region)) continue;
            if (live == null) live = new HashSet<>();
            live.add(region.id());
        }
        tracking = live != null;
        if (PLACED.isEmpty() && EXPIRING.isEmpty()) return;
        Set<RegionId> retained = live == null ? Set.of() : live;
        PLACED.keySet().removeIf(id -> !retained.contains(id));
        EXPIRING.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) return false;
            ArrayDeque<Temporary> queue = entry.getValue();
            synchronized (queue) {
                PENDING.addAndGet(-queue.size());
                queue.clear();
            }
            return true;
        });
    }

    /**
     * Records a block a player placed inside a region, and starts its clock when the
     * region declares one.
     *
     * <p>Creative is not special-cased here. Whether an operator's placement counts is
     * a question about enforcement, and enforcement is the consumer's; the record just
     * states what happened.
     *
     * @param region region containing the block, which must declare a tracking policy
     * @param playerId player who placed it
     * @param material material as placed, checked again before a temporary removal
     * @param x block x
     * @param y block y
     * @param z block z
     */
    public static void placed(@NotNull RegionSnapshot region, @NotNull UUID playerId,
                              @NotNull Material material, int x, int y, int z) {
        long key = PositionSet.pack(x, y, z);
        PositionSet positions = PLACED.computeIfAbsent(region.id(), id -> new PositionSet());
        synchronized (positions) {
            positions.add(key);
        }
        if (!region.policySet().explicit(CommonRegionPolicies.TEMPORARY_BLOCKS).orElse(false)) {
            return;
        }
        int seconds = region.policySet()
                .explicit(CommonRegionPolicies.TEMPORARY_BLOCKS_SECONDS)
                .orElse(CommonRegionPolicies.TEMPORARY_BLOCKS_SECONDS.defaultValue());
        if (seconds <= 0) return;
        boolean reGive = region.policySet()
                .explicit(CommonRegionPolicies.RE_GIVE_BLOCKS).orElse(false);
        Temporary entry = new Temporary(key, region.worldId(), reGive ? playerId : null,
                material, System.currentTimeMillis() + seconds * 1000L);
        ArrayDeque<Temporary> queue = EXPIRING.computeIfAbsent(region.id(), id -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(entry);
        }
        PENDING.incrementAndGet();
        startSweeper();
    }

    /** Whether a position is recorded as player-placed inside one region. */
    public static boolean tracked(@NotNull RegionId id, int x, int y, int z) {
        PositionSet positions = PLACED.get(Objects.requireNonNull(id, "id"));
        if (positions == null) return false;
        long key = PositionSet.pack(x, y, z);
        synchronized (positions) {
            return positions.contains(key);
        }
    }

    /**
     * Forgets one position, returning whether it was recorded.
     *
     * <p>The emptied table is left in place rather than removed from the map: taking
     * it out would race a concurrent placement that already holds the same instance,
     * and the region's own removal reclaims it anyway.
     */
    public static boolean untrack(@NotNull RegionId id, int x, int y, int z) {
        PositionSet positions = PLACED.get(Objects.requireNonNull(id, "id"));
        if (positions == null) return false;
        long key = PositionSet.pack(x, y, z);
        synchronized (positions) {
            return positions.remove(key);
        }
    }

    /** Forgets every recorded block and stops the sweeper. */
    public static void releaseAll() {
        PLACED.clear();
        EXPIRING.clear();
        PENDING.set(0);
        tracking = false;
        stopSweeper();
    }

    private static void startSweeper() {
        Plugin owner = libraryPlugin;
        if (owner == null) return;
        synchronized (SWEEPER_LOCK) {
            if (sweeper != null) return;
            sweeper = Tasks.of(owner).runTimer(SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS,
                    PlacedBlockRuntime::sweep);
        }
    }

    private static void stopSweeper() {
        synchronized (SWEEPER_LOCK) {
            if (sweeper == null) return;
            sweeper.cancel();
            sweeper = null;
        }
    }

    /**
     * Retires every temporary block whose time is up.
     *
     * <p>Each queue is drained from its head and only while the head is due, so a
     * sweep costs one comparison per region plus the blocks actually expiring.
     */
    private static void sweep(TaskHandle handle) {
        if (PENDING.get() == 0) {
            synchronized (SWEEPER_LOCK) {
                if (PENDING.get() != 0) return;
                handle.cancel();
                if (sweeper == handle) sweeper = null;
            }
            return;
        }

        long now = System.currentTimeMillis();
        List<Expired> due = null;
        for (Map.Entry<RegionId, ArrayDeque<Temporary>> entry : EXPIRING.entrySet()) {
            ArrayDeque<Temporary> queue = entry.getValue();
            synchronized (queue) {
                Temporary head;
                while ((head = queue.peekFirst()) != null && head.expiresAt() <= now) {
                    queue.pollFirst();
                    PENDING.decrementAndGet();
                    if (due == null) due = new ArrayList<>();
                    due.add(new Expired(entry.getKey(), head));
                }
            }
        }
        if (due == null) return;

        Plugin owner = libraryPlugin;
        if (owner == null) return;
        for (Expired expired : due) {
            retire(owner, expired.regionId(), expired.entry());
        }
    }

    /**
     * Removes one expired block on the thread that owns it.
     *
     * <p>The material is compared before the block is cleared: a block broken and
     * replaced with something else at the same position is not the one that was
     * placed, and clearing it would delete somebody else's work.
     */
    private static void retire(Plugin owner, RegionId regionId, Temporary entry) {
        World world = Bukkit.getWorld(entry.worldId());
        if (world == null) return;
        int x = unpackX(entry.key());
        int y = unpackY(entry.key());
        int z = unpackZ(entry.key());
        Tasks.of(owner).runAtLocation(new Location(world, x, y, z), () -> {
            if (!untrack(regionId, x, y, z)) return;
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != entry.material()) return;
            block.setType(Material.AIR, false);
            reGive(owner, entry);
        });
    }

    private static void reGive(Plugin owner, Temporary entry) {
        UUID playerId = entry.playerId();
        if (playerId == null) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        Tasks.of(owner).runAtEntity(player,
                () -> player.getInventory().addItem(new ItemStack(entry.material(), 1)));
    }

    static int unpackX(long key) {
        return (int) (key >> 38);
    }

    static int unpackZ(long key) {
        return (int) (key << 26 >> 38);
    }

    static int unpackY(long key) {
        return (int) (key << 52 >> 52);
    }

    /** One temporary block waiting for its time. */
    private record Temporary(long key, UUID worldId, @Nullable UUID playerId,
                             Material material, long expiresAt) {
    }

    /** A drained entry, paired with the region it belonged to. */
    private record Expired(RegionId regionId, Temporary entry) {
    }
}
