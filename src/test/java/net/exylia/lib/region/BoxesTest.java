package net.exylia.lib.region;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoxesTest {

    private World world;
    private RegionSnapshot region;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        world = FakeServer.newWorld("mines");
        FakeServer.worlds(world);
        region = new RegionSnapshot(new RegionId("test", "box"), "test", WorldIdentity.from(world),
                Cuboid.blocks(0, 10, 0, 9, 19, 4), 0, PolicySet.empty());
    }

    @Test
    void cornersAreInclusiveBlocks() {
        Location min = Boxes.min(region);
        Location max = Boxes.max(region);
        assertEquals(List.of(0, 10, 0), List.of(min.getBlockX(), min.getBlockY(), min.getBlockZ()));
        assertEquals(List.of(9, 19, 4), List.of(max.getBlockX(), max.getBlockY(), max.getBlockZ()));
        assertEquals(world, min.getWorld());
    }

    @Test
    void volumeCountsEveryBlock() {
        assertEquals(10L * 10 * 5, Boxes.volume(region));
    }

    @Test
    void playersInsideAreTheOnesInTheShape() {
        FakePlayer in = new FakePlayer("In");
        in.at(new Location(world, 5.5, 12, 2.5));
        FakePlayer out = new FakePlayer("Out");
        out.at(new Location(world, 50, 12, 2));
        FakeServer.online(in.player(), out.player());
        assertEquals(List.of(in.player()), Boxes.playersInside(region));
        assertTrue(Boxes.contains(region, new Location(world, 9.9, 19.9, 4.9)));
        assertFalse(Boxes.contains(region, new Location(world, 10, 15, 2)));
        assertFalse(Boxes.contains(region, null));
    }
}
