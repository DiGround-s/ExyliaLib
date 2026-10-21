package net.exylia.lib.model.internal;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Feet that stand on the ground they are over, and stay where they were put
 * down.
 *
 * <h2>Which bones are legs</h2>
 * By name: a bone whose name holds {@code leg}, {@code thigh}, {@code shin},
 * {@code calf}, {@code knee}, {@code foot}, {@code paw}, {@code hoof},
 * {@code toe} or {@code ankle} as a word ({@code leg_left}, {@code left.foot},
 * {@code leg_right_0_low}). The end of a leg is such a bone with no such bone
 * below it, and the leg is that end and the leg bones it hangs off:
 * <ul>
 *   <li><b>Upper, lower, foot</b> &mdash; the end is a foot, a paw, a hoof, a
 *       toe or an ankle and hangs off two leg bones. The hip and knee bend so the
 *       ankle reaches its spot; the foot keeps the turn its animation gave it.</li>
 *   <li><b>Upper, lower</b> &mdash; the end hangs off one leg bone. The hip and
 *       knee bend so the lowest point of the lower bone reaches its spot.</li>
 *   <li><b>One bone</b> &mdash; nothing bends. The leg still tells the body how
 *       far to sink and tilt.</li>
 * </ul>
 *
 * <h2>What it does each pose</h2>
 * Every leg's sole is found where its animation put it, and the ground under
 * it is read. The body sinks to the lowest of them and tilts towards them, front
 * to back and side to side, and every bending leg reaches down or up to its own
 * ground, keeping the lift its animation gave it. While the carrier walks, a
 * sole that is down stays on the spot it was put down on until the animation
 * lifts it or the body has gone too far, so a foot does not skate.
 *
 * <p>Pure: the ground is asked through {@link Ground}, so all of it can be
 * asserted without a world. One per live model, on its thread.
 */
@ApiStatus.Internal
public final class Legs {

    private static final Set<String> LEG_WORDS = Set.of("leg", "legs", "thigh", "shin", "calf", "knee", "foot",
            "feet", "paw", "hoof", "toe", "ankle");
    private static final Set<String> FOOT_WORDS = Set.of("foot", "feet", "paw", "hoof", "toe", "ankle");

    /** How high above its rest a sole may be and still count as put down, in pixels. */
    static final double DOWN_PX = 0.6;
    /** The share of a leg's length the body may move from a planted sole before it lets go. */
    static final double STRIDE = 0.6;
    /** The steepest the body tilts, front to back and side to side, in degrees. */
    static final double MAX_PITCH = 22;
    static final double MAX_ROLL = 15;
    /** How much of the way to the new ground a pose goes: smooths a step onto a slab. */
    static final double FOLLOW = 0.35;
    /** Below this speed, in blocks a second, nothing is planted: a standing animation keeps its feet. */
    static final double PLANT_SPEED = 0.3;

    /** The height of the ground under a point, or {@code NaN} where it cannot be read. */
    @FunctionalInterface
    public interface Ground {
        /**
         * @param x     east, in blocks
         * @param z     south, in blocks
         * @param y     the carrier's feet
         * @param reach how far above and below them to look
         * @return the top of the highest solid surface within reach, in blocks
         */
        double top(double x, double z, double y, double reach);
    }

    /**
     * One leg.
     *
     * @param end   its lowest bone
     * @param knee  the bone the end hangs off, or {@code -1}
     * @param hip   the bone that hangs off, or {@code -1}
     * @param foot  whether the end is a foot that keeps its own turn, the knee and hip above it bending
     * @param sole  the lowest point of the end's cubes, in its own space
     * @param restY the sole's height in the rest pose, in pixels
     * @param restX the sole's place across, in the rest pose, in pixels; +X is the model's right
     * @param restZ the sole's place along, in the rest pose, in pixels; -Z is its front
     * @param length the leg's length from its top joint to its sole, in pixels
     */
    record Limb(int end, int knee, int hip, boolean foot, double[] sole, double restY, double restX, double restZ,
                double length) {

        boolean bends() {
            return foot ? hip >= 0 && knee >= 0 : knee >= 0;
        }
    }

    private final List<Limb> limbs;
    private final double[] planted;
    private final boolean[] locked;
    private final double[] drops;
    private final double[] soleA;
    private final double[] effectorA;
    private final double[] footTurn;
    private final boolean[] fixed;
    private final double front;
    private final double side;
    private double weight;

