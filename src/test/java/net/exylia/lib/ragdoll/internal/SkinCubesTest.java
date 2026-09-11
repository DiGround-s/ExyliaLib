package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.ragdoll.RagdollPart;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A skin repainted as heads: that every face of every piece shows the pixels
 * it was cut from, the right way round, at every quality.
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

    private static List<SkinCubes.Cube> high(BufferedImage skin, boolean slim) {
        return SkinCubes.cut(skin, slim, SkinCubes.Quality.HIGH);
    }

    private static SkinCubes.Cube piece(List<SkinCubes.Cube> cubes, RagdollPart part, int x, int y) {
        return cubes.stream()
                .filter(cube -> cube.part() == part && cube.region().x() == x && cube.region().y() == y)
                .findFirst().orElseThrow();
    }

    private static BufferedImage cube(List<SkinCubes.Cube> cubes, RagdollPart part, int x, int y) {
        return piece(cubes, part, x, y).image();
    }

    @Test
    @DisplayName("each quality cuts the pieces it promises, each its own picture")
    void piecesPerQuality() {
        BufferedImage skin = coordinates(64, 64);
        List<SkinCubes.Cube> cubes = high(skin, false);
        assertEquals(18, cubes.size());
        assertEquals(18, cubes.stream().map(SkinCubes.Cube::hash).distinct().count());
        assertEquals(10, SkinCubes.cut(skin, false, SkinCubes.Quality.NORMAL).size());
        assertEquals(5, SkinCubes.cut(skin, false, SkinCubes.Quality.LOW).size());
        assertEquals(RagdollPart.TORSO, cubes.get(0).part(), "the torso is queued first");
    }

    @Test
    @DisplayName("a quality is read from its name in any case, and nothing else is one")
    void qualityNames() {
        assertEquals(SkinCubes.Quality.NORMAL, SkinCubes.Quality.of(" Normal "));
        assertEquals(SkinCubes.Quality.LOW, SkinCubes.Quality.of("low"));
        assertNull(SkinCubes.Quality.of("ultra"));
        assertNull(SkinCubes.Quality.of(null));
    }

    @Test
    @DisplayName("a front is copied pixel for pixel, each pixel two texels wide")
    void frontIsCopied() {
        List<SkinCubes.Cube> cubes = high(coordinates(64, 64), false);
        BufferedImage first = cube(cubes, RagdollPart.TORSO, 0, 0);
        BufferedImage source = coordinates(64, 64);
        assertEquals(at(source, 20, 20), at(first, 8, 8));
        assertEquals(at(source, 20, 20), at(first, 9, 9));
        assertEquals(at(source, 21, 20), at(first, 10, 8));
        assertEquals(at(source, 20, 21), at(first, 8, 10));
        assertEquals(at(source, 24, 20), at(cube(cubes, RagdollPart.TORSO, 4, 0), 8, 8),
                "the second column starts where the first ends");
    }

    @Test
    @DisplayName("a back is mirrored, so the wearer's right cube shows the right of their back")
    void backIsMirrored() {
        List<SkinCubes.Cube> cubes = high(coordinates(64, 64), false);
        BufferedImage source = coordinates(64, 64);
        assertEquals(at(source, 36, 20), at(cube(cubes, RagdollPart.TORSO, 0, 0), 24, 8));
        assertEquals(at(source, 32, 20), at(cube(cubes, RagdollPart.TORSO, 4, 0), 24, 8));
    }

    @Test
    @DisplayName("only the top row has the part's top; the rows below extrude their own edge")
    void topOnlyOnTheTopRow() {
        List<SkinCubes.Cube> cubes = high(coordinates(64, 64), false);
        BufferedImage source = coordinates(64, 64);
        assertEquals(at(source, 20, 16), at(cube(cubes, RagdollPart.TORSO, 0, 0), 8, 0));
        BufferedImage middle = cube(cubes, RagdollPart.TORSO, 0, 4);
        assertEquals(at(source, 20, 24), at(middle, 8, 7), "the half by the front takes the front");
        assertEquals(at(source, 39, 24), at(middle, 8, 0), "the half by the back takes the back");
    }

    @Test
    @DisplayName("an outer side is the part's side; an inner side extrudes the edges beside it")
    void sides() {
        List<SkinCubes.Cube> cubes = high(coordinates(64, 64), false);
        BufferedImage source = coordinates(64, 64);
        assertEquals(at(source, 16, 20), at(cube(cubes, RagdollPart.TORSO, 0, 0), 0, 8));
        BufferedImage second = cube(cubes, RagdollPart.TORSO, 4, 0);
        assertEquals(at(source, 24, 20), at(second, 7, 8), "by the front, the front's edge");
        assertEquals(at(source, 35, 20), at(second, 0, 8), "by the back, the back's edge");
    }

    @Test
    @DisplayName("normal quality is pixel exact: eight rows one texel each, four rows two, columns doubled")
    void normalIsExact() {
        BufferedImage source = coordinates(64, 64);
        List<SkinCubes.Cube> cubes = SkinCubes.cut(source, false, SkinCubes.Quality.NORMAL);
        BufferedImage upper = cube(cubes, RagdollPart.ARM_RIGHT, 0, 0);
        BufferedImage lower = cube(cubes, RagdollPart.ARM_RIGHT, 0, 8);
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                assertEquals(at(source, 44 + column / 2, 20 + row), at(upper, 8 + column, 8 + row));
                assertEquals(at(source, 44 + column / 2, 28 + row / 2), at(lower, 8 + column, 8 + row));
            }
        }
        SkinCubes.Region top = piece(cubes, RagdollPart.ARM_RIGHT, 0, 0).region();
        assertArrayEquals(new float[]{0f, 2f, 0f}, top.centre(), 1e-5f);
        assertArrayEquals(new float[]{4f, 8f, 4f}, top.size(), 1e-5f);
        SkinCubes.Region bottom = piece(cubes, RagdollPart.ARM_RIGHT, 0, 8).region();
        assertArrayEquals(new float[]{0f, -4f, 0f}, bottom.centre(), 1e-5f);
        assertArrayEquals(new float[]{4f, 4f, 4f}, bottom.size(), 1e-5f);
    }

    @Test
    @DisplayName("low quality squeezes twelve rows into eight texels")
    void lowSqueezes() {
        BufferedImage source = coordinates(64, 64);
        BufferedImage torso = cube(SkinCubes.cut(source, false, SkinCubes.Quality.LOW), RagdollPart.TORSO, 0, 0);
        assertEquals(at(source, 20, 20), at(torso, 8, 8));
        assertEquals(at(source, 20, 30), at(torso, 8, 15), "the last texel row is the eleventh row");
    }

    @Test
    @DisplayName("a piece in one flat block is plain and carries that block; a designed piece is not")
    void plainPieces() {
        BufferedImage flat = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                flat.setRGB(x, y, 0xFFE06101);
            }
        }
        for (SkinCubes.Cube cube : SkinCubes.cut(flat, false, SkinCubes.Quality.NORMAL)) {
            assertEquals(Material.ORANGE_CONCRETE, cube.plain());
            assertEquals(Material.ORANGE_CONCRETE, cube.dominant());
        }
        // A checkerboard of white and black, because the coordinates picture's
        // dark corners are all one block to the palette, and rightly plain.
        BufferedImage checked = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                checked.setRGB(x, y, ((x + y) & 1) == 0 ? 0xFFFFFFFF : 0xFF000000);
            }
        }
        for (SkinCubes.Cube cube : SkinCubes.cut(checked, false, SkinCubes.Quality.NORMAL)) {
            assertNull(cube.plain(), cube.part() + " has a design");
            assertNotNull(cube.dominant());
        }
    }

    @Test
    @DisplayName("a clear base pixel takes the jacket over it, or grey, and never a hole")
    void clearBaseIsFilled() {
        BufferedImage skin = coordinates(64, 64);
        skin.setRGB(20, 20, 0);
        BufferedImage covered = cube(high(skin, false), RagdollPart.TORSO, 0, 0);
        assertEquals(at(skin, 20, 36), at(covered, 8, 8));

        skin.setRGB(20, 36, 0);
        BufferedImage bare = cube(high(skin, false), RagdollPart.TORSO, 0, 0);
        assertEquals(0xFF9E9E9E, at(bare, 8, 8));
        assertEquals(0, at(bare, 40, 8) >>> 24, "no jacket there, so no hat texel either");
    }

    @Test
    @DisplayName("the jacket layer is worn in the hat")
    void overlayIsTheHat() {
        BufferedImage source = coordinates(64, 64);
        BufferedImage first = cube(high(source, false), RagdollPart.TORSO, 0, 0);
        assertEquals(at(source, 20, 36), at(first, 40, 8));
        BufferedImage left = cube(high(source, false), RagdollPart.ARM_LEFT, 0, 0);
        assertEquals(at(source, 36, 52), at(left, 8, 8), "the left arm has a net of its own");
        assertEquals(at(source, 52, 52), at(left, 40, 8), "and a jacket net of its own");
    }

    @Test
    @DisplayName("a slim arm's three columns stretch across all eight texels")
    void slimArmStretches() {
        BufferedImage source = coordinates(64, 64);
        BufferedImage arm = cube(high(source, true), RagdollPart.ARM_RIGHT, 0, 0);
        assertEquals(at(source, 44, 20), at(arm, 10, 8));
        assertEquals(at(source, 45, 20), at(arm, 11, 8));
        assertEquals(at(source, 46, 20), at(arm, 15, 8));
        assertEquals(at(source, 47, 20), at(arm, 16, 8), "the left side starts right after three columns");
    }

    @Test
    @DisplayName("a legacy skin lends its right limbs to the left and has no jacket")
    void legacySkin() {
        BufferedImage source = coordinates(64, 32);
        List<SkinCubes.Cube> cubes = high(source, false);
        BufferedImage left = cube(cubes, RagdollPart.ARM_LEFT, 0, 0);
        assertEquals(at(source, 44, 20), at(left, 8, 8));
        assertEquals(0, at(left, 40, 8) >>> 24);
        assertNotEquals(0, at(left, 8, 8) >>> 24);
    }
}
