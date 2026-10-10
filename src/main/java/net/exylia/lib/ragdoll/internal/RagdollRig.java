package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollPart;
import org.jetbrains.annotations.ApiStatus;

/**
 * A body as a skeleton: where every part is once its joints have been turned.
 *
 * <h2>Why a skeleton</h2>
 * Every other pose in this module moves each part on its own curve and trusts
 * the curves to agree. That is fine for a body coming apart and hopeless for a
 * body that dances: an arm that is moved rather than carried drifts off the
 * shoulder the moment the chest turns, and the gap is the first thing anybody
 * sees. Here nothing is moved. The hips are placed, the chest hangs off the
 * hips, the head and the arms hang off the chest and the legs off the hips, so
 * a turn anywhere carries everything below it and no joint can open.
 *
 * <h2>The body's own axes</h2>
 * Worked out facing the way the body faces, and turned into the world last:
 * {@code X} is the body's left, {@code Y} is up and {@code Z} is forward, towards
 * whoever it is facing. Every number a file writes is in those terms, so the
 * same choreography reads the same from wherever the killer was standing.
 *
 * <h2>Channels</h2>
 * A pose is a flat array of doubles, one per channel, because a timeline
 * interpolates numbers and nothing else. Angles are in degrees and stay
 * unwrapped: 720 is two turns, which is how a spin is written.
 *
 * <h2>Bends</h2>
 * Elbows, knees and the middle of the back bend. A bend is a hinge at the
 * middle of the part: the half beyond it (a forearm, a shin, a chest) turns
 * about that hinge and the half before it stays with the part. The part
 * reports the hinge's turn in its own axes, and whatever draws it gives each
 * piece the turn of the half it is in. A chest that bends carries the head and
 * both arms with it, which is what makes a bow a bow and not a plank tipping.
 *
 * <h2>Two ways of writing a pose</h2>
 * A {@code keys:} line is written in this rig's own terms, mirrored so that
 * {@code arms=} means both arms. An animation made in Blockbench or
 * Emotecraft is written in the vanilla player model's terms instead: the head
 * and arms hang off the model rather than off the chest, each turn is the
 * model's {@code x, y, z} in the model's order, and a bend is an amount and an
 * axis. Such a body is placed the vanilla way, so it moves exactly as it did
 * where it was made.
 *
 * <p>Free of Bukkit, so where a hand ends up can be asserted.
 */
@ApiStatus.Internal
public final class RagdollRig {

    /** Where the hips are moved to: to the body's right, up and forward, in blocks. */
    public static final int RIGHT = 0;
    public static final int UP = 1;
    public static final int FORWARD = 2;

    /** The whole body turned about the hips: forwards, to its left, to its right. */
    public static final int FLIP = 3;
    public static final int TURN = 4;
    public static final int LEAN = 5;

    /** How big the whole body is, as a multiple of its own size. */
    public static final int SIZE = 6;

    /** How hard the whole body trembles, in blocks. */
    public static final int SHAKE = 7;

    /** How firmly the feet stay where the body stood, from 0 to 1. */
    public static final int PLANT = 8;

    /** The joints, in the order their channels are laid out. */
    public static final int HEAD = 0;
    public static final int BODY = 1;
    public static final int ARM_RIGHT = 2;
    public static final int ARM_LEFT = 3;
    public static final int LEG_RIGHT = 4;
    public static final int LEG_LEFT = 5;

    /** What each joint has. */
    public static final int PITCH = 0;
    public static final int YAW = 1;
    public static final int ROLL = 2;
    public static final int OUT = 3;
    public static final int RAISE = 4;
    public static final int AHEAD = 5;
    public static final int SCALE = 6;

    /** How far the joint's hinge is bent, in degrees: an elbow, a knee, the back. */
    public static final int BEND = 7;

    /** Which way round the limb that hinge faces, in degrees. */
    public static final int BEND_AXIS = 8;

    private static final int FIRST_JOINT = 9;
    private static final int PER_JOINT = 9;

    /** How many channels a pose has. */
    public static final int COUNT = FIRST_JOINT + 6 * PER_JOINT;

