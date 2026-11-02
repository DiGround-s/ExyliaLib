package net.exylia.lib.block.internal;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A double chest is one registered block: each half has to find the other where Minecraft put it. */
class DoubleChestHalfTest {

    @Test
    @DisplayName("a left half joins clockwise of its facing, a right half counter-clockwise")
    void halvesPointAtEachOther() {
        assertEquals(BlockFace.EAST, BlockRuntime.otherHalf(BlockFace.NORTH, Chest.Type.LEFT));
        assertEquals(BlockFace.WEST, BlockRuntime.otherHalf(BlockFace.NORTH, Chest.Type.RIGHT));
        assertEquals(BlockFace.SOUTH, BlockRuntime.otherHalf(BlockFace.EAST, Chest.Type.LEFT));
        assertEquals(BlockFace.NORTH, BlockRuntime.otherHalf(BlockFace.EAST, Chest.Type.RIGHT));
        assertEquals(BlockFace.WEST, BlockRuntime.otherHalf(BlockFace.SOUTH, Chest.Type.LEFT));
        assertEquals(BlockFace.NORTH, BlockRuntime.otherHalf(BlockFace.WEST, Chest.Type.LEFT));
        // Each half points back at the other.
        for (BlockFace facing : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            assertEquals(BlockRuntime.otherHalf(facing, Chest.Type.LEFT).getOppositeFace(),
                    BlockRuntime.otherHalf(facing, Chest.Type.RIGHT));
        }
    }

    @Test
    @DisplayName("a single chest has no other half")
    void singleChestHasNone() {
        assertNull(BlockRuntime.otherHalf(BlockFace.NORTH, Chest.Type.SINGLE));
    }
}
