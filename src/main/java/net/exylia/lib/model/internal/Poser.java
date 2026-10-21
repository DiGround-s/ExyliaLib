package net.exylia.lib.model.internal;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Where every cube of a model is, for one set of bone offsets: the whole
 * hierarchy walked once, pivots and all, and handed over in the game's axes.
 *
 * <h2>The conventions, in one place</h2>
 * Blockbench keeps a model in pixels with its front towards north, turns each
 * group and cube about its pivot in the order X, then Y, then Z, and animates in
 * the Bedrock convention: a keyed rotation turns X and Y the other way round,
 * and a keyed position moves X the other way. A child sits at its pivot minus
 * its parent's pivot, so a bone that turns carries everything below it about
 * its own pivot. That is all reproduced here, and nowhere else.
 *
 * <p>The game's side: the model is turned half round so its front faces the way
 * the carrier walks, turned again by the carrier's yaw and scaled from pixels to
 * blocks, about the carrier's feet.
 *
 * <p>Pure: numbers in, numbers out. One instance per live model, reused every
 * pose, on one thread.
 */
@ApiStatus.Internal
public final class Poser {

    /** What one cube's pose is made of, as {@link #pose} writes it. */
    public static final int TX = 0;
    public static final int TY = 1;
    public static final int TZ = 2;
    public static final int QX = 3;
    public static final int QY = 4;
    public static final int QZ = 5;
    public static final int QW = 6;
    public static final int SX = 7;
    public static final int SY = 8;
    public static final int SZ = 9;
    public static final int FIELDS = 10;

    private final Rig rig;
    /** Per bone: world translation (3), rotation (4), scale (3), in the model's own pixels. */
    private final double[][] world;
    /** Per bone: the same in its parent's space, as the last {@link #bones} worked it out. */
    private final double[][] locals;
    private final boolean[] dirty;
    /** The whole body's lift and tilt, for one {@link #bones}. */
    private final double[] body = new double[FIELDS];

    public Poser(Rig rig) {
        this.rig = rig;
        this.world = new double[rig.bones().size()][FIELDS];
        this.locals = new double[rig.bones().size()][FIELDS];
        this.dirty = new boolean[rig.bones().size()];
    }

    public Rig rig() {
        return rig;
    }

    /**
     * Every cube's pose.
     *
     * @param offsets     per bone, the nine channels an animation gives it
     *                    ({@link Clip} order); {@code null} rows are the rest pose
     * @param head        the bone that looks, or {@code -1}
     * @param headYaw     how far that bone turns beyond the body, in degrees, the
     *                    game's way (positive turns right)
     * @param headPitch   how far it looks down, in degrees
     * @param scale       how big the model is drawn
     * @param yaw         which way the carrier's body faces, the game's degrees
     * @param out         per cube, {@link #FIELDS} numbers: the centre in blocks
     *                    from the feet, the turn as a quaternion and the size in blocks
     */
    public void pose(double[][] offsets, int head, double headYaw, double headPitch, double scale, double yaw,
                     double[][] out) {
        bones(offsets, head, headYaw, headPitch, 0, 0, 0);
        parts(scale, yaw, ZERO, out);
    }