    /**
     * Where planted feet stand, kept after the channels of an animated pose:
     * the hips' right and forward once the plant began, so a body planted
     * away from where the effect is drawn keeps its feet under itself.
     */
    public static final int FEET_RIGHT = COUNT;
    public static final int FEET_FORWARD = COUNT + 1;

    /** How long an animated pose is: every channel, then where the feet stand. */
    public static final int POSE = COUNT + 2;

    private static final float P = RagdollPart.PIXEL;

    private RagdollRig() {
    }

    /** The channel one joint keeps one of its numbers in. */
    public static int of(int joint, int field) {
        return FIRST_JOINT + joint * PER_JOINT + field;
    }

    /** Whether a channel is an angle, which is what a spin can go wrong on. */
    public static boolean isAngle(int channel) {
        if (channel == FLIP || channel == TURN || channel == LEAN) {
            return true;
        }
        if (channel < FIRST_JOINT) {
            return false;
        }
        int field = (channel - FIRST_JOINT) % PER_JOINT;
        return field == PITCH || field == YAW || field == ROLL || field == BEND || field == BEND_AXIS;
    }

    /** A body standing still, at its own size. */
    public static double[] standing() {
        double[] pose = new double[COUNT];
        pose[SIZE] = 1;
        for (int joint = HEAD; joint <= LEG_LEFT; joint++) {
            pose[of(joint, SCALE)] = 1;
        }
        return pose;
    }

    /**
     * Where one part is, how it is turned and how big it is, for a pose written
     * in this rig's own terms.
     *
     * @see #place(RagdollPart, double[], double, Rotation, double, boolean)
     */
    public static Placed place(RagdollPart part, double[] pose, double scale, Rotation facing,
                               double seconds) {
        return place(part, pose, scale, facing, seconds, false);
    }

    /**
     * Where one part is, how it is turned and how big it is.
     *
     * @param part    which part
     * @param pose    every channel
     * @param scale   how big the body is; 1 is player-sized
     * @param facing  which way the body faces in the world
     * @param seconds how far into the choreography, for the tremble
     * @param vanilla whether the pose is written the way the vanilla player
     *                model is posed
     * @return the part's centre, relative to the feet, in world axes
     */
    public static Placed place(RagdollPart part, double[] pose, double scale, Rotation facing,
                               double seconds, boolean vanilla) {
        Placed local = vanilla ? vanilla(part, pose, scale) : own(part, pose, scale, seconds);
        double[] world = turn(facing, new double[]{local.x(), local.y(), local.z()});
        return new Placed(world[0], world[1], world[2], local.rotation().then(facing), local.size(), local.bend());
    }

