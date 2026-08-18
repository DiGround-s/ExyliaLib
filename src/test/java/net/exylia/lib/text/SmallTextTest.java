package net.exylia.lib.text;

import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the small capitals switch: it changes the letters a player reads and
 * nothing else.
 *
 * <p>The cases that matter are the ones that fail silently. A rewritten
 * MiniMessage tag stops being a tag; a rewritten placeholder name stops
 * matching when its value is substituted, and the raw {@code %name%} reaches
 * chat. Both look like the feature working right up until someone reads the
 * screen.
 */
class SmallTextTest {

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    @BeforeEach
    void setUp() {
        Colors.apply(new Palette());
        TextEngine.invalidate();
        TextEngine.smallText(true);
    }

    @AfterEach
    void tearDown() {
        TextEngine.smallText(false);
        TextEngine.invalidate();
    }

    private static String plain(String text) {
        return PLAIN.serialize(TextEngine.parse(text));
    }

    // ------------------------------------------------------------------
    // What it does
    // ------------------------------------------------------------------

    @Test
    @DisplayName("letters are drawn as small capitals")
    void lettersBecomeSmallCapitals() {
        assertEquals("ᴡᴇʟᴄᴏᴍᴇ", plain("WELCOME"));
    }

    @Test
    @DisplayName("case makes no difference, since both map to one glyph")
    void caseIsIrrelevant() {
        assertEquals(plain("welcome"), plain("WELCOME"));
        assertEquals(plain("WeLcOmE"), plain("welcome"));
    }

    @Test
    @DisplayName("s and x stay lowercase, as ExyliaCommons drew them")
    void lettersWithoutASmallCapital() {
        // Unicode has no small capital for these two. Commons drew lowercase
        // and the message files were written against that look.
        assertEquals("sᴛᴀx", plain("STAX"));
    }

    @Test
    @DisplayName("digits, punctuation and symbols are untouched")
    void nonLettersSurvive() {
        assertEquals("ʟᴠʟ 42 ➥ [1/3] ⏱", plain("LVL 42 ➥ [1/3] ⏱"));
    }

    @Test
    @DisplayName("off by default, so nothing changes until an owner asks")
    void offByDefault() {
        TextEngine.smallText(false);
        assertEquals("WELCOME", plain("WELCOME"));
        assertFalse(TextEngine.smallText());
    }

    // ------------------------------------------------------------------
    // What it must not touch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a MiniMessage tag still works after the rewrite")
    void miniMessageTagsSurvive() {
        Component component = TextEngine.parse("<bold>WELCOME</bold>");
        assertEquals("ᴡᴇʟᴄᴏᴍᴇ", PLAIN.serialize(component));
        // Rewriting the tag itself would leave "<ʙᴏʟᴅ>" as literal text and
        // the line would not be bold.
        assertEquals(TextDecoration.State.TRUE,
                component.decoration(TextDecoration.BOLD));
    }

    @Test
    @DisplayName("a gradient tag with hex arguments still parses")
    void gradientSurvives() {
        Component component = TextEngine.parse("<gradient:#8a51c4:#ff6b9d>EXYLIA</gradient>");
        assertEquals("ᴇxʏʟɪᴀ", PLAIN.serialize(component));
        // A gradient colours each character separately, so a surviving tag
        // leaves several differently coloured parts. A broken one would leave
        // a single uncoloured run with the tag printed as text.
        assertNotEquals(null, firstColor(component));
        assertTrue(PLAIN.serialize(component).indexOf('<') < 0,
                "a broken gradient tag would reach the screen as text");
    }

    @Test
    @DisplayName("a palette token still resolves to its colour")
    void paletteTokensSurvive() {
        Component component = TextEngine.parse("{primary}WELCOME");
        assertEquals("ᴡᴇʟᴄᴏᴍᴇ", PLAIN.serialize(component));
        assertEquals(TextColor.fromHexString("#8a51c4"), firstColor(component));
    }