    /**
     * Every bone's place in the model's own space, pixels, front to the north:
     * the first half of {@link #pose}. {@link #world} reads the result.
     *
     * @param lift  how far the whole body is moved up, in pixels
     * @param pitch how far the whole body tips its front up, in degrees, about its feet
     * @param roll  how far the whole body tips its right side (+X) up, in degrees
     */
    public void bones(double[][] offsets, int head, double headYaw, double headPitch, double lift, double pitch,
                      double roll) {
        boolean adjusted = lift != 0 || pitch != 0 || roll != 0;
        if (adjusted) {
            body[TX] = 0;
            body[TY] = lift;
            body[TZ] = 0;
            euler(Math.toRadians(pitch), 0, Math.toRadians(roll), body);
            body[SX] = 1;
            body[SY] = 1;
            body[SZ] = 1;
        }
        List<Rig.Bone> bones = rig.bones();
        for (int index = 0; index < bones.size(); index++) {
            Rig.Bone bone = bones.get(index);
            double[] offset = offsets == null ? null : offsets[index];
            double[] parentOrigin = bone.parent() < 0 ? ZERO : bones.get(bone.parent()).origin();
            double tx = bone.origin()[0] - parentOrigin[0];
            double ty = bone.origin()[1] - parentOrigin[1];
            double tz = bone.origin()[2] - parentOrigin[2];
            double rx = bone.rotation()[0];
            double ry = bone.rotation()[1];
            double rz = bone.rotation()[2];
            double sx = 1;
            double sy = 1;
            double sz = 1;
            if (offset != null) {
                rx -= offset[Clip.ROT_X];
                ry -= offset[Clip.ROT_Y];
                rz += offset[Clip.ROT_Z];
                tx -= offset[Clip.POS_X];
                ty += offset[Clip.POS_Y];
                tz += offset[Clip.POS_Z];
                sx = offset[Clip.SCALE_X];
                sy = offset[Clip.SCALE_Y];
                sz = offset[Clip.SCALE_Z];
            }
            if (index == head) {
                // The front is north, so a turn to the right is a turn about Y the
                // other way, and looking down tips the face towards -Y.
                ry -= headYaw;
                rx -= headPitch;
            }
            double[] local = locals[index];
            local[TX] = tx;
            local[TY] = ty;
            local[TZ] = tz;
            euler(Math.toRadians(rx), Math.toRadians(ry), Math.toRadians(rz), local);
            local[SX] = sx;
            local[SY] = sy;
            local[SZ] = sz;
            if (bone.parent() >= 0) {
                compose(world[bone.parent()], local, world[index]);
            } else if (adjusted) {
                compose(body, local, world[index]);
            } else {
                System.arraycopy(local, 0, world[index], 0, FIELDS);
            }
        }
    }

    /** One bone's place after {@link #bones}: translation, rotation and scale, in pixels. Live; not a copy. */
    public double[] world(int bone) {
        return world[bone];
    }

    /**
     * Places every bone below one whose world was set by hand again, from their
     * own local transforms: what a leg's bones hang off moved, so they move too.
     *
     * @param fixed bones whose world is already what it should be, and that are not recomputed
     */
    public void reworld(boolean[] fixed) {
        List<Rig.Bone> bones = rig.bones();
        boolean[] moved = dirty;
        for (int index = 0; index < bones.size(); index++) {
            int parent = bones.get(index).parent();
            moved[index] = fixed[index];
            if (!fixed[index] && parent >= 0 && moved[parent]) {
                compose(world[parent], locals[index], world[index]);
                moved[index] = true;
            }
        }
    }

    /**
     * Every cube's pose from the bones as {@link #bones} and any hand placing
     * left them: the second half of {@link #pose}.
     *
     * @param offset where the whole model is moved, in blocks, in its own axes before it turns
     */
    public void parts(double scale, double yaw, double[] offset, double[][] out) {
        parts(scale, yaw, 0, offset, out);
    }