    /** A pose in this rig's own terms, before it is turned the way the body faces. */
    private static Placed own(RagdollPart part, double[] pose, double scale, double seconds) {
        double size = scale * pose[SIZE];
        double legs = Math.max(pose[of(LEG_RIGHT, SCALE)], pose[of(LEG_LEFT, SCALE)]);

        double[] root = {-pose[RIGHT], 12 * P * size * legs + pose[UP], pose[FORWARD]};
        double shakeYaw = 0;
        double shake = pose[SHAKE];
        if (shake != 0) {
            // Several sines at frequencies that never line up, so it trembles
            // rather than oscillates. The same for every part, because a body
            // that shakes apart is a different effect.
            double t = seconds;
            root[0] += shake * (0.6 * Math.sin(37.1 * t) + 0.4 * Math.sin(23.3 * t + 1.3));
            root[1] += shake * 0.5 * (0.6 * Math.sin(41.7 * t + 0.7) + 0.4 * Math.sin(19.9 * t + 2.2));
            root[2] += shake * (0.6 * Math.sin(29.9 * t + 2.1) + 0.4 * Math.sin(45.2 * t + 0.4));
            shakeYaw = shake * 60 * Math.sin(33.3 * t + 1.9);
        }
        Rotation hips = centred(pose[FLIP], pose[TURN] + shakeYaw, pose[LEAN]);

        double[] centre;
        Rotation turned;
        Rotation bend;
        double grown;
        if (part == RagdollPart.LEG_RIGHT || part == RagdollPart.LEG_LEFT) {
            int joint = part == RagdollPart.LEG_LEFT ? LEG_LEFT : LEG_RIGHT;
            double side = part == RagdollPart.LEG_LEFT ? 1 : -1;
            grown = pose[of(joint, SCALE)];
            double[] hip = plus(root, turn(hips, plus(
                    new double[]{side * 2 * P * size * grown, 0, 0}, moved(pose, joint, side))));
            turned = limb(pose, joint, side).then(hips);
            // A knee bends the shin backwards; turned outwards, it swings it out.
            bend = hinge(side * pose[of(joint, BEND_AXIS)], pose[of(joint, BEND)]);
            double plant = Math.clamp(pose[PLANT], 0, 1);
            if (plant > 0) {
                // The feet turn with the body: a body that turns round steps
                // round, rather than twisting its legs about feet nailed down.
                double[] rest = turn(Rotation.around(Rotation.Axis.Y, Math.toRadians(pose[TURN])),
                        new double[]{side * 2 * P * size * grown, 0, 0});
                if (pose.length > FEET_FORWARD) {
                    rest[0] -= pose[FEET_RIGHT];
                    rest[2] += pose[FEET_FORWARD];
                }
                Rotation[] reached = planted(hip, hips, rest, 6 * P * size * grown);
                turned = slerp(turned, reached[0], plant);
                bend = slerp(bend, reached[1], plant);
            }
            centre = plus(hip, turn(turned, new double[]{0, -6 * P * size * grown, 0}));
        } else {
            double chest = pose[of(BODY, SCALE)];
            Rotation body = centred(pose, BODY).then(hips);
            double[] waist = plus(root, turn(hips, moved(pose, BODY, -1)));
            double[] middle = plus(waist, turn(body, new double[]{0, 6 * P * size * chest, 0}));
            // The back bends at its middle, forwards, or to its right when
            // turned a quarter round; the head and the arms ride the upper half.
            Rotation spine = hinge(pose[of(BODY, BEND_AXIS)], pose[of(BODY, BEND)]);
            Rotation upper = spine.then(body);
            switch (part) {
                case TORSO -> {
                    grown = chest;
                    turned = body;
                    centre = middle;
                    bend = spine;
                }
                case HEAD -> {
                    grown = pose[of(HEAD, SCALE)];
                    double[] neck = plus(middle, turn(upper, plus(
                            new double[]{0, 6 * P * size * chest, 0}, moved(pose, HEAD, -1))));
                    turned = centred(pose, HEAD).then(upper);
                    centre = plus(neck, turn(turned, new double[]{0, 4 * P * size * grown, 0}));
                    bend = Rotation.NONE;
                }
                default -> {
                    int joint = part == RagdollPart.ARM_LEFT ? ARM_LEFT : ARM_RIGHT;
                    double side = part == RagdollPart.ARM_LEFT ? 1 : -1;
                    grown = pose[of(joint, SCALE)];
                    // The shoulder sits on the edge of the chest and two pixels
                    // down from its top, where the vanilla model turns an arm.
                    double[] shoulder = plus(middle, turn(upper, plus(new double[]{
                            side * (4 * chest + 2 * grown) * P * size,
                            (6 * chest - 2 * grown) * P * size,
                            0}, moved(pose, joint, side))));
                    turned = limb(pose, joint, side).then(upper);
                    // An elbow bends the forearm forwards; turned outwards, it
                    // swings it out to the side.
                    bend = hinge(-side * pose[of(joint, BEND_AXIS)], -pose[of(joint, BEND)]);
                    centre = plus(shoulder, turn(turned, new double[]{0, -4 * P * size * grown, 0}));
                }
            }
        }
        return new Placed(centre[0], centre[1], centre[2], turned, size * grown, bend);
    }

