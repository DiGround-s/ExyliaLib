package net.exylia.lib.region.internal;

import net.exylia.lib.region.HorizontalBounds;
import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.region.RegionId;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.RegionSnapshot;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.UUID;

/**
 * Immutable, package-private spatial lookup state for one published region revision.
 *
 * <p>A build uses mutable collections only while constructing an instance off-side. Every
 * collection and candidate array reachable from the finished instance is then private and never
 * mutated. Publication can consequently be a single reference replacement; readers need neither
 * locks nor concurrent collections.
 *
 * <p>The horizontal index is a sparse, aligned hierarchy. A region is placed at the smallest
 * power-of-two level whose cell width is at least the larger horizontal extent of its bounds. An
 * interval no wider than a cell intersects at most two aligned cells on an axis, so a region has
 * at most four references regardless of its size. This avoids both chunk-by-chunk amplification
 * and an oversized-region fallback list. A point visits exactly one cell at each of the 33 levels.
 * Because a point can belong to only one of a region's cells at its selected level, candidates on
 * a query path are unique and need no deduplication.
 */
final class RegionIndex {

    private static final int MIN_LEVEL = 0;
    private static final int MAX_LEVEL = 32;
    private static final int LEVEL_COUNT = MAX_LEVEL - MIN_LEVEL + 1;

    private static final Comparator<RegionSnapshot> ORDER = (left, right) -> {
        int priority = Integer.compare(right.priority(), left.priority());
        return priority != 0 ? priority : left.id().compareTo(right.id());
    };

    private static final RegionIndex EMPTY = new RegionIndex(
        0L,
        List.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        new Stats(0, 0, 0, 0, 0)
    );

    private final long revision;
    private final List<RegionSnapshot> all;
    private final Map<RegionId, RegionSnapshot> byId;
    private final Map<String, OwnerRegions> byOwner;
    private final Map<UUID, WorldIndex> byWorld;
    private final Stats stats;

    private RegionIndex(long revision,
                        List<RegionSnapshot> all,
                        Map<RegionId, RegionSnapshot> byId,
                        Map<String, OwnerRegions> byOwner,
                        Map<UUID, WorldIndex> byWorld,
                        Stats stats) {
        this.revision = revision;
        this.all = all;
        this.byId = byId;
        this.byOwner = byOwner;
        this.byWorld = byWorld;
        this.stats = stats;
    }

    /**
     * Returns the canonical empty index.
     *
     * @return an immutable index at revision zero
     */
    static RegionIndex empty() {
        return EMPTY;
    }

    /**
     * Whether this index holds no regions at all.
     *
     * <p>Lets the movement path skip a query that can only return empty, which is
     * the whole cost of the region module for a server whose plugins never
     * register one.
     *
     * @return {@code true} when no region is indexed
     */
    boolean isEmpty() {
        return all.isEmpty();
    }

