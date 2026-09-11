package net.exylia.lib.ragdoll.internal;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The flat palette, matched the way the eye compares colours. */
class BlockPaletteTest {

    @Test
    @DisplayName("every block in the palette is what its own colour matches")
    void ownColourMatchesItself() {
        BlockPalette.colours().forEach((material, rgb) ->
                assertEquals(material, BlockPalette.match(rgb), material.name()));
    }

    @Test
    @DisplayName("black is black concrete")
    void blackIsConcrete() {
        assertEquals(Material.BLACK_CONCRETE, BlockPalette.match(0x000000));
    }

    @Test
    @DisplayName("a light skin tone lands on a warm, muted block rather than a saturated concrete")
    void skinToneIsMuted() {
        String name = BlockPalette.match(0xE0AC7E).name();
        assertTrue(name.contains("TERRACOTTA") || name.startsWith("STRIPPED_") || name.contains("SAND"), name);
    }

    @Test
    @DisplayName("a cell is the block most of its solid pixels are, clear pixels skipped")
    void dominantCountsPixels() {
        int red = 0xFF8E2121;
        int white = 0xFFCFD5D6;
        int clear = 0x00FFFFFF;
        Material block = BlockPalette.dominant(new int[]{red, clear, red, white, clear, clear, red, clear, clear});
        assertEquals(Material.RED_CONCRETE, block);
    }

    @Test
    @DisplayName("a cell with nothing solid is the grey of a missing skin")
    void emptyCellIsGrey() {
        Material block = BlockPalette.dominant(new int[]{0, 0x10FFFFFF});
        assertNotNull(block);
        assertEquals(BlockPalette.match(0x9E9E9E), block);
    }
}