    /**
     * A leg solved to put its foot back where it stood.
     *
     * <p>Two bones of equal length from the hip to the floor under the hip's
     * resting place, the knee pushed forwards the way the pelvis faces. When
     * the hips are too far away to reach, the leg points straight at the spot
     * instead: a body that leaps leaves the floor rather than stretching.
     *
     * @param hip    where the hip is
     * @param hips   how the pelvis is turned
     * @param rest   where the foot rests on the floor
     * @param bone   how long the thigh is, and the shin
     * @return the turn of the whole leg, and the turn of the shin at the knee
     */
    static Rotation[] planted(double[] hip, Rotation hips, double[] rest, double bone) {
        double[] reach = {rest[0] - hip[0], -hip[1], rest[2] - hip[2]};
        double length = Math.sqrt(dot(reach, reach));
        double[] down = length < 1e-6 ? new double[]{0, -1, 0}
                : new double[]{reach[0] / length, reach[1] / length, reach[2] / length};
        double used = Math.min(length, 2 * bone * 0.9999);
        double half = Math.acos(Math.clamp(used / (2 * bone), -1, 1));
        double[] ahead = turn(hips, new double[]{0, 0, 1});
        double[] knee = minus(ahead, scaled(down, dot(ahead, down)));
        if (dot(knee, knee) < 1e-8) {
            knee = turn(hips, new double[]{0, 1, 0});
            knee = minus(knee, scaled(down, dot(knee, down)));
        }
        knee = unit(knee);
        double[] thigh = plus(scaled(down, Math.cos(half)), scaled(knee, Math.sin(half)));
        double[] front = unit(minus(knee, scaled(thigh, dot(knee, thigh))));
        double[] up = scaled(thigh, -1);
        double[] left = cross(up, front);
        return new Rotation[]{basis(left, up, front), hinge(0, Math.toDegrees(2 * half))};
    }

    /** A pose in the vanilla player model's terms, before it is turned the way the body faces. */
    private static Placed vanilla(RagdollPart part, double[] pose, double scale) {
        // Every bend of the chest swings the head and arms about the middle of
        // the torso, in the model's own axes rather than the torso's.
        double[] middle = {0, 18 * P, 0};
        Rotation lean = hinge(pose[of(BODY, BEND_AXIS)], pose[of(BODY, BEND)]);
        double[] centre;
        Rotation turned;
        Rotation bend = Rotation.NONE;
        double grown;
        boolean upper = false;
        switch (part) {
            case HEAD -> {
                grown = pose[of(HEAD, SCALE)];
                turned = model(pose, HEAD);
                double[] pivot = plus(new double[]{0, 24 * P, 0}, offset(pose, HEAD));
                centre = plus(pivot, turn(turned, new double[]{0, 4 * P * grown, 0}));
                upper = true;
            }
            case TORSO -> {
                grown = pose[of(BODY, SCALE)];
                turned = model(pose, BODY);
                double[] pivot = plus(new double[]{0, 24 * P, 0}, offset(pose, BODY));
                centre = plus(pivot, turn(turned, new double[]{0, -6 * P * grown, 0}));
                bend = lean;
            }
            case ARM_RIGHT, ARM_LEFT -> {
                int joint = part == RagdollPart.ARM_LEFT ? ARM_LEFT : ARM_RIGHT;
                double side = part == RagdollPart.ARM_LEFT ? 1 : -1;
                grown = pose[of(joint, SCALE)];
                turned = model(pose, joint);
                // The model turns an arm a pixel inside its middle.
                double[] pivot = plus(new double[]{side * 5 * P, 22 * P, 0}, offset(pose, joint));
                centre = plus(pivot, turn(turned, new double[]{side * P * grown, -4 * P * grown, 0}));
                bend = hinge(-pose[of(joint, BEND_AXIS)], pose[of(joint, BEND)]);
                upper = true;
            }
            default -> {
                int joint = part == RagdollPart.LEG_LEFT ? LEG_LEFT : LEG_RIGHT;
                double side = part == RagdollPart.LEG_LEFT ? 1 : -1;
                grown = pose[of(joint, SCALE)];
                turned = model(pose, joint);
                double[] pivot = plus(new double[]{side * 1.9 * P, 12 * P, -0.1 * P}, offset(pose, joint));
                centre = plus(pivot, turn(turned, new double[]{0, -6 * P * grown, 0}));
                bend = hinge(-pose[of(joint, BEND_AXIS)], pose[of(joint, BEND)]);
            }
        }
        if (upper) {
            centre = plus(middle, turn(lean, minus(centre, middle)));
            turned = turned.then(lean);
        }
        // The whole body, about the point the vanilla renderer turns it on,
        // seven tenths of a block up a model drawn at fifteen sixteenths.
        double[] pivot = {0, 0.7 / VANILLA_SCALE, 0};
        Rotation whole = Rotation.around(Rotation.Axis.X, Math.toRadians(pose[FLIP]))
                .then(Rotation.around(Rotation.Axis.Y, Math.toRadians(pose[TURN])))
                .then(Rotation.around(Rotation.Axis.Z, Math.toRadians(pose[LEAN])));
        centre = plus(plus(new double[]{-pose[RIGHT], pose[UP], pose[FORWARD]}, pivot),
                turn(whole, minus(centre, pivot)));
        double size = scale * pose[SIZE];
        return new Placed(centre[0] * size, centre[1] * size, centre[2] * size,
                turned.then(whole), size * grown, bend);
    }

