package net.exylia.lib.region;

import net.exylia.lib.FakeServer;
import net.exylia.lib.region.internal.RegionRuntime;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shapes and scales the ecosystem actually uses, at the sizes it uses them.
 *
 * <p>Every case here was taken from a real plugin: PracticeCore's arenas and
 * portals, SandBox's teleport zones, FFA's spawn protection, Clans' claims,
 * Capture's payload, Events' shrinking safe zone. The point is not that the
 * arithmetic is right — other tests cover that — but that the whole registry
 * behaves when it is loaded the way production loads it.
 */
class EcosystemRegionsTest {

    /** How many claims a survival server really has. */
    private static final int CLAIMS = 5_000;

    private World world;
    private Plugin plugin;
    private PluginRegions regions;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Regions.releaseAll();
        world = FakeServer.newWorld("world");
        plugin = FakeServer.newPlugin("Ecosystem", null);
        RegionRuntime.init(plugin);
        regions = Regions.of(plugin);
    }

    @Test
    @DisplayName("every shape the ecosystem needs is registered and found")
    void everyRealShape() {
        WorldIdentity id = WorldIdentity.from(world);

        // PracticeCore arena, SandBox teleport zone, Survival mine: a cuboid.
        regions.register(regions.region("arena", id,
                Cuboid.blocks(0, 0, 0, 63, 63, 63), 0, PolicySet.empty()));
        // Clans claim: an XZ rectangle with no vertical limit.
        regions.register(regions.region("claim", id,
                new UnboundedYRectangle(200, 200, 264, 264), 0, PolicySet.empty()));
        // FFA spawn protection, Capture payload: a sphere.
        regions.register(regions.region("spawn", id,
                new Sphere(500, 64, 500, 16), 0, PolicySet.empty()));
        // Events LMS safe zone: a circle with no vertical limit.
        regions.register(regions.region("safezone", id,
                new HorizontalCylinder(1000, 1000, 40), 0, PolicySet.empty()));
        // Capture's DTC block: one block, which is a cuboid.
        regions.register(regions.region("core", id, Cuboid.block(2000, 70, 2000),
                0, PolicySet.empty()));

        assertEquals(1, Regions.at(world.getUID(), 30, 30, 30).size(), "arena");
        assertEquals(1, Regions.at(world.getUID(), 230, -60, 230).size(),
                "a claim protects bedrock as well as the surface");
        assertEquals(1, Regions.at(world.getUID(), 500, 70, 500).size(), "spawn");
        assertEquals(1, Regions.at(world.getUID(), 1000, 300, 1000).size(),
                "a safe zone reaches the build limit");
        assertEquals(1, Regions.at(world.getUID(), 2000.5, 70.5, 2000.5).size(), "the core block");
        assertTrue(Regions.at(world.getUID(), 2001.5, 70.5, 2000.5).isEmpty(),
                "one block means one block");
    }

    @Test
    @DisplayName("five thousand claims still answer from one chunk's worth of work")
    void manyClaims() {
        // A survival server's claims are small, scattered and numerous. The old
        // system checked every region in the world on every move.
        WorldIdentity id = WorldIdentity.from(world);
        List<RegionSnapshot> claims = new ArrayList<>(CLAIMS);
        for (int index = 0; index < CLAIMS; index++) {
            int x = (index % 100) * 64;
            int z = (index / 100) * 64;
            claims.add(regions.region("claim_" + index, id,
                    new UnboundedYRectangle(x, z, x + 32, z + 32), 0, PolicySet.empty()));
        }
        regions.replaceAll(claims);

        assertEquals(CLAIMS, Regions.registered());
        assertEquals(1, Regions.at(world.getUID(), 10, 64, 10).size(), "inside the first claim");
        assertTrue(Regions.at(world.getUID(), 50, 64, 50).isEmpty(), "between claims");
        // The last claim of the grid: index 4999 sits at x=6336, z=3136.
        assertEquals(1, Regions.at(world.getUID(), 6350, 64, 3150).size(), "a claim far out");
    }

    @Test
    @DisplayName("a claim and the arena it sits in are both found, in order")
    void nestedRegions() {
        // Survival stacks these: a world border region, a claim inside it, and
        // a mine inside that. All three apply; the innermost decides first.
        WorldIdentity id = WorldIdentity.from(world);
        regions.register(regions.region("world", id,
                new UnboundedYRectangle(-10_000, -10_000, 10_000, 10_000), 0,
                PolicySet.of(CommonRegionPolicies.PVP, true)));
        regions.register(regions.region("claim", id,
                new UnboundedYRectangle(0, 0, 64, 64), 10,
                PolicySet.of(CommonRegionPolicies.BUILD, false)));
        regions.register(regions.region("mine", id,
                Cuboid.blocks(10, 0, 10, 20, 40, 20), 20,
                PolicySet.of(CommonRegionPolicies.PVP, false)));

        List<RegionSnapshot> found = Regions.at(world.getUID(), 15, 20, 15);

        assertEquals(3, found.size());
        assertEquals(regions.id("mine"), found.get(0).id());
        assertEquals(regions.id("claim"), found.get(1).id());
        assertEquals(regions.id("world"), found.get(2).id());

        // The mine turns pvp off; the claim says nothing about it and must not
        // hide the mine's answer, nor let the world region's answer win.
        assertEquals(false, Regions.resolve(world.getUID(), 15, 20, 15,
                CommonRegionPolicies.PVP).value());
        // Nothing but the claim mentions building.
        assertEquals(false, Regions.resolve(world.getUID(), 15, 20, 15,
                CommonRegionPolicies.BUILD).value());
    }

    @Test
    @DisplayName("a shrinking safe zone is replaced, not mutated")
    void shrinkingZone() {
        // Events' LMS shrinks a circle every few seconds. Commons mutated the
        // shape in place, which left the spatial index pointing at the old
        // bounds. Here each size is a new immutable snapshot.
        WorldIdentity id = WorldIdentity.from(world);
        regions.register(regions.region("safezone", id,
                new HorizontalCylinder(0, 0, 100), 0, PolicySet.empty()));

        for (int radius = 90; radius >= 10; radius -= 10) {
            regions.replace(regions.region("safezone", id,
                    new HorizontalCylinder(0, 0, radius), 0, PolicySet.empty()));
            assertEquals(1, Regions.at(world.getUID(), radius - 1, 64, 0).size(),
                    "just inside radius " + radius);
            assertTrue(Regions.at(world.getUID(), radius + 1, 64, 0).isEmpty(),
                    "just outside radius " + radius);
        }
    }

    @Test
    @DisplayName("a thousand random queries agree with checking every region by hand")
    void queriesAgreeWithBruteForce() {
        // The index exists to avoid looking at every region. This is the only
        // test that proves it still finds the same answer as doing exactly that.
        WorldIdentity id = WorldIdentity.from(world);
        Random random = new Random(20_260_216L);
        List<RegionSnapshot> all = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            all.add(regions.region("r_" + index, id, randomShape(random),
                    random.nextInt(20), PolicySet.empty()));
        }
        regions.replaceAll(all);

        for (int probe = 0; probe < 1_000; probe++) {
            double x = random.nextInt(4_000) - 2_000 + random.nextDouble();
            double y = random.nextInt(320) - 64 + random.nextDouble();
            double z = random.nextInt(4_000) - 2_000 + random.nextDouble();

            List<RegionId> expected = all.stream()
                    .filter(region -> region.shape().contains(x, y, z))
                    .sorted(RegionSnapshot.ORDER)
                    .map(RegionSnapshot::id)
                    .toList();
            List<RegionId> actual = Regions.at(world.getUID(), x, y, z).stream()
                    .map(RegionSnapshot::id)
                    .toList();

            assertEquals(expected, actual, "at " + x + "," + y + "," + z);
        }
    }

    @Test
    @DisplayName("no query ever returns the same region twice")
    void noDuplicateResults() {
        // A region can sit in more than one bucket, and the amount of hand
        // waving needed to avoid that is exactly where duplicates creep in.
        WorldIdentity id = WorldIdentity.from(world);
        Random random = new Random(7L);
        List<RegionSnapshot> all = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            all.add(regions.region("r_" + index, id, randomShape(random), 0, PolicySet.empty()));
        }
        regions.replaceAll(all);

        for (int probe = 0; probe < 500; probe++) {
            double x = random.nextInt(2_000) - 1_000;
            double z = random.nextInt(2_000) - 1_000;
            List<RegionSnapshot> found = Regions.at(world.getUID(), x, 64, z);
            Set<RegionId> unique = new HashSet<>();
            for (RegionSnapshot region : found) {
                assertTrue(unique.add(region.id()), "returned twice: " + region.id());
            }
        }
    }

    @Test
    @DisplayName("a region spanning the whole coordinate range is still found")
    void wholeWorldRegion() {
        // A world-wide rule region is a real thing: Survival registers one to
        // hold the defaults every other region overrides. It is also the only
        // shape that reaches the top of the index's hierarchy, so without this
        // that level could be dropped entirely and every other test would pass.
        regions.register(regions.region("world_rules", WorldIdentity.from(world),
                new UnboundedYRectangle(Integer.MIN_VALUE, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE), 0,
                PolicySet.of(CommonRegionPolicies.BUILD, false)));

        assertEquals(1, Regions.at(world.getUID(), 0, 64, 0).size(), "at the origin");
        assertEquals(1, Regions.at(world.getUID(), 29_000_000, 64, -29_000_000).size(),
                "at the world border");
        assertEquals(false, Regions.resolve(world.getUID(), 1_000_000, 64, 1_000_000,
                CommonRegionPolicies.BUILD).value());
    }

    @Test
    @DisplayName("worlds do not leak into each other")
    void worldsAreSeparate() {
        World second = FakeServer.newWorld("nether");
        regions.register(regions.region("overworld", WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 99, 99, 99), 0, PolicySet.empty()));
        regions.register(regions.region("nether", WorldIdentity.from(second),
                Cuboid.blocks(0, 0, 0, 99, 99, 99), 0, PolicySet.empty()));

        assertEquals(regions.id("overworld"),
                Regions.at(world.getUID(), 50, 50, 50).getFirst().id());
        assertEquals(regions.id("nether"),
                Regions.at(second.getUID(), 50, 50, 50).getFirst().id());
        assertTrue(Regions.at(UUID.randomUUID(), 50, 50, 50).isEmpty(),
                "a world nobody registered anything in");
    }

    /** A shape of the kind and scale the ecosystem really registers. */
    private static RegionShape randomShape(Random random) {
        int x = random.nextInt(3_000) - 1_500;
        int z = random.nextInt(3_000) - 1_500;
        return switch (random.nextInt(4)) {
            case 0 -> Cuboid.blocks(x, random.nextInt(100) - 64, z,
                    x + random.nextInt(200), random.nextInt(100) + 40, z + random.nextInt(200));
            case 1 -> new UnboundedYRectangle(x, z, x + 1 + random.nextInt(200),
                    z + 1 + random.nextInt(200));
            case 2 -> new Sphere(x, random.nextInt(200) - 64, z, 1 + random.nextInt(80));
            default -> new HorizontalCylinder(x, z, 1 + random.nextInt(120));
        };
    }
}
