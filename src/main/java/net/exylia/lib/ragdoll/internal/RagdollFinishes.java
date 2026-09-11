package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollMotion;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * What one piece of a choreographed body does after the last frame.
 *
 * <h2>Carried on from where it was</h2>
 * Every finish starts from the piece's last two poses: where it was, how fast
 * it was going and how fast it was turning. A body flung upwards and burst at
 * the top of its arc goes on rising for a moment before it comes apart, and a
 * body that implodes while it is spinning keeps spinning on the way in. The one
 * thing a finish never does is start from standing, because that is a snap, and
 * a snap at the moment everybody is looking is the whole effect ruined.
 *
 * <p>Worked out per piece rather than per part, so a body cut finely comes apart
 * into all of its pieces and not into six rigid limbs.
 *
 * <p>Free of Bukkit, so what a piece does can be asserted.
 */
@ApiStatus.Internal
public final class RagdollFinishes {

    /** A ceiling on how long a finish is sampled for, in poses. */
    private static final int MAX_FRAMES = 240;

    /** The fastest a piece may carry on moving, in blocks a second. */
    private static final double MAX_SPEED = 24;

    /** How long a piece takes to lose the spin and the speed it had, in seconds. */
    private static final double DECAY = 0.45;

    /** The most spin a piece keeps from the choreography, in turns a second. */
    private static final double MAX_KEPT_TURNS = 2.0;

    private RagdollFinishes() {
    }

