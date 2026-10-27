package net.exylia.lib.stage.kit;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.stage.Stage;
import net.exylia.lib.stage.StageHud;
import net.exylia.lib.stage.StageLook;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * A playing card on a table, drawn by a {@link Stage}: dealt in from the deck with a glide, face
 * down or face up, flipped with a turn, lit when it matters.
 *
 * <p>A face is a white plate with its rank and suit, red or dark by suit; a back is the table's own
 * colour with a star. Sizes are in blocks.
 *
 * <pre>{@code
 * List<PlayingCard> deck = PlayingCard.shuffledDeck();
 * CardSprite card = new CardSprite(stage, CardSprite.Style.standard(), stage.plane().point(0, 0, 0.02),
 *         deck.removeLast(), false, 0.9, 0.6, 120);       // dealt face down from the deck's offset
 * card.reveal(card.card());                                  // turned over
 * }</pre>
 *
 * <p>A card in a player's hand is {@linkplain #held held}: its face is drawn for that player alone, and
 * whoever watches their screen sees its back, until it is {@linkplain #uncover uncovered} (a showdown).
 *
 * @since 1.356.0
 */
public final class CardSprite {

    /**
     * How a face is drawn. {@code FRENCH} writes the rank and the suit symbol, {@code A♠}. {@code SPANISH}
     * draws the 40-card Spanish deck players of truco and escoba know: the number ({@code 1} to {@code 7},
     * {@code 10} to {@code 12}) over the suit as an item, a sword for espadas, a club for bastos, a coin
     * for oros and a cup for copas, each in its own colour.
     *
     * @since 1.361.0
     */
    public enum Deck { FRENCH, SPANISH }

    /** The look of every card, so a game passes one value rather than a dozen. */
    public record Style(String faceBlock, String backBlock, String red, String black, String back,
                        double width, double height, double textScale, Deck deck) {

        /** A French card, as before {@link Deck} existed. */
        public Style(String faceBlock, String backBlock, String red, String black, String back,
                     double width, double height, double textScale) {
            this(faceBlock, backBlock, red, black, back, width, height, textScale, Deck.FRENCH);
        }

        public static Style standard() {
            return new Style("WHITE_CONCRETE", "PURPLE_CONCRETE", "{error}", "{letters_black}", "{letters}&l✦",
                    0.3, 0.44, 1.1);
        }

        /**
         * The same look drawing another deck.
         *
         * @since 1.361.0
         */
        public Style with(Deck deck) {
            return new Style(faceBlock, backBlock, red, black, back, width, height, textScale, deck);
        }
    }

    private final Canvas canvas;
    private final Style style;
    private final Location at;
    private DisplayHandle plate;
    private DisplayHandle label;
    /** The suit of a Spanish face, drawn as an item; {@code null} otherwise. */
    private DisplayHandle icon;
    private PlayingCard card;
    private boolean faceUp;
    private boolean held;
    /** The back watchers see while a held card is face up to its holder. */
    private DisplayHandle coverPlate;
    private DisplayHandle coverLabel;

    /** What a view lends a card so it can draw on it; {@link Stage} implements it. */
    public interface Canvas {
        DisplayHandle draw(DisplayModel model, DisplayMotion motion, Location at);

        void erase(DisplayHandle handle);

        Rotation facing();

        long life();

        /** Draws what only the canvas's own player may see; a canvas nobody watches just draws it. @since 1.370.0 */
        default void secretly(Runnable draw) {
            draw.run();
        }

        /** Draws what whoever watches sees but the canvas's own player; a canvas nobody watches draws nothing. @since 1.370.0 */
        default void toOthers(Runnable draw) {
        }
    }

    /**
     * Deals a card: it glides from {@code from} (the deck, as an offset from its place) to its place.
     *
     * @param delay how long it waits before it moves, so a hand is dealt one card at a time
     */
    public CardSprite(Canvas canvas, Style style, Location at, PlayingCard card, boolean faceUp,
                      double fromAcross, double fromUp, long delay) {
        this(canvas, style, at, card, faceUp, false, fromAcross, fromUp, delay);
    }

    /**
     * Deals a card as {@link #CardSprite(Canvas, Style, Location, PlayingCard, boolean, double, double, long)},
     * held or not.
     *
     * @param held whether it is in the canvas player's hand: its face is theirs alone, watchers see its back
     * @since 1.370.0
     */
    public CardSprite(Canvas canvas, Style style, Location at, PlayingCard card, boolean faceUp, boolean held,
                      double fromAcross, double fromUp, long delay) {
        this.canvas = canvas;
        this.style = style;
        this.at = at.clone();
        this.card = card;
        this.faceUp = faceUp && card != null;
        this.held = held;
        draw(fromAcross, fromUp, delay);
    }

    /** Turns the card over to show its face, with a half turn. A held card shows it to its holder alone. */
    public void reveal(PlayingCard card) {
        this.card = card;
        this.faceUp = true;
        erase();
        draw(0, 0, 0);
    }

    /**
     * Shows a held card's face to everyone watching too, as at a showdown; turns it over if it was down.
     *
     * @since 1.370.0
     */
    public void uncover() {
        if (!held && faceUp) return;
        held = false;
        if (card != null) reveal(card);
    }

    /** Whether its face is its holder's alone. @since 1.370.0 */
    public boolean held() {
        return held;
    }

    /** Outlines the card, or takes the outline off with a negative colour. */
    public void glow(int rgb) {
        if (plate != null) plate.glow(rgb);
        if (coverPlate != null) coverPlate.glow(rgb);
    }

    /** Lifts the card out of its row a little, as a selected or winning card. */
    public void lift(double up) {
        if (plate == null) return;
        for (DisplayHandle piece : new DisplayHandle[]{plate, label, icon, coverPlate, coverLabel}) {
            DisplayKeyframe here = piece == null ? null : piece.pose();
            if (here != null) {
                piece.moveTo(new DisplayKeyframe(0L, here.x(), (float) up, here.z(), here.rotation(),
                        here.scaleX(), here.scaleY(), here.scaleZ()), 180L, DisplayMotion.Easing.BACK_OUT);
            }
        }
    }

    /**
     * Sweeps the card off the table: it glides {@code (across, up)} along the board, shrinking to
     * nothing, and is gone. A folded hand, or the cards cleared before the next deal.
     */
    public void muck(double across, double up, long millis) {
        float[] by = canvas.facing().apply(new float[]{(float) across, (float) up, 0f});
        for (DisplayHandle piece : new DisplayHandle[]{plate, label, icon, coverPlate, coverLabel}) {
            DisplayKeyframe here = piece == null ? null : piece.pose();
            if (here == null) continue;
            // The new motion is the piece's whole remaining life, so it ends where it vanishes.
            piece.then(DisplayMotion.of(java.util.List.of(
                    new DisplayKeyframe(0L, here.x(), here.y(), here.z(), here.rotation(),
                            here.scaleX(), here.scaleY(), here.scaleZ()),
                    new DisplayKeyframe(millis, here.x() + by[0], here.y() + by[1], here.z() + by[2], here.rotation(),
                            0.01f, 0.01f, 0.01f)), millis));
        }
        plate = null;
        label = null;
        icon = null;
        coverPlate = null;
        coverLabel = null;
    }

    public void remove() {
        erase();
    }

    private void erase() {
        canvas.erase(plate);
        canvas.erase(label);
        canvas.erase(icon);
        canvas.erase(coverPlate);
        canvas.erase(coverLabel);
        plate = null;
        label = null;
        icon = null;
        coverPlate = null;
        coverLabel = null;
    }

    public PlayingCard card() {
        return card;
    }

    private void draw(double fromAcross, double fromUp, long delay) {
        if (!held || !faceUp) {
            drawSide(faceUp, fromAcross, fromUp, delay);
            return;
        }
        canvas.secretly(() -> drawSide(true, fromAcross, fromUp, delay));
        // Whoever watches sees the back; the face pieces are kept, the back ones put aside as the cover.
        DisplayHandle facePlate = plate;
        DisplayHandle faceLabel = label;
        DisplayHandle faceIcon = icon;
        canvas.toOthers(() -> drawSide(false, fromAcross, fromUp, delay));
        coverPlate = plate;
        coverLabel = label;
        plate = facePlate;
        label = faceLabel;
        icon = faceIcon;
    }

    private void drawSide(boolean faceUp, double fromAcross, double fromUp, long delay) {
        Rotation facing = canvas.facing();
        String block = faceUp ? style.faceBlock() : style.backBlock();
        plate = canvas.draw(DisplayModel.block(StageLook.block(block, Material.WHITE_CONCRETE)).light(15),
                glide(facing, style.width(), style.height(), 0.02, fromAcross, fromUp, delay), at);
        double scale = style.textScale();
        if (faceUp && style.deck() == Deck.SPANISH) {
            // The number over the top half, the suit's item filling the bottom half.
            double number = scale * 0.7;
            label = canvas.draw(DisplayModel.text(StageHud.line(spanishColour(card.suit()) + "&l" + card.spanishLabel(), null))
                            .billboard("FIXED").light(15).background(0),
                    glide(facing, number, number, number, fromAcross, fromUp, delay),
                    at.clone().add(forward(facing, 0.02)));
            double size = Math.min(style.width(), style.height() / 2) * 0.8;
            icon = canvas.draw(DisplayModel.item(new ItemStack(spanishIcon(card.suit()))).light(15),
                    glide(facing, size, size, size * 0.1, fromAcross, fromUp, delay),
                    at.clone().add(up(facing, -style.height() / 4)).add(forward(facing, 0.025)));
            return;
        }
        String text = faceUp
                ? (card.suit().isRed() ? style.red() : style.black()) + "&l" + card.label() + "<newline>" + card.suit().symbol()
                : style.back();
        icon = null;
        label = canvas.draw(DisplayModel.text(StageHud.line(text, null)).billboard("FIXED").light(15).background(0),
                glide(facing, scale, scale, scale, fromAcross, fromUp, delay),
                at.clone().add(0, -Stage.TEXT_HEIGHT * scale, 0).add(forward(facing, 0.02)));
    }

    /** The colours of a Spanish deck: gold oros, red copas, blue espadas, green bastos. */
    private static String spanishColour(PlayingCard.Suit suit) {
        return switch (suit) {
            case DIAMONDS -> "{highlight}";
            case HEARTS -> "{error}";
            case SPADES -> "{info}";
            case CLUBS -> "{success}";
        };
    }

    /** The item a Spanish suit is drawn as. */
    private static Material spanishIcon(PlayingCard.Suit suit) {
        return switch (suit) {
            case DIAMONDS -> Material.GOLD_NUGGET;
            case HEARTS -> Material.FLOWER_POT;
            case SPADES -> Material.IRON_SWORD;
            case CLUBS -> Material.STICK;
        };
    }

    /** From the deck to its place, with the turn of a card being flipped down onto the table. */
    private DisplayMotion glide(Rotation facing, double x, double y, double z, double fromAcross, double fromUp, long delay) {
        float sx = (float) x;
        float sy = (float) y;
        float sz = (float) z;
        // Offsets are in the display's own frame, which the board's turn maps onto the seat's right and up.
        float[] from = facing.apply(new float[]{(float) fromAcross, (float) fromUp, 0f});
        Rotation flipped = Rotation.around(Rotation.Axis.Y, Math.PI / 2).then(facing);
        return DisplayMotion.of(java.util.List.of(
                new DisplayKeyframe(0L, from[0], from[1], from[2], flipped, sx * 0.01f, sy * 0.01f, sz),
                new DisplayKeyframe(delay, from[0], from[1], from[2], flipped, sx, sy, sz),
                new DisplayKeyframe(delay + 260L, 0f, 0f, 0f, facing, sx, sy, sz)), canvas.life());
    }

    private static org.bukkit.util.Vector up(Rotation facing, double distance) {
        float[] out = facing.apply(new float[]{0f, (float) distance, 0f});
        return new org.bukkit.util.Vector(out[0], out[1], out[2]);
    }

    private static org.bukkit.util.Vector forward(Rotation facing, double distance) {
        float[] out = facing.apply(new float[]{0f, 0f, (float) distance});
        return new org.bukkit.util.Vector(out[0], out[1], out[2]);
    }
}
