package net.exylia.lib.region.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.PluginRegions;
import net.exylia.lib.region.PolicySet;
import net.exylia.lib.region.RegionSnapshot;
import net.exylia.lib.region.Regions;
import net.exylia.lib.region.WorldIdentity;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Block ownership: what the record says, and how long it says it.
 *
 * <p>The failure modes worth catching are the ones that make {@code player_build_only}
 * either useless or unusable: a position that packs onto another position, a removal
 * that breaks the probe chain and hides unrelated blocks, a gate that stays armed on
 * a server that declares nothing, and a region that goes away leaving its blocks
 * behind.
 */
class PlacedBlockTest {

    private World world;
    private Plugin plugin;
    private PluginRegions regions;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Regions.releaseAll();

        world = FakeServer.newWorld("arena");
        plugin = FakeServer.newPlugin("Practice", null);
        RegionRuntime.init(plugin);
        regions = Regions.of(plugin);
    }

    private RegionSnapshot arena(String key, PolicySet policies) {
        return regions.region(key, WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 63, 63, 63), 0, policies);
    }

    private static final PolicySet BUILD_ONLY =
            PolicySet.of(CommonRegionPolicies.PLAYER_BUILD_ONLY, true);

    // ------------------------------------------------------------------
    // Packing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every position packs to its own key and unpacks back")
    void packingIsExact() {
        int[] samples = {0, 1, -1, 63, -64, 30_000_000, -30_000_000, 2047, -2048};
        Set<Long> keys = new HashSet<>();
        for (int x : samples) {
            for (int z : samples) {
                for (int y : new int[]{0, 1, -1, 319, -64, 2047, -2048}) {
                    long key = PositionSet.pack(x, y, z);
                    assertTrue(keys.add(key), "duplicate key for " + x + "," + y + "," + z);
                    assertEquals(x, PlacedBlockRuntime.unpackX(key));
                    assertEquals(y, PlacedBlockRuntime.unpackY(key));
                    assertEquals(z, PlacedBlockRuntime.unpackZ(key));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a removal never hides another position")
    void removalKeepsProbeChainsIntact() {
        // Removal shifts the chain backwards rather than leaving tombstones, and
        // a shift that stops one slot early makes an unrelated block invisible.
        // Randomised churn across a full growth cycle is what surfaces that.
        PositionSet set = new PositionSet();
        Random random = new Random(20260824L);
        List<Long> live = new ArrayList<>();
        Set<Long> mirror = new HashSet<>();

        for (int step = 0; step < 20_000; step++) {
            if (live.isEmpty() || random.nextInt(3) != 0) {
                long key = PositionSet.pack(random.nextInt(400) - 200,
                        random.nextInt(320) - 64, random.nextInt(400) - 200);
                if (mirror.add(key)) {
                    assertTrue(set.add(key));
                    live.add(key);
                } else {
                    assertFalse(set.add(key));
                }
            } else {
                long key = live.remove(random.nextInt(live.size()));
                mirror.remove(key);
                assertTrue(set.remove(key));
                assertFalse(set.contains(key));
            }
            assertEquals(mirror.size(), set.size());
        }
        for (long key : mirror) {
            assertTrue(set.contains(key), "lost a live position after churn");
        }
    }

    @Test
    @DisplayName("the origin is a real position, not the empty marker")
    void originIsHeldApart() {
        PositionSet set = new PositionSet();
        assertFalse(set.contains(PositionSet.pack(0, 0, 0)));
        assertTrue(set.add(PositionSet.pack(0, 0, 0)));
        assertTrue(set.contains(PositionSet.pack(0, 0, 0)));
        assertEquals(1, set.size());
        assertTrue(set.remove(PositionSet.pack(0, 0, 0)));
        assertTrue(set.isEmpty());
    }

    // ------------------------------------------------------------------
    // The gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a server whose regions declare nothing never arms the tracker")
    void gateStaysDisarmedWithoutThePolicy() {
        regions.register(arena("plain", PolicySet.empty()));
        assertFalse(PlacedBlockRuntime.tracking());

        regions.register(arena("tracked", BUILD_ONLY));
        assertTrue(PlacedBlockRuntime.tracking());

        regions.unregister("tracked");
        assertFalse(PlacedBlockRuntime.tracking());
    }

    @Test
    @DisplayName("turning the policy off disarms the tracker and drops the record")
    void replacingWithoutThePolicyForgets() {
        RegionSnapshot arena = regions.register(arena("arena", BUILD_ONLY));
        PlacedBlockRuntime.placed(arena, UUID.randomUUID(), Material.STONE, 4, 5, 6);
        assertTrue(PlacedBlockRuntime.tracked(arena.id(), 4, 5, 6));

        regions.replace(arena("arena", PolicySet.empty()));
        assertFalse(PlacedBlockRuntime.tracking());
        assertFalse(PlacedBlockRuntime.tracked(arena.id(), 4, 5, 6));
    }

    // ------------------------------------------------------------------
    // The record
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a placed block is this plugin's, until it is broken")
    void placedThenBroken() {
        RegionSnapshot arena = regions.register(arena("arena", BUILD_ONLY));
        UUID player = UUID.randomUUID();

        assertFalse(regions.placedByPlayer(world.getUID(), 10, 11, 12));
        PlacedBlockRuntime.placed(arena, player, Material.STONE, 10, 11, 12);
        assertTrue(regions.placedByPlayer(world.getUID(), 10, 11, 12));

        // The block next to it was never placed by anybody.
        assertFalse(regions.placedByPlayer(world.getUID(), 11, 11, 12));

        assertTrue(PlacedBlockRuntime.untrack(arena.id(), 10, 11, 12));
        assertFalse(regions.placedByPlayer(world.getUID(), 10, 11, 12));
    }

    @Test
    @DisplayName("a block outside the region is nobody's")
    void outsideTheRegionIsNotAttributed() {
        RegionSnapshot arena = regions.register(arena("arena", BUILD_ONLY));
        PlacedBlockRuntime.placed(arena, UUID.randomUUID(), Material.STONE, 4, 5, 6);

        // Recorded against the region, but the query is spatial: a point the
        // region does not contain cannot reach the record.
        assertFalse(regions.placedByPlayer(world.getUID(), 400, 5, 400));
    }

    @Test
    @DisplayName("unregistering a region forgets every block inside it")
    void unregisteringForgets() {
        RegionSnapshot arena = regions.register(arena("arena", BUILD_ONLY));
        PlacedBlockRuntime.placed(arena, UUID.randomUUID(), Material.STONE, 1, 2, 3);
        assertTrue(PlacedBlockRuntime.tracked(arena.id(), 1, 2, 3));

        regions.unregister("arena");
        assertFalse(PlacedBlockRuntime.tracked(arena.id(), 1, 2, 3));
    }

    // ------------------------------------------------------------------
    // Temporary blocks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the expiry timer exists only while something is waiting on it")
    void sweeperIsStartedAndReleased() {
        RegionSnapshot arena = regions.register(arena("arena",
                PolicySet.of(CommonRegionPolicies.TEMPORARY_BLOCKS, true)
                        .with(CommonRegionPolicies.TEMPORARY_BLOCKS_SECONDS, 30)));
        assertEquals(0, FakeServer.liveRepeatingTasks());

        PlacedBlockRuntime.placed(arena, UUID.randomUUID(), Material.STONE, 2, 3, 4);
        assertEquals(1, FakeServer.liveRepeatingTasks(), "the first temporary block starts the timer");

        PlacedBlockRuntime.placed(arena, UUID.randomUUID(), Material.STONE, 2, 4, 4);
        assertEquals(1, FakeServer.liveRepeatingTasks(), "one timer serves the whole server");

        Regions.releaseAll();
        assertEquals(0, FakeServer.liveRepeatingTasks(), "nothing waiting, nothing running");
    }

    @Test
    @DisplayName("a temporary block with no lifetime never enters the queue")
    void zeroSecondsDoesNotSchedule() {
        RegionSnapshot arena = regions.register(arena("arena",
                PolicySet.of(CommonRegionPolicies.TEMPORARY_BLOCKS, true)));

        PlacedBlockRuntime.placed(arena, UUID.randomUUID(), Material.STONE, 2, 3, 4);
        assertEquals(0, FakeServer.liveRepeatingTasks());
        // Still recorded: the region tracks its blocks either way.
        assertTrue(PlacedBlockRuntime.tracked(arena.id(), 2, 3, 4));
    }

    @Test
    @DisplayName("one plugin's regions do not answer for another's blocks")
    void ownershipIsScoped() {
        Plugin other = FakeServer.newPlugin("Survival", null);
        PluginRegions otherRegions = Regions.of(other);
        RegionSnapshot arena = regions.register(arena("arena", BUILD_ONLY));
        PlacedBlockRuntime.placed(arena, UUID.randomUUID(), Material.STONE, 7, 8, 9);

        assertTrue(regions.placedByPlayer(world.getUID(), 7, 8, 9));
        assertFalse(otherRegions.placedByPlayer(world.getUID(), 7, 8, 9));
    }
}