    private Legs(List<Limb> limbs, int bones) {
        this.limbs = limbs;
        this.planted = new double[limbs.size() * 2];
        this.locked = new boolean[limbs.size()];
        this.drops = new double[limbs.size()];
        this.soleA = new double[limbs.size() * 3];
        this.effectorA = new double[limbs.size() * 3];
        this.footTurn = new double[limbs.size() * 4];
        this.fixed = new boolean[bones];
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        for (Limb limb : limbs) {
            minZ = Math.min(minZ, limb.restZ());
            maxZ = Math.max(maxZ, limb.restZ());
            minX = Math.min(minX, limb.restX());
            maxX = Math.max(maxX, limb.restX());
        }
        this.front = limbs.isEmpty() ? 0 : maxZ - minZ;
        this.side = limbs.isEmpty() ? 0 : maxX - minX;
    }

    /** A model's legs, found by their names; empty for one that has none. */
    public static Legs of(Rig rig, Poser poser) {
        List<Rig.Bone> bones = rig.bones();
        boolean[] leg = new boolean[bones.size()];
        boolean[] hasLegChild = new boolean[bones.size()];
        for (int index = 0; index < bones.size(); index++) {
            leg[index] = isLeg(bones.get(index).name());
        }
        for (int index = 0; index < bones.size(); index++) {
            int parent = bones.get(index).parent();
            if (leg[index] && parent >= 0) {
                hasLegChild[parent] = true;
            }
        }
        List<Limb> limbs = new ArrayList<>();
        for (int index = 0; index < bones.size(); index++) {
            if (!leg[index] || hasLegChild[index]) {
                continue;
            }
            int knee = bones.get(index).parent();
            knee = knee >= 0 && leg[knee] ? knee : -1;
            int hip = knee >= 0 ? bones.get(knee).parent() : -1;
            hip = hip >= 0 && leg[hip] ? hip : -1;
            boolean foot = isFoot(bones.get(index).name()) && hip >= 0;
            double[] sole = poser.sole(index);
            // sole() leaves the rest pose in the poser.
            double[] at = soleOf(poser.world(index), sole);
            // The joint the leg bends from: the hip above a foot, else the bone the end hangs off.
            int top = foot ? hip : knee >= 0 ? knee : index;
            double[] topAt = poser.world(top);
            double length = Math.sqrt(square(at[0] - topAt[Poser.TX]) + square(at[1] - topAt[Poser.TY])
                    + square(at[2] - topAt[Poser.TZ]));
            limbs.add(new Limb(index, knee, foot ? hip : -1, foot, sole, at[1], at[0], at[2], Math.max(1, length)));
        }
        return new Legs(List.copyOf(limbs), bones.size());
    }

    static boolean isLeg(String name) {
        for (String word : name.toLowerCase(Locale.ROOT).split("[._\\- ]")) {
            if (LEG_WORDS.contains(word)) return true;
        }
        return false;
    }

    static boolean isFoot(String name) {
        for (String word : name.toLowerCase(Locale.ROOT).split("[._\\- ]")) {
            if (FOOT_WORDS.contains(word)) return true;
        }
        return false;
    }

    /** Whether it has any legs at all. */
    public boolean isEmpty() {
        return limbs.isEmpty();
    }

    List<Limb> limbs() {
        return limbs;
    }

    /** How much of the adjustment shows, {@code 0-1}: it fades in and out rather than snapping. */
    public double weight() {
        return weight;
    }

    /** Lets go of every planted sole: the model jumped, was teleported or was turned away. */
    public void release() {
        java.util.Arrays.fill(locked, false);
    }