    /**
     * {@link #parts(double, double, double[], double[][])}, tilted first about
     * the model's own right axis: a climber standing up a wall.
     *
     * @param tilt degrees; {@code 90} turns its front straight up and its belly to what is in front of it
     */
    public void parts(double scale, double yaw, double tilt, double[] offset, double[][] out) {
        double[] facing = facing(yaw, tilt);
        double ex = facing[0];
        double ey = facing[1];
        double ez = facing[2];
        double ew = facing[3];
        double toBlocks = scale / 16.0;

        List<Rig.Part> parts = rig.parts();
        double[] cube = new double[FIELDS];
        double[] placed = new double[FIELDS];
        for (int index = 0; index < parts.size(); index++) {
            Rig.Part part = parts.get(index);
            double[] bone = world[part.bone()];
            double[] boneOrigin = rig.bones().get(part.bone()).origin();
            cube[TX] = part.origin()[0] - boneOrigin[0];
            cube[TY] = part.origin()[1] - boneOrigin[1];
            cube[TZ] = part.origin()[2] - boneOrigin[2];
            euler(Math.toRadians(part.rotation()[0]), Math.toRadians(part.rotation()[1]),
                    Math.toRadians(part.rotation()[2]), cube);
            cube[SX] = 1;
            cube[SY] = 1;
            cube[SZ] = 1;
            compose(bone, cube, placed);

            double cx = (part.from()[0] + part.to()[0]) / 2 - part.origin()[0];
            double cy = (part.from()[1] + part.to()[1]) / 2 - part.origin()[1];
            double cz = (part.from()[2] + part.to()[2]) / 2 - part.origin()[2];
            double[] centre = rotate(placed, cx * placed[SX], cy * placed[SY], cz * placed[SZ]);
            double wx = placed[TX] + centre[0];
            double wy = placed[TY] + centre[1];
            double wz = placed[TZ] + centre[2];

            double[] target = out[index];
            double[] seen = rotate(ex, ey, ez, ew, wx * toBlocks + offset[0], wy * toBlocks + offset[1],
                    wz * toBlocks + offset[2]);
            target[TX] = seen[0];
            target[TY] = seen[1];
            target[TZ] = seen[2];
            multiply(ex, ey, ez, ew, placed[QX], placed[QY], placed[QZ], placed[QW], target);
            target[SX] = Math.abs(part.to()[0] - part.from()[0]) * placed[SX] * toBlocks;
            target[SY] = Math.abs(part.to()[1] - part.from()[1]) * placed[SY] * toBlocks;
            target[SZ] = Math.abs(part.to()[2] - part.from()[2]) * placed[SZ] * toBlocks;
        }
    }

    /**
     * The lowest corner of a bone's own cubes in the rest pose, in the bone's own
     * space: the point a leg stands on. Its pivot when it has no cubes.
     *
     * @param bone which
     * @return x, y, z in pixels, from the bone's pivot, unturned by the bone
     */
    public double[] sole(int bone) {
        bones(null, -1, 0, 0, 0, 0, 0);
        double[] at = world[bone];
        double bestY = Double.MAX_VALUE;
        double[] best = {at[TX], at[TY], at[TZ]};
        int count = 0;
        double[] cube = new double[FIELDS];
        double[] placed = new double[FIELDS];
        for (Rig.Part part : rig.parts()) {
            if (part.bone() != bone) {
                continue;
            }
            double[] boneOrigin = rig.bones().get(bone).origin();
            cube[TX] = part.origin()[0] - boneOrigin[0];
            cube[TY] = part.origin()[1] - boneOrigin[1];
            cube[TZ] = part.origin()[2] - boneOrigin[2];
            euler(Math.toRadians(part.rotation()[0]), Math.toRadians(part.rotation()[1]),
                    Math.toRadians(part.rotation()[2]), cube);
            cube[SX] = 1;
            cube[SY] = 1;
            cube[SZ] = 1;
            compose(at, cube, placed);
            for (int corner = 0; corner < 8; corner++) {
                double cx = ((corner & 1) == 0 ? part.from()[0] : part.to()[0]) - part.origin()[0];
                double cy = ((corner & 2) == 0 ? part.from()[1] : part.to()[1]) - part.origin()[1];
                double cz = ((corner & 4) == 0 ? part.from()[2] : part.to()[2]) - part.origin()[2];
                double[] point = rotate(placed, cx, cy, cz);
                double y = placed[TY] + point[1];
                // The middle of the lowest face, not one of its corners: a foot stands on its sole.
                if (y < bestY - 1e-6) {
                    bestY = y;
                    best = new double[]{placed[TX] + point[0], y, placed[TZ] + point[2]};
                    count = 1;
                } else if (Math.abs(y - bestY) <= 1e-6) {
                    best[0] = (best[0] * count + placed[TX] + point[0]) / (count + 1);
                    best[2] = (best[2] * count + placed[TZ] + point[2]) / (count + 1);
                    count++;
                }
            }
        }
        // Into the bone's own space.
        double[] inverse = {-at[QX], -at[QY], -at[QZ], at[QW]};
        return rotate(inverse[0], inverse[1], inverse[2], inverse[3], best[0] - at[TX], best[1] - at[TY],
                best[2] - at[TZ]);
    }

