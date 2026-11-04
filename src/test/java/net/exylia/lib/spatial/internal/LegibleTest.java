package net.exylia.lib.spatial.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A catalogue's words, written for an inventory, read whole over a 3D board's backdrop. */
class LegibleTest {

    private static final Map<String, Integer> PALETTE = Map.of("letters", 0xE7CFFF, "letters_black", 0xA89AB5,
            "highlight", 0xFFD700, "neutral", 0x6C757D, "muted", 0x868E96, "error", 0xA33B53);

    private static String over(String raw) {
        return Legible.over(raw, 150, PALETTE::get);
    }

    @Test
    void aDarkColourTakesTheLinesOwnReadableOne() {
        assertEquals("{letters_black}then {highlight}the whole dance{letters_black}.",
                over("{letters_black}then {highlight}the whole dance{neutral}."));
        assertEquals("{letters}quiet", over("{muted}quiet"), "with nothing readable before it: {letters}");
        assertEquals("{letters}dark {letters}grey", over("{letters}dark &8grey"));
        assertEquals("{letters}red {letters}x", over("{letters}red <#3a3a3a>x"));
    }

    @Test
    void readableWordsAndUnknownTokensAreLeftAlone() {
        String line = "{letters}Look {highlight}here &lnow {unknown}x";
        assertEquals(line, over(line));
        assertEquals("{neutral}x", Legible.over("{neutral}x", 0, PALETTE::get), "no backdrop: nothing to read against");
    }

    @Test
    void theThresholdIsTheLayoutTestsOne() {
        assertTrue(Legible.readable(0xA89AB5, 150));
        assertFalse(Legible.readable(0x6C757D, 150));
        assertFalse(Legible.readable(0x868E96, 150));
    }

    @Test
    void aColourOnSymbolsAloneCarriesMeaningAndIsKept() {
        assertEquals("<#1d1d21>■", over("<#1d1d21>■"), "a black dye's swatch stays black");
        assertEquals("<#3c44aa>■ {letters}Stripe", over("<#3c44aa>■ {letters}Stripe"));
        assertEquals("&8&l↶", over("&8&l↶"), "an icon the layout coloured");
        assertEquals("{neutral}✔ {letters}x", over("{neutral}✔ {letters}x"));
        assertEquals("{letters}a {letters}word ■", over("{letters}a {neutral}word ■"), "words beside a symbol are words");
        assertEquals("{letters}x{letters}.", over("{letters}x{neutral}."), "punctuation is not a symbol");
        assertEquals("<#1d1d21>■ {letters}Stripe", over("<#1d1d21>■ {neutral}Stripe"), "a dark swatch is not the body colour");
    }

    @Test
    void aHueCarriesMeaningAndIsKept() {
        assertEquals("{error}✘ Locked", over("{error}✘ Locked"));
        assertEquals("<#3c44aa>Rare", over("<#3c44aa>Rare"), "a rarity's blue");
        assertEquals("&1x", over("&1x"));
        assertEquals("{letters}x {letters}y", over("{letters}x &8y"), "a grey still gives way");
    }

    @Test
    void aSwatchIsItsColourAndTheWordsAfterIt() {
        assertEquals(new Legible.Swatch(0x1D1D21, ""), Legible.swatch("<#1d1d21>■", PALETTE::get));
        assertEquals(new Legible.Swatch(0xA33B53, "{letters}Stripe Left"),
                Legible.swatch("{error}■ {letters}Stripe Left", PALETTE::get));
        assertEquals(new Legible.Swatch(0x555555, ""), Legible.swatch("&l&8■", PALETTE::get));
        assertEquals(null, Legible.swatch("{letters}Base", PALETTE::get));
        assertEquals(null, Legible.swatch("{unknown}■", PALETTE::get));
    }
}