    /**
     * Poses the bones, legs standing on the ground.
     *
     * <p>Runs {@link Poser#bones} twice: once as the animation has it, to see
     * where every sole would be, and once with the body lifted and tilted, after
     * which the bending legs are reached to their spots and everything below them
     * placed again. {@link Poser#parts} comes after.
     *
     * @param poser    the model's poser
     * @param offsets  the animation's offsets, as {@link Poser#bones} takes them
     * @param head     as {@link Poser#bones} takes it
     * @param headYaw  as {@link Poser#bones} takes it
     * @param headPitch as {@link Poser#bones} takes it
     * @param on       whether it should stand on the ground at all: on the ground, near somebody
     * @param moving   how fast the carrier moves, blocks a second
     * @param toBlocks blocks per pixel at its current size
     * @param yaw      which way the carrier faces, the game's degrees
     * @param x        the carrier's feet, east
     * @param y        the carrier's feet, up
     * @param z        the carrier's feet, south
     * @param offset   where the model is moved, in blocks, in its own axes
     * @param ground   how high the ground is
     */
    public void pose(Poser poser, double[][] offsets, int head, double headYaw, double headPitch, boolean on,
                     double moving, double toBlocks, double yaw, double x, double y, double z, double[] offset,
                     Ground ground) {
        weight = on ? Math.min(1, weight + 0.25) : Math.max(0, weight - 0.25);
        if (limbs.isEmpty() || weight <= 0) {
            poser.bones(offsets, head, headYaw, headPitch, 0, 0, 0);
            if (weight <= 0) release();
            return;
        }
        poser.bones(offsets, head, headYaw, headPitch, 0, 0, 0);
        double turn = Math.toRadians(-yaw) + Math.PI;
        double cos = Math.cos(turn);
        double sin = Math.sin(turn);
        boolean plant = on && moving > PLANT_SPEED;
        double lowest = 0;
        double frontSum = 0;
        double backSum = 0;
        int frontCount = 0;
        int backCount = 0;
        double rightSum = 0;
        double leftSum = 0;
        int rightCount = 0;
        int leftCount = 0;
        double middleZ = 0;
        double middleX = 0;
        for (Limb limb : limbs) {
            middleZ += limb.restZ();
            middleX += limb.restX();
        }
        middleZ /= limbs.size();
        middleX /= limbs.size();
        for (int index = 0; index < limbs.size(); index++) {
            Limb limb = limbs.get(index);
            double[] end = poser.world(limb.end());
            double[] sole = soleOf(end, limb.sole());
            System.arraycopy(sole, 0, soleA, index * 3, 3);
            effectorA[index * 3] = limb.foot() ? end[Poser.TX] : sole[0];
            effectorA[index * 3 + 1] = limb.foot() ? end[Poser.TY] : sole[1];
            effectorA[index * 3 + 2] = limb.foot() ? end[Poser.TZ] : sole[2];
            System.arraycopy(end, Poser.QX, footTurn, index * 4, 4);

            // Where the sole is in the world, and the ground under it.
            double lx = sole[0] * toBlocks + offset[0];
            double lz = sole[2] * toBlocks + offset[2];
            double wx = x + cos * lx + sin * lz;
            double wz = z - sin * lx + cos * lz;
            double reach = Math.clamp(limb.length() * toBlocks * 0.6, 0.25, 1.5);
            double top = ground.top(wx, wz, y, reach);
            double wanted = Double.isNaN(top) ? 0 : Math.clamp(top - y, -reach, reach);
            drops[index] += (wanted - drops[index]) * FOLLOW;

            // A sole that is down stays where it was put down while the carrier walks.
            double lifted = sole[1] - limb.restY();
            if (!plant || lifted > DOWN_PX || !limb.bends()) {
                locked[index] = false;
            } else if (!locked[index]) {
                locked[index] = true;
                planted[index * 2] = wx;
                planted[index * 2 + 1] = wz;
            } else {
                double far = Math.hypot(planted[index * 2] - wx, planted[index * 2 + 1] - wz);
                if (far > limb.length() * toBlocks * STRIDE) {
                    planted[index * 2] = wx;
                    planted[index * 2 + 1] = wz;
                }
            }

            double drop = drops[index];
            lowest = Math.min(lowest, drop);
            if (limb.restZ() < middleZ - 1e-6) {
                frontSum += drop;
                frontCount++;
            } else if (limb.restZ() > middleZ + 1e-6) {
                backSum += drop;
                backCount++;
            }
            if (limb.restX() > middleX + 1e-6) {
                rightSum += drop;
                rightCount++;
            } else if (limb.restX() < middleX - 1e-6) {
                leftSum += drop;
                leftCount++;
            }
        }

        double pitch = 0;
        if (frontCount > 0 && backCount > 0 && front > 1e-6) {
            pitch = Math.toDegrees(Math.atan2(frontSum / frontCount - backSum / backCount, front * toBlocks));
            pitch = Math.clamp(pitch, -MAX_PITCH, MAX_PITCH);
        }
        double roll = 0;
        if (rightCount > 0 && leftCount > 0 && side > 1e-6) {
            roll = Math.toDegrees(Math.atan2(rightSum / rightCount - leftSum / leftCount, side * toBlocks));
            roll = Math.clamp(roll, -MAX_ROLL, MAX_ROLL);
        }
        double lift = lowest / toBlocks;
        poser.bones(offsets, head, headYaw, headPitch, lift * weight, pitch * weight, roll * weight);

        java.util.Arrays.fill(fixed, false);
        boolean any = false;
        for (int index = 0; index < limbs.size(); index++) {
            Limb limb = limbs.get(index);
            if (!limb.bends()) {
                continue;
            }
            // Where the sole should be, in the model's own space before the body moved.
            double sx = soleA[index * 3];
            double sy = soleA[index * 3 + 1] + drops[index] / toBlocks;
            double sz = soleA[index * 3 + 2];
            if (locked[index]) {
                double dx = planted[index * 2] - x;
                double dz = planted[index * 2 + 1] - z;
                // The inverse of the turn above, then back to pixels.
                double lx = cos * dx - sin * dz - offset[0];
                double lz = sin * dx + cos * dz - offset[2];
                sx = lx / toBlocks;
                sz = lz / toBlocks;
            }
            double shiftX = sx - soleA[index * 3];
            double shiftY = sy - soleA[index * 3 + 1];
            double shiftZ = sz - soleA[index * 3 + 2];
            double[] wantedAt = {effectorA[index * 3] + shiftX, effectorA[index * 3 + 1] + shiftY,
                    effectorA[index * 3 + 2] + shiftZ};
            // Faded in: the target slides from where the lifted body has the effector now.
            double[] now = effector(poser, limb);
            for (int axis = 0; axis < 3; axis++) {
                wantedAt[axis] = now[axis] + (wantedAt[axis] - now[axis]) * weight;
            }
            if (reach(poser, limb, wantedAt, index)) {
                any = true;
            }
        }
        if (any) {
            poser.reworld(fixed);
        }
    }

