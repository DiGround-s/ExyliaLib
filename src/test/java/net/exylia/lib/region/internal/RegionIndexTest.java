package net.exylia.lib.region.internal;

import net.exylia.lib.region.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RegionIndexTest {

    private static final UUID WORLD_A_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_B_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final WorldIdentity WORLD_A = new WorldIdentity(WORLD_A_ID, "world-a");
    private static final WorldIdentity WORLD_B = new WorldIdentity(WORLD_B_ID, "world-b");

    @Test
    @DisplayName("Point queries apply exact shape, world, owner, and edge semantics")
    void exactPointQueries() {
        List<RegionSnapshot> regions = List.of(
                region("test:cuboid", "alice", WORLD_A, new Cuboid(-16, -5, -16, 16, 5, 16), 40),
                region("test:sphere", "alice", WORLD_A, new Sphere(0, 0, 0, 5), 30),
                region("test:cylinder", "bob", WORLD_A, new HorizontalCylinder(0, 0, 5), 20),
                region("test:rectangle", "bob", WORLD_A, new UnboundedYRectangle(-5, -5, 5, 5), 10),
                region("test:other-world", "alice", WORLD_B, new Cuboid(-10, -10, -10, 10, 10, 10), 100));
        RegionIndex index = RegionIndex.build(regions, 7);

        assertIds(index.query(WORLD_A_ID, 3, 4, 0), "test:cuboid", "test:sphere", "test:cylinder", "test:rectangle");
        assertIds(index.queryOwner("alice", WORLD_A_ID, 3, 4, 0), "test:cuboid", "test:sphere");
        assertIds(index.queryOwner("bob", WORLD_A_ID, 3, 4000, 0), "test:cylinder", "test:rectangle");
        assertIds(index.query(WORLD_B_ID, 0, 0, 0), "test:other-world");
        assertTrue(index.query(UUID.randomUUID(), 0, 0, 0).isEmpty());

        assertIds(index.query(WORLD_A_ID, 5, 0, 0), "test:cuboid", "test:sphere", "test:cylinder");
        assertIds(index.query(WORLD_A_ID, Math.nextUp(5.0), 0, 0), "test:cuboid");
        assertFalse(index.query(WORLD_A_ID, 16, 0, 0).stream().anyMatch(r -> r.id().equals(RegionId.parse("test:cuboid"))));
        assertTrue(index.query(WORLD_A_ID, 0, 5, 0).stream().noneMatch(r -> r.id().equals(RegionId.parse("test:cuboid"))));
    }

    @Test
    @DisplayName("Queries remain deterministic regardless of insertion order")
    void insertionOrderDoesNotAffectResults() {
        List<RegionSnapshot> regions = List.of(
                region("test:z", "owner", WORLD_A, Cuboid.block(0, 0, 0), 10),
                region("test:a", "owner", WORLD_A, Cuboid.block(0, 0, 0), 10),
                region("test:low", "owner", WORLD_A, new HorizontalCylinder(0, 0, 50), -5));
        List<RegionSnapshot> reversed = new ArrayList<>(regions);
        Collections.reverse(reversed);

        RegionIndex first = RegionIndex.build(regions, 1);
        RegionIndex second = RegionIndex.build(reversed, 2);
        assertIds(first.query(WORLD_A_ID, 0, 0, 0), "test:a", "test:z", "test:low");
        assertEquals(first.all(), second.all());
        assertEquals(first.query(WORLD_A_ID, 0, 0, 0), second.query(WORLD_A_ID, 0, 0, 0));
        assertEquals(List.of(RegionId.parse("test:a"), RegionId.parse("test:z"), RegionId.parse("test:low")),
                first.ownerIds("owner"));
    }

    @Test
    @DisplayName("Duplicate IDs are rejected and owner release can be published as a new immutable build")
    void duplicateAndOwnerReleaseBuilds() {
        RegionSnapshot alice = region("test:alice", "alice", WORLD_A, Cuboid.block(0, 0, 0), 2);
        RegionSnapshot bob = region("test:bob", "bob", WORLD_A, Cuboid.block(0, 0, 0), 1);
        assertThrows(IllegalArgumentException.class,
                () -> RegionIndex.build(List.of(alice, region("test:alice", "other", WORLD_B,
                        Cuboid.block(10, 10, 10), 99)), 1));

        RegionIndex beforeRelease = RegionIndex.build(List.of(alice, bob), 10);
        RegionIndex afterRelease = RegionIndex.build(beforeRelease.all().stream()
                .filter(region -> !region.owner().equals("alice")).toList(), 11);
        assertIds(beforeRelease.query(WORLD_A_ID, 0, 0, 0), "test:alice", "test:bob");
        assertIds(afterRelease.query(WORLD_A_ID, 0, 0, 0), "test:bob");
        assertTrue(afterRelease.owner("alice").isEmpty());
        assertEquals(10, beforeRelease.revision());
        assertEquals(11, afterRelease.revision());
    }

    @Test
    @DisplayName("Policy resolution skips missing high-priority declarations")
    void policyResolutionUsesFirstDeclaration() {
        PolicyKey<Boolean> key = PolicyKey.of(RegionId.parse("test:build"), Boolean.class, true);
        RegionSnapshot missingHigh = region("test:high", "owner", WORLD_A,
                new Cuboid(0, 0, 0, 10, 10, 10), 100, PolicySet.empty());
        RegionSnapshot explicitLow = region("test:low", "owner", WORLD_A,
                new Cuboid(0, 0, 0, 10, 10, 10), 1, PolicySet.of(key, false));
        RegionIndex index = RegionIndex.build(List.of(explicitLow, missingHigh), 1);

        assertFalse(index.resolve(WORLD_A_ID, 1, 1, 1, key));
        assertTrue(index.resolve(WORLD_A_ID, 50, 1, 50, key));

        RegionSnapshot explicitDefault = region("test:default-high", "owner", WORLD_A,
                new Cuboid(0, 0, 0, 10, 10, 10), 200, PolicySet.of(key, true));
        assertTrue(RegionIndex.build(List.of(explicitLow, explicitDefault), 2)
                .resolve(WORLD_A_ID, 1, 1, 1, key));
    }

    @Test
    @DisplayName("Sparse hierarchy covers chunk boundaries, negative cells, and every scale")
    void hierarchyLevelsAndBoundaries() {
        List<RegionSnapshot> regions = new ArrayList<>();
        for (int level = 0; level <= 32; level++) {
            double width = Math.scalb(1.0, level);
            double min = -Math.min(width / 3.0, 1_000_000_000.0);
            double max = Math.min(min + Math.max(0.5, width * 0.75), Integer.MAX_VALUE);
            regions.add(region("levels:l" + level, "levels", WORLD_A,
                    new UnboundedYRectangle(min, -0.25, max, 0.25), level));
        }
        regions.add(region("test:negative-boundary", "owner", WORLD_A,
                new Cuboid(-32, 0, -17, -16, 1, -16), 100));
        RegionIndex index = RegionIndex.build(regions, 1);

        for (RegionSnapshot region : regions) {
            HorizontalBounds bounds = region.shape().horizontalBounds();
            double x = (bounds.minX() + bounds.maxX()) / 2.0;
            double z = (bounds.minZ() + bounds.maxZ()) / 2.0;
            assertTrue(index.query(WORLD_A_ID, x, 0, z).contains(region), "missed " + region.id());
        }
        assertTrue(index.query(WORLD_A_ID, -32, 0, -17).stream()
                .anyMatch(r -> r.id().equals(RegionId.parse("test:negative-boundary"))));
        assertFalse(index.query(WORLD_A_ID, -16, 0, -16).stream()
                .anyMatch(r -> r.id().equals(RegionId.parse("test:negative-boundary"))));
    }

    @Test
    @DisplayName("Structural stats bound huge-region references without timing assumptions")
    void hugeShapeHasConstantReferenceCount() {
        double chunksWide = 100_000.0 * 16.0;
        RegionSnapshot huge = region("test:huge", "owner", WORLD_A,
                new UnboundedYRectangle(-800_000, -0.5, -800_000 + chunksWide, 0.5), 0);
        RegionIndex index = RegionIndex.build(List.of(huge), 42);
        RegionIndex.Stats stats = index.stats();

        assertEquals(1, stats.regions());
        assertEquals(1, stats.worlds());
        assertTrue(stats.references() >= 1 && stats.references() <= 4,
                "a 100k-chunk-wide shape must use at most four references");
        assertEquals(stats.references(), stats.buckets());
        assertEquals(1, stats.maxBucketCandidates());
        assertIds(index.query(WORLD_A_ID, 0, 1_000_000, 0), "test:huge");
    }

    @Test
    @DisplayName("Empty and singleton query results are immutable")
    void compactResultsAreImmutable() {
        RegionIndex empty = RegionIndex.empty();
        assertEquals(0, empty.revision());
        assertTrue(empty.all().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> empty.query(WORLD_A_ID, 0, 0, 0).add(region("test:x", "x", WORLD_A, Cuboid.block(0, 0, 0), 0)));

        RegionIndex single = RegionIndex.build(List.of(
                region("test:single", "owner", WORLD_A, Cuboid.block(0, 0, 0), 0)), 1);
        List<RegionSnapshot> result = single.query(WORLD_A_ID, 0, 0, 0);
        assertEquals(1, result.size());
        assertThrows(UnsupportedOperationException.class, () -> result.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> single.all().clear());
        assertThrows(UnsupportedOperationException.class, () -> single.owner("owner").clear());
    }

    @Test
    @DisplayName("Randomized index queries match a canonical brute-force scan")
    void randomizedQueriesMatchBruteForce() {
        Random random = new Random(0x5EED_C0DEL);
        List<RegionSnapshot> regions = new ArrayList<>();
        for (int index = 0; index < 360; index++) {
            WorldIdentity world = index % 5 == 0 ? WORLD_B : WORLD_A;
            RegionShape shape = randomShape(random, index);
            regions.add(region("random:r" + String.format("%03d", index), "owner-" + index % 7,
                    world, shape, random.nextInt(21) - 10));
        }
        // Explicit large bounds force high hierarchy levels and overlap ordinary regions.
        regions.add(region("random:huge", "owner-huge", WORLD_A,
                new UnboundedYRectangle(-1_500_000_000, -1_500_000_000,
                        1_500_000_000, 1_500_000_000), 3));
        Collections.shuffle(regions, random);
        RegionIndex index = RegionIndex.build(regions, 99);

        for (int query = 0; query < 4_000; query++) {
            UUID world = query % 7 == 0 ? WORLD_B_ID : WORLD_A_ID;
            double x = randomCoordinate(random, query);
            double y = random.nextDouble(-100, 101);
            double z = randomCoordinate(random, query * 31);
            List<RegionSnapshot> expected = regions.stream()
                    .filter(region -> region.worldId().equals(world))
                    .filter(region -> region.shape().contains(x, y, z))
                    .sorted(RegionSnapshot.ORDER)
                    .toList();
            List<RegionSnapshot> actual = index.query(world, x, y, z);
            assertEquals(expected, actual, () -> "query at " + x + ',' + y + ',' + z);
            assertEquals(actual.size(), actual.stream().map(RegionSnapshot::id).distinct().count(),
                    "result must never duplicate a region ID");
        }
    }

    private static RegionShape randomShape(Random random, int index) {
        double x = random.nextInt(-2_000, 2_001) + (index % 3 == 0 ? 0 : random.nextDouble());
        double y = random.nextInt(-80, 81);
        double z = random.nextInt(-2_000, 2_001) + (index % 4 == 0 ? 0 : random.nextDouble());
        double width = index % 29 == 0 ? 65_536 : random.nextDouble(0.25, 90);
        double depth = index % 31 == 0 ? 131_072 : random.nextDouble(0.25, 90);
        return switch (index % 4) {
            case 0 -> new Cuboid(x, y, z, x + width, y + random.nextDouble(0.25, 50), z + depth);
            case 1 -> new UnboundedYRectangle(x, z, x + width, z + depth);
            case 2 -> new Sphere(x, y, z, random.nextDouble(0.25, 50));
            default -> new HorizontalCylinder(x, z, random.nextDouble(0.25, 50));
        };
    }

    private static double randomCoordinate(Random random, int query) {
        if (query % 19 == 0) {
            int[] boundaries = {-2048, -1024, -32, -17, -16, -1, 0, 1, 15, 16, 17, 32, 1024, 2048};
            return boundaries[random.nextInt(boundaries.length)];
        }
        if (query % 97 == 0) return random.nextDouble(-1_400_000_000, 1_400_000_000);
        return random.nextDouble(-2_200, 2_201);
    }

    private static RegionSnapshot region(String id, String owner, WorldIdentity world,
                                           RegionShape shape, int priority) {
        return region(id, owner, world, shape, priority, PolicySet.empty());
    }

    private static RegionSnapshot region(String id, String owner, WorldIdentity world,
                                           RegionShape shape, int priority, PolicySet policies) {
        return new RegionSnapshot(RegionId.parse(id), owner, world, shape, priority, policies);
    }

    private static void assertIds(List<RegionSnapshot> actual, String... expected) {
        List<RegionId> ids = actual.stream().map(RegionSnapshot::id).toList();
        assertEquals(List.of(expected).stream().map(RegionId::parse).toList(), ids);
        assertEquals(ids.size(), new HashSet<>(ids).size(), "query returned duplicate region IDs");
    }
}