    /**
     * How much smaller the vanilla renderer draws a player than its model.
     *
     * <p>Distances an animation gives the whole body are in the world's blocks
     * around a model drawn at this size; here the model is drawn at its own,
     * so they are grown to match.
     */
    public static final double VANILLA_SCALE = 0.9375;

    /**
     * A part's turn as the vanilla model writes it, in this rig's axes.
     *
     * <p>The model turns about {@code x}, then {@code y}, then {@code z}, with
     * {@code y} pointing down and {@code z} out of the back; here {@code y} is
     * up and {@code z} is forward, which flips the sign of the last two.
     */
    private static Rotation model(double[] pose, int joint) {
        return Rotation.around(Rotation.Axis.X, Math.toRadians(pose[of(joint, PITCH)]))
                .then(Rotation.around(Rotation.Axis.Y, -Math.toRadians(pose[of(joint, YAW)])))
                .then(Rotation.around(Rotation.Axis.Z, -Math.toRadians(pose[of(joint, ROLL)])));
    }

    /** How far a vanilla part has been moved from where the model keeps it, in blocks. */
    private static double[] offset(double[] pose, int joint) {
        return new double[]{pose[of(joint, OUT)], pose[of(joint, RAISE)], pose[of(joint, AHEAD)]};
    }

    /**
     * A hinge's turn: {@code degrees} about an axis lying across the part,
     * {@code facing} degrees round from the part's own left.
     */
    static Rotation hinge(double facing, double degrees) {
        if (degrees == 0) {
            return Rotation.NONE;
        }
        double half = Math.toRadians(degrees) / 2;
        double sin = Math.sin(half);
        double around = Math.toRadians(facing);
        return new Rotation((float) (Math.cos(around) * sin), 0f, (float) (Math.sin(around) * sin),
                (float) Math.cos(half));
    }

    /** Part of the way from one turn to another, the short way round. */
    static Rotation slerp(Rotation from, Rotation to, double share) {
        if (share <= 0) {
            return from;
        }
        if (share >= 1) {
            return to;
        }
        double dot = from.x() * to.x() + from.y() * to.y() + from.z() * to.z() + from.w() * to.w();
        double sign = dot < 0 ? -1 : 1;
        dot = Math.abs(dot);
        double a;
        double b;
        if (dot > 0.9995) {
            a = 1 - share;
            b = share;
        } else {
            double angle = Math.acos(dot);
            double sin = Math.sin(angle);
            a = Math.sin((1 - share) * angle) / sin;
            b = Math.sin(share * angle) / sin;
        }
        double x = a * from.x() + b * sign * to.x();
        double y = a * from.y() + b * sign * to.y();
        double z = a * from.z() + b * sign * to.z();
        double w = a * from.w() + b * sign * to.w();
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        return new Rotation((float) (x / length), (float) (y / length), (float) (z / length), (float) (w / length));
    }

