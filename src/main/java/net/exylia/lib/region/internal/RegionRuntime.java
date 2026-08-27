package net.exylia.lib.region.internal;

import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.PlayerRegionChangeEvent;
import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.region.PolicyResolution;
import net.exylia.lib.region.RegionChangeCause;
import net.exylia.lib.region.RegionId;
import net.exylia.lib.region.RegionSnapshot;
import net.exylia.lib.region.WorldIdentity;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Shared copy-on-write region registry and player membership runtime. */
public final class RegionRuntime {

    private static final Object MUTATION_LOCK = new Object();
    private static final AtomicReference<RegionIndex> INDEX =
            new AtomicReference<>(RegionIndex.empty());
    private static final Map<String, Plugin> ACTIVE_OWNERS = new HashMap<>();
    private static final Map<UUID, Membership> MEMBERSHIPS = new ConcurrentHashMap<>();

    /**
     * The per-player poll that catches a move no event reported.
     *
     * <p>Events are the fast path and stay the fast path: a step is answered in
     * the same tick it happened. They are not, however, the whole truth. Folia
     * does not put every teleport through {@code PlayerTeleportEvent}, a plugin
     * can move somebody by packet, and a passenger's movement is the vehicle's.
     * A tracker that believes only what it is told leaves a player standing in
     * a region the server says they left.
     *
     * <p>Quarter of a second, on the thread that owns the player, and it costs a
     * point lookup that returns the same regions it returned last time — the
     * same work one step already does, done five times a second instead of
     * twenty.
     */
    private static final Map<UUID, TaskHandle> POLLS = new ConcurrentHashMap<>();

    private static final long POLL_TICKS = 5;

    private static volatile Plugin libraryPlugin;

    private RegionRuntime() {
    }

    /** Initializes the scheduling owner used for lifecycle-safe reconciliation. */
    /**
     * The library's own plugin, for work that must outlive a consumer.
     *
     * <p>A consumer is already disabled when its regions are released, and a
     * disabled plugin cannot schedule anything — so anything that has to run
     * during that release runs here instead.
     *
     * @return ExyliaLib's plugin, or {@code null} before it enabled
     */
    static @Nullable Plugin library() {
        return libraryPlugin;
    }

    public static void init(@NotNull Plugin plugin) {
        libraryPlugin = Objects.requireNonNull(plugin, "plugin");
        PlacedBlockRuntime.init(plugin);
        // Empty on a normal boot, and not on a reload: a player who was already
        // online has no join to be tracked from, and without this their first
        // step would arrive as an entry into every region they were standing in.
        for (Player player : Bukkit.getOnlinePlayers()) {
            initialize(player);
        }
    }

    public static @NotNull List<RegionSnapshot> all() {
        return INDEX.get().all();
    }

    public static @Nullable RegionSnapshot get(@NotNull RegionId id) {
        return INDEX.get().get(Objects.requireNonNull(id, "id"));
    }

    public static @NotNull List<RegionSnapshot> owner(@NotNull String owner) {
        return INDEX.get().owner(Objects.requireNonNull(owner, "owner"));
    }

    public static @NotNull List<RegionSnapshot> query(@NotNull UUID worldId,
                                                       double x, double y, double z) {
        return INDEX.get().query(Objects.requireNonNull(worldId, "worldId"), x, y, z);
    }

    public static @NotNull List<RegionSnapshot> queryOwner(@NotNull String owner,
                                                            @NotNull UUID worldId,
                                                            double x, double y, double z) {
        return INDEX.get().queryOwner(Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(worldId, "worldId"), x, y, z);
    }

    public static <T> @NotNull PolicyResolution<T> resolve(@NotNull UUID worldId,
                                                            double x, double y, double z,
                                                            @NotNull PolicyKey<T> key) {
        return resolveFrom(INDEX.get().query(Objects.requireNonNull(worldId, "worldId"), x, y, z), key);
    }

    public static <T> @NotNull PolicyResolution<T> resolveOwner(@NotNull String owner,
                                                                 @NotNull UUID worldId,
                                                                 double x, double y, double z,
                                                                 @NotNull PolicyKey<T> key) {
        return resolveFrom(INDEX.get().queryOwner(Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(worldId, "worldId"), x, y, z), key);
    }

