package net.exylia.lib.util;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.PolicySet;
import net.exylia.lib.region.PluginRegions;
import net.exylia.lib.region.Regions;
import net.exylia.lib.region.WorldIdentity;
import net.exylia.lib.region.internal.RegionRuntime;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Build zones: every missing source allows, and a region's own policy refuses per action. */
class BuildZonesTest {

    private World world;
    private Player player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Regions.releaseAll();
        world = FakeServer.newWorld("survival");
        Plugin plugin = FakeServer.newPlugin("Spawners", null);
        RegionRuntime.init(plugin);
        PluginRegions regions = Regions.of(plugin);
        regions.register(regions.region("spawn", WorldIdentity.from(world), Cuboid.blocks(0, 0, 0, 9, 9, 9), 0,
                PolicySet.of(CommonRegionPolicies.BUILD, false).with(CommonRegionPolicies.INTERACT, false)));
        player = new FakePlayer("Builder").player();
    }

    @Test
    void withoutAnyClaimPluginOnlyTheRegionRefuses() {
        Block outside = block(50, 5, 50);
        for (BuildZones.Action action : BuildZones.Action.values()) {
            assertNull(BuildZones.deniedBy(player, outside, action, BuildZones.ALL), action.name());
        }
        Block inside = block(5, 5, 5);
        assertEquals(BuildZones.Source.EXYLIA_REGIONS, BuildZones.deniedBy(player, inside, BuildZones.Action.BUILD, BuildZones.ALL));
        assertTrue(BuildZones.canBreak(player, inside), "break is its own policy, still allowed");
        assertEquals(BuildZones.Source.EXYLIA_REGIONS, BuildZones.deniedBy(player, inside, BuildZones.Action.CONTAINER, BuildZones.ALL),
                "a container is guarded by interact");
        assertTrue(BuildZones.allows(BuildZones.Source.LANDS, player, inside, BuildZones.Action.BUILD), "a missing plugin allows");
    }

    private Block block(int x, int y, int z) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "getType" -> Material.SPAWNER;
                    case "getLocation" -> args == null || args.length == 0 ? new Location(world, x, y, z) : null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
