package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollMotion;
import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollPose;
import net.exylia.lib.ragdoll.RagdollSkin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A body at detail five: a core per part and the design laid over it in plates. */
class RagdollShellTest {

    private static final int BLUE = 0xFF3A3A9E;
    private static final int SNOW = 0xFFF9FEFE;
    private static final int BLACK = 0xFF080A0F;
    private static final float T = RagdollShell.THICKNESS;

    private static int pixels(float blocks) {
        return Math.round(blocks / RagdollPart.PIXEL);
    }

    private static int netWidth(RagdollPart part) {
        return 2 * (pixels(part.blockDepth()) + pixels(part.blockWidth()));
    }

    private static Map<RagdollPart, int[]> flat(int argb) {
        Map<RagdollPart, int[]> nets = new EnumMap<>(RagdollPart.class);
        for (RagdollPart part : RagdollPart.values()) {
            int[] net = new int[netWidth(part) * (pixels(part.blockDepth()) + pixels(part.blockHeight()))];
            Arrays.fill(net, argb);
            nets.put(part, net);
        }
        return nets;
    }

    private static void front(Map<RagdollPart, int[]> nets, RagdollPart part, int column, int row, int argb) {
        int depth = pixels(part.blockDepth());
        nets.get(part)[(depth + row) * netWidth(part) + depth + column] = argb;
    }

    private static void back(Map<RagdollPart, int[]> nets, RagdollPart part, int column, int row, int argb) {
        int depth = pixels(part.blockDepth());
        nets.get(part)[(depth + row) * netWidth(part) + 2 * depth + pixels(part.blockWidth()) + column] = argb;
    }

    private static List<RagdollShell.Piece> of(RagdollPart part, List<RagdollShell.Piece> pieces) {
        return pieces.stream().filter(piece -> piece.part() == part).toList();
    }

    @Test
    @DisplayName("a body in flat colours is one core per part and no plates")
    void flatPartsAreCores() {
        List<RagdollShell.Piece> pieces = RagdollShell.build(new RagdollSkin(flat(BLUE)));
        assertEquals(5, pieces.size());
        for (RagdollShell.Piece piece : pieces) {
            assertEquals(BlockPalette.match(BLUE & 0xFFFFFF), piece.block());
            assertArrayEquals(new float[]{
                    pixels(piece.part().blockWidth()) - 2 * T,
                    pixels(piece.part().blockHeight()) - 2 * T,
                    pixels(piece.part().blockDepth()) - 2 * T}, piece.size(), 1e-5f);
        }
    }

    @Test
    @DisplayName("one odd pixel on a chest is one plate, flush with the front where the pixel is")
    void oddPixelIsOnePlate() {
        Map<RagdollPart, int[]> nets = flat(BLUE);
        front(nets, RagdollPart.TORSO, 2, 3, SNOW);
        List<RagdollShell.Piece> torso = of(RagdollPart.TORSO, RagdollShell.build(new RagdollSkin(nets)));
        assertEquals(2, torso.size());
        RagdollShell.Piece plate = torso.get(1);
        assertEquals(BlockPalette.match(SNOW & 0xFFFFFF), plate.block());
        // Eight wide, twelve tall, four deep: column 2 is 1.5 right of the
        // middle towards the wearer's right, row 3 is 2.5 above it.
        assertArrayEquals(new float[]{-1.5f, 2.5f, 2f - T / 2}, plate.centre(), 1e-5f);
        assertArrayEquals(new float[]{1f, 1f, T}, plate.size(), 1e-5f);
    }

    @Test
    @DisplayName("a stripe across a chest is merged into a single plate")
    void stripeIsMerged() {
        Map<RagdollPart, int[]> nets = flat(BLUE);
        for (int column = 0; column < 8; column++) {
            front(nets, RagdollPart.TORSO, column, 5, SNOW);
        }
        List<RagdollShell.Piece> torso = of(RagdollPart.TORSO, RagdollShell.build(new RagdollSkin(nets)));
        assertEquals(2, torso.size());
        assertArrayEquals(new float[]{8f, 1f, T}, torso.get(1).size(), 1e-5f);
        assertArrayEquals(new float[]{0f, 0.5f, 2f - T / 2}, torso.get(1).centre(), 1e-5f);
    }

    @Test
    @DisplayName("over budget, the largest plates are kept and the pieces fit exactly")
    void overBudgetKeepsLargest() {
        Map<RagdollPart, int[]> nets = flat(BLUE);
        for (int row = 0; row < 12; row++) {
            for (int column = 0; column < 8; column++) {
                front(nets, RagdollPart.TORSO, column, row, (column + row) % 2 == 0 ? SNOW : BLACK);
            }
        }
        for (int column = 0; column < 8; column++) {
            // Black, one of the torso's three blocks: a fourth would be snapped
            // to one of them before any plate is cut.
            back(nets, RagdollPart.TORSO, column, 0, BLACK);
        }
        RagdollSkin skin = new RagdollSkin(nets);
        assertEquals(5 + 96 + 1, RagdollShell.build(skin).size(), "a checkerboard does not merge");
        List<RagdollShell.Piece> kept = RagdollShell.build(skin, 30);
        assertEquals(30, kept.size());
        assertTrue(kept.stream().anyMatch(piece -> piece.block() == BlockPalette.match(BLACK & 0xFFFFFF)
                && piece.size()[0] == 8f), "the stripe across the back is the largest plate");
    }

    @Test
    @DisplayName("a lone pixel takes its neighbours' block, and a run is left alone")
    void despeckleRemovesDither() {
        org.bukkit.Material red = org.bukkit.Material.RED_CONCRETE;
        org.bukkit.Material white = org.bukkit.Material.WHITE_CONCRETE;
        org.bukkit.Material[][] face = {
                {red, red, red},
                {red, white, red},
                {white, white, red}};
        org.bukkit.Material[][] smoothed = RagdollShell.despeckle(face);
        assertEquals(white, smoothed[1][1], "joined to the white below it, so not alone");
        org.bukkit.Material[][] lone = RagdollShell.despeckle(new org.bukkit.Material[][]{
                {red, red, red},
                {red, white, red},
                {red, red, red}});
        assertEquals(red, lone[1][1]);
        assertEquals(red, lone[0][0], "the pixels around it are untouched");
    }

    @Test
    @DisplayName("a shell is solved as pieces carrying their blocks, sized as its plates")
    void shellIsSolved() {
        Map<RagdollPart, int[]> nets = flat(BLUE);
        for (int column = 0; column < 8; column++) {
            front(nets, RagdollPart.TORSO, column, 5, SNOW);
        }
        List<RagdollShell.Piece> shell = RagdollShell.build(new RagdollSkin(nets));
        RagdollMotion motion = RagdollMotion.builder().life(3.0).intactFor(0.3).pose(RagdollPose.BURST).build();
        List<RagdollPieces.Piece> pieces = RagdollPieces.solve(motion, 4, 1.0, Rotation.NONE, new Random(7),
                EnumSet.noneOf(RagdollPieces.Prop.class), RagdollShell.placed(shell));
        assertEquals(1 + shell.size(), pieces.size());
        for (RagdollPieces.Piece piece : pieces.subList(1, pieces.size())) {
            assertNotNull(piece.block(), piece.part() + " carries its block");
        }
        RagdollPieces.Piece stripe = pieces.stream()
                .filter(piece -> piece.block() == BlockPalette.match(SNOW & 0xFFFFFF))
                .findFirst().orElseThrow();
        DisplayKeyframe pose = stripe.poses().get(0);
        assertEquals(8f / T, pose.scaleX() / pose.scaleZ(), 1e-3);
    }
}
