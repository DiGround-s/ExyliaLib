package net.exylia.lib.replay.internal;

/**
 * Where the behind and orbit cameras of a playback sit, as plain numbers: kept
 * apart from the packets so the geometry can be checked without a server.
 *
 * <p>Every place is {@code {x, y, z, yaw, pitch}}, in Minecraft's angles: yaw
 * zero looks south (+Z) and grows clockwise seen from above, pitch is positive
 * looking down.
 *
 * <p>ponytail: neither camera looks for walls, so in a tunnel it can sit inside
 * the rock; a ray march towards the body would fix it if staff ever mind.
 */
final class CameraMath {

    /** Where a player's eyes are above their feet, which is what both cameras look at. */
    static final double EYE = 1.62;

    static final double BEHIND_DISTANCE = 4.0;
    static final double BEHIND_HEIGHT = 1.2;

    /**
     * How much of the way to the body's facing the behind camera turns each
     * tick: a body flicks its look about in a fight, and a camera that turned
     * with every flick would be unwatchable.
     */
    static final double BEHIND_EASE = 0.2;

    static final double ORBIT_RADIUS = 5.0;
    static final double ORBIT_HEIGHT = 1.5;

    /** Degrees the orbit moves per tick: a full turn in twelve seconds. */
    static final double ORBIT_STEP = 1.5;

    private CameraMath() {
    }

    /** Behind a body facing {@code yaw}, a little above its eyes, looking at them. */
    static double[] behind(double x, double y, double z, double yaw) {
        double radians = Math.toRadians(yaw);
        return look(x + Math.sin(radians) * BEHIND_DISTANCE, y + EYE + BEHIND_HEIGHT,
                z - Math.cos(radians) * BEHIND_DISTANCE, x, y + EYE, z);
    }

    /** On the circle around a body, {@code angle} degrees round it, looking at its eyes. */
    static double[] orbit(double x, double y, double z, double angle) {
        double radians = Math.toRadians(angle);
        return look(x + Math.cos(radians) * ORBIT_RADIUS, y + EYE + ORBIT_HEIGHT,
                z + Math.sin(radians) * ORBIT_RADIUS, x, y + EYE, z);
    }

    /** A camera at one point looking at another. */
    static double[] look(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
        return new double[]{fromX, fromY, fromZ, yaw, pitch};
    }

    /** Part of the way from one angle to another, the short way round. */
    static double ease(double from, double to, double amount) {
        double difference = ((to - from) % 360 + 540) % 360 - 180;
        return from + difference * amount;
    }
}
