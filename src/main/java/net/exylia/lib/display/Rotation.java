package net.exylia.lib.display;

import org.jetbrains.annotations.NotNull;

/**
 * A rotation, as the quaternion a display entity is turned by.
 *
 * <pre>{@code
 * Rotation spin = Rotation.around(Rotation.Axis.Y, Math.PI);   // half a turn
 * Rotation tilted = Rotation.around(Rotation.Axis.X, 0.4).then(spin);
 * }</pre>
 *
 * <h2>Why a quaternion and not three angles</h2>
 * The client interpolates a display's rotation by taking the shortest arc
 * between two quaternions. Euler angles have no shortest arc &mdash; two ways of
 * writing the same orientation interpolate differently, and one of them goes the
 * long way round. Storing the rotation in the form the protocol carries is what
 * makes an animation land where the file says it lands.
 *
 * <h2>The shortest arc is also the trap</h2>
 * Because the client takes the short way, a keyframe more than half a turn from
 * the last one spins <em>backwards</em>. A full rotation therefore cannot be one
 * keyframe: it has to be several, each less than half a turn from the one
 * before. {@link DisplayMotion} is what works that out; this class only does the
 * maths.
 *
 * <p>Immutable, so a rotation worked out when configuration was read is shared
 * by every play of that effect, on any thread.
 *
 * @param x the imaginary x component
 * @param y the imaginary y component
 * @param z the imaginary z component
 * @param w the real component
 * @since 1.85.0
 */
public record Rotation(float x, float y, float z, float w) {

    /** No rotation at all. */
    public static final Rotation NONE = new Rotation(0f, 0f, 0f, 1f);

    /** The axis a rotation turns around. */
    public enum Axis {

        /** Pitch: tips the model forwards and backwards. */
        X,

        /** Yaw: turns the model on the spot, which is what most spins want. */
        Y,

        /** Roll: rolls the model sideways, which is what a thrown blade does. */
        Z;

        /** Reads an axis from configuration, defaulting to {@link #Y}. */
        public static @NotNull Axis of(@NotNull String name) {
            return switch (name.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "X", "PITCH" -> X;
                case "Z", "ROLL" -> Z;
                default -> Y;
            };
        }
    }

    /**
     * A rotation of {@code radians} around one axis.
     *
     * @param axis    what to turn around
     * @param radians how far, in radians
     * @return the rotation
     */
    public static @NotNull Rotation around(@NotNull Axis axis, double radians) {
        double half = radians / 2.0;
        float sin = (float) Math.sin(half);
        float cos = (float) Math.cos(half);
        return switch (axis) {
            case X -> new Rotation(sin, 0f, 0f, cos);
            case Y -> new Rotation(0f, sin, 0f, cos);
            case Z -> new Rotation(0f, 0f, sin, cos);
        };
    }

    /**
     * This rotation followed by another.
     *
     * <p>Reads in the order it happens: {@code tilt.then(spin)} tips the model
     * and then turns the tipped model, which is what somebody writing
     * {@code tilt:30;spin:2} means.
     *
     * @param next what happens after this
     * @return the combined rotation
     */
    public @NotNull Rotation then(@NotNull Rotation next) {
        // next * this: quaternion composition applies the right-hand side first.
        return new Rotation(
                next.w * x + next.x * w + next.y * z - next.z * y,
                next.w * y - next.x * z + next.y * w + next.z * x,
                next.w * z + next.x * y - next.y * x + next.z * w,
                next.w * w - next.x * x - next.y * y - next.z * z);
    }

    /**
     * This rotation applied to a vector.
     *
     * <p>Needed because a block display is drawn from its corner rather than
     * its centre, so centring one means offsetting it by half its size
     * <em>in the direction it has been turned</em>. Offsetting before the turn
     * would make a spinning block orbit the point instead of turning on it.
     *
     * @param vector the three components to turn
     * @return the turned components, as a new array
     */
    public float @NotNull [] apply(float @NotNull [] vector) {
        if (isNone()) {
            return new float[]{vector[0], vector[1], vector[2]};
        }
        // v + 2w(q x v) + 2(q x (q x v)), the standard unit-quaternion form:
        // no matrix, no trigonometry, and exact for the quaternions this
        // module builds.
        float cx = y * vector[2] - z * vector[1];
        float cy = z * vector[0] - x * vector[2];
        float cz = x * vector[1] - y * vector[0];
        float ccx = y * cz - z * cy;
        float ccy = z * cx - x * cz;
        float ccz = x * cy - y * cx;
        return new float[]{
                vector[0] + 2 * (w * cx + ccx),
                vector[1] + 2 * (w * cy + ccy),
                vector[2] + 2 * (w * cz + ccz)};
    }

    /** Whether this turns the model at all, so a packet can leave it out. */
    public boolean isNone() {
        return x == 0f && y == 0f && z == 0f && w == 1f;
    }
}
