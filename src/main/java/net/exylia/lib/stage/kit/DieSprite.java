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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A die on a table, shared by every game played with dice: a white cube that is thrown onto the table
 * and then shows its face, or a hidden face for dice under a cup.
 *
 * <p>The pips are small flat blocks on the side that faces the seat, laid out as on a real die: a
 * die face glyph in a text display is drawn by the client's fallback font at a size of its own, a
 * thin outline wider than the cube with pips a pixel across.
 *
 * <p>A throw is one path of keyframes handed to the client at once: an arc off the table, a tumble
 * on three axes that slows down, two bounces each lower than the last, a short rock as it settles.
 * The cube turns whole quarter turns on every axis, so it always lands square to the seat.
 *
 * <pre>{@code
 * DieSprite die = new DieSprite(stage, DieSprite.Style.standard(), stage.plane().point(0, 0, 0.12), 0);
 * die.roll(value, 1_200);                     // the value decided before the roll is drawn
 * }</pre>
 *
 * @since 1.356.0
 */
public final class DieSprite {

    /** Where the pips of each face sit, as {across, up} pairs in steps of {@link #PIP_STEP} of the side. */
    private static final int[][] PIPS = {
            {0, 0},
            {-1, 1, 1, -1},
            {-1, 1, 0, 0, 1, -1},
            {-1, 1, 1, 1, -1, -1, 1, -1},
            {-1, 1, 1, 1, 0, 0, -1, -1, 1, -1},
            {-1, 1, -1, 0, -1, -1, 1, 1, 1, 0, 1, -1}};
    private static final double PIP_STEP = 0.27;
    private static final double PIP_SIZE = 0.2;
    private static final double PIP_DEPTH = 0.006;

    /** A keyframe every tick: the client draws the straight line between two of them. */
    static final long STEP = 50L;
    /** Where the die touches the table, as shares of the throw: two bounces, then it slides to rest. */
    private static final double FIRST_LANDING = 0.42;
    private static final double SECOND_LANDING = 0.68;
    private static final double THIRD_LANDING = 0.85;

    /**
     * The look of every die.
     *
     * @param pips      the block its pips are made of; anything else (an old palette token) is black concrete
     * @param hidden    the text on a hidden face; empty for none
     * @param faceScale how big the face is drawn: ten times {@code size} is a face that fills the side
     */
    public record Style(String block, String pips, String hidden, double size, double faceScale) {

        public static Style standard() {
            return new Style("WHITE_CONCRETE", "BLACK_CONCRETE", "{letters}&l?", 0.24, 2.4);
        }

        /** The face's size as a share of a full side. */
        double face() {
            return size > 0 ? faceScale / (size * 10) : 1;
        }
    }

    private final CardSprite.Canvas canvas;
    private final Style style;
    private final Location at;
    private final DisplayHandle cube;
    private final List<DisplayHandle> face = new ArrayList<>();

    public DieSprite(CardSprite.Canvas canvas, Style style, Location at, long delay) {
        this(canvas, style, at, StageLook.pop(canvas.facing(), style.size(), style.size(), style.size(), delay, canvas.life()));
    }

    private DieSprite(CardSprite.Canvas canvas, Style style, Location at, DisplayMotion motion) {
        this.canvas = canvas;
        this.style = style;
        this.at = at.clone();
        this.cube = canvas.draw(DisplayModel.block(StageLook.block(style.block(), Material.WHITE_CONCRETE)).light(15), motion, at);
    }

    /**
     * A die thrown onto the table: it appears {@code fromAcross} and {@code fromUp} away from
     * {@code at} after {@code delay}, flies there on an arc tumbling, bounces twice and settles on
     * {@code value} (1 to 6, or 0 to keep the face hidden) once {@code millis} more have passed.
     *
     * @param fromAcross where it leaves the hand, across the board from where it lands: the side of
     *                   the player who throws it
     * @param fromUp     the same, up the board
     */
    public static DieSprite thrown(CardSprite.Canvas canvas, Style style, Location at, int value,
                                   double fromAcross, double fromUp, long delay, long millis) {
        List<DisplayKeyframe> path = path(canvas.facing(), style.size(), fromAcross, fromUp, millis,
                ThreadLocalRandom.current());
        DisplayKeyframe first = path.getFirst();
        List<DisplayKeyframe> keys = new ArrayList<>();
        // Unseen until the throw, then out of the hand at full size.
        keys.add(sized(first, 0L, 0.01f));
        if (delay > 0) keys.add(sized(first, delay, 0.01f));
        for (DisplayKeyframe key : path) keys.add(key.at(key.atMillis() + delay + 1));
        DieSprite die = new DieSprite(canvas, style, at, DisplayMotion.of(keys, canvas.life()));
        if (value > 0) die.show(value, delay + millis);
        return die;
    }

