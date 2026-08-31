package net.exylia.lib.text;

import net.exylia.lib.text.internal.TextEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Centring, measured in pixels rather than characters.
 *
 * <p>The widths are ExyliaCommons', so a line centred there is centred here.
 */
class CenteringTest {

    @BeforeEach
    void setUp() {
        Colors.apply(new Palette());
    }

    // ------------------------------------------------------------------
    // Measuring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the font is not monospaced: an i is not a W")
    void charactersDifferInWidth() {
        assertTrue(Centering.pixelWidth("W") > Centering.pixelWidth("i"),
                "centring by character count is exactly the bug this avoids");
    }

    @Test
    @DisplayName("a character carries its one-pixel gap")
    void widthIncludesSpacing() {
        // 'A' is five pixels plus the gap after it.
        assertEquals(6, Centering.pixelWidth("A"));
        assertEquals(2, Centering.pixelWidth("i"));
    }

    @Test
    @DisplayName("MiniMessage tags take no space")
    void tagsAreInvisible() {
        assertEquals(Centering.pixelWidth("Hello"),
                Centering.pixelWidth("<red>Hello</red>"));
    }

    @Test
    @DisplayName("legacy codes take no space")
    void legacyCodesAreInvisible() {
        assertEquals(Centering.pixelWidth("Hello"), Centering.pixelWidth("&cHello"));
        assertEquals(Centering.pixelWidth("Hello"), Centering.pixelWidth("\u00a7cHello"));
    }

    @Test
    @DisplayName("palette tokens take no space")
    void paletteTokensAreInvisible() {
        assertEquals(Centering.pixelWidth("Hello"),
                Centering.pixelWidth("{primary}Hello"));
    }

    @Test
    @DisplayName("a token that is not in the palette is text, and is measured")
    void unknownBraceIsText() {
        assertTrue(Centering.pixelWidth("{notatoken}Hi") > Centering.pixelWidth("Hi"));
    }

    @Test
    @DisplayName("bold text is wider, as it is on screen")
    void boldIsWider() {
        assertTrue(Centering.pixelWidth("<bold>Hello</bold>")
                        > Centering.pixelWidth("Hello"),
                "bold draws every character twice, one pixel apart");
        assertTrue(Centering.pixelWidth("&lHello") > Centering.pixelWidth("Hello"));
    }

    @Test
    @DisplayName("a colour code ends bold, exactly as the client does")
    void colourResetsBold() {
        assertEquals(Centering.pixelWidth("&cHello"), Centering.pixelWidth("&l&cHello"));
    }

    @Test
    @DisplayName("a gradient's quoted argument does not leak into the text")
    void quotedTagArgument() {
        assertEquals(Centering.pixelWidth("Exylia"),
                Centering.pixelWidth("<gradient:#8a51c4:#ff6b9d>Exylia</gradient>"));
    }

    // ------------------------------------------------------------------
    // Centring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a centred line is padded with spaces")
    void centreAddsPadding() {
        String centred = Centering.center("Hi");

        assertTrue(centred.startsWith(" "));
        assertEquals("Hi", centred.strip());
    }

    @Test
    @DisplayName("a shorter line gets more padding")
    void shorterLineIsPaddedMore() {
        int shortPad = Centering.center("Hi").indexOf('H');
        int longPad = Centering.center("A much longer line of text").indexOf('A');

        assertTrue(shortPad > longPad, "otherwise nothing is actually centred");
    }

    @Test
    @DisplayName("padding ignores formatting, so colour does not shift a line")
    void colourDoesNotShiftCentring() {
        String plain = Centering.center("Hello");
        String coloured = Centering.center("{primary}Hello");

        assertEquals(plain.indexOf('H'), coloured.indexOf('{'));
    }

    @Test
    @DisplayName("a line too wide to centre is left alone")
    void tooWideIsUntouched() {
        String wide = "x".repeat(200);

        assertSame(wide, Centering.center(wide));
    }

    @Test
    @DisplayName("an empty line is left alone")
    void emptyIsUntouched() {
        assertEquals("", Centering.center(""));
    }

    @Test
    @DisplayName("a list is centred line by line")
    void centresAList() {
        List<String> centred = Centering.center(List.of("Hi", "There"));

        assertEquals(2, centred.size());
        assertTrue(centred.get(0).startsWith(" "));
        assertTrue(centred.get(1).startsWith(" "));
    }

    @Test
    @DisplayName("a custom width centres against that width")
    void customWidth() {
        String narrow = Centering.centerWithin("Hi", 100);
        String wide = Centering.centerWithin("Hi", 320);

        assertTrue(wide.indexOf('H') > narrow.indexOf('H'),
                "a wider space needs more padding to reach its middle");
    }
    // ------------------------------------------------------------------
    // Placeholders
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a centred line is padded for its values, not for its placeholders")
    void centresAroundSubstitutedValues() {
        String centred = Text.of("[center]Started by %player% (%players%/%max%)")
                .with("%player%", "DiGround_")
                .with("%players%", 0)
                .with("%max%", 32)
                .plain();

        // Padding the template instead would count "%players%/%max%" as fifty
        // pixels of text that never reaches the screen, and drag the line left.
        assertEquals(Centering.center("Started by DiGround_ (0/32)"), centred);
    }

    @Test
    @DisplayName("a literal value is measured as written, small capitals or not")
    void literalValueIsNotMeasuredAsSmallCapitals() {
        TextEngine.smallText(true);
        try {
            String centred = Text.of("[center]Playing %game%")
                    .with("%game%", "MACE")
                    .plain();

            // The line is drawn as small capitals, the value is not: it is
            // inserted as its own component and never meets the transform.
            int width = Centering.pixelWidth("Playing ") + Centering.pixelWidth("MACE", false);
            String padding = Centering.paddingFor(width, Centering.CHAT_WIDTH_PX);

            assertEquals(padding.length(), centred.indexOf('\u1D18'),
                    "'P' is drawn as the small capital '\u1D18'; the value keeps its capitals");
        } finally {
            TextEngine.smallText(false);
        }
    }
}