    /**
     * Appends the finish to one piece's poses.
     *
     * @param poses  the piece's poses so far, in the display's own terms, centred
     * @param motion what the file asked for
     * @param scale  how big the body is
     * @param middle where the middle of the body was at the last frame
     * @param head   whether this piece is the head, which is thrown higher
     * @param random where the variation between pieces comes from
     */
    public static void extend(List<DisplayKeyframe> poses, RagdollMotion motion, double scale,
                              double[] middle, boolean head, RandomGenerator random,
                              Spelling spelling) {
        int count = poses.size();
        if (count == 0) {
            return;
        }
        DisplayKeyframe last = poses.get(count - 1);
        long from = last.atMillis();
        long life = motion.lifeMillis();
        if (from >= life) {
            return;
        }
        DisplayKeyframe before = count >= 2 ? poses.get(count - 2) : last;
        double gap = (last.atMillis() - before.atMillis()) / 1000.0;
        double[] velocity = {0, 0, 0};
        if (gap > 0) {
            velocity = new double[]{
                    (last.x() - before.x()) / gap,
                    (last.y() - before.y()) / gap,
                    (last.z() - before.z()) / gap};
            double speed = Math.sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1]
                    + velocity[2] * velocity[2]);
            if (speed > MAX_SPEED) {
                for (int axis = 0; axis < 3; axis++) {
                    velocity[axis] *= MAX_SPEED / speed;
                }
            }
        }
        Kept kept = Kept.between(before.rotation(), last.rotation(), gap);
        Context piece = new Context(poses, motion, scale, middle, last, velocity, kept, from, life);
        switch (motion.finish()) {
            case HOLD -> hold(piece);
            case BURST -> fall(piece, true, head, random);
            case COLLAPSE -> fall(piece, false, head, random);
            case IMPLODE -> implode(piece, head, random);
            case DISSOLVE -> dissolve(piece, random);
            case SPELL -> spell(piece, head, spelling, random);
        }
    }

    /**
     * Which piece of the word a piece is.
     *
     * @param index  which body piece this is, or a negative number for the head
     *               and anything carried, which are not part of the word
     * @param pieces how many body pieces there are
     * @param facing which way the body faces, and so the word
     */
    public record Spelling(int index, int pieces, Rotation facing) {
    }

    /** How long a piece takes to fly into its letter. */
    private static final long SPELL_FLY_MS = 550L;

    /** How long a word takes to fall once it has been read. */
    private static final long SPELL_DROP_MS = 650L;

    /**
     * Flies into its stroke of the word, holds while it is read, and drops.
     *
     * <p>The head is not part of the word: it floats above it, bobbing, so the
     * face of whoever the word is about is the first thing read.
     */
    private static void spell(Context piece, boolean head, Spelling spelling, RandomGenerator random) {
        RagdollMotion motion = piece.motion();
        DisplayKeyframe last = piece.last();
        double scale = piece.scale();
        Rotation facing = spelling.facing();
        float[] target;
        Rotation turned;
        float[] size;
        if (head) {
            target = facing.apply(new float[]{0f,
                    (float) ((motion.rise() + motion.letters() + 0.45) * scale), 0f});
            turned = last.rotation();
            size = new float[]{last.scaleX(), last.scaleY(), last.scaleZ()};
        } else {
            RagdollSign.Placement to = spelling.index() < 0 ? null
                    : RagdollSign.place(motion.sign(), motion.letters(), spelling.index(), spelling.pieces());
            if (to == null) {
                // Carried things and pieces the word has no stroke for simply
                // fall off, which is what they would do.
                fall(piece, false, false, random);
                return;
            }
            target = facing.apply(new float[]{
                    (float) (to.x() * scale), (float) ((motion.rise() + to.y()) * scale), 0f});
            double yaw = 2 * Math.atan2(facing.y(), facing.w());
            // A piece is a box standing on its end, so a stroke running across
            // is that box rolled a quarter turn from upright.
            turned = Rotation.around(Rotation.Axis.Z, to.angle() - Math.PI / 2)
                    .then(Rotation.around(Rotation.Axis.Y, yaw));
            size = new float[]{
                    (float) (to.thickness() * scale),
                    (float) (to.length() * scale),
                    (float) (to.thickness() * scale)};
        }
        long release = Math.max(piece.from() + SPELL_FLY_MS + 200, piece.life() - SPELL_DROP_MS);
        double stagger = random.nextDouble(0, 0.12);
        double flying = SPELL_FLY_MS / 1000.0;
        RagdollFlight.Fall fall = null;
        List<DisplayKeyframe> poses = piece.poses();
        for (long at : beats(piece.from(), piece.life())) {
            double seconds = (at - piece.from()) / 1000.0;
            if (at < release) {
                double progress = Math.clamp((seconds - stagger) / flying, 0, 1);
                double eased = progress < 0.5
                        ? 4 * progress * progress * progress
                        : 1 - Math.pow(-2 * progress + 2, 3) / 2;
                // An arc rather than a line, so the pieces are thrown into place.
                double arc = Math.sin(Math.PI * progress) * 0.5 * scale;
                double bob = head ? Math.sin(seconds * 3) * 0.06 * scale * eased : 0;
                Rotation rotation = head
                        ? last.rotation().then(Rotation.around(Rotation.Axis.Y, Math.sin(seconds * 2) * 0.4 * eased))
                        : KeyframeThinning.slerp(last.rotation(), turned, eased);
                poses.add(new DisplayKeyframe(at,
                        (float) (last.x() + (target[0] - last.x()) * eased),
                        (float) (last.y() + (target[1] - last.y()) * eased + arc + bob),
                        (float) (last.z() + (target[2] - last.z()) * eased),
                        rotation,
                        (float) (last.scaleX() + (size[0] - last.scaleX()) * eased),
                        (float) (last.scaleY() + (size[1] - last.scaleY()) * eased),
                        (float) (last.scaleZ() + (size[2] - last.scaleZ()) * eased)));
                continue;
            }
            DisplayKeyframe held = poses.get(poses.size() - 1);
            if (fall == null) {
                fall = new RagdollFlight.Fall(motion, new double[]{held.x(), held.y(), held.z()},
                        new double[]{0, 0, 0}, floor(piece, head));
            }
            fall.to((at - release) / 1000.0);
            double[] position = fall.position();
            poses.add(new DisplayKeyframe(at, (float) position[0], (float) position[1], (float) position[2],
                    held.rotation(), held.scaleX(), held.scaleY(), held.scaleZ()));
        }
    }

    /** Everything a finish needs about one piece. */
    private record Context(List<DisplayKeyframe> poses, RagdollMotion motion, double scale,
                           double[] middle, DisplayKeyframe last, double[] velocity, Kept kept,
                           long from, long life) {

        /** How far the momentum it had carries it by a moment, in blocks. */
        double carried(int axis, double seconds) {
            return velocity[axis] * DECAY * (1 - Math.exp(-seconds / DECAY));
        }

        void add(long at, double x, double y, double z, Rotation rotation, double shrink) {
            poses.add(new DisplayKeyframe(at, (float) x, (float) y, (float) z, rotation,
                    (float) (last.scaleX() * shrink),
                    (float) (last.scaleY() * shrink),
                    (float) (last.scaleZ() * shrink)));
        }
    }

    /** The spin a piece had when the choreography ended, dying away. */
    private record Kept(double[] axis, double radiansPerSecond) {

        static Kept between(Rotation before, Rotation after, double gap) {
            if (gap <= 0) {
                return new Kept(new double[]{0, 1, 0}, 0);
            }
            // after = delta * before, so delta = after * conjugate(before).
            Rotation delta = new Rotation(-before.x(), -before.y(), -before.z(), before.w()).then(after);
            double sign = delta.w() < 0 ? -1 : 1;
            double w = Math.min(1, delta.w() * sign);
            double half = Math.sqrt(Math.max(0, 1 - w * w));
            if (half < 1e-6) {
                return new Kept(new double[]{0, 1, 0}, 0);
            }
            double[] axis = {delta.x() * sign / half, delta.y() * sign / half, delta.z() * sign / half};
            double rate = Math.min(2 * Math.acos(w) / gap, MAX_KEPT_TURNS * Math.PI * 2);
            return new Kept(axis, rate);
        }

        Rotation by(double seconds) {
            double angle = radiansPerSecond * DECAY * (1 - Math.exp(-seconds / DECAY));
            if (angle == 0) {
                return Rotation.NONE;
            }
            double sin = Math.sin(angle / 2);
            return new Rotation((float) (axis[0] * sin), (float) (axis[1] * sin),
                    (float) (axis[2] * sin), (float) Math.cos(angle / 2));
        }
    }

    /** Stays exactly as it was. Sampled anyway, so a fade at the end has poses to shrink. */
    private static void hold(Context piece) {
        DisplayKeyframe last = piece.last();
        for (long at : beats(piece.from(), piece.life())) {
            piece.add(at, last.x(), last.y(), last.z(), last.rotation(), 1);
        }
    }

    /** Drops, with or without a throw on top of what it was already doing. */
    private static void fall(Context piece, boolean thrown, boolean head, RandomGenerator random) {
        DisplayKeyframe last = piece.last();
        RagdollMotion motion = piece.motion();
        double[] velocity = piece.velocity().clone();
        double turns;
        if (thrown) {
            double angle = outwards(last, piece.middle(), random) + random.nextDouble(-1, 1) * motion.spread();
            double vary = 1 + random.nextDouble(-1, 1) * motion.spread();
            velocity[0] += Math.cos(angle) * motion.speed() * vary;
            velocity[1] += motion.up() * (1 + random.nextDouble(-0.5, 0.5) * motion.spread())
                    * (head ? RagdollFlight.HEAD_LIFT_SPEED : 1.0);
            velocity[2] += Math.sin(angle) * motion.speed() * vary;
            turns = RagdollFlight.spinRate(motion, random);
        } else {
            // Nothing throws it, so nothing much turns it: a limp piece rolls a
            // little as it goes down and no more.
            turns = random.nextDouble(-0.5, 0.5);
        }
        double[] axis = RagdollFlight.tumbleAxis(random);
        RagdollFlight.Fall fall = new RagdollFlight.Fall(motion,
                new double[]{last.x(), last.y(), last.z()}, velocity, floor(piece, head));
        Rotation rested = null;
        for (long at : beats(piece.from(), piece.life())) {
            double seconds = (at - piece.from()) / 1000.0;
            fall.to(seconds);
            Rotation turned;
            if (fall.resting() && motion.settle()) {
                if (rested == null) {
                    rested = turned(piece, axis, turns, fall.restedAt());
                }
                turned = rested;
            } else {
                turned = turned(piece, axis, turns, seconds);
            }
            double[] at3 = fall.position();
            piece.add(at, at3[0], at3[1], at3[2], turned, 1);
        }
    }

    /** Spirals into the middle of the body, faster and smaller as it goes. */
    private static void implode(Context piece, boolean head, RandomGenerator random) {
        DisplayKeyframe last = piece.last();
        double[] middle = piece.middle();
        double span = Math.max(0.05, (piece.life() - piece.from()) / 1000.0);
        // A little apart, so a body is taken piece by piece and not all at
        // once, which reads as one object shrinking.
        double stagger = random.nextDouble(0, 0.3);
        double direction = piece.motion().turns() < 0 ? -1 : 1;
        double turns = Math.max(0.75, Math.abs(piece.motion().turns())) * direction;
        double[] axis = RagdollFlight.tumbleAxis(random);
        double ox = last.x() - middle[0];
        double oy = last.y() - middle[1];
        double oz = last.z() - middle[2];
        double floor = floor(piece, head);
        for (long at : beats(piece.from(), piece.life())) {
            double seconds = (at - piece.from()) / 1000.0;
            double progress = Math.clamp((seconds / span - stagger) / (1 - stagger), 0, 1);
            // Squared: it hangs on at the edge and is then taken quickly, which
            // is what being pulled in looks like. At one rate it is a drain.
            double drawn = progress * progress;
            double angle = turns * Math.PI * 2 * drawn;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double left = 1 - drawn;
            double x = middle[0] + (ox * cos + oz * sin) * left + piece.carried(0, seconds) * left;
            double y = middle[1] + oy * left + piece.carried(1, seconds) * left;
            double z = middle[2] + (-ox * sin + oz * cos) * left + piece.carried(2, seconds) * left;
            piece.add(at, x, Math.max(floor, y), z,
                    turned(piece, axis, 1.6 * direction, seconds), Math.max(0.02, left));
        }
    }

    /** Blows away, from the top of the body down. */
    private static void dissolve(Context piece, RandomGenerator random) {
        DisplayKeyframe last = piece.last();
        double[] middle = piece.middle();
        double span = Math.max(0.05, (piece.life() - piece.from()) / 1000.0);
        // The head goes first and the feet last, with a little of each piece's
        // own luck, so it reads as something passing down the body.
        double height = Math.clamp((last.y() - middle[1] + 1.0) / 2.0, 0, 1);
        double delay = span * (0.32 * (1 - height) + random.nextDouble(0, 0.18));
        double drifting = Math.max(0.05, span - delay);
        double away = outwards(last, middle, random);
        double wind = random.nextDouble(0.45, 0.95);
        double lift = random.nextDouble(0.7, 1.5);
        double[] axis = RagdollFlight.tumbleAxis(random);
        double turns = random.nextDouble(0.2, 0.7);
        for (long at : beats(piece.from(), piece.life())) {
            double seconds = (at - piece.from()) / 1000.0;
            double after = Math.max(0, seconds - delay);
            double progress = Math.clamp(after / drifting, 0, 1);
            double gone = Math.pow(after, 1.5);
            piece.add(at,
                    last.x() + piece.carried(0, seconds) + Math.cos(away) * wind * gone,
                    last.y() + piece.carried(1, seconds) + lift * gone,
                    last.z() + piece.carried(2, seconds) + Math.sin(away) * wind * gone,
                    turned(piece, axis, turns, after),
                    after <= 0 ? 1 : Math.max(0.02, 1 - Math.pow(progress, 1.3)));
        }
    }

    /** Its last rotation, with what it kept of its spin and a turn of its own on top. */
    private static Rotation turned(Context piece, double[] axis, double turns, double seconds) {
        return piece.last().rotation()
                .then(piece.kept().by(seconds))
                .then(RagdollFlight.tumble(axis, turns, seconds));
    }

    /**
     * How high a piece's centre stops above the floor.
     *
     * <p>A head is half a block thick and a cell a fraction of that. Resting
     * both at the cell's height puts half of every head that lands inside the
     * floor, which is the one piece anybody is looking for.
     */
    private static double floor(Context piece, boolean head) {
        return (head ? HEAD_REST : RagdollFlight.REST_HEIGHT) * piece.scale();
    }

    /** How high a head's centre rests, per block of body: half its own height. */
    private static final double HEAD_REST = 0.25;

    /** Which way along the ground a piece is from the middle of the body. */
    private static double outwards(DisplayKeyframe piece, double[] middle, RandomGenerator random) {
        double dx = piece.x() - middle[0];
        double dz = piece.z() - middle[2];
        return Math.abs(dx) < 1e-3 && Math.abs(dz) < 1e-3
                ? random.nextDouble(Math.PI * 2)
                : Math.atan2(dz, dx);
    }

    /** Every beat after {@code from}, up to and including {@code life}. */
    private static long[] beats(long from, long life) {
        long span = life - from;
        long beat = RagdollFlight.FRAME_MS;
        if (span / beat > MAX_FRAMES) {
            beat = (long) Math.ceil((double) span / MAX_FRAMES / RagdollFlight.FRAME_MS) * RagdollFlight.FRAME_MS;
        }
        int count = (int) Math.ceil((double) span / beat);
        long[] times = new long[count];
        for (int index = 0; index < count; index++) {
            times[index] = Math.min(life, from + (index + 1) * beat);
        }
        return times;
    }
}
