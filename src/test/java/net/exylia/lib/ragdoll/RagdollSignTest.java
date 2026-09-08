package net.exylia.lib.ragdoll;

import net.exylia.lib.ragdoll.internal.RagdollSign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A word spelled out of somebody has to actually be spelled.
 *
 * <p>Every one of these is invisible in the code and unmistakable in game: a
 * letter with a stroke nobody was assigned to, a stroke drawn as a dot because
 * its length came out at zero, or a word that walks off to one side because it
 * was never centred.
 */
class RagdollSignTest {

    private static final double HEIGHT = 2.4;

    @Test
    @DisplayName("every stroke of a word gets at least one piece of the body")
    void nothingIsLeftUndrawn() {
        for (String word : new String[]{"EZ", "GG EZ", "RIP", "L", "OWNED"}) {
            int strokes = RagdollSign.strokeCount(word, HEIGHT);
            assertTrue(strokes > 0, word + " came out with no strokes at all");
            int pieces = 20;
            Set<String> drawn = new HashSet<>();
            for (int index = 0; index < pieces; index++) {
                RagdollSign.Placement at = RagdollSign.place(word, HEIGHT, index, pieces);
                assertNotNull(at, word + " lost piece " + index);
                assertTrue(at.length() > 0.05,
                        word + ": piece " + index + " is " + at.length() + " blocks long");
                assertTrue(at.thickness() > 0.05, word + ": piece " + index + " has no thickness");
                assertTrue(at.y() >= -0.01 && at.y() <= HEIGHT + 0.01,
                        word + ": piece " + index + " sits at " + at.y() + ", off the line");
                drawn.add(Math.round(at.x() * 40) + ":" + Math.round(at.y() * 40));
            }
            // With more pieces than strokes, no two may end up in the same place:
            // that is a letter drawn twice and another one not drawn at all.
            assertTrue(drawn.size() >= Math.min(pieces, strokes),
                    word + " drew only " + drawn.size() + " distinct places for "
                            + strokes + " strokes");
        }
    }

    @Test
    @DisplayName("a word is centred on the body it came out of")
    void wordsAreCentred() {
        double left = Double.MAX_VALUE;
        double right = -Double.MAX_VALUE;
        for (int index = 0; index < 40; index++) {
            RagdollSign.Placement at = RagdollSign.place("GG EZ", HEIGHT, index, 40);
            left = Math.min(left, at.x());
            right = Math.max(right, at.x());
        }
        assertTrue(Math.abs(left + right) < HEIGHT * 0.6,
                "the word runs from " + left + " to " + right + ", which is not centred");
    }

    @Test
    @DisplayName("a word with nothing spellable in it draws nothing")
    void nonsenseDrawsNothing() {
        // Rather than throwing: a sign that quietly loses a comma is better
        // than a kill effect that refuses to play.
        assertNull(RagdollSign.place("...", HEIGHT, 0, 20));
    }
}
