package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollSkin;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A body in its real skin, drawn by parts as its textures arrive. */
class RagdollBuilderWornTest {

    /** A picture whose every pixel is its own coordinates, salted so each test cuts pieces of its own. */
    private static BufferedImage picture(int salt) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, 0xFF000000 | x << 16 | y << 8 | salt);
            }
        }
        return image;
    }

    private static RagdollSkin skin(BufferedImage image) {
        Map<RagdollPart, int[]> nets = new EnumMap<>(RagdollPart.class);
        for (RagdollPart part : RagdollPart.values()) {
            nets.put(part, SkinCubes.net(image, part, false));
        }
        return new RagdollSkin(nets, image, false);
    }

    @Test
    @DisplayName("a skin with no texture yet is left to its detail")
    void nothingRealIsLeftToDetail() {
        assertNull(RagdollBuilder.worn(skin(picture(0x11)), SkinCubes.Quality.NORMAL));
    }

    @Test
    @DisplayName("one arrived region is a head, and every region still waiting is a block of its own")
    void drawnByParts() {
        RagdollSkin skin = skin(picture(0x22));
        List<SkinCubes.Cube> cubes = skin.cubes(SkinCubes.Quality.NORMAL);
        assertNotNull(cubes);
        RagdollTextures.arrived(cubes.get(0).hash(), "torso-top");
        List<RagdollPieces.Placed> placed = RagdollBuilder.worn(skin, SkinCubes.Quality.NORMAL);
        assertNotNull(placed);
        assertEquals(10, placed.size());
        assertEquals("torso-top", placed.get(0).texture());
        assertNull(placed.get(0).block());
        assertArrayEquals(new float[]{8f, 8f, 4f}, placed.get(0).size(), 1e-5f);
        for (RagdollPieces.Placed waiting : placed.subList(1, placed.size())) {
            assertNull(waiting.texture());
            assertNotNull(waiting.block(), waiting.part() + " is drawn in a block meanwhile");
        }
    }

    @Test
    @DisplayName("a skin in flat colours is worn in blocks at once, with nothing to wait for")
    void plainSkin() {
        BufferedImage flat = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                flat.setRGB(x, y, 0xFF157788);
            }
        }
        List<RagdollPieces.Placed> placed = RagdollBuilder.worn(skin(flat), SkinCubes.Quality.LOW);
        assertNotNull(placed);
        assertEquals(5, placed.size());
        for (RagdollPieces.Placed piece : placed) {
            assertEquals(Material.CYAN_CONCRETE, piece.block());
            assertNull(piece.texture());
        }
    }

    @Test
    @DisplayName("a skin never read from a picture has nothing to wear")
    void noPicture() {
        Map<RagdollPart, int[]> nets = new EnumMap<>(RagdollPart.class);
        for (RagdollPart part : RagdollPart.values()) {
            nets.put(part, SkinCubes.net(picture(0x33), part, false));
        }
        assertNull(RagdollBuilder.worn(new RagdollSkin(nets), SkinCubes.Quality.NORMAL));
    }
}
