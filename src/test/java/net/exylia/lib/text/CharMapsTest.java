package net.exylia.lib.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the character walk behind small capitals and player fonts.
 *
 * <p>The cases that matter fail silently: a rewritten tag stops being a tag,
 * a rewritten placeholder name stops matching, and a surrogate pair split in
 * half reaches the client as two broken glyphs.
 */
class CharMapsTest {

    /** Every ASCII letter becomes an upper-case X; everything else stays. */
    private static final IntUnaryOperator LETTERS_TO_X =
            codePoint -> Character.isLetter(codePoint) && codePoint < 128 ? 'X' : codePoint;

    /** {@code a} becomes fraktur {@code 𝔞}, which needs two chars. */
    private static final IntUnaryOperator A_TO_FRAKTUR =
            codePoint -> codePoint == 'a' ? 0x1D51E : codePoint;

    @Test
    @DisplayName("visible letters are rewritten")
    void rewritesLetters() {
        assertEquals("XXXXX 123!", CharMaps.transform("Hello 123!", LETTERS_TO_X));
    }

    @Test
    @DisplayName("tags, tokens, placeholders and legacy codes are copied through")
    void keepsInstructions() {
        String raw = "{primary}<bold>Hi</bold> &lok &#8a51c4go %player_name% 50%";
        assertEquals("{primary}<bold>XX</bold> &lXX &#8a51c4XX %player_name% 50%",
                CharMaps.transform(raw, LETTERS_TO_X));
    }

    @Test
    @DisplayName("an unclosed tag or brace is text")
    void unclosedIsText() {
        assertEquals("X < X {X", CharMaps.transform("a < b {c", LETTERS_TO_X));
    }

    @Test
    @DisplayName("a replacement outside the basic plane is written whole")
    void supplementaryReplacement() {
        assertEquals("𝔞b𝔞", CharMaps.transform("aba", A_TO_FRAKTUR));
    }

    @Test
    @DisplayName("a surrogate pair in the input is one code point")
    void supplementaryInput() {
        int[] seen = new int[1];
        CharMaps.transform("𝔞", codePoint -> {
            seen[0] = codePoint;
            return codePoint;
        });
        assertEquals(0x1D51E, seen[0]);
    }

    @Test
    @DisplayName("a line that does not change is the same instance")
    void unchangedIsSame() {
        String raw = "123 <bold>%x%</bold>";
        assertSame(raw, CharMaps.transform(raw, LETTERS_TO_X));
    }
}
