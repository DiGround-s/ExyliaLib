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
        assertEquals("{letters}red {letters}x", over("{letters}red <#3a0000>x"));
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
}