    /** Where a leg's effector is now: the foot's pivot, or the lower bone's sole. */
    private static double[] effector(Poser poser, Limb limb) {
        double[] end = poser.world(limb.end());
        return limb.foot() ? new double[]{end[Poser.TX], end[Poser.TY], end[Poser.TZ]} : soleOf(end, limb.sole());
    }

    /** Bends one leg so its effector reaches a point; {@code false} when it cannot be bent. */
    private boolean reach(Poser poser, Limb limb, double[] target, int index) {
        int upperBone = limb.foot() ? limb.hip() : limb.knee();
        int lowerBone = limb.foot() ? limb.knee() : limb.end();
        double[] upper = poser.world(upperBone);
        double[] lower = poser.world(lowerBone);
        double[] a = {upper[Poser.TX], upper[Poser.TY], upper[Poser.TZ]};
        double[] b = {lower[Poser.TX], lower[Poser.TY], lower[Poser.TZ]};
        double[] c = effector(poser, limb);
        Bend bend = bend(a, b, c, target, FORWARD);
        if (bend == null) {
            return false;
        }
        turn(upper, bend.upper());
        double[] lowerTurn = new double[4];
        mul(bend.lower(), bend.upper(), lowerTurn);
        turn(lower, lowerTurn);
        lower[Poser.TX] = bend.knee()[0];
        lower[Poser.TY] = bend.knee()[1];
        lower[Poser.TZ] = bend.knee()[2];
        fixed[upperBone] = true;
        fixed[lowerBone] = true;
        if (limb.foot()) {
            double[] foot = poser.world(limb.end());
            foot[Poser.TX] = bend.end()[0];
            foot[Poser.TY] = bend.end()[1];
            foot[Poser.TZ] = bend.end()[2];
            // A foot keeps the turn its animation gave it, whatever the leg above did.
            System.arraycopy(footTurn, index * 4, foot, Poser.QX, 4);
            fixed[limb.end()] = true;
        }
        return true;
    }

    /** Which way a straight leg's knee goes when it bends: forwards, to the model's front. */
    static final double[] FORWARD = {0, 0, -1};

    /**
     * How a two-bone chain bends to reach a point.
     *
     * @param upper the turn to put on the upper bone, before its own
     * @param lower the turn to put on the lower bone after the upper's, before its own
     * @param knee  where the joint between them ends up
     * @param end   where the effector ends up: the target, or as near as the bones reach
     */
    record Bend(double[] upper, double[] lower, double[] knee, double[] end) {
    }

