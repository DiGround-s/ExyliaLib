package net.exylia.lib.block.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.block.Blocks;
import net.exylia.lib.block.PluginBlocks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A blast loses the protected blocks, except those whose owner decides explosions itself. */
class ExplosionSpareTest {

    @Test
    @DisplayName("a block whose owner handles explosions stays in the blast list")
    void ownerHandledBlocksStay() {
        FakeServer.install();
        FakeServer.reset();
        Blocks.releaseAll();
        World world = FakeServer.newWorld("blast");
        PluginBlocks blocks = Blocks.of(FakeServer.newPlugin("Spawners", null));
        blocks.at(new Location(world, 0, 64, 0)).register();
        blocks.at(new Location(world, 1, 64, 0)).ownExplosions(true).register();

        Block crate = world.getBlockAt(0, 64, 0);
        Block spawner = world.getBlockAt(1, 64, 0);
        Block dirt = world.getBlockAt(2, 64, 0);
        List<Block> list = new ArrayList<>(List.of(crate, spawner, dirt));
        BlockListener.spare(list);

        assertEquals(List.of(spawner, dirt), list);
    }
}