    /** The turn that carries this rig's left, up and forward onto three other directions. */
    static Rotation basis(double[] left, double[] up, double[] front) {
        double m00 = left[0], m01 = up[0], m02 = front[0];
        double m10 = left[1], m11 = up[1], m12 = front[1];
        double m20 = left[2], m21 = up[2], m22 = front[2];
        double trace = m00 + m11 + m22;
        double x;
        double y;
        double z;
        double w;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1) * 2;
            w = 0.25 * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1 + m00 - m11 - m22) * 2;
            w = (m21 - m12) / s;
            x = 0.25 * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1 + m11 - m00 - m22) * 2;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25 * s;
            z = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1 + m22 - m00 - m11) * 2;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25 * s;
        }
        return new Rotation((float) x, (float) y, (float) z, (float) w);
    }

    /**
     * One part, placed.
     *
     * @param bend the turn of the half beyond its hinge, in the part's own
     *             axes; {@link Rotation#NONE} when it is straight
     */
    public record Placed(double x, double y, double z, Rotation rotation, double size, Rotation bend) {

        public Placed(double x, double y, double z, Rotation rotation, double size) {
            this(x, y, z, rotation, size, Rotation.NONE);
        }
    }

    /**
     * A turn of the hips, the chest or the head.
     *
     * <p>Pitched first, rolled second and turned last, so a body that is
     * flipped over and turned is a body lying down and pointing somewhere else,
     * which is what anybody writing both means.
     */
    static Rotation centred(double pitch, double yaw, double roll) {
        return Rotation.around(Rotation.Axis.X, Math.toRadians(pitch))
                .then(Rotation.around(Rotation.Axis.Z, Math.toRadians(roll)))
                .then(Rotation.around(Rotation.Axis.Y, Math.toRadians(yaw)));
    }

    static Rotation centred(double[] pose, int joint) {
        return centred(pose[of(joint, PITCH)], pose[of(joint, YAW)], pose[of(joint, ROLL)]);
    }

    /**
     * A turn of an arm or a leg, written the same way on either side.
     *
     * <p>Pitch swings it forwards and up, roll raises it away from the body and
     * yaw swings a raised limb outwards. Mirrored here rather than in the file,
     * so {@code arms=0,0,90} is both arms out and not one arm out and the other
     * through the chest.
     */
    private static Rotation limb(double[] pose, int joint, double side) {
        return Rotation.around(Rotation.Axis.X, -Math.toRadians(pose[of(joint, PITCH)]))
                .then(Rotation.around(Rotation.Axis.Z, side * Math.toRadians(pose[of(joint, ROLL)])))
                .then(Rotation.around(Rotation.Axis.Y, side * Math.toRadians(pose[of(joint, YAW)])));
    }

    /** How far a joint has been pulled away from where it belongs, in its parent's axes. */
    private static double[] moved(double[] pose, int joint, double side) {
        return new double[]{side * pose[of(joint, OUT)], pose[of(joint, RAISE)], pose[of(joint, AHEAD)]};
    }

    private static double[] plus(double[] one, double[] other) {
        return new double[]{one[0] + other[0], one[1] + other[1], one[2] + other[2]};
    }

    private static double[] minus(double[] one, double[] other) {
        return new double[]{one[0] - other[0], one[1] - other[1], one[2] - other[2]};
    }

    private static double[] scaled(double[] vector, double by) {
        return new double[]{vector[0] * by, vector[1] * by, vector[2] * by};
    }

    private static double dot(double[] one, double[] other) {
        return one[0] * other[0] + one[1] * other[1] + one[2] * other[2];
    }

    private static double[] cross(double[] one, double[] other) {
        return new double[]{
                one[1] * other[2] - one[2] * other[1],
                one[2] * other[0] - one[0] * other[2],
                one[0] * other[1] - one[1] * other[0]};
    }

    private static double[] unit(double[] vector) {
        double length = Math.sqrt(dot(vector, vector));
        return length < 1e-12 ? vector : scaled(vector, 1 / length);
    }

    private static double[] turn(Rotation rotation, double[] vector) {
        float[] turned = rotation.apply(new float[]{(float) vector[0], (float) vector[1], (float) vector[2]});
        return new double[]{turned[0], turned[1], turned[2]};
    }
}
