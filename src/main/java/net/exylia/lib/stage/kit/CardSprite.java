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
 * @since 1.356.0
 */
public final class CardSprite {

    /** The look of every card, so a game passes one value rather than a dozen. */
    public record Style(String faceBlock, String backBlock, String red, String black, String back,
                        double width, double height, double textScale) {

        public static Style standard() {
            return new Style("WHITE_CONCRETE", "PURPLE_CONCRETE", "{error}", "{letters_black}", "{letters}&l✦",
                    0.3, 0.44, 1.1);
        }
    }

    private final Canvas canvas;
    private final Style style;
    private final Location at;
    private DisplayHandle plate;
    private DisplayHandle label;
    private PlayingCard card;
    private boolean faceUp;

    /** What a view lends a card so it can draw on it; {@link Stage} implements it. */
    public interface Canvas {
        DisplayHandle draw(DisplayModel model, DisplayMotion motion, Location at);

        void erase(DisplayHandle handle);

        Rotation facing();

        long life();
    }

    /**
     * Deals a card: it glides from {@code from} (the deck, as an offset from its place) to its place.
     *
     * @param delay how long it waits before it moves, so a hand is dealt one card at a time
     */
    public CardSprite(Canvas canvas, Style style, Location at, PlayingCard card, boolean faceUp,
                      double fromAcross, double fromUp, long delay) {
        this.canvas = canvas;
        this.style = style;
        this.at = at.clone();
        this.card = card;
        this.faceUp = faceUp && card != null;
        draw(fromAcross, fromUp, delay);
    }

    /** Turns the card over to show its face, with a half turn. */
    public void reveal(PlayingCard card) {
        this.card = card;
        this.faceUp = true;
        canvas.erase(plate);
        canvas.erase(label);
        draw(0, 0, 0);
    }

    /** Outlines the card, or takes the outline off with a negative colour. */
    public void glow(int rgb) {
        if (plate != null) plate.glow(rgb);
    }

    /** Lifts the card out of its row a little, as a selected or winning card. */
    public void lift(double up) {
        if (plate == null) return;
        for (DisplayHandle piece : new DisplayHandle[]{plate, label}) {
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
        for (DisplayHandle piece : new DisplayHandle[]{plate, label}) {
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
    }

    public void remove() {
        canvas.erase(plate);
        canvas.erase(label);
        plate = null;
        label = null;
    }

    public PlayingCard card() {
        return card;
    }

    private void draw(double fromAcross, double fromUp, long delay) {
        Rotation facing = canvas.facing();
        String block = faceUp ? style.faceBlock() : style.backBlock();
        plate = canvas.draw(DisplayModel.block(StageLook.block(block, Material.WHITE_CONCRETE)).light(15),
                glide(facing, style.width(), style.height(), 0.02, fromAcross, fromUp, delay), at);
        String text = faceUp
                ? (card.suit().isRed() ? style.red() : style.black()) + "&l" + card.label() + "<newline>" + card.suit().symbol()
                : style.back();
        double scale = style.textScale();
        label = canvas.draw(DisplayModel.text(StageHud.line(text, null)).billboard("FIXED").light(15).background(0),
                glide(facing, scale, scale, scale, fromAcross, fromUp, delay),
                at.clone().add(0, -Stage.TEXT_HEIGHT * scale, 0).add(forward(facing, 0.02)));
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

    private static org.bukkit.util.Vector forward(Rotation facing, double distance) {
        float[] out = facing.apply(new float[]{0f, 0f, (float) distance});
        return new org.bukkit.util.Vector(out[0], out[1], out[2]);
    }
}
