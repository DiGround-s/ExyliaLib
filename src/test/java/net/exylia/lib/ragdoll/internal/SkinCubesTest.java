package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.ragdoll.RagdollPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A skin repainted as heads: that every face of every cube shows the pixels it
 * was cut from, the right way round.
 *
 * <p>The picture paints every pixel with its own coordinates, so a texel that
 * came from the wrong place says exactly where it came from instead.
 */
class SkinCubesTest {

    private static BufferedImage coordinates(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000 | x << 16 | y << 8 | 0x40);
            }
        }
        return image;
    }

    private static int at(BufferedImage image, int x, int y) {
        return image.getRGB(x, y);
    }

    private static BufferedImage cube(List<SkinCubes.Cube> cubes, RagdollPart part, int cellX, int cellY) {
        return cubes.stream()
                .filter(cube -> cube.part() == part && cube.cellX() == cellX && cube.cellY() == cellY)
                .findFirst().orElseThrow().image();
    }

    @Test
    @DisplayName("a body is eighteen cubes, each its own picture")
    void eighteenCubes() {
        List<SkinCubes.Cube> cubes = SkinCubes.cut(coordinates(64, 64), false);
        assertEquals(18, cubes.size());
        assertEquals(18, cubes.stream().map(SkinCubes.Cube::hash).distinct().count());
    }

    @Test
    @DisplayName("a front is copied pixel for pixel, each pixel two texels wide")
    void frontIsCopied() {
        List<SkinCubes.Cube> cubes = SkinCubes.cut(coordinates(64, 64), false);
        BufferedImage first = cube(cubes, RagdollPart.TORSO, 0, 0);
        BufferedImage source = coordinates(64, 64);
        assertEquals(at(source, 20, 20), at(first, 8, 8));
        assertEquals(at(source, 20, 20), at(first, 9, 9));
        assertEquals(at(source, 21, 20), at(first, 10, 8));
        assertEquals(at(source, 20, 21), at(first, 8, 10));
        assertEquals(at(source, 24, 20), at(cube(cubes, RagdollPart.TORSO, 1, 0), 8, 8),
                "the second column starts where the first ends");
    }

    @Test
    @DisplayName("a back is mirrored, so the wearer's right cube shows the right of their back")
    void backIsMirrored() {
        List<SkinCubes.Cube> cubes = SkinCubes.cut(coordinates(64, 64), false);
        BufferedImage source = coordinates(64, 64);
        assertEquals(at(source, 36, 20), at(cube(cubes, RagdollPart.TORSO, 0, 0), 24, 8));
        assertEquals(at(source, 32, 20), at(cube(cubes, RagdollPart.TORSO, 1, 0), 24, 8));
    }

    @Test
    @DisplayName("only the top row has the part's top; the rows below extrude their own edge")
    void topOnlyOnTheTopRow() {
        List<SkinCubes.Cube> cubes = SkinCubes.cut(coordinates(64, 64), false);
        BufferedImage source = coordinates(64, 64);
        assertEquals(at(source, 20, 16), at(cube(cubes, RagdollPart.TORSO, 0, 0), 8, 0));
        BufferedImage middle = cube(cubes, RagdollPart.TORSO, 0, 1);
        assertEquals(at(source, 20, 24), at(middle, 8, 7), "the half by the front takes the front");
        assertEquals(at(source, 39, 24), at(middle, 8, 0), "the half by the back takes the back");
    }

    @Test
    @DisplayName("an outer side is the part's side; an inner side extrudes the edges beside it")
    void sides() {
        List<SkinCubes.Cube> cubes = SkinCubes.cut(coordinates(64, 64), false);
        BufferedImage source = coordinates(64, 64);
        assertEquals(at(source, 16, 20), at(cube(cubes, RagdollPart.TORSO, 0, 0), 0, 8));
        BufferedImage second = cube(cubes, RagdollPart.TORSO, 1, 0);
        assertEquals(at(source, 24, 20), at(second, 7, 8), "by the front, the front's edge");
        assertEquals(at(source, 35, 20), at(second, 0, 8), "by the back, the back's edge");
    }

    @Test
    @DisplayName("a clear base pixel takes the jacket over it, or grey, and never a hole")
    void clearBaseIsFilled() {
        BufferedImage skin = coordinates(64, 64);
        skin.setRGB(20, 20, 0);
        BufferedImage covered = cube(SkinCubes.cut(skin, false), RagdollPart.TORSO, 0, 0);
        assertEquals(at(skin, 20, 36), at(covered, 8, 8));

        skin.setRGB(20, 36, 0);
        BufferedImage bare = cube(SkinCubes.cut(skin, false), RagdollPart.TORSO, 0, 0);
        assertEquals(0xFF9E9E9E, at(bare, 8, 8));
        assertEquals(0, at(bare, 40, 8) >>> 24, "no jacket there, so no hat texel either");
    }

    @Test
    @DisplayName("the jacket layer is worn in the hat")
    void overlayIsTheHat() {
        BufferedImage source = coordinates(64, 64);
        BufferedImage first = cube(SkinCubes.cut(source, false), RagdollPart.TORSO, 0, 0);
        assertEquals(at(source, 20, 36), at(first, 40, 8));
        BufferedImage left = cube(SkinCubes.cut(source, false), RagdollPart.ARM_LEFT, 0, 0);
        assertEquals(at(source, 36, 52), at(left, 8, 8), "the left arm has a net of its own");
        assertEquals(at(source, 52, 52), at(left, 40, 8), "and a jacket net of its own");
    }

    @Test
    @DisplayName("a slim arm's three columns stretch across all eight texels")
    void slimArmStretches() {
        BufferedImage source = coordinates(64, 64);
        BufferedImage arm = cube(SkinCubes.cut(source, true), RagdollPart.ARM_RIGHT, 0, 0);
        assertEquals(at(source, 44, 20), at(arm, 10, 8));
        assertEquals(at(source, 45, 20), at(arm, 11, 8));
        assertEquals(at(source, 46, 20), at(arm, 15, 8));
        assertEquals(at(source, 47, 20), at(arm, 16, 8), "the left side starts right after three columns");
    }

    @Test
    @DisplayName("a legacy skin lends its right limbs to the left and has no jacket")
    void legacySkin() {
        BufferedImage source = coordinates(64, 32);
        List<SkinCubes.Cube> cubes = SkinCubes.cut(source, false);
        BufferedImage left = cube(cubes, RagdollPart.ARM_LEFT, 0, 0);
        assertEquals(at(source, 44, 20), at(left, 8, 8));
        assertEquals(0, at(left, 40, 8) >>> 24);
        assertNotEquals(0, at(left, 8, 8) >>> 24);
    }
}