    private static final double[] ZERO = new double[3];

    /**
     * A child's place in its parent's space, moved into the world's.
     *
     * <p>Scales multiply axis by axis, which is exact whenever a scaled bone
     * does not also turn a child by something other than right angles; Blockbench
     * shears in that case and a display entity cannot.
     */
    static void compose(double[] parent, double[] child, double[] out) {
        double[] moved = rotate(parent, child[TX] * parent[SX], child[TY] * parent[SY], child[TZ] * parent[SZ]);
        double tx = parent[TX] + moved[0];
        double ty = parent[TY] + moved[1];
        double tz = parent[TZ] + moved[2];
        double sx = parent[SX] * child[SX];
        double sy = parent[SY] * child[SY];
        double sz = parent[SZ] * child[SZ];
        multiply(parent[QX], parent[QY], parent[QZ], parent[QW], child[QX], child[QY], child[QZ], child[QW], out);
        out[TX] = tx;
        out[TY] = ty;
        out[TZ] = tz;
        out[SX] = sx;
        out[SY] = sy;
        out[SZ] = sz;
    }

    /**
     * The turn the whole model is drawn with: tilted about its own right axis,
     * then turned half round and by the carrier's yaw, so a yaw of 0 faces south.
     *
     * @return x, y, z, w
     */
    static double[] facing(double yaw, double tilt) {
        double half = (Math.toRadians(-yaw) + Math.PI) / 2;
        double lean = Math.toRadians(tilt) / 2;
        double[] turned = new double[FIELDS];
        multiply(0, Math.sin(half), 0, Math.cos(half), Math.sin(lean), 0, 0, Math.cos(lean), turned);
        return new double[]{turned[QX], turned[QY], turned[QZ], turned[QW]};
    }

    /** X first, then Y, then Z, about the fixed axes: Blockbench's (three.js's {@code ZYX}) order. */
    static void euler(double x, double y, double z, double[] out) {
        double cx = Math.cos(x / 2);
        double sx = Math.sin(x / 2);
        double cy = Math.cos(y / 2);
        double sy = Math.sin(y / 2);
        double cz = Math.cos(z / 2);
        double sz = Math.sin(z / 2);
        // qz * qy * qx
        out[QX] = sx * cy * cz - cx * sy * sz;
        out[QY] = cx * sy * cz + sx * cy * sz;
        out[QZ] = cx * cy * sz - sx * sy * cz;
        out[QW] = cx * cy * cz + sx * sy * sz;
    }

    /** {@code a * b} into {@code out}'s rotation fields. */
    static void multiply(double ax, double ay, double az, double aw, double bx, double by, double bz, double bw,
                         double[] out) {
        out[QX] = aw * bx + ax * bw + ay * bz - az * by;
        out[QY] = aw * by - ax * bz + ay * bw + az * bx;
        out[QZ] = aw * bz + ax * by - ay * bx + az * bw;
        out[QW] = aw * bw - ax * bx - ay * by - az * bz;
    }

    private static double[] rotate(double[] pose, double x, double y, double z) {
        return rotate(pose[QX], pose[QY], pose[QZ], pose[QW], x, y, z);
    }

    static double[] rotate(double qx, double qy, double qz, double qw, double x, double y, double z) {
        double cx = qy * z - qz * y;
        double cy = qz * x - qx * z;
        double cz = qx * y - qy * x;
        double ccx = qy * cz - qz * cy;
        double ccy = qz * cx - qx * cz;
        double ccz = qx * cy - qy * cx;
        return new double[]{x + 2 * (qw * cx + ccx), y + 2 * (qw * cy + ccy), z + 2 * (qw * cz + ccz)};
    }
}
