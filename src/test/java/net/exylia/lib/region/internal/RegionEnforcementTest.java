package net.exylia.lib.region.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.MaterialSet;
import net.exylia.lib.region.PluginRegions;
import net.exylia.lib.region.PolicySet;
import net.exylia.lib.region.RegionSnapshot;
import net.exylia.lib.region.Regions;
import net.exylia.lib.region.WorldIdentity;
import net.exylia.lib.region.internal.RegionEnforcement.Check;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What opt-in enforcement refuses, and for whom.
 *
 * <p>The failures worth catching: an owner enforcing regions it never opted into or
 * that belong to somebody else, an audience that does not narrow, a lower region
 * overruling a higher one, a block list that does not whitelist, and enforcement
 * outliving its plugin.
 */
class RegionEnforcementTest {

    private World world;
    private PluginRegions regions;
    private Player player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Regions.releaseAll();
        world = FakeServer.newWorld("arena");
        Plugin plugin = FakeServer.newPlugin("Practice");
        RegionRuntime.init(plugin);
        regions = Regions.of(plugin);
        player = new FakePlayer("Steve").at(new Location(world, 5, 5, 5)).player();
    }

    @AfterEach
    void tearDown() {
        Regions.releaseAll();
    }

    private void register(PluginRegions owner, String key, int priority, PolicySet policies) {
        owner.register(owner.region(key, WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 15, 15, 15), priority, policies));
    }

    private boolean denies(Check check, Material material) {
        return RegionEnforcement.denies(player, world.getUID(), 5, 5, 5, check, material);
    }

    @Test
    @DisplayName("regions declare but nothing is refused until the owner enforces")
    void nothingWithoutOptIn() {
        register(regions, "arena", 0, PolicySet.of(CommonRegionPolicies.BREAK, false));
        assertFalse(RegionEnforcement.active());
        assertFalse(denies(Check.BREAK, Material.STONE));

        regions.enforce();
        assertTrue(regions.enforcing());
        assertTrue(RegionEnforcement.active());
        assertTrue(denies(Check.BREAK, Material.STONE));
        assertFalse(denies(Check.BUILD, Material.STONE), "undeclared policy keeps its permissive default");
    }

    @Test
    @DisplayName("one plugin's enforcement never reaches another plugin's regions")
    void ownerScoped() {
        PluginRegions other = Regions.of(FakeServer.newPlugin("Survival"));
        register(other, "claim", 0, PolicySet.of(CommonRegionPolicies.BREAK, false));
        // A higher region of the enforcing owner allowing it must not relax the other's either way.
        register(regions, "arena", 10, PolicySet.of(CommonRegionPolicies.BREAK, true));
        regions.enforce();
        assertFalse(denies(Check.BREAK, Material.STONE));

        other.enforce();
        assertTrue(denies(Check.BREAK, Material.STONE), "each enforcing owner judges its own regions");
    }

    @Test
    @DisplayName("a rejecting audience leaves the region out for that player")
    void audienceNarrows() {
        register(regions, "arena", 0, PolicySet.of(CommonRegionPolicies.PVP, false));
        regions.enforce((who, region) -> false);
        assertFalse(denies(Check.PVP, null));

        regions.enforce((who, region) -> region.id().value().equals("arena"));
        assertTrue(denies(Check.PVP, null), "enforce again replaces the audience");
    }

    @Test
    @DisplayName("the default audience exempts creative mode")
    void creativeIsExempt() {
        register(regions, "arena", 0, PolicySet.of(CommonRegionPolicies.ITEM_DROP, false));
        regions.enforce();
        assertTrue(denies(Check.ITEM_DROP, null));
        player = creative();
        assertFalse(denies(Check.ITEM_DROP, null));
    }

    @Test
    @DisplayName("the highest applying region that declares the policy decides")
    void firstExplicitWins() {
        register(regions, "floor", 0, PolicySet.of(CommonRegionPolicies.BUILD, false));
        register(regions, "bridge", 5, PolicySet.of(CommonRegionPolicies.BUILD, true));
        register(regions, "banner", 9, PolicySet.of(CommonRegionPolicies.PVP, false));
        regions.enforce();
        assertFalse(denies(Check.BUILD, Material.STONE));

        // The bridge does not apply to this player, so the floor is the first to declare.
        regions.enforce((who, region) -> !region.id().value().equals("bridge"));
        assertTrue(denies(Check.BUILD, Material.STONE));
    }

    @Test
    @DisplayName("a *_only flag makes its list a whitelist, even where the plain flag says no")
    void blockListsWhitelist() {
        register(regions, "mine", 0, PolicySet.of(CommonRegionPolicies.BREAK, false)
                .with(CommonRegionPolicies.BREAKABLE_BLOCKS_ONLY, true)
                .with(CommonRegionPolicies.BREAKABLE_BLOCKS, MaterialSet.of(Material.STONE))
                .with(CommonRegionPolicies.BUILD, true)
                .with(CommonRegionPolicies.ALLOWED_BLOCKS_ONLY, true)
                .with(CommonRegionPolicies.ALLOWED_BLOCKS, MaterialSet.of(Material.SANDSTONE)));
        regions.enforce();
        assertFalse(denies(Check.BREAK, Material.STONE));
        assertTrue(denies(Check.BREAK, Material.DIAMOND_ORE));
        assertFalse(denies(Check.BUILD, Material.SANDSTONE));
        assertTrue(denies(Check.BUILD, Material.TNT));
    }

    @Test
    @DisplayName("player_build_only refuses breaking the map but not what a player placed")
    void playerBuildOnly() {
        register(regions, "arena", 0, PolicySet.of(CommonRegionPolicies.PLAYER_BUILD_ONLY, true));
        regions.enforce();
        assertTrue(denies(Check.BREAK, Material.STONE));

        RegionSnapshot arena = regions.get("arena").orElseThrow();
        PlacedBlockRuntime.placed(arena, null, Material.STONE, 5, 5, 5);
        assertFalse(denies(Check.BREAK, Material.STONE));
        assertFalse(denies(Check.BUILD, Material.STONE), "building is a separate flag");
    }

    @Test
    @DisplayName("break false refuses even a player's own block")
    void breakFalseWinsOverPlayerBuildOnly() {
        register(regions, "arena", 0, PolicySet.of(CommonRegionPolicies.PLAYER_BUILD_ONLY, true)
                .with(CommonRegionPolicies.BREAK, false));
        regions.enforce();
        PlacedBlockRuntime.placed(regions.get("arena").orElseThrow(), null, Material.STONE, 5, 5, 5);
        assertTrue(denies(Check.BREAK, Material.STONE));
    }

    @Test
    @DisplayName("PvP is refused from either end, and hurting yourself never is")
    void pvpEitherSide() {
        register(regions, "safe", 0, PolicySet.of(CommonRegionPolicies.PVP, false));
        regions.enforce();
        Player inside = player;
        Player outside = new FakePlayer("Alex").at(new Location(world, 100, 5, 100)).player();

        assertTrue(RegionEnforcement.pvpDenied(outside, inside), "into the region");
        assertTrue(RegionEnforcement.pvpDenied(inside, outside), "out of the region");
        assertFalse(RegionEnforcement.pvpDenied(inside, inside));

        regions.enforce((who, region) -> who == outside);
        assertFalse(RegionEnforcement.pvpDenied(outside, inside),
                "the region applies to neither end here");
    }

    @Test
    @DisplayName("stopping, or the plugin going away, ends enforcement and closes the gate")
    void stopAndRelease() {
        register(regions, "arena", 0, PolicySet.of(CommonRegionPolicies.FALL_DAMAGE, false));
        regions.enforce();
        regions.stopEnforcing();
        assertFalse(regions.enforcing());
        assertFalse(RegionEnforcement.active());
        assertFalse(denies(Check.FALL_DAMAGE, null));

        regions.enforce();
        Regions.release("Practice");
        assertFalse(regions.enforcing());
        assertFalse(RegionEnforcement.active());
    }

    private Player creative() {
        Player base = player;
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
                (self, method, args) -> method.getName().equals("getGameMode")
                        ? GameMode.CREATIVE
                        : method.invoke(base, args));
    }
}