    private static <T> PolicyResolution<T> resolveFrom(List<RegionSnapshot> regions,
                                                        PolicyKey<T> key) {
        Objects.requireNonNull(key, "key");
        for (RegionSnapshot region : regions) {
            java.util.Optional<T> value = region.policySet().explicit(key);
            if (value.isPresent()) {
                return new PolicyResolution<>(key, value.get(), region);
            }
        }
        return new PolicyResolution<>(key, key.defaultValue(), null);
    }

    public static @NotNull RegionSnapshot register(@NotNull Plugin plugin,
                                                    @NotNull RegionSnapshot snapshot) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(snapshot, "snapshot");
        mutate(plugin, RegionChangeCause.REGISTER, regions -> {
            if (regions.stream().anyMatch(region -> region.id().equals(snapshot.id()))) {
                throw new IllegalStateException("Region already registered: " + snapshot.id());
            }
            regions.add(snapshot);
        });
        return snapshot;
    }

    public static @Nullable RegionSnapshot unregister(@NotNull Plugin plugin,
                                                       @NotNull RegionId id) {
        Objects.requireNonNull(id, "id");
        RegionSnapshot[] removed = new RegionSnapshot[1];
        mutate(plugin, RegionChangeCause.UNREGISTER, regions -> {
            for (int index = 0; index < regions.size(); index++) {
                if (regions.get(index).id().equals(id)) {
                    removed[0] = regions.remove(index);
                    return;
                }
            }
        }, false);
        return removed[0];
    }

    public static @NotNull RegionSnapshot replace(@NotNull Plugin plugin,
                                                   @NotNull RegionSnapshot snapshot) {
        mutate(plugin, RegionChangeCause.REPLACE, regions -> {
            for (int index = 0; index < regions.size(); index++) {
                if (regions.get(index).id().equals(snapshot.id())) {
                    regions.set(index, snapshot);
                    return;
                }
            }
            throw new IllegalStateException("Region is not registered: " + snapshot.id());
        });
        return snapshot;
    }

    public static void replaceAll(@NotNull Plugin plugin,
                                  @NotNull Collection<RegionSnapshot> replacements) {
        Objects.requireNonNull(replacements, "replacements");
        List<RegionSnapshot> copy = List.copyOf(replacements);
        String owner = plugin.getName();
        mutate(plugin, RegionChangeCause.REPLACE, regions -> {
            regions.removeIf(region -> region.owner().equals(owner));
            regions.addAll(copy);
        });
    }

    public static int release(@NotNull String owner) {
        Objects.requireNonNull(owner, "owner");
        int[] count = new int[1];
        synchronized (MUTATION_LOCK) {
            RegionIndex oldIndex = INDEX.get();
            List<RegionSnapshot> next = new ArrayList<>(oldIndex.all());
            next.removeIf(region -> {
                boolean remove = region.owner().equals(owner);
                if (remove) count[0]++;
                return remove;
            });
            ACTIVE_OWNERS.remove(owner);
            if (count[0] == 0) return 0;
            publish(oldIndex, RegionIndex.build(next, oldIndex.revision() + 1),
                    RegionChangeCause.RELEASE);
        }
        return count[0];
    }

    public static void releaseAll() {
        synchronized (MUTATION_LOCK) {
            RegionIndex oldIndex = INDEX.get();
            ACTIVE_OWNERS.clear();
            if (oldIndex.all().isEmpty()) return;
            publish(oldIndex, RegionIndex.build(List.of(), oldIndex.revision() + 1),
                    RegionChangeCause.RELEASE);
        }
    }

    public static int size() {
        return INDEX.get().all().size();
    }

    private static void mutate(Plugin plugin, RegionChangeCause cause,
                               java.util.function.Consumer<List<RegionSnapshot>> change) {
        mutate(plugin, cause, change, true);
    }

    private static void mutate(Plugin plugin, RegionChangeCause cause,
                               java.util.function.Consumer<List<RegionSnapshot>> change,
                               boolean publishUnchanged) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (MUTATION_LOCK) {
            RegionIndex oldIndex = INDEX.get();
            List<RegionSnapshot> next = new ArrayList<>(oldIndex.all());
            change.accept(next);
            if (!publishUnchanged && next.size() == oldIndex.all().size()) return;
            RegionIndex built = RegionIndex.build(next, oldIndex.revision() + 1);
            if (built.owner(plugin.getName()).isEmpty()) {
                ACTIVE_OWNERS.remove(plugin.getName());
            } else {
                ACTIVE_OWNERS.put(plugin.getName(), plugin);
            }
            publish(oldIndex, built, cause);
        }
    }

    /** Publishes only after a complete build; validation exceptions leave the old index untouched. */
    private static void publish(RegionIndex oldIndex, RegionIndex newIndex,
                                 RegionChangeCause cause) {
        INDEX.set(newIndex);
        // Before reconciliation: block ownership belongs to a region, and a
        // revision that drops one must not leave its blocks addressable.
        PlacedBlockRuntime.onPublish(newIndex);
        reconcile(newIndex.revision(), cause);
    }

    private static void reconcile(long expectedRevision, RegionChangeCause cause) {
        Plugin schedulerOwner = libraryPlugin;
        if (schedulerOwner == null) return;
        for (UUID playerId : List.copyOf(MEMBERSHIPS.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;
            Tasks.of(schedulerOwner).runAtEntity(player,
                    () -> reconcilePlayer(player, expectedRevision, cause));
        }
    }

    private static void reconcilePlayer(Player player, long expectedRevision,
                                        RegionChangeCause cause) {
        RegionIndex index = INDEX.get();
        // A newer publication schedules its own pass, so stale work must not overwrite it.
        if (index.revision() != expectedRevision) return;
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) return;
        update(player, world.getUID(), world.getName(),
                location.getX(), location.getY(), location.getZ(), cause, true);
    }

    /** Initializes join state without manufacturing enter events for an already-present player. */
    public static void initialize(@NotNull Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) return;
        RegionIndex index = INDEX.get();
        List<RegionSnapshot> regions = index.query(world.getUID(),
                location.getX(), location.getY(), location.getZ());
        MEMBERSHIPS.put(player.getUniqueId(), new Membership(player.getUniqueId(),
                WorldIdentity.from(world),
                floor(location.getX()), floor(location.getY()), floor(location.getZ()),
                regions, index.revision()));
        poll(player);
    }

    /**
     * Starts this player's reconciliation timer.
     *
     * <p>It runs at the entity rather than on a global tick, which is what makes
     * it correct on a regionised server: the task follows the player across
     * regions and reads a location it is allowed to read.
     */
    private static void poll(Player player) {
        Plugin owner = libraryPlugin;
        if (owner == null) return;
        UUID playerId = player.getUniqueId();
        TaskHandle previous = POLLS.remove(playerId);
        if (previous != null) previous.cancel();
        POLLS.put(playerId, Tasks.of(owner).runAtEntityTimer(player, POLL_TICKS, POLL_TICKS, () -> {
            if (!player.isOnline() || !MEMBERSHIPS.containsKey(playerId)) return;
            Location location = player.getLocation();
            World world = location.getWorld();
            if (world == null) return;
            update(player, world.getUID(), world.getName(),
                    location.getX(), location.getY(), location.getZ(),
                    RegionChangeCause.SYNC, true);
        }));
    }

    public static void move(@NotNull Player player, @NotNull UUID worldId,
                            @NotNull String worldName, double x, double y, double z,
                            @NotNull RegionChangeCause cause) {
        update(player, worldId, worldName, x, y, z, cause, true);
    }

    private static void update(Player player, UUID worldId, String worldName,
                               double x, double y, double z,
                               RegionChangeCause cause, boolean fire) {
        RegionIndex index = INDEX.get();
        UUID playerId = player.getUniqueId();
        Membership before = MEMBERSHIPS.get(playerId);
        // Reused while the player stays in one world, which is almost always. A
        // player crossing worlds gets a new one, and identity is the UUID anyway.
        WorldIdentity world = before != null && before.world().id().equals(worldId)
                ? before.world()
                : new WorldIdentity(worldId, worldName);
        int blockX = floor(x);
        int blockY = floor(y);
        int blockZ = floor(z);

        // The whole-server fast path: nothing is registered, so the query can only
        // return empty and no event can fire. Position still has to advance, or the
        // first event after a region is registered would report a stale previous().
        if (index.isEmpty() && (before == null || before.isEmpty())) {
            MEMBERSHIPS.put(playerId, Membership.empty(playerId, world, blockX, blockY, blockZ,
                    index.revision()));
            return;
        }

        List<RegionSnapshot> now = index.query(world.id(), x, y, z);
        // Both lists come out of the same immutable index, so equal membership means
        // identical references. Comparing them directly answers the overwhelmingly
        // common "nothing changed" without allocating the two id lists it used to.
        boolean unchanged = before != null && sameRegions(before.regions(), now);

        Membership updated = new Membership(playerId, world, blockX, blockY, blockZ,
                now, index.revision());
        MEMBERSHIPS.put(playerId, updated);
        if (!fire || unchanged) {
            return;
        }

        List<RegionSnapshot> previous = before == null ? List.of() : before.regions();
        List<RegionSnapshot> exited = difference(previous, now);
        List<RegionSnapshot> entered = difference(now, previous);
        if (exited.isEmpty() && entered.isEmpty()) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new PlayerRegionChangeEvent(player, cause,
                before == null ? null : before.position(), updated.position(),
                exited, entered, index.revision()));
    }

    /**
     * Identity comparison over two results of the same index.
     *
     * <p>A published index is immutable and its query results reuse the very same
     * snapshot instances, so reference equality is exact here rather than an
     * optimistic shortcut. A revision change produces new instances and correctly
     * reports a difference, which the reconciliation pass then resolves.
     */
    private static boolean sameRegions(List<RegionSnapshot> before, List<RegionSnapshot> now) {
        int size = before.size();
        if (size != now.size()) return false;
        for (int index = 0; index < size; index++) {
            if (before.get(index) != now.get(index)) return false;
        }
        return true;
    }

    /** Regions present in {@code source} whose id is absent from {@code other}. */
    private static List<RegionSnapshot> difference(List<RegionSnapshot> source,
                                                    List<RegionSnapshot> other) {
        if (source.isEmpty()) return List.of();
        if (other.isEmpty()) return source;

        List<RegionSnapshot> missing = null;
        for (int index = 0; index < source.size(); index++) {
            RegionSnapshot region = source.get(index);
            if (contains(other, region.id())) continue;
            // Allocated only once something actually entered or exited, which is
            // rare compared to the number of moves that reach this point.
            if (missing == null) missing = new ArrayList<>(source.size() - index);
            missing.add(region);
        }
        return missing == null ? List.of() : List.copyOf(missing);
    }

    /**
     * Linear scan on purpose: a player stands in a handful of regions at most, and
     * at that size a scan beats building the hash set the old code allocated.
     */
    private static boolean contains(List<RegionSnapshot> regions, RegionId id) {
        for (int index = 0; index < regions.size(); index++) {
            if (regions.get(index).id().equals(id)) return true;
        }
        return false;
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    public static void forget(@NotNull UUID playerId) {
        MEMBERSHIPS.remove(Objects.requireNonNull(playerId, "playerId"));
        TaskHandle poll = POLLS.remove(playerId);
        if (poll != null) poll.cancel();
    }

    /**
     * A player's last known position and regions.
     *
     * <p>The position is held as primitives rather than as a {@link BlockPosition}
     * because it is written on every block step and read only when an event fires,
     * which is far rarer. The region list is stored as handed over: it is always a
     * query result from an immutable index, so copying it defensively would
     * duplicate an already immutable list on every move.
     */
    private record Membership(UUID playerId, WorldIdentity world, int x, int y, int z,
                              List<RegionSnapshot> regions, long revision) {
        private Membership {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(regions, "regions");
        }

        private static Membership empty(UUID playerId, WorldIdentity world,
                                        int x, int y, int z, long revision) {
            return new Membership(playerId, world, x, y, z, List.of(), revision);
        }

        private boolean isEmpty() {
            return regions.isEmpty();
        }

        /** Materialized only when an event is about to carry it. */
        private BlockPosition position() {
            return new BlockPosition(world, x, y, z);
        }
    }
}