    /**
     * Builds and validates an immutable index without modifying any published index.
     *
     * <p>Input order is deliberately discarded. Regions, owner views, and every spatial bucket
     * use the same total order: descending priority followed by ascending region identifier.
     * Duplicate identifiers, blank owners, absent worlds or shapes, and non-finite or reversed
     * bounds fail the whole build so a partially valid revision can never be published.
     *
     * @param regions immutable region snapshots to index
     * @param revision revision represented by the new index
     * @return the fully built immutable index
     * @throws NullPointerException if the collection or one of its snapshots is {@code null}
     * @throws IllegalArgumentException if a snapshot violates an index invariant
     */
    static RegionIndex build(Collection<RegionSnapshot> regions, long revision) {
        Objects.requireNonNull(regions, "regions");
        if (regions.isEmpty()) {
            return revision == 0L ? EMPTY : new RegionIndex(
                revision, List.of(), Map.of(), Map.of(), Map.of(),
                new Stats(0, 0, 0, 0, 0)
            );
        }

        List<RegionSnapshot> ordered = new ArrayList<>(regions.size());
        Map<RegionId, RegionSnapshot> ids = new HashMap<>(mapCapacity(regions.size()));
        Map<String, List<RegionSnapshot>> owners = new HashMap<>();
        Map<UUID, MutableWorldIndex> worlds = new HashMap<>();

        for (RegionSnapshot region : regions) {
            Objects.requireNonNull(region, "regions contains null");
            RegionId id = Objects.requireNonNull(region.id(), "region id");
            String owner = requireOwner(region.owner(), id);
            UUID worldId = Objects.requireNonNull(region.worldId(), "world id for " + id);
            RegionShape shape = Objects.requireNonNull(region.shape(), "shape for " + id);
            HorizontalBounds bounds = Objects.requireNonNull(
                shape.horizontalBounds(), "horizontal bounds for " + id
            );
            validateBounds(bounds, id);

            RegionSnapshot duplicate = ids.putIfAbsent(id, region);
            if (duplicate != null) {
                throw new IllegalArgumentException("Duplicate region id: " + id);
            }

            ordered.add(region);
            owners.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(region);
            worlds.computeIfAbsent(worldId, ignored -> new MutableWorldIndex()).add(region, bounds);
        }

        ordered.sort(ORDER);
        List<RegionSnapshot> immutableAll = List.copyOf(ordered);

        Map<String, OwnerRegions> immutableOwners = new HashMap<>(mapCapacity(owners.size()));
        for (Map.Entry<String, List<RegionSnapshot>> entry : owners.entrySet()) {
            List<RegionSnapshot> ownerRegions = entry.getValue();
            ownerRegions.sort(ORDER);
            List<RegionSnapshot> regionView = List.copyOf(ownerRegions);
            List<RegionId> idView = ownerRegions.stream().map(RegionSnapshot::id).toList();
            immutableOwners.put(entry.getKey(), new OwnerRegions(idView, regionView));
        }

        int buckets = 0;
        long references = 0L;
        int maxBucketCandidates = 0;
        Map<UUID, WorldIndex> immutableWorlds = new HashMap<>(mapCapacity(worlds.size()));
        for (Map.Entry<UUID, MutableWorldIndex> entry : worlds.entrySet()) {
            WorldIndex world = entry.getValue().freeze();
            immutableWorlds.put(entry.getKey(), world);
            buckets += world.bucketCount;
            references += world.referenceCount;
            maxBucketCandidates = Math.max(maxBucketCandidates, world.maxBucketCandidates);
        }

        return new RegionIndex(
            revision,
            immutableAll,
            Map.copyOf(ids),
            Map.copyOf(immutableOwners),
            Map.copyOf(immutableWorlds),
            new Stats(ordered.size(), worlds.size(), buckets, references, maxBucketCandidates)
        );
    }

    /**
     * Returns the revision represented by this immutable snapshot.
     *
     * @return revision number supplied at build time
     */
    long revision() {
        return revision;
    }

    /**
     * Returns all regions in deterministic query order.
     *
     * @return immutable ordered region list
     */
    List<RegionSnapshot> all() {
        return all;
    }

    /**
     * Performs an exact identifier lookup.
     *
     * @param id region identifier
     * @return the matching snapshot, or {@code null} when absent
     */
    RegionSnapshot get(RegionId id) {
        return byId.get(id);
    }

    /**
     * Returns one owner's regions without examining any other owner.
     *
     * @param owner exact owner name
     * @return immutable ordered list, or the shared empty list when absent
     */
    List<RegionSnapshot> owner(String owner) {
        OwnerRegions found = byOwner.get(owner);
        return found == null ? List.of() : found.regions;
    }

    /**
     * Returns one owner's exact identifiers without examining any other owner.
     *
     * @param owner exact owner name
     * @return immutable ordered identifier list, or the shared empty list when absent
     */
    List<RegionId> ownerIds(String owner) {
        OwnerRegions found = byOwner.get(owner);
        return found == null ? List.of() : found.ids;
    }

