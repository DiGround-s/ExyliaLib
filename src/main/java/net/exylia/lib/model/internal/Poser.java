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
 * pose, so a pose allocates nothing.
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

    public Poser(Rig rig) {
        this.rig = rig;
        this.world = new double[rig.bones().size()][FIELDS];
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
            double[] local = new double[FIELDS];
            local[TX] = tx;
            local[TY] = ty;
            local[TZ] = tz;
            euler(Math.toRadians(rx), Math.toRadians(ry), Math.toRadians(rz), local);
            local[SX] = sx;
            local[SY] = sy;
            local[SZ] = sz;
            if (bone.parent() < 0) {
                System.arraycopy(local, 0, world[index], 0, FIELDS);
            } else {
                compose(world[bone.parent()], local, world[index]);
            }
        }

        // The model turned half round, then by the carrier's yaw: a yaw of 0 faces south.
        double turn = Math.toRadians(-yaw) + Math.PI;
        double half = turn / 2;
        double ex = 0;
        double ey = Math.sin(half);
        double ez = 0;
        double ew = Math.cos(half);
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
            double[] seen = rotate(ex, ey, ez, ew, wx * toBlocks, wy * toBlocks, wz * toBlocks);
            target[TX] = seen[0];
            target[TY] = seen[1];
            target[TZ] = seen[2];
            multiply(ex, ey, ez, ew, placed[QX], placed[QY], placed[QZ], placed[QW], target);
            target[SX] = Math.abs(part.to()[0] - part.from()[0]) * placed[SX] * toBlocks;
            target[SY] = Math.abs(part.to()[1] - part.from()[1]) * placed[SY] * toBlocks;
            target[SZ] = Math.abs(part.to()[2] - part.from()[2]) * placed[SZ] * toBlocks;
        }
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
