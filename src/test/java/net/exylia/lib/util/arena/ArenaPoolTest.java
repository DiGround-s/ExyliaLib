package net.exylia.lib.util.arena;

import net.exylia.lib.FakeServer;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.PolicySet;
import net.exylia.lib.region.RegionId;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaPoolTest {

    /** Pastes at x=1000*n, z=-500; regenerations wait until the test completes them. */
    static final class FakePlacement implements ArenaPool.Placement {
        final World temporary = FakeServer.newWorld("temporary");
        final List<CompletableFuture<Boolean>> regenerations = new ArrayList<>();
        final Set<String> regions = new HashSet<>();
        int pastes;
        boolean failPaste;

        @Override
        public CompletableFuture<Location> copy(ArenaTemplate template) {
            if (failPaste) {
                return CompletableFuture.completedFuture(null);
            }
            pastes++;
            return CompletableFuture.completedFuture(new Location(temporary, 1000 * pastes, template.bounds().minY(), -500));
        }

        @Override
        public CompletableFuture<Boolean> regenerate(String schematic, Cuboid bounds, World world) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            regenerations.add(future);
            return future;
        }

        @Override
        public RegionId register(String key, World world, Cuboid bounds, PolicySet policies) {
            regions.add(key);
            return new RegionId("test", key);
        }

        @Override
        public void unregister(RegionId id) {
            regions.remove(id.value());
        }

        void finishRegenerations() {
            new ArrayList<>(regenerations).forEach(future -> future.complete(true));
            regenerations.clear();
        }
    }

    World world;
    FakePlacement placement;
    ArenaPool pool;

    @BeforeEach
    void setUp() {
        world = FakeServer.newWorld("world");
        placement = new FakePlacement();
        pool = new ArenaPool(placement, "duel_");
    }

    ArenaTemplate template(String id, int maxCopies) {
        return new ArenaTemplate(id, world, Cuboid.blocks(100, 60, 200, 139, 79, 229), "s_" + id,
                Map.of("a", new Location(world, 110.5, 61, 210.5, 90, 10)),
                Map.of("goal", Cuboid.blocks(120, 60, 220, 121, 62, 221)), PolicySet.empty(), maxCopies);
    }

    ArenaLease take(String id) {
        return pool.acquire(id).join().orElseThrow();
    }

    @Test
    void theOriginalComesFirstAndIsNotMoved() {
        pool.register(template("forest", 2));
        ArenaLease lease = take("forest");
        assertFalse(lease.isClone());
        assertSame(world, lease.world());
        assertEquals(new Location(world, 110.5, 61, 210.5, 90, 10), lease.point("a"));
        assertEquals(Cuboid.blocks(100, 60, 200, 139, 79, 229), lease.bounds());
        assertEquals(0, placement.pastes);
    }

    @Test
    void aCopyMovesPointsAndBoxesByThePasteOffset() {
        pool.register(template("forest", 2));
        take("forest");
        ArenaLease copy = take("forest");
        assertTrue(copy.isClone());
        assertEquals(1, copy.copy());
        assertSame(placement.temporary, copy.world());
        // Pasted at (1000, 60, -500): the box minimum (100, 60, 200) lands there.
        assertEquals(Cuboid.blocks(1000, 60, -500, 1039, 79, -471), copy.bounds());
        Location a = copy.point("a");
        assertEquals(new Location(placement.temporary, 1010.5, 61, -489.5, 90, 10), a);
        assertEquals(Cuboid.blocks(1020, 60, -480, 1021, 62, -479), copy.box("goal"));
        assertNull(copy.point("missing"));
        assertNull(copy.box("missing"));
        assertTrue(placement.regions.containsAll(Set.of("duel_forest", "duel_forest_1")));
    }

    @Test
    void copiesStopAtTheMaximum() {
        pool.register(template("forest", 1));
        take("forest");
        take("forest");
        assertEquals(Optional.empty(), pool.acquire("forest").join());
        assertEquals(1, placement.pastes);
    }

    @Test
    void acquireCopyLeavesTheOriginalAloneAndReusesFreedCopies() {
        pool.register(template("forest", 1));
        ArenaLease copy = pool.acquireCopy("forest").join().orElseThrow();
        assertTrue(copy.isClone());
        assertTrue(pool.acquireCopy("forest").join().isEmpty(), "the only copy is taken");
        assertFalse(take("forest").isClone(), "the original was never handed out");
        copy.release();
        placement.finishRegenerations();
        assertEquals(1, pool.acquireCopy("forest").join().orElseThrow().copy());
        assertEquals(1, placement.pastes);
        assertTrue(pool.acquireCopy("missing").join().isEmpty());
    }

    @Test
    void zeroCopiesNeverPastes() {
        pool.register(template("forest", 0));
        take("forest");
        assertTrue(pool.acquire("forest").join().isEmpty());
        assertEquals(0, placement.pastes);
    }

    @Test
    void anArenaIsFreeOnlyAfterItRegenerated() {
        pool.register(template("forest", 0));
        ArenaLease lease = take("forest");
        CompletableFuture<Boolean> released = lease.release();
        assertTrue(pool.acquire("forest").join().isEmpty(), "still regenerating");
        placement.finishRegenerations();
        assertTrue(released.join());
        assertFalse(take("forest").isClone());
    }

    @Test
    void regeneratingIdleArenasSkipsBusyOnesAndHoldsTheRestUntilDone() {
        pool.register(template("forest", 1));
        pool.register(template("desert", 0));
        take("forest");
        CompletableFuture<Integer> done = pool.regenerateIdle();
        assertEquals(1, placement.regenerations.size(), "only the free desert original");
        assertTrue(pool.acquire("desert").join().isEmpty(), "still regenerating");
        assertEquals(new ArenaPool.Usage(1, 0, 0), pool.usage("desert").orElseThrow());
        placement.finishRegenerations();
        assertEquals(1, done.join());
        assertEquals(new ArenaPool.Usage(0, 0, 0), pool.usage("desert").orElseThrow());
    }

    @Test
    void usageCountsBusyInstancesAndCopies() {
        pool.register(template("forest", 3));
        take("forest");
        take("forest");
        assertEquals(new ArenaPool.Usage(2, 1, 3), pool.usage("forest").orElseThrow());
        assertTrue(pool.usage("missing").isEmpty());
    }

    @Test
    void aFreedCopyIsReusedRatherThanPastedAgain() {
        pool.register(template("forest", 3));
        take("forest");
        ArenaLease copy = take("forest");
        copy.release();
        placement.finishRegenerations();
        ArenaLease again = take("forest");
        assertEquals(1, again.copy());
        assertEquals(1, placement.pastes);
    }

    @Test
    void releasingTwiceDoesNotFreeSomebodyElsesMatch() {
        pool.register(template("forest", 0));
        ArenaLease first = take("forest");
        CompletableFuture<Boolean> once = first.release();
        placement.finishRegenerations();
        ArenaLease second = take("forest");
        assertSame(once, first.release());
        assertTrue(placement.regenerations.isEmpty());
        assertTrue(pool.acquire("forest").join().isEmpty(), "the second match still holds it");
        assertNotSame(first, second);
    }

    @Test
    void aFailedPasteGivesTheSlotBackAndTriesTheNextTemplate() {
        pool.register(template("forest", 1));
        pool.register(template("desert", 1));
        take("forest");
        take("desert");
        placement.failPaste = true;
        assertTrue(pool.acquireAny(List.of("forest", "desert")).join().isEmpty());
        placement.failPaste = false;
        ArenaLease copy = pool.acquireAny(List.of("forest", "desert")).join().orElseThrow();
        assertEquals("forest", copy.template().id());
    }

    @Test
    void acquireAnyPrefersAFreeInstanceOverANewCopy() {
        pool.register(template("forest", 5));
        pool.register(template("desert", 5));
        take("forest");
        ArenaLease lease = pool.acquireAny(List.of("forest", "desert")).join().orElseThrow();
        assertEquals("desert", lease.template().id());
        assertEquals(0, placement.pastes);
    }

    @Test
    void reRegisteringKeepsABusyOriginalTakenAndDropsOldCopies() {
        pool.register(template("forest", 2));
        ArenaLease original = take("forest");
        ArenaLease copy = take("forest");
        pool.register(template("forest", 2));
        assertTrue(placement.regions.contains("duel_forest_1"), "busy copy keeps its region");

        ArenaLease next = take("forest");
        assertTrue(next.isClone(), "the original is still in a match");
        assertEquals(2, next.copy(), "numbers are not reused");

        copy.release();
        original.release();
        placement.finishRegenerations();
        assertFalse(placement.regions.contains("duel_forest_1"), "released old copy is retired");
        assertFalse(take("forest").isClone());
    }

    @Test
    void unregisteringRemovesRegionsAndRefusesAcquires() {
        pool.register(template("forest", 2));
        take("forest");
        take("forest").release();
        placement.finishRegenerations();
        assertTrue(pool.unregister("forest"));
        assertTrue(placement.regions.isEmpty());
        assertTrue(pool.acquire("forest").join().isEmpty());
        assertFalse(pool.unregister("forest"));
    }
}
