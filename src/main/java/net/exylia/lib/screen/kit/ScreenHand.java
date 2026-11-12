package net.exylia.lib.screen.kit;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * A hand of cards (or dice, tiles) across a strip of a scene: side by side while they fit, overlapping more as the hand
 * grows, so one card or thirty always span the same strip and every card keeps a part the cursor can hit. The hovered
 * card lifts. A rival's hand is only {@link #backs backs}: it never holds a face.
 *
 * <pre>{@code
 * ScreenHand hand = new ScreenHand(0, 280, 26);
 * for (int i = 0; i < cards.size(); i++) {
 *     scene.sprite("hand" + i).size(26, 38).at(hand.x(i, cards.size()), 140).layer(i)
 *             .content(() -> ScreenHand.lifting(face(cards.get(i)), 6));
 * }
 * }</pre>
 *
 * @since 1.602.0
 */
public final class ScreenHand {

    /** The space between two cards while the hand still fits without overlapping. */
    public static final double GAP = 2;

    private final double left;
    private final double width;
    private final double card;

    /**
     * @param left  where the strip starts on the scene
     * @param width how wide the strip is
     * @param card  one card across
     */
    public ScreenHand(double left, double width, double card) {
        this.left = left;
        this.width = width;
        this.card = card;
    }

    /** From one card's left edge to the next: a card and a gap while they fit, then less, down to what the strip holds. */
    public double pitch(int count) {
        if (count <= 1) return card + GAP;
        return Math.min(card + GAP, (width - card) / (count - 1));
    }

    /** The left edge of card {@code index} of {@code count}, the hand centred on the strip. */
    public double x(int index, int count) {
        double pitch = pitch(count);
        double span = count <= 0 ? 0 : (count - 1) * pitch + card;
        return left + (width - span) / 2 + index * pitch;
    }

    /** Whether {@code count} cards overlap, so each shows only a strip: draw its index in the corner, not centred. */
    public boolean overlaps(int count) {
        return count > 1 && pitch(count) < card + GAP;
    }

    /**
     * Fills a card frame: its {@code centre} while the hand does not overlap, or only the small {@code corner} mark
     * (rank or symbol) at the top left, inside the strip that stays visible, like the index of a real card.
     *
     * @since 1.603.1
     */
    public static @NotNull Node indexed(@NotNull Node frame, @NotNull Node centre, @NotNull Node corner, boolean overlapped) {
        if (!overlapped) return frame.add(centre);
        return frame.add(corner.absolute(1, Double.NaN, Double.NaN, 1.5));
    }

    /** A card that rises by {@code lift} pixels while the cursor is on it; where it can be clicked stays put. */
    public static @NotNull Node lifting(@NotNull Node card, double lift) {
        return card.hoverTransform(Motion.Transform.translate(0, -lift)).transition(3, Motion.Ease.OUT);
    }

    /**
     * A rival's hand as a small fan of backs: up to {@code most} drawn, however many they hold. Only the count reaches the
     * client, never a face.
     *
     * @param count  the cards they hold
     * @param back   one back, as the game draws it at {@code width} by {@code height}
     * @param most   the most backs drawn
     */
    public static @NotNull Node backs(int count, @NotNull Supplier<Node> back, double width, double height, int most) {
        int shown = Math.max(0, Math.min(count, most));
        double step = width / 2;
        Node fan = Node.box().size(width + Math.max(0, shown - 1) * step, height);
        for (int i = 0; i < shown; i++) fan.add(back.get().absolute(0, Double.NaN, Double.NaN, i * step).layer(i));
        return fan;
    }
}
