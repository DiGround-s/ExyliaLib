package net.exylia.lib.screen.kit;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.ScreenScene;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Dice on a {@link ScreenScene}, with the pips laid out as on a real die. One sprite each; a face costs one display for
 * the die and one for each pip. The value is decided before the roll: the faces shown until it lands are decoys, and the
 * real one is sent last.
 *
 * <pre>{@code
 * ScreenDice dice = new ScreenDice(ScreenDice.Look.standard());
 * dice.roll(board.sprite("die0"), board, 20, 40, 3, 16);   // tumbles, lands on 3
 * }</pre>
 *
 * @since 1.532.0
 */
public final class ScreenDice {

    /** Where the pips of each face sit, as {across, down} pairs in steps from the middle. */
    private static final int[][] PIPS = {
            {0, 0},
            {-1, -1, 1, 1},
            {-1, -1, 0, 0, 1, 1},
            {-1, -1, 1, -1, -1, 1, 1, 1},
            {-1, -1, 1, -1, 0, 0, -1, 1, 1, 1},
            {-1, -1, -1, 0, -1, 1, 1, -1, 1, 0, 1, 1}};

    /**
     * The look of every die.
     *
     * @param face the die's colour
     * @param edge its outline
     * @param pip  a pip's colour
     * @param size a die across and down, in screen pixels
     */
    public record Look(@NotNull String face, @NotNull String edge, @NotNull String pip, double size) {

        public static @NotNull Look standard() {
            return new Look("{letters}", "{letters_black}", "{primary}", 32);
        }

        public @NotNull Look sized(double size) {
            return new Look(face, edge, pip, size);
        }
    }

    private final Look look;

    public ScreenDice(@NotNull Look look) {
        this.look = look;
    }

    public @NotNull Look look() {
        return look;
    }

    /** A face of the die, 1 to 6, as a node of the look's size. */
    public @NotNull Node face(int value) {
        double size = look.size();
        double pip = Math.max(2, Math.round(size * 0.18));
        double step = size * 0.28;
        Node die = Node.box().size(size, size).background(look.face()).outline(look.edge(), 1);
        int[] spots = PIPS[Math.max(1, Math.min(6, value)) - 1];
        for (int i = 0; i < spots.length; i += 2) {
            die.add(Node.box().size(pip, pip).background(look.pip())
                    .absolute(size / 2 + spots[i + 1] * step - pip / 2, Double.NaN, Double.NaN, size / 2 + spots[i] * step - pip / 2));
        }
        return die;
    }

    /** {@code sprite} shows {@code value}, at once. */
    public @NotNull ScreenScene.Sprite show(@NotNull ScreenScene.Sprite sprite, int value) {
        return sprite.size(look.size(), look.size()).content(() -> face(value));
    }

    /**
     * The die tumbles in its place for about {@code ticks} and lands on {@code value}, with a small pop.
     *
     * @return the timeline, to cancel it with the round
     */
    public @NotNull ScreenScene.Timeline roll(@NotNull ScreenScene.Sprite sprite, @NotNull ScreenScene scene, double x, double y,
                                              int value, int ticks) {
        sprite.size(look.size(), look.size());
        ScreenScene.Timeline tumble = scene.timeline();
        if (scene.reducedMotion()) {
            show(sprite.at(x, y), value);
            return tumble.play();
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int[] shown = {random.nextInt(1, 7)};
        show(sprite.at(x, y), shown[0]);
        for (int at = 1; at < ticks - 2; at += 2) {
            double left = (double) (ticks - at) / ticks;
            double dx = random.nextDouble(-3, 3) * left;
            double dy = random.nextDouble(-3, 3) * left;
            tumble.at(at, () -> {
                shown[0] = shown[0] % 6 + random.nextInt(1, 5);
                if (shown[0] > 6) shown[0] -= 6;
                show(sprite, shown[0]);
                sprite.move(x + dx, y + dy, 2, Motion.Ease.LINEAR);
            });
        }
        tumble.at(ticks, () -> {
            show(sprite, value);
            sprite.move(x, y, 1, Motion.Ease.LINEAR);
            sprite.pop(1.15, 4);
        });
        return tumble.play();
    }
}