    /**
     * Finds regions containing a point.
     *
     * <p>No location object, candidate set, or sorting workspace is created. Candidate arrays are
     * preordered, and the at-most-33 arrays on the point's hierarchy path are merged directly. An
     * empty result returns the shared empty list and a single result uses the JDK singleton list.
     * Larger results allocate only the returned immutable list and its growable backing array.
     *
     * @param worldId world UUID, never a platform world object
     * @param x point x coordinate
     * @param y point y coordinate
     * @param z point z coordinate
     * @return immutable regions ordered by priority and identifier
     */
    List<RegionSnapshot> query(UUID worldId, double x, double y, double z) {
        return query0(null, worldId, x, y, z);
    }

    /**
     * Finds one owner's regions containing a point while using the world's spatial candidates.
     *
     * <p>The owner map is consulted first, so an unknown owner exits immediately. Candidate
     * filtering then compares only regions on the point's bounded hierarchy path; no owner map or
     * global region list is scanned.
     *
     * @param owner exact owner name
     * @param worldId world UUID
     * @param x point x coordinate
     * @param y point y coordinate
     * @param z point z coordinate
     * @return immutable ordered containing regions for the owner
     */
    List<RegionSnapshot> queryOwner(String owner, UUID worldId, double x, double y, double z) {
        if (!byOwner.containsKey(owner)) {
            return List.of();
        }
        return query0(owner, worldId, x, y, z);
    }

    /**
     * Resolves a typed policy using normal spatial precedence.
     *
     * <p>A containing region that does not explicitly declare the key is skipped rather than
     * masking lower-priority regions. The key's default is returned only if no containing region
     * declares it. The unchecked cast is confined here: {@link PolicyKey} is the type token that
     * governs values in a snapshot's policy map.
     *
     * @param worldId world UUID
     * @param x point x coordinate
     * @param y point y coordinate
     * @param z point z coordinate
     * @param key typed policy key
     * @param <T> policy value type
     * @return the first explicit value, or the key default
     */
    <T> T resolve(UUID worldId, double x, double y, double z, PolicyKey<T> key) {
        Objects.requireNonNull(key, "key");
        WorldIndex world = queryableWorld(worldId, x, y, z);
        if (world == null) {
            return key.defaultValue();
        }

        RegionSnapshot previous = null;
        RegionSnapshot candidate;
        while ((candidate = nextCandidate(world, x, z, previous)) != null) {
            previous = candidate;
            if (!candidate.shape().contains(x, y, z)) {
                continue;
            }
            java.util.Optional<T> explicit = candidate.policySet().explicit(key);
            if (explicit.isPresent()) {
                return explicit.get();
            }
        }
        return key.defaultValue();
    }

    /**
     * Returns compact, precomputed build statistics; this never walks the index.
     *
     * @return immutable index statistics
     */
    Stats stats() {
        return stats;
    }

    private List<RegionSnapshot> query0(String owner, UUID worldId, double x, double y, double z) {
        WorldIndex world = queryableWorld(worldId, x, y, z);
        if (world == null) {
            return List.of();
        }

        RegionSnapshot first = nextMatch(world, owner, x, y, z, null);
        if (first == null) {
            return List.of();
        }
        RegionSnapshot second = nextMatch(world, owner, x, y, z, first);
        if (second == null) {
            return List.of(first);
        }

        ResultBuilder matches = new ResultBuilder(first, second);
        RegionSnapshot previous = second;
        RegionSnapshot candidate;
        while ((candidate = nextMatch(world, owner, x, y, z, previous)) != null) {
            matches.add(candidate);
            previous = candidate;
        }
        return matches.freeze();
    }

    private static RegionSnapshot nextMatch(WorldIndex world, String owner,
                                            double x, double y, double z,
                                            RegionSnapshot previous) {
        RegionSnapshot candidate = previous;
        while ((candidate = nextCandidate(world, x, z, candidate)) != null) {
            if ((owner == null || owner.equals(candidate.owner()))
                && candidate.shape().contains(x, y, z)) {
                return candidate;
            }
        }
        return null;
    }