    /**
     * Rolls it where it stands: the cube jumps out of its place, tumbles, bounces twice and lands back
     * on it after {@code millis}, showing {@code value} from 1 to 6; or {@code 0} to keep the face hidden.
     */
    public void roll(int value, long millis) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double size = style.size();
        throwFrom(value, random.nextDouble(-1.6, 1.6) * size, random.nextDouble(1.2, 2.0) * size, millis);
    }

    /**
     * Throws it again from {@code fromAcross} and {@code fromUp} away from its place, as
     * {@link #thrown} does, landing on {@code value} after {@code millis}.
     */
    public void throwFrom(int value, double fromAcross, double fromUp, long millis) {
        erase();
        if (cube != null) cube.then(DisplayMotion.of(path(canvas.facing(), style.size(), fromAcross, fromUp, millis,
                ThreadLocalRandom.current()), canvas.life()));
        if (value > 0) show(value, millis);
    }

    /**
     * The whole path of a throw that lands at the die's place, every pose relative to it and turned by
     * {@code facing}: one keyframe a tick, the last one at rest and square to the seat.
     *
     * <p>Height off the table is the board's forward axis; a share of it is added upwards too, so the
     * arc still reads to a player looking at the board face on. The spin is whole quarter turns per
     * axis, eased out, budgeted so no two keyframes are more than a third of a turn apart and the
     * client's shorter-arc turn between them never runs backwards.
     */
    static List<DisplayKeyframe> path(Rotation facing, double size, double fromAcross, double fromUp, long millis,
                                      Random random) {
        long life = Math.max(STEP * 6, millis);
        double arc = Math.max(size * 1.6, Math.hypot(fromAcross, fromUp) * 0.35);
        // About a hundred milliseconds a quarter turn keeps the fastest step, at the start, under 120°.
        int budget = (int) Math.max(3, life / 100);
        int qx = Math.max(1, budget * 2 / 5 + random.nextInt(2));
        int qy = Math.max(1, budget / 3 - random.nextInt(2));
        int qz = Math.max(1, budget - qx - qy);
        int sx = random.nextBoolean() ? 1 : -1;
        int sz = random.nextBoolean() ? 1 : -1;
        float scale = (float) size;
        List<DisplayKeyframe> keys = new ArrayList<>();
        for (long t = 0; ; t = Math.min(life, t + STEP)) {
            double s = (double) t / life;
            double travel = 1 - Math.pow(1 - s, 2.2);
            double height = height(s, arc);
            double across = fromAcross * (1 - travel);
            double up = fromUp * (1 - travel) + height * 0.3;
            double turn = (1 - (1 - s) * (1 - s)) * Math.PI / 2;
            // Whole quarter turns at the end: a cube that looks exactly as it does square to the seat.
            Rotation spin = Rotation.around(Rotation.Axis.X, sx * qx * turn + rock(s))
                    .then(Rotation.around(Rotation.Axis.Y, qy * turn))
                    .then(Rotation.around(Rotation.Axis.Z, sz * qz * turn))
                    .then(facing);
            float[] offset = facing.apply(new float[]{(float) across, (float) up, (float) height});
            keys.add(new DisplayKeyframe(t, offset[0], offset[1], offset[2], spin, scale, scale, scale));
            if (t == life) return keys;
        }
    }

    /** Height off the table at share {@code s} of a throw: a lob, then two bounces each lower than the last. */
    static double height(double s, double arc) {
        if (s < FIRST_LANDING) {
            double u = s / FIRST_LANDING;
            return arc * 0.55 * (1 - u) + arc * 1.6 * u * (1 - u);
        }
        if (s < SECOND_LANDING) {
            double u = (s - FIRST_LANDING) / (SECOND_LANDING - FIRST_LANDING);
            return arc * 0.4 * 4 * u * (1 - u);
        }
        if (s < THIRD_LANDING) {
            double u = (s - SECOND_LANDING) / (THIRD_LANDING - SECOND_LANDING);
            return arc * 0.12 * 4 * u * (1 - u);
        }
        return 0;
    }

    /** The rock of a die settling after the last bounce, in radians, dying away. */
    private static double rock(double s) {
        if (s < THIRD_LANDING) return 0;
        double u = (s - THIRD_LANDING) / (1 - THIRD_LANDING);
        return 0.2 * (1 - u) * Math.sin(u * Math.PI * 2);
    }

    private static DisplayKeyframe sized(DisplayKeyframe key, long millis, float size) {
        return new DisplayKeyframe(millis, key.x(), key.y(), key.z(), key.rotation(), size, size, size);
    }

    /** Shows a face without a roll, as when the cup is lifted: its pips, or the hidden mark for 0. */
    public void show(int value, long delay) {
        erase();
        double size = style.size();
        double share = style.face();
        if (value < 1 || value > 6) {
            if (style.hidden() == null || style.hidden().isEmpty()) return;
            double scale = size * 3 * share;
            face.add(canvas.draw(DisplayModel.text(StageHud.line(style.hidden(), null)).billboard("FIXED").light(15)
                            .background(0), StageLook.pop(canvas.facing(), scale, scale, scale, delay, canvas.life()),
                    at.clone().add(0, -Stage.TEXT_HEIGHT * scale / 2, 0).add(offset(0, 0, size / 2 + 0.01))));
            return;
        }
        double pip = size * PIP_SIZE * share;
        double step = size * PIP_STEP * share;
        DisplayModel model = DisplayModel.block(StageLook.block(style.pips(), Material.BLACK_CONCRETE)).light(15);
        int[] spots = PIPS[value - 1];
        for (int i = 0; i < spots.length; i += 2) {
            face.add(canvas.draw(model, StageLook.pop(canvas.facing(), pip, pip, PIP_DEPTH, delay + i * 10L, canvas.life()),
                    at.clone().add(offset(spots[i] * step, spots[i + 1] * step, size / 2 + PIP_DEPTH / 2 + 0.001))));
        }
    }

    /** Hides the face, as under a cup. */
    public void hide() {
        show(0, 0);
    }

    /**
     * Takes the die off the table: after {@code delay} it flies up and away, tumbling and shrinking,
     * and is gone once {@code millis} more have passed. A die lost in a game of dice.
     */
    public void toss(long delay, long millis) {
        erase();
        double size = style.size();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Offsets are in the display's own frame, which the board's turn maps onto the seat's right and up.
        float[] away = canvas.facing().apply(new float[]{(float) random.nextDouble(-0.6, 0.6), 0.9f, 0.4f});
        if (cube == null) return;
        cube.then(DisplayMotion.chain(
                StageLook.still(canvas.facing(), size, size, size, Math.max(1, delay)),
                DisplayMotion.builder().life(Math.max(1, millis)).from(0, 0, 0).to(away[0], away[1], away[2])
                        .rotation(canvas.facing()).scale(size, 0.01)
                        .spin(2, 3, 1)
                        .ease(DisplayMotion.Easing.QUAD_IN)
                        .build()));
    }

    public void glow(int rgb) {
        if (cube != null) cube.glow(rgb);
    }

    public void remove() {
        canvas.erase(cube);
        erase();
    }

    private void erase() {
        face.forEach(canvas::erase);
        face.clear();
    }

    /** A point {@code across}, {@code up} and {@code forward} of the die's centre, in the seat's frame. */
    private org.bukkit.util.Vector offset(double across, double up, double forward) {
        float[] out = canvas.facing().apply(new float[]{(float) across, (float) up, (float) forward});
        return new org.bukkit.util.Vector(out[0], out[1], out[2]);
    }
}