    /**
     * Two-bone inverse kinematics, solved exactly: the knee stays in the plane
     * the leg already bends in, so a leg that bends forward keeps bending forward.
     *
     * @param a      the top joint
     * @param b      the knee
     * @param c      the effector
     * @param target where the effector should be
     * @param hint   which way a straight leg bends
     * @return the bend, or {@code null} for a chain with a bone of no length
     */
    static Bend bend(double[] a, double[] b, double[] c, double[] target, double[] hint) {
        double l1 = distance(a, b);
        double l2 = distance(b, c);
        if (l1 < 1e-6 || l2 < 1e-6) {
            return null;
        }
        double[] at = sub(target, a);
        double reach = length(at);
        if (reach < 1e-6) {
            return null;
        }
        double[] d = scaled(at, 1 / reach);
        reach = Math.clamp(reach, Math.abs(l1 - l2) + 1e-4, l1 + l2 - 1e-4);

        // The way the knee points, square to the line from the top joint to the target.
        double[] h = sub(b, a);
        h = sub(h, scaled(d, dot(h, d)));
        if (length(h) < 1e-4 * l1) {
            h = sub(hint, scaled(d, dot(hint, d)));
            if (length(h) < 1e-6) {
                h = Math.abs(d[0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
                h = sub(h, scaled(d, dot(h, d)));
            }
        }
        h = scaled(h, 1 / length(h));

        double cos = Math.clamp((l1 * l1 + reach * reach - l2 * l2) / (2 * l1 * reach), -1, 1);
        double sin = Math.sqrt(1 - cos * cos);
        double[] knee = {a[0] + l1 * (cos * d[0] + sin * h[0]), a[1] + l1 * (cos * d[1] + sin * h[1]),
                a[2] + l1 * (cos * d[2] + sin * h[2])};
        double[] end = {a[0] + reach * d[0], a[1] + reach * d[1], a[2] + reach * d[2]};

        double[] upper = between(sub(b, a), sub(knee, a));
        double[] shin = Poser.rotate(upper[0], upper[1], upper[2], upper[3], c[0] - b[0], c[1] - b[1], c[2] - b[2]);
        double[] lower = between(shin, sub(end, knee));
        return new Bend(upper, lower, knee, end);
    }

    /** The shortest turn taking one direction to another, as a quaternion x, y, z, w. */
    static double[] between(double[] from, double[] to) {
        double[] u = scaled(from, 1 / length(from));
        double[] v = scaled(to, 1 / length(to));
        double cos = dot(u, v);
        if (cos < -0.999999) {
            double[] axis = cross(new double[]{1, 0, 0}, u);
            if (length(axis) < 1e-6) {
                axis = cross(new double[]{0, 1, 0}, u);
            }
            axis = scaled(axis, 1 / length(axis));
            return new double[]{axis[0], axis[1], axis[2], 0};
        }
        double[] axis = cross(u, v);
        double w = 1 + cos;
        double norm = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2] + w * w);
        return new double[]{axis[0] / norm, axis[1] / norm, axis[2] / norm, w / norm};
    }

    /** A bone's world turn, turned again by a world turn put before it. */
    private static void turn(double[] bone, double[] by) {
        double[] out = new double[Poser.FIELDS];
        Poser.multiply(by[0], by[1], by[2], by[3], bone[Poser.QX], bone[Poser.QY], bone[Poser.QZ], bone[Poser.QW],
                out);
        System.arraycopy(out, Poser.QX, bone, Poser.QX, 4);
    }

    private static void mul(double[] a, double[] b, double[] out) {
        double[] fields = new double[Poser.FIELDS];
        Poser.multiply(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3], fields);
        System.arraycopy(fields, Poser.QX, out, 0, 4);
    }

    /** A point given in a bone's own space, where it is in the model's. */
    static double[] soleOf(double[] bone, double[] local) {
        double[] turned = Poser.rotate(bone[Poser.QX], bone[Poser.QY], bone[Poser.QZ], bone[Poser.QW],
                local[0] * bone[Poser.SX], local[1] * bone[Poser.SY], local[2] * bone[Poser.SZ]);
        return new double[]{bone[Poser.TX] + turned[0], bone[Poser.TY] + turned[1], bone[Poser.TZ] + turned[2]};
    }

    private static double square(double value) {
        return value * value;
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt(square(a[0] - b[0]) + square(a[1] - b[1]) + square(a[2] - b[2]));
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] scaled(double[] a, double by) {
        return new double[]{a[0] * by, a[1] * by, a[2] * by};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double length(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