    private static RegionSnapshot nextCandidate(WorldIndex world, double x, double z,
                                                 RegionSnapshot previous) {
        RegionSnapshot selected = null;
        for (int level = MIN_LEVEL; level <= MAX_LEVEL; level++) {
            Map<Long, RegionSnapshot[]> levelMap = world.levels[level];
            if (levelMap == null) {
                continue;
            }
            RegionSnapshot[] bucket = levelMap.get(cellKey(cell(x, level), cell(z, level)));
            if (bucket == null) {
                continue;
            }
            int position = previous == null ? 0 : insertionPointAfter(bucket, previous);
            if (position < bucket.length
                && (selected == null || ORDER.compare(bucket[position], selected) < 0)) {
                selected = bucket[position];
            }
        }
        return selected;
    }

    private static int insertionPointAfter(RegionSnapshot[] bucket, RegionSnapshot previous) {
        int low = 0;
        int high = bucket.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ORDER.compare(bucket[middle], previous) <= 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private WorldIndex queryableWorld(UUID worldId, double x, double y, double z) {
        if (worldId == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || x < Integer.MIN_VALUE || x >= (double) Integer.MAX_VALUE + 1.0
            || z < Integer.MIN_VALUE || z >= (double) Integer.MAX_VALUE + 1.0) {
            return null;
        }
        return byWorld.get(worldId);
    }

    private static String requireOwner(String owner, RegionId id) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Region " + id + " has a blank owner");
        }
        return owner;
    }

    private static void validateBounds(HorizontalBounds bounds, RegionId id) {
        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();
        if (!Double.isFinite(minX) || !Double.isFinite(maxX)
            || !Double.isFinite(minZ) || !Double.isFinite(maxZ)
            || minX > maxX || minZ > maxZ
            || minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE
            || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Region " + id + " has invalid horizontal bounds: " + bounds);
        }
    }

    private static int selectedLevel(HorizontalBounds bounds) {
        double extent = Math.max(bounds.maxX() - bounds.minX(), bounds.maxZ() - bounds.minZ());
        int level = MIN_LEVEL;
        while (level < MAX_LEVEL && Math.scalb(1.0, level) < extent) {
            level++;
        }
        return level;
    }

    private static int cell(double coordinate, int level) {
        return floorToInt(Math.scalb(coordinate, -level));
    }

    private static int floorToInt(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xffff_ffffL);
    }

    private static int mapCapacity(int expectedSize) {
        if (expectedSize < 3) {
            return expectedSize + 1;
        }
        return expectedSize < 1 << 30 ? (int) Math.ceil(expectedSize / 0.75d) : Integer.MAX_VALUE;
    }

    /** Compact immutable build statistics. */
    record Stats(int regions, int worlds, int buckets, long references, int maxBucketCandidates) {
    }

    /** Stores both exact owner projections once, avoiding repeated identifier extraction. */
    private record OwnerRegions(List<RegionId> ids, List<RegionSnapshot> regions) {
    }

    /** Mutable off-side builder for one world's level maps. */
    private static final class MutableWorldIndex {

        private final Map<Long, List<RegionSnapshot>>[] levels;

        @SuppressWarnings("unchecked")
        private MutableWorldIndex() {
            levels = (Map<Long, List<RegionSnapshot>>[]) new Map<?, ?>[LEVEL_COUNT];
        }

        private void add(RegionSnapshot region, HorizontalBounds bounds) {
            int level = selectedLevel(bounds);
            int minCellX = cell(bounds.minX(), level);
            int maxCellX = cell(bounds.maxX(), level);
            int minCellZ = cell(bounds.minZ(), level);
            int maxCellZ = cell(bounds.maxZ(), level);

            // Extent <= cell width proves this bound. Keep the check to guard arithmetic changes.
            if (maxCellX - (long) minCellX > 1L || maxCellZ - (long) minCellZ > 1L) {
                throw new IllegalArgumentException("Horizontal bounds exceed four cells: " + bounds);
            }

            Map<Long, List<RegionSnapshot>> levelMap = levels[level];
            if (levelMap == null) {
                levelMap = new HashMap<>();
                levels[level] = levelMap;
            }
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    levelMap.computeIfAbsent(cellKey(cellX, cellZ), ignored -> new ArrayList<>())
                        .add(region);
                }
            }
        }

        private WorldIndex freeze() {
            @SuppressWarnings("unchecked")
            Map<Long, RegionSnapshot[]>[] frozen = (Map<Long, RegionSnapshot[]>[]) new Map<?, ?>[LEVEL_COUNT];
            int bucketCount = 0;
            long referenceCount = 0L;
            int maxBucketCandidates = 0;

            for (int level = MIN_LEVEL; level <= MAX_LEVEL; level++) {
                Map<Long, List<RegionSnapshot>> mutableLevel = levels[level];
                if (mutableLevel == null) {
                    continue;
                }
                Map<Long, RegionSnapshot[]> immutableLevel = new HashMap<>(mapCapacity(mutableLevel.size()));
                for (Map.Entry<Long, List<RegionSnapshot>> entry : mutableLevel.entrySet()) {
                    List<RegionSnapshot> candidates = entry.getValue();
                    candidates.sort(ORDER);
                    RegionSnapshot[] bucket = candidates.toArray(RegionSnapshot[]::new);
                    immutableLevel.put(entry.getKey(), bucket);
                    bucketCount++;
                    referenceCount += bucket.length;
                    maxBucketCandidates = Math.max(maxBucketCandidates, bucket.length);
                }
                frozen[level] = Map.copyOf(immutableLevel);
            }
            return new WorldIndex(frozen, bucketCount, referenceCount, maxBucketCandidates);
        }
    }

    /**
     * Lazily materializes a multi-result list without an intermediate collection or final copy.
     */
    private static final class ResultBuilder {

        private RegionSnapshot[] snapshots = new RegionSnapshot[4];
        private int size = 2;

        private ResultBuilder(RegionSnapshot first, RegionSnapshot second) {
            snapshots[0] = first;
            snapshots[1] = second;
        }

        private void add(RegionSnapshot snapshot) {
            if (size == snapshots.length) {
                snapshots = java.util.Arrays.copyOf(snapshots, size << 1);
            }
            snapshots[size++] = snapshot;
        }

        private List<RegionSnapshot> freeze() {
            if (size != snapshots.length) {
                snapshots = java.util.Arrays.copyOf(snapshots, size);
            }
            return new SnapshotList(snapshots);
        }
    }

    /**
     * Exact-size, array-backed immutable query result.
     *
     * <p>Immutable in contract but not a {@code java.util.ImmutableCollections}
     * type, so {@link List#copyOf} does not recognize it and copies instead. That
     * makes a defensive copy of a query result silently real work on a hot path;
     * callers holding on to one must store it as handed over.
     */
    private static final class SnapshotList extends AbstractList<RegionSnapshot> implements RandomAccess {

        private final RegionSnapshot[] snapshots;

        private SnapshotList(RegionSnapshot[] snapshots) {
            this.snapshots = snapshots;
        }

        @Override
        public RegionSnapshot get(int index) {
            Objects.checkIndex(index, snapshots.length);
            return snapshots[index];
        }

        @Override
        public int size() {
            return snapshots.length;
        }
    }

    /** Read-only sparse levels for one UUID-addressed world. */
    private static final class WorldIndex {

        private final Map<Long, RegionSnapshot[]>[] levels;
        private final int bucketCount;
        private final long referenceCount;
        private final int maxBucketCandidates;

        private WorldIndex(Map<Long, RegionSnapshot[]>[] levels,
                           int bucketCount,
                           long referenceCount,
                           int maxBucketCandidates) {
            this.levels = levels;
            this.bucketCount = bucketCount;
            this.referenceCount = referenceCount;
            this.maxBucketCandidates = maxBucketCandidates;
        }
    }

}
