package net.exylia.lib.stage.kit;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A card of the standard 52-card deck, shared by every game played with one. Pure: no server, no
 * drawing.
 *
 * <pre>{@code
 * List<PlayingCard> deck = PlayingCard.shuffledDeck();
 * PlayingCard top = deck.removeLast();      // top.label() + top.suit().symbol(), "A♠"
 * }</pre>
 *
 * @param rank 2 to 14, where 11 is a jack, 12 a queen, 13 a king and 14 an ace
 * @param suit its suit
 * @since 1.356.0
 */
public record PlayingCard(int rank, Suit suit) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Suit {
        SPADES("♠", false), HEARTS("♥", true), DIAMONDS("♦", true), CLUBS("♣", false);

        private final String symbol;
        private final boolean red;

        Suit(String symbol, boolean red) {
            this.symbol = symbol;
            this.red = red;
        }

        public String symbol() {
            return symbol;
        }

        public boolean isRed() {
            return red;
        }
    }

    /** What the corner of the card reads: {@code A}, {@code 10}, {@code K}. */
    public String label() {
        return switch (rank) {
            case 11 -> "J";
            case 12 -> "Q";
            case 13 -> "K";
            case 14 -> "A";
            default -> String.valueOf(rank);
        };
    }

    /** A full deck, shuffled from a secure random source. */
    public static List<PlayingCard> shuffledDeck() {
        return decks(1, RANDOM);
    }

    /** {@code decks} full decks shuffled together, for a game dealt from more than one. */
    public static List<PlayingCard> decks(int decks, java.util.Random random) {
        List<PlayingCard> cards = new ArrayList<>(52 * Math.max(1, decks));
        for (int deck = 0; deck < Math.max(1, decks); deck++) {
            for (Suit suit : Suit.values()) {
                for (int rank = 2; rank <= 14; rank++) cards.add(new PlayingCard(rank, suit));
            }
        }
        Collections.shuffle(cards, random);
        return cards;
    }

    /** {@code decks} full decks shuffled from a secure random source. */
    public static List<PlayingCard> decks(int decks) {
        return decks(decks, RANDOM);
    }
}
