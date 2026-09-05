package net.exylia.lib.text;

import net.exylia.lib.text.internal.Shadows;
import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drop shadow every line is drawn with: read from one value, applied to
 * every way a component leaves the engine, and never over one the line
 * already carries.
 */
class TextShadowTest {

    @BeforeEach
    void setUp() {
        Colors.apply(new Palette());
        TextEngine.invalidate();
    }

    @AfterEach
    void tearDown() {
        TextEngine.shadow(null);
        TextEngine.invalidate();
    }

    @Test
    @DisplayName("a colour is read as written, and nonsense changes nothing")
    void reading() {
        assertEquals(0xFF000000, Shadows.read("#000000"));
        assertEquals(0xFF41DBA8, Shadows.read("41DBA8"));
        // Alpha last, as MiniMessage writes it: #rrggbbaa becomes ARGB.
        assertEquals(0x80112233, Shadows.read("#11223380"));
        // "none" is a shadow of nothing, which is not the same as no opinion.
        assertEquals(0, Shadows.read("none"));
        assertNull(Shadows.read(""));
        assertNull(Shadows.read("  "));
        assertNull(Shadows.read(null));
        assertNull(Shadows.read("#12345"));
        assertNull(Shadows.read("wat"));
    }

    @Test
    @DisplayName("every line the engine builds carries the shadow")
    void appliedEverywhere() {
        TextEngine.shadow(0xFF102030);
        ShadowColor expected = ShadowColor.shadowColor(0xFF102030);
        // Plain, parsed, and a value: three different paths out of the engine.
        assertEquals(expected, TextEngine.parse("hello").shadowColor());
        assertEquals(expected, TextEngine.parse("{primary}hello").shadowColor());
        assertEquals(expected, TextEngine.parseValue("<red>hello").shadowColor());
        assertEquals(expected, Text.verbatim("{primary}HELLO").shadowColor());
        assertEquals(expected, Text.of("{primary}hello %x%").with("%x%", "1").build().shadowColor());
    }

    @Test
    @DisplayName("a line that wrote its own shadow keeps it")
    void ownShadowWins() {
        TextEngine.shadow(0xFF102030);
        Component written = TextEngine.parse("<shadow:#40506070>hi");
        assertEquals(ShadowColor.shadowColor(Shadows.read("#40506070")), written.shadowColor());
    }

    @Test
    @DisplayName("no shadow configured leaves the line as the client draws it")
    void noneConfigured() {
        TextEngine.shadow(null);
        assertNull(TextEngine.parse("hello").shadowColor());
        assertNull(TextEngine.parse("{primary}hello").shadowColor());
    }

    @Test
    @DisplayName("changing the shadow drops what was cached in the old one")
    void changingDropsTheCache() {
        TextEngine.shadow(0xFF102030);
        assertEquals(ShadowColor.shadowColor(0xFF102030), TextEngine.parse("{primary}cached").shadowColor());
        TextEngine.shadow(0xFF405060);
        assertEquals(ShadowColor.shadowColor(0xFF405060), TextEngine.parse("{primary}cached").shadowColor());
    }

    @Test
    @DisplayName("the shadow colour type is there to be used at all")
    void supported() {
        assertTrue(Shadows.supported());
        assertNotNull(ShadowColor.shadowColor(0));
    }
}
