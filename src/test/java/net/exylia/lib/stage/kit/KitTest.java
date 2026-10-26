package net.exylia.lib.stage.kit;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitTest {

    @Test
    @DisplayName("a deck holds the 52 cards once; several decks hold each card that many times")
    void decks() {
        List<PlayingCard> deck = PlayingCard.shuffledDeck();
        assertEquals(52, deck.size());
        assertEquals(52, new HashSet<>(deck).size());
        List<PlayingCard> six = PlayingCard.decks(6, new Random(1));
        assertEquals(312, six.size());
        assertEquals(6, six.stream().filter(card -> card.equals(new PlayingCard(14, PlayingCard.Suit.SPADES))).count());
        assertEquals("A", new PlayingCard(14, PlayingCard.Suit.HEARTS).label());
        assertEquals("10", new PlayingCard(10, PlayingCard.Suit.CLUBS).label());
        assertTrue(PlayingCard.Suit.DIAMONDS.isRed());
    }

    @Test
    @DisplayName("a thrown die lands where it belongs, square to the seat, one keyframe a tick")
    void throwLands() {
        Rotation facing = Rotation.around(Rotation.Axis.Y, 0.7);
        List<DisplayKeyframe> path = DieSprite.path(facing, 0.24, 0.8, 0.4, 1_000, new Random(7));
        DisplayKeyframe last = path.getLast();
        assertEquals(1_000, last.atMillis());
        assertEquals(0, last.x(), 1e-5);
        assertEquals(0, last.y(), 1e-5);
        assertEquals(0, last.z(), 1e-5);
        float[] forward = last.rotation().apply(new float[]{0, 0, 1});
        float[] expected = facing.apply(new float[]{0, 0, 1});
        // Whole quarter turns on every axis: a face of the cube looks the way the seat's front does.
        double best = 0;
        for (float[] axis : new float[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}) {
            float[] turned = last.rotation().apply(axis);
            best = Math.max(best, Math.abs(turned[0] * expected[0] + turned[1] * expected[1] + turned[2] * expected[2]));
        }
        assertEquals(1, best, 1e-4, "some face points at the seat: " + java.util.Arrays.toString(forward));
        for (int i = 1; i < path.size(); i++) {
            assertTrue(path.get(i).atMillis() - path.get(i - 1).atMillis() <= DieSprite.STEP);
        }
    }

    @Test
    @DisplayName("a die touches the table at each landing and rests after the last")
    void bounces() {
        assertEquals(0, DieSprite.height(0.42, 1), 1e-9);
        assertEquals(0, DieSprite.height(0.68, 1), 1e-9);
        assertEquals(0, DieSprite.height(0.85, 1), 1e-9);
        assertEquals(0, DieSprite.height(0.95, 1), 1e-9);
        assertTrue(DieSprite.height(0.55, 1) > DieSprite.height(0.77, 1), "each bounce lower than the last");
    }
}
