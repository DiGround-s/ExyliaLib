package net.exylia.lib.screen.kit;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.ScreenScene;
import net.exylia.lib.stage.kit.CardSprite;
import net.exylia.lib.stage.kit.PlayingCard;
import org.jetbrains.annotations.NotNull;

/**
 * Playing cards on a {@link ScreenScene}: one sprite each, its face sent only when it is turned, so the server can hold a
 * hidden card as long as the rules ask. Shared by every game played with cards.
 *
 * <pre>{@code
 * ScreenCards cards = new ScreenCards(ScreenCards.Look.standard());
 * ScreenScene.Sprite c = cards.dealt(board.sprite("dealer0"), board, 170, -50, 20, 10, 6);   // face down, gliding in
 * cards.turn(c, card, 6);                                                                     // flips, face sent at its edge
 * }</pre>
 *
 * A card costs 3 or 4 displays; a flip one transform per display, per step.
 *
 * @since 1.532.0
 */
public final class ScreenCards {

    /**
     * The look of every card.
     *
     * @param face    the face's colour
     * @param edge    the outline, of a face and of a back
     * @param back    the back's colour
     * @param mark    the words in the middle of a back, with their colours
     * @param red     hearts and diamonds, as a text colour
     * @param black   spades and clubs, as a text colour
     * @param width   a card across, in screen pixels
     * @param height  a card down
     * @param deck    which deck's labels it reads
     */
    public record Look(@NotNull String face, @NotNull String edge, @NotNull String back, @NotNull String mark,
                       @NotNull String red, @NotNull String black, double width, double height,
                       CardSprite.@NotNull Deck deck) {

        public static @NotNull Look standard() {
            return new Look("{letters}", "{letters_black}", "{primary}", "{letters}✦", "{error}", "&0", 30, 42, CardSprite.Deck.FRENCH);
        }

        /** The same look at another size; the words inside keep their place at any size from 16 by 22 up. */
        public @NotNull Look sized(double width, double height) {
            return new Look(face, edge, back, mark, red, black, width, height, deck);
        }

        /** The same look reading another deck. */
        public @NotNull Look with(CardSprite.@NotNull Deck deck) {
            return new Look(face, edge, back, mark, red, black, width, height, deck);
        }
    }

    private final Look look;

    public ScreenCards(@NotNull Look look) {
        this.look = look;
    }

    public @NotNull Look look() {
        return look;
    }

    /** What the card's corner reads in this deck. */
    private String label(PlayingCard card) {
        return look.deck() == CardSprite.Deck.SPANISH ? card.spanishLabel() : card.label();
    }

    /** A card's face as a node of the look's size: the rank in its corner and the suit large in the middle. */
    public @NotNull Node face(@NotNull PlayingCard card) {
        String ink = card.suit().isRed() ? look.red() : look.black();
        double big = look.height() / 22;
        double w = look.width();
        double h = look.height();
        Node suit = Node.text(ink + card.suit().symbol()).scale(big);
        return Node.box().size(w, h).background(look.face()).outline(look.edge(), 1)
                .add(Node.text(ink + label(card)).absolute(2, Double.NaN, Double.NaN, 3),
                        suit.width(w).textAlign(Node.Align.CENTER).absolute((h - 9 * big) / 2 + 3, Double.NaN, Double.NaN, 0));
    }

    /** A card's back as a node of the look's size. */
    public @NotNull Node back() {
        return Node.box().size(look.width(), look.height()).background(look.back()).outline(look.edge(), 1)
                .align(Node.Align.CENTER).justify(Node.Justify.CENTER).add(Node.text(look.mark()));
    }

    /** {@code sprite} shows {@code card}, at once. */
    public @NotNull ScreenScene.Sprite up(@NotNull ScreenScene.Sprite sprite, @NotNull PlayingCard card) {
        return sprite.size(look.width(), look.height()).content(() -> face(card));
    }

    /** {@code sprite} shows a card's back, at once. */
    public @NotNull ScreenScene.Sprite down(@NotNull ScreenScene.Sprite sprite) {
        return sprite.size(look.width(), look.height()).content(this::back);
    }

    /**
     * A card comes in face down from {@code fromX}, {@code fromY} to its place, over {@code ticks}. It needs a tick on the
     * scene before it can glide, so the move starts a tick later.
     */
    public @NotNull ScreenScene.Sprite dealt(@NotNull ScreenScene.Sprite sprite, @NotNull ScreenScene scene, double fromX, double fromY,
                                             double x, double y, int ticks) {
        down(sprite).at(fromX, fromY);
        scene.timeline().at(1, () -> sprite.move(x, y, ticks, Motion.Ease.OUT)).play();
        return sprite;
    }

    /** Turns the card over to show {@code card}; its face leaves the server only at the card's edge. */
    public @NotNull ScreenScene.Sprite turn(@NotNull ScreenScene.Sprite sprite, @NotNull PlayingCard card, int ticks) {
        return sprite.flip(ticks, () -> up(sprite, card));
    }

    /** Turns the card over to show its back. */
    public @NotNull ScreenScene.Sprite hide(@NotNull ScreenScene.Sprite sprite, int ticks) {
        return sprite.flip(ticks, () -> down(sprite));
    }
}
