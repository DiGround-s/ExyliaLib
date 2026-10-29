package net.exylia.lib.guide.internal;

/**
 * The few sums guidance places things with, kept apart from any world so
 * they are tested as numbers.
 */
final class GuideGeometry {

    /**
     * How far up and down a companion text follows the view, in degrees.
     *
     * <p>Not all the way: a player looking at their feet to place a block, or
     * at the sky, should not find the panel pasted over what they are looking
     * at. Past these it stays at the edge of the view.
     */
    static final double COMPANION_PITCH_UP = -25;
    static final double COMPANION_PITCH_DOWN = 30;

    private GuideGeometry() {
    }

    /**
     * Where a companion text floats.
     *
     * @param eyeX  the eye's x
     * @param eyeY  the eye's y
     * @param eyeZ  the eye's z
     * @param yaw   the view's yaw, Minecraft degrees
     * @param pitch the view's pitch, Minecraft degrees (positive looks down)
     * @param ahead how far along the view
     * @param drop  how far below the line of sight
     * @return {@code x, y, z}
     */
    static double[] ahead(double eyeX, double eyeY, double eyeZ, double yaw, double pitch,
                          double ahead, double drop) {
        double clamped = Math.clamp(pitch, COMPANION_PITCH_UP, COMPANION_PITCH_DOWN);
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(clamped);
        double horizontal = Math.cos(pitchRad);
        return new double[]{
                eyeX - Math.sin(yawRad) * horizontal * ahead,
                eyeY - Math.sin(pitchRad) * ahead - drop,
                eyeZ + Math.cos(yawRad) * horizontal * ahead};
    }

    /**
     * Where a beacon's stand-in stands when its target is too far to draw.
     *
     * <p>At eye height, {@code distance} blocks from the eye towards the target
     * on the horizontal plane: a marker at the target's own height thirty
     * blocks away would be underground or in the sky whenever the target is.
     * A target nearer than {@code distance} gets the target itself.
     *
     * @return {@code x, y, z}
     */
    static double[] marker(double eyeX, double eyeY, double eyeZ, double targetX, double targetY,
                           double targetZ, double distance) {
        double dx = targetX - eyeX;
        double dz = targetZ - eyeZ;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat <= distance || flat < 1e-6) {
            return new double[]{targetX, targetY, targetZ};
        }
        double scale = distance / flat;
        return new double[]{eyeX + dx * scale, eyeY, eyeZ + dz * scale};
    }

    /**
     * How large an anchored text is drawn at a distance, so it stays readable.
     *
     * <p>Full size up to eight blocks away, then growing in proportion, which
     * keeps it the same size on screen, up to {@code max} times.
     */
    static float scaleFor(double distance, float base, float max) {
        return (float) (base * Math.clamp(distance / 8.0, 1.0, max));
    }

    /** The automatic cut-over from a beam to a stand-in, from the view distance in chunks. */
    static double farDistance(int viewChunks) {
        return Math.max(32, viewChunks * 16 - 24);
    }

    /** The yaw that points from one place to another, Minecraft degrees. */
    static float yawTowards(double fromX, double fromZ, double toX, double toZ) {
        return (float) Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
    }

    /** The shortest turn between two yaws, in degrees. */
    static double turn(double from, double to) {
        return Math.abs(((to - from) % 360 + 540) % 360 - 180);
    }

    /**
     * Whether a move inside one world went far enough for the client to drop
     * what guidance it held: more than half the view distance across
     * (never under 32 blocks), which unloads the chunks behind the player.
     */
    // ponytail: a half-view threshold, not per-piece chunk checks; a redraw costs a few packets
    static boolean isLongTeleport(double fromX, double fromZ, double toX, double toZ, int viewChunks) {
        double limit = Math.max(32, viewChunks * 8);
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return dx * dx + dz * dz > limit * limit;
    }

    /** Half the view's height one block ahead, at the default 70 degree field of view. */
    static final double VIEW_HALF_HEIGHT = Math.tan(Math.toRadians(35));

    /**
     * Where a view text sits, in the axes of a {@code CENTER} billboard seated on the player.
     *
     * <p>The seat is {@code seatAboveEye} blocks straight above the eyes (a
     * player's height minus their eye height, about 0.2 in every pose), and the
     * billboard turns the translation into the view's own axes. World up in
     * those axes is {@code (0, cos pitch, sin pitch)}, so taking it back off
     * puts the panel where it would be if it were seated on the eyes.
     *
     * @param screenY      where the bottom edge sits, as a share of the screen from the bottom
     * @param pitch        the view's pitch, degrees, positive looking down
     * @param seatAboveEye how far the seat is above the eyes
     * @param distance     how far ahead of the eyes
     * @return the translation {@code x, y, z}
     */
    static float[] viewTranslation(double screenY, double pitch, double seatAboveEye, double distance) {
        double up = (screenY * 2 - 1) * VIEW_HALF_HEIGHT * distance;
        double radians = Math.toRadians(pitch);
        return new float[]{0f, (float) (up - seatAboveEye * Math.cos(radians)),
                (float) (-distance - seatAboveEye * Math.sin(radians))};
    }
}