    @Test
    @DisplayName("a legacy code is still a code, not a letter")
    void legacyCodesSurvive() {
        // "&l" would otherwise become "&ʟ", which is no longer bold.
        Component component = TextEngine.parse("&lWELCOME");
        assertEquals("ᴡᴇʟᴄᴏᴍᴇ", PLAIN.serialize(component));
        assertEquals(TextDecoration.State.TRUE,
                component.decoration(TextDecoration.BOLD));
    }

    @Test
    @DisplayName("a legacy hex code keeps its digits")
    void legacyHexSurvives() {
        Component component = TextEngine.parse("&#8a51c4WELCOME");
        assertEquals("ᴡᴇʟᴄᴏᴍᴇ", PLAIN.serialize(component));
        assertEquals(TextColor.fromHexString("#8a51c4"), firstColor(component));
    }

    @Test
    @DisplayName("a placeholder name is left alone, so its value still matches")
    void placeholderNamesSurvive() {
        // This is the one that fails in silence. The value is substituted by
        // matching "%coins%" literally against the parsed component, so a
        // rewritten name never matches and the placeholder reaches the player.
        assertEquals("ᴄᴏɪɴs: %coins%", plain("COINS: %coins%"));
    }

    @Test
    @DisplayName("a lone percent is text, not the start of a placeholder")
    void loosePercentIsText() {
        // Swallowing to the end of the line here would leave a whole sentence
        // untransformed for the sake of one percent sign.
        assertEquals("100% ᴄʜᴀɴᴄᴇ", plain("100% CHANCE"));
    }

    @Test
    @DisplayName("substituted values are never transformed")
    void valuesAreNotTransformed() {
        // A player's name is data. Drawing "Steve" as "sᴛᴇᴠᴇ" makes the name
        // on screen differ from the one they type to be messaged.
        String result = Text.of("{letters}KILLED BY %player%")
                .with("%player%", "Steve")
                .plain();
        assertEquals("ᴋɪʟʟᴇᴅ ʙʏ Steve", result);
    }

    @Test
    @DisplayName("numbers substituted into a line stay readable")
    void numbersAreNotTransformed() {
        String result = Text.of("BALANCE: %coins%")
                .with("%coins%", 1500)
                .plain();
        assertEquals("ʙᴀʟᴀɴᴄᴇ: 1500", result);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("switching the style drops components cached in the other one")
    void togglingInvalidatesTheCache() {
        assertEquals("ᴡᴇʟᴄᴏᴍᴇ", plain("{primary}WELCOME"));
        TextEngine.smallText(false);
        // Without dropping the cache this still reads "ᴡᴇʟᴄᴏᴍᴇ": the entry was
        // built in the other style and is keyed only by the raw text.
        assertEquals("WELCOME", plain("{primary}WELCOME"));
    }

    @Test
    @DisplayName("the uncached path transforms the same as the cached one")
    void uncachedPathAgrees() {
        assertEquals(PLAIN.serialize(TextEngine.parse("{primary}WELCOME")),
                PLAIN.serialize(TextEngine.parseUncached("{primary}WELCOME")));
    }

    @Test
    @DisplayName("plain text with no formatting is transformed too")
    void plainFastPathIsTransformed() {
        // The scanner sends text with no '&', '{' or '<' straight to a plain
        // component, skipping the parser. Forgetting that path would leave
        // every unformatted lore line in normal capitals.
        assertEquals("ᴘʟᴀɪɴ ʟɪɴᴇ", plain("PLAIN LINE"));
    }

    // ------------------------------------------------------------------
    // Centring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a centred line is measured by the glyph that is drawn")
    void centringMeasuresTheDrawnGlyph() {
        // A capital is five pixels wide; the small capital that replaces it is
        // four. Measuring the source text would pad every centred line too far
        // to the right.
        int small = Centering.pixelWidth("WELCOME");
        TextEngine.smallText(false);
        int normal = Centering.pixelWidth("WELCOME");
        assertTrue(small < normal,
                "small capitals are narrower, so the measured width must drop");
    }

    private static TextColor firstColor(Component component) {
        if (component.color() != null) {
            return component.color();
        }
        for (Component child : component.children()) {
            TextColor found = firstColor(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
