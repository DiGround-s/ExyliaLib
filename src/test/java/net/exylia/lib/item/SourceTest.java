package net.exylia.lib.item;

import net.exylia.lib.skull.SkullSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@code material} value means.
 *
 * <p>The strings here are copied from deployed configuration rather than
 * invented: four hundred menu entries across the ecosystem write a head as a
 * prefix on {@code material}, in four spellings, and getting any of them wrong
 * turns a head into a stone block on a live server.
 */
class SourceTest {

    /** A real head texture, as ExyliaClans writes them. */
    private static final String TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJl"
            + "cy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjIzZmI2NzQyOTcxNmIyMWJjNmU4ZTdkNjY5Y2VkZGY2NWIxM2Uw"
            + "NzkwYTVjZTU1YjJlMDc3YjgyZDE5ZTEyNCJ9fX0=";

    @Test
    @DisplayName("a plain name is a material")
    void plainMaterial() {
        Source source = Source.of("DIAMOND_SWORD");

        Source.OfMaterial material = assertInstanceOf(Source.OfMaterial.class, source);
        assertEquals("DIAMOND_SWORD", material.raw());
        assertFalse(source.isDynamic());
    }

    @Test
    @DisplayName("both head-texture spellings mean a texture")
    void textureHeads() {
        for (String written : new String[] {"basehead-" + TEXTURE, "headbase-" + TEXTURE,
                "basehead:" + TEXTURE, "BASEHEAD-" + TEXTURE}) {
            Source.OfHead head = assertInstanceOf(Source.OfHead.class, Source.of(written),
                    written + " should be a head");
            assertEquals(SkullSource.texture(TEXTURE), head.head(), written);
            assertFalse(head.isDynamic(), written);
        }
    }

    @Test
    @DisplayName("both url spellings mean a url, keeping the whole address")
    void urlHeads() {
        String url = "https://textures.minecraft.net/texture/1a4af718455d4aab528e7a61f86fa25e";

        for (String written : new String[] {"urlhead-" + url, "headurl-" + url}) {
            Source.OfHead head = assertInstanceOf(Source.OfHead.class, Source.of(written), written);
            // The address contains both separators; only the prefix's own counts.
            assertEquals(SkullSource.url(url), head.head(), written);
        }
    }

    @Test
    @DisplayName("a player head with a fixed name resolves without a viewer")
    void namedPlayerHead() {
        Source source = Source.of("playerhead-Notch");

        Source.OfHead head = assertInstanceOf(Source.OfHead.class, source);
        assertEquals(SkullSource.player("Notch"), head.head());
        assertFalse(source.isDynamic());
    }

    @Test
    @DisplayName("a player head named by a placeholder waits for a viewer")
    void templatedPlayerHead() {
        Source source = Source.of("playerhead-%player_name%");

        Source.OfHeadTemplate template = assertInstanceOf(Source.OfHeadTemplate.class, source);
        assertEquals(Source.Kind.PLAYER, template.kind());
        assertTrue(source.isDynamic());
        assertEquals("playerhead-%player_name%", template.raw());
    }

    @Test
    @DisplayName("a serialised item is a snapshot")
    void snapshot() {
        Source source = Source.of("bytes:rO0ABXNyAB1v");

        Source.OfSnapshot snapshot = assertInstanceOf(Source.OfSnapshot.class, source);
        assertEquals("rO0ABXNyAB1v", snapshot.base64());
        assertFalse(source.isDynamic());
    }

    @Test
    @DisplayName("a material named by a placeholder is dynamic")
    void placeholderMaterial() {
        assertTrue(Source.of("%kit_icon%").isDynamic());
    }

    @Test
    @DisplayName("an unknown prefix is a material name, not a head")
    void unknownPrefixIsNotAHead() {
        // Custom item plugins are addressed this way; the item module does not
        // know them, and guessing would turn a typo into a silent player head.
        assertInstanceOf(Source.OfMaterial.class, Source.of("itemsadder:ruby"));
    }

    @Test
    @DisplayName("surrounding whitespace does not change what a value means")
    void trimmed() {
        assertInstanceOf(Source.OfHead.class, Source.of("  playerhead-Notch  "));
    }
}
