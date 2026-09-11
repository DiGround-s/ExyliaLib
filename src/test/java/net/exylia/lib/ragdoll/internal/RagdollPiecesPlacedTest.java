package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollMotion;
import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollPose;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pieces placed before solving: a head of real skin is the same piece in blocks, sent stretched. */
class RagdollPiecesPlacedTest {

    private static List<RagdollPieces.Piece> solve(String texture, Material block) {
        RagdollMotion motion = RagdollMotion.builder().life(3.0).intactFor(0.3).pose(RagdollPose.BURST).build();
        List<RagdollPieces.Placed> placed = List.of(
                new RagdollPieces.Placed(RagdollPart.TORSO, new float[]{0f, 2f, 0f}, new float[]{8f, 8f, 4f},
                        block, texture),
                new RagdollPieces.Placed(RagdollPart.ARM_RIGHT, new float[]{0f, 0f, 0f}, new float[]{4f, 12f, 4f},
                        block, texture));
        return RagdollPieces.solve(motion, 2, 1.0, Rotation.NONE, new Random(7),
                EnumSet.noneOf(RagdollPieces.Prop.class), placed);
    }

    @Test
    @DisplayName("a head is sent at twice the size of the same piece in blocks, on each axis on its own")
    void headsAreStretchedToTheirPiece() {
        List<RagdollPieces.Piece> heads = solve("abc", null);
        List<RagdollPieces.Piece> blocks = solve(null, Material.STONE);
        assertEquals(3, heads.size(), "the real head and the two placed pieces");
        assertEquals(blocks.size(), heads.size());
        for (int index = 1; index < heads.size(); index++) {
            RagdollPieces.Piece head = heads.get(index);
            RagdollPieces.Piece block = blocks.get(index);
            assertEquals("abc", head.texture());
            assertNull(head.block());
            assertEquals(Material.STONE, block.block());
            assertNull(block.texture());
            DisplayKeyframe headPose = head.poses().get(0);
            DisplayKeyframe blockPose = block.poses().get(0);
            assertEquals(blockPose.scaleX() * 2f, headPose.scaleX(), 1e-5, head.part() + " width");
            assertEquals(blockPose.scaleY() * 2f, headPose.scaleY(), 1e-5, head.part() + " height");
            assertEquals(blockPose.scaleZ() * 2f, headPose.scaleZ(), 1e-5, head.part() + " depth");
        }
        DisplayKeyframe torso = blocks.get(1).poses().get(0);
        assertEquals(0.5f, torso.scaleY(), 1e-5, "eight pixels is half a block");
        assertEquals(0.25f, torso.scaleZ(), 1e-5, "four pixels deep is a quarter");
    }
}
