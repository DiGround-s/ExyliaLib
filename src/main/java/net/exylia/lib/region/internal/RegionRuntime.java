package net.exylia.lib.region.internal;

import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.PlayerRegionChangeEvent;
import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.region.PolicyResolution;
import net.exylia.lib.region.RegionChangeCause;
import net.exylia.lib.region.RegionId;
import net.exylia.lib.region.RegionSnapshot;
import net.exylia.lib.region.WorldIdentity;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    private static volatile Plugin libraryPlugin;

    private RegionRuntime() {
    }

    /** Initializes the scheduling owner used for lifecycle-safe reconciliation. */
    public static void init(@NotNull Plugin plugin) {
        libraryPlugin = Objects.requireNonNull(plugin, "plugin");
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
        update(player, WorldIdentity.from(world), location.getX(), location.getY(), location.getZ(),
                cause, true);
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
                position(WorldIdentity.from(world), location.getX(), location.getY(), location.getZ()),
                regions, index.revision()));
    }

    public static void move(@NotNull Player player, @NotNull UUID worldId,
                            @NotNull String worldName, double x, double y, double z,
                            @NotNull RegionChangeCause cause) {
        update(player, new WorldIdentity(worldId, worldName), x, y, z, cause, true);
    }

    private static void update(Player player, WorldIdentity world, double x, double y, double z,
                               RegionChangeCause cause, boolean fire) {
        RegionIndex index = INDEX.get();
        List<RegionSnapshot> now = index.query(world.id(), x, y, z);
        List<RegionId> nowIds = now.stream().map(RegionSnapshot::id).toList();
        UUID playerId = player.getUniqueId();
        Membership before = MEMBERSHIPS.get(playerId);
        BlockPosition current = position(world, x, y, z);
        List<RegionId> oldIds = before == null ? List.of() : before.regionIds();

        MEMBERSHIPS.put(playerId, new Membership(playerId, current, now, index.revision()));
        if (!fire || oldIds.equals(nowIds)) {
            return;
        }

        Set<RegionId> oldSet = new HashSet<>(oldIds);
        Set<RegionId> newSet = new HashSet<>(nowIds);
        List<RegionSnapshot> exited = before == null ? List.of() : before.regions().stream()
                .filter(region -> !newSet.contains(region.id())).toList();
        List<RegionSnapshot> entered = now.stream().filter(region -> !oldSet.contains(region.id())).toList();
        Bukkit.getPluginManager().callEvent(new PlayerRegionChangeEvent(player, cause,
                before == null ? null : before.position(), current, exited, entered, index.revision()));
    }

    private static BlockPosition position(WorldIdentity world, double x, double y, double z) {
        return new BlockPosition(world, floor(x), floor(y), floor(z));
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    public static void forget(@NotNull UUID playerId) {
        MEMBERSHIPS.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    private record Membership(UUID playerId, BlockPosition position,
                              List<RegionSnapshot> regions, long revision) {
        private Membership {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(position, "position");
            regions = List.copyOf(regions);
        }

        private List<RegionId> regionIds() {
            return regions.stream().map(RegionSnapshot::id).toList();
        }
    }
}
