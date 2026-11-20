package net.exylia.lib.clientmob.internal;

/** The pure maths of walking and facing, kept apart so it can be tested without a server. */
final class MobMath {

    private MobMath() {
    }

    /** Minecraft yaw (0 faces +Z, 90 faces -X) of a horizontal direction. */
    static float yaw(double dx, double dz) {
        return (float) wrap(Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** Minecraft pitch (negative looks up) of a direction. */
    static float pitch(double dx, double dy, double dz) {
        return (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
    }

    /** An angle in [-180, 180). */
    static double wrap(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    /** Where a straight walk stands after {@code distance} more blocks, and whether it got there. */
    record Step(double x, double y, double z, boolean arrived) {
    }

    static Step step(double x, double y, double z, double tx, double ty, double tz, double distance) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        double left = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (left <= distance || left < 1.0E-6) {
            return new Step(tx, ty, tz, true);
        }
        double f = distance / left;
        return new Step(x + dx * f, y + dy * f, z + dz * f, false);
    }
}
