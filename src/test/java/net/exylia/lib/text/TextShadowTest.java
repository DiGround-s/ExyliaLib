package net.exylia.lib.text;

import net.exylia.lib.text.internal.Shadows;
import net.exylia.lib.text.internal.Shadows.Spec;
import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static int fixed(String written) {
        Spec spec = Shadows.read(written);
        assertNotNull(spec, written);
        assertFalse(spec.automatic(), written);
        return spec.color();
    }

    @Test
    @DisplayName("a colour is read as written, and nonsense changes nothing")
    void reading() {
        assertEquals(0xFF000000, fixed("#000000"));
        assertEquals(0xFF41DBA8, fixed("41DBA8"));
        // Alpha last, as MiniMessage writes it: #rrggbbaa becomes ARGB.
        assertEquals(0x80112233, fixed("#11223380"));
        // "none" is a shadow of nothing, which is not the same as no opinion.
        assertEquals(0, fixed("none"));
        assertNull(Shadows.read(""));
        assertNull(Shadows.read("  "));
        assertNull(Shadows.read(null));
        assertNull(Shadows.read("#12345"));
        assertNull(Shadows.read("wat"));
    }

    @Test
    @DisplayName("auto is read with its factor, and a silly factor is not read at all")
    void readingAuto() {
        assertEquals(new Spec(null, Shadows.VANILLA_FACTOR), Shadows.read("auto"));
        assertEquals(new Spec(null, 0.5f), Shadows.read("auto:0.5"));
        assertEquals(new Spec(null, 0f), Shadows.read("AUTO: 0"));
        assertNull(Shadows.read("auto:2"));
        assertNull(Shadows.read("auto:-1"));
        assertNull(Shadows.read("auto:half"));
        assertNull(Shadows.read("automatic"));
    }

    @Test
    @DisplayName("an automatic shadow is a quarter of the letter's own colour")
    void automaticIsTheColourDarkened() {
        TextEngine.shadow(Shadows.read("auto"));
        // The two ends of the gradient RGBirdflop draws, and what it writes
        // under them: #41DBA8 casts #10372A, #4C00FF casts #130040.
        assertEquals(ShadowColor.shadowColor(0xFF10372A),
                TextEngine.parse("<#41dba8>Birdflop").shadowColor());
        assertEquals(ShadowColor.shadowColor(0xFF130040),
                TextEngine.parse("<#4c00ff>Birdflop").shadowColor());
        // A letter with no colour of its own is white, and casts vanilla's grey.
        assertEquals(ShadowColor.shadowColor(0xFF404040), TextEngine.parse("plain").shadowColor());
    }

    @Test
    @DisplayName("each part of a gradient casts its own shadow")
    void automaticFollowsEveryPart() {
        TextEngine.shadow(Shadows.read("auto"));
        Component line = TextEngine.parse("<#ff0000>red<#0000ff>blue");
        List<ShadowColor> found = new ArrayList<>();
        collect(line, found);
        assertTrue(found.contains(ShadowColor.shadowColor(0xFF400000)), "the red half casts red");
        assertTrue(found.contains(ShadowColor.shadowColor(0xFF000040)), "the blue half casts blue");
    }

    @Test
    @DisplayName("a factor of somebody's choosing is the one used")
    void factorIsHonoured() {
        TextEngine.shadow(Shadows.read("auto:0.5"));
        assertEquals(ShadowColor.shadowColor(0xFF804040), TextEngine.parse("<#ff8080>x").shadowColor());
    }

    @Test
    @DisplayName("a component painted after the parse can be shadowed too")
    void paintedAfterwards() {
        TextEngine.shadow(Shadows.read("auto"));
        // What a gradient painter hands back: coloured, never parsed.
        Component painted = Component.text("x").color(TextColor.color(0x41DBA8));
        assertNull(painted.shadowColor());
        assertEquals(ShadowColor.shadowColor(0xFF10372A), Text.shadowed(painted).shadowColor());
    }

    private static void collect(Component component, List<ShadowColor> into) {
        if (component.shadowColor() != null) into.add(component.shadowColor());
        component.children().forEach(child -> collect(child, into));
    }

    @Test
    @DisplayName("every line the engine builds carries the shadow")
    void appliedEverywhere() {
        TextEngine.shadow(new Spec(0xFF102030, 0f));
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
        TextEngine.shadow(new Spec(0xFF102030, 0f));
        Component written = TextEngine.parse("<shadow:#40506070>hi");
        assertEquals(ShadowColor.shadowColor(fixed("#40506070")), written.shadowColor());
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
        TextEngine.shadow(new Spec(0xFF102030, 0f));
        assertEquals(ShadowColor.shadowColor(0xFF102030), TextEngine.parse("{primary}cached").shadowColor());
        TextEngine.shadow(new Spec(0xFF405060, 0f));
        assertEquals(ShadowColor.shadowColor(0xFF405060), TextEngine.parse("{primary}cached").shadowColor());
    }

    @Test
    @DisplayName("the shadow colour type is there to be used at all")
    void supported() {
        assertTrue(Shadows.supported());
        assertNotNull(ShadowColor.shadowColor(0));
    }
}
