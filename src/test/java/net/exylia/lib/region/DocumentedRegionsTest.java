package net.exylia.lib.region;

import net.exylia.lib.FakeServer;
import net.exylia.lib.region.internal.OutlineAccess;
import net.exylia.lib.region.internal.RegionRuntime;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claims {@code docs/regions.md} makes, checked against what the code does.
 *
 * <p>A number in documentation is a promise, and the ones here are the promises
 * somebody would design around: the block corners being inclusive, a policy
 * nobody declares falling through, a frame's point budget, the fifteen policy
 * names matching the old system exactly.
 */
class DocumentedRegionsTest {

    private World world;
    private PluginRegions regions;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Regions.releaseAll();
        world = FakeServer.newWorld("world");
        Plugin plugin = FakeServer.newPlugin("Docs", null);
        RegionRuntime.init(plugin);
        regions = Regions.of(plugin);
    }

    private WorldIdentity here() {
        return WorldIdentity.from(world);
    }

    @Test
    @DisplayName("\"0..15 is sixteen blocks\"")
    void inclusiveBlockCorners() {
        // The doc's own example. An admin selects two corners and expects both
        // of them to be inside; an off-by-one here is a protection hole along
        // one face of every region in the ecosystem.
        regions.register(regions.region("arena", here(),
                Cuboid.blocks(0, 64, 0, 15, 79, 15), 0, PolicySet.empty()));

        assertFalse(Regions.at(world.getUID(), 0.0, 64.0, 0.0).isEmpty(), "the first corner");
        assertFalse(Regions.at(world.getUID(), 15.5, 79.5, 15.5).isEmpty(), "the last block");
        assertTrue(Regions.at(world.getUID(), 16.0, 64.0, 0.0).isEmpty(), "one past the end");
    }

    @Test
    @DisplayName("\"a claim: bedrock to sky\"")
    void unboundedRectangleHasNoCeiling() {
        regions.register(regions.region("claim", here(),
                new UnboundedYRectangle(0, 0, 64, 64), 0, PolicySet.empty()));

        assertFalse(Regions.at(world.getUID(), 32, -64, 32).isEmpty(), "at bedrock");
        assertFalse(Regions.at(world.getUID(), 32, 320, 32).isEmpty(), "at the build limit");
    }

    @Test
    @DisplayName("the doc's worked example of overlapping policies")
    void theWorkedExample() {
        // Copied straight out of the doc: three regions, three answers.
        regions.register(regions.region("world_rules", here(),
                new UnboundedYRectangle(-1000, -1000, 1000, 1000), 0,
                PolicySet.of(CommonRegionPolicies.PVP, true)));
        regions.register(regions.region("claim", here(),
                new UnboundedYRectangle(0, 0, 64, 64), 10,
                PolicySet.of(CommonRegionPolicies.BUILD, false)));
        regions.register(regions.region("mine", here(),
                Cuboid.blocks(10, 0, 10, 20, 40, 20), 20,
                PolicySet.of(CommonRegionPolicies.PVP, false)));

        assertEquals(false, Regions.resolve(world.getUID(), 15, 20, 15,
                CommonRegionPolicies.PVP).value(), "pvp -> false, from the mine");
        assertEquals(false, Regions.resolve(world.getUID(), 15, 20, 15,
                CommonRegionPolicies.BUILD).value(), "build -> false, from the claim");
        assertEquals(true, Regions.resolve(world.getUID(), 15, 20, 15,
                CommonRegionPolicies.INTERACT).value(), "interact -> the key's own default");
    }

    @Test
    @DisplayName("\"the fifteen keys the ecosystem already uses, same names, same defaults\"")
    void policiesMatchTheOldSystem() {
        // Taken from RegionFlag in ExyliaCommons. A default flipped here would
        // silently invert protection on every migrated region.
        record Expected(PolicyKey<Boolean> key, String id, boolean value) { }
        List<Expected> expected = List.of(
                new Expected(CommonRegionPolicies.PVP, "pvp", true),
                new Expected(CommonRegionPolicies.BUILD, "build", true),
                new Expected(CommonRegionPolicies.BREAK, "break", true),
                new Expected(CommonRegionPolicies.INTERACT, "interact", true),
                new Expected(CommonRegionPolicies.PLAYER_BUILD_ONLY, "player_build_only", false),
                new Expected(CommonRegionPolicies.ALLOWED_BLOCKS_ONLY, "allowed_blocks_only", false),
                new Expected(CommonRegionPolicies.BREAKABLE_BLOCKS_ONLY, "breakable_blocks_only", false),
                new Expected(CommonRegionPolicies.TEMPORARY_BLOCKS, "temporary_blocks", false),
                new Expected(CommonRegionPolicies.RE_GIVE_BLOCKS, "re_give_blocks", false),
                new Expected(CommonRegionPolicies.REGION_MEMBERS_ONLY, "region_members_only", false),
                new Expected(CommonRegionPolicies.ENTRY, "entry", true),
                new Expected(CommonRegionPolicies.EXIT, "exit", true),
                new Expected(CommonRegionPolicies.ITEM_DROP, "item_drop", true),
                new Expected(CommonRegionPolicies.ITEM_PICKUP, "item_pickup", true),
                new Expected(CommonRegionPolicies.FALL_DAMAGE, "fall_damage", true));

        assertEquals(15, expected.size(), "the doc says fifteen");
        for (Expected each : expected) {
            assertEquals(each.id(), each.key().id().value(),
                    "the name a migrated database row would carry");
            assertEquals(each.value(), each.key().defaultValue(),
                    each.id() + " must default the way Commons did");
        }
    }

    @Test
    @DisplayName("\"fails if that id already exists\" and \"fails if it does not\"")
    void registerAndReplaceContracts() {
        RegionSnapshot arena = regions.region("arena", here(),
                Cuboid.blocks(0, 0, 0, 9, 9, 9), 0, PolicySet.empty());

        regions.register(arena);
        assertThrows(IllegalStateException.class, () -> regions.register(arena),
                "the doc says registering twice fails");
        assertThrows(IllegalStateException.class, () -> regions.replace(
                        regions.region("missing", here(),
                                Cuboid.blocks(0, 0, 0, 9, 9, 9), 0, PolicySet.empty())),
                "the doc says replacing something absent fails");
    }

    @Test
    @DisplayName("\"a frame draws at most 512 points\"")
    void frameBudget() {
        // The number is in the doc, so it is a promise about what a viewer
        // costs the server.
        assertEquals(512, OutlineAccess.maxPointsPerFrame());

        // And the doc's own example of the shape that would break it.
        Cuboid claim = new Cuboid(-50_000, 0, -50_000, 50_000, 384, 50_000);
        assertTrue(OutlineAccess.pointCount(claim, 1.0) <= 512);
    }

    @Test
    @DisplayName("\"decoding a policy the caller did not declare fails\"")
    void codecFailsClosed() {
        RegionSnapshot region = regions.region("arena", here(),
                Cuboid.blocks(0, 0, 0, 9, 9, 9), 0,
                PolicySet.of(CommonRegionPolicies.PVP, false));

        List<PolicyKey<?>> known = List.of(CommonRegionPolicies.PVP);
        RegionData data = RegionCodec.encode(region, known);

        // Round trip with the key: the doc's example.
        assertEquals(region, RegionCodec.decode(data, known));

        // Without it: the doc says this fails rather than dropping the policy,
        // which is how the old serializer lost protection silently.
        assertThrows(IllegalArgumentException.class,
                () -> RegionCodec.decode(data, List.of(CommonRegionPolicies.BUILD)));
    }

    @Test
    @DisplayName("\"replaces only the calling plugin's regions\"")
    void replaceAllIsOwnerScoped() {
        Plugin other = FakeServer.newPlugin("Other", null);
        PluginRegions theirs = Regions.of(other);
        regions.register(regions.region("mine", here(),
                Cuboid.blocks(0, 0, 0, 9, 9, 9), 0, PolicySet.empty()));
        theirs.register(theirs.region("theirs", here(),
                Cuboid.blocks(50, 0, 50, 59, 9, 59), 0, PolicySet.empty()));

        regions.replaceAll(List.of());

        assertTrue(regions.all().isEmpty());
        assertEquals(1, theirs.all().size(), "the doc says the other plugin is untouched");
    }

    @Test
    @DisplayName("\"a rejected build leaves the server exactly as it was\"")
    void failedBuildDoesNotPublish() {
        regions.register(regions.region("arena", here(),
                Cuboid.blocks(0, 0, 0, 9, 9, 9), 0, PolicySet.empty()));

        assertThrows(RuntimeException.class, () -> regions.replaceAll(List.of(
                regions.region("clash", here(), Cuboid.blocks(0, 0, 0, 9, 9, 9), 0, PolicySet.empty()),
                regions.region("clash", here(), Cuboid.blocks(0, 0, 0, 9, 9, 9), 0, PolicySet.empty()))));

        assertEquals(1, regions.all().size());
        assertEquals(regions.id("arena"), regions.all().getFirst().id());
    }

    @Test
    @DisplayName("\"drawn at the viewer's own height\" applies to exactly the shapes with no ceiling")
    void viewerHeightShapes() {
        assertTrue(OutlineAccess.followsViewerHeight(new UnboundedYRectangle(0, 0, 10, 10), 1.0));
        assertTrue(OutlineAccess.followsViewerHeight(new HorizontalCylinder(0, 0, 10), 1.0));
        assertFalse(OutlineAccess.followsViewerHeight(new Cuboid(0, 0, 0, 10, 10, 10), 1.0));
        assertFalse(OutlineAccess.followsViewerHeight(new Sphere(0, 64, 0, 10), 1.0));
    }
}
