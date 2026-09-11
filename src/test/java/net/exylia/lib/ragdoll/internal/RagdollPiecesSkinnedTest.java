package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollMotion;
import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollPose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A body in its real skin is the same body as one in blocks, sent as heads. */
class RagdollPiecesSkinnedTest {

    private static List<RagdollPieces.Piece> solve(boolean skinned) {
        RagdollMotion motion = RagdollMotion.builder().life(3.0).intactFor(0.3).pose(RagdollPose.BURST).build();
        return RagdollPieces.solve(motion, SkinCubes.DETAIL, 1.0, Rotation.NONE, new Random(7),
                EnumSet.noneOf(RagdollPieces.Prop.class), skinned);
    }

    @Test
    @DisplayName("nineteen pieces, each cube a head twice the size of its block")
    void cubesAreHalfSizeHeads() {
        List<RagdollPieces.Piece> blocks = solve(false);
        List<RagdollPieces.Piece> heads = solve(true);
        assertEquals(19, heads.size());
        assertEquals(blocks.size(), heads.size());
        for (int index = 0; index < heads.size(); index++) {
            RagdollPieces.Piece block = blocks.get(index);
            RagdollPieces.Piece head = heads.get(index);
            DisplayKeyframe blockPose = block.poses().get(0);
            DisplayKeyframe headPose = head.poses().get(0);
            float growth = head.part() == RagdollPart.HEAD ? 1f : 2f;
            assertEquals(blockPose.scaleX() * growth, headPose.scaleX(), 1e-5, head.part() + " width");
            assertEquals(blockPose.scaleY() * growth, headPose.scaleY(), 1e-5, head.part() + " height");
        }
    }
}
