package net.exylia.lib.display;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * How a display moves, turns and grows over its life.
 *
 * <pre>{@code
 * DisplayMotion thrown = DisplayMotion.builder()
 *         .life(1200)
 *         .from(0, 6, 0).to(0, 0, 0)
 *         .spin(Rotation.Axis.Z, 3)
 *         .scale(1.0, 1.4)
 *         .gravity(18)
 *         .build();
 * }</pre>
 *
 * <h2>Poses, not frames</h2>
 * A motion is a handful of poses with times on them. The client draws the frames
 * between them, so a two-second animation costs about six packets per viewer
 * however smooth it looks. Nothing here runs per tick.
 *
 * <h2>How many poses a spin needs</h2>
 * The client turns a display by the shortest arc between two rotations, so a
 * pose more than half a turn from the last one spins the wrong way. A spin is
 * therefore cut into pieces small enough that the short way round is the way
 * the file meant &mdash; six per turn, which is a sixth of a turn each and well
 * inside the limit. Nobody writing {@code spin:3} should have to know this.
 *
 * <p>Immutable; built when configuration is read and shared by every play.
 *
 * @since 1.85.0
 */
public final class DisplayMotion {

    /** Poses per turn of spin. Six is 60&deg; a pose, comfortably under the 180&deg; limit. */
    private static final int POSES_PER_TURN = 6;

    /** Poses used for a fall, which needs enough to look like a curve and not a corner. */
    private static final int POSES_PER_FALL = 8;

    /** A ceiling, so a file asking for forty turns does not send forty packets a viewer. */
    private static final int MAX_POSES = 48;

    private final List<DisplayKeyframe> poses;
    private final long lifeMillis;

    private DisplayMotion(List<DisplayKeyframe> poses, long lifeMillis) {
        this.poses = List.copyOf(poses);
        this.lifeMillis = lifeMillis;
    }

    /**
     * A display that appears, stays exactly as it is, and goes.
     *
     * @param lifeMillis how long it lasts
     * @return the motion
     */
    public static @NotNull DisplayMotion still(long lifeMillis) {
        return new DisplayMotion(List.of(
                new DisplayKeyframe(0L, 0f, 0f, 0f, Rotation.NONE, 1f, 1f, 1f)), lifeMillis);
    }

    /**
     * A motion built from poses worked out elsewhere.
     *
     * @param poses      the poses, in time order; the first should be at zero
     * @param lifeMillis how long the display lasts
     * @return the motion
     */
    public static @NotNull DisplayMotion of(@NotNull List<DisplayKeyframe> poses, long lifeMillis) {
        return poses.isEmpty() ? still(lifeMillis) : new DisplayMotion(poses, lifeMillis);
    }

    /** A builder for the movements configuration can describe. */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** The poses, in time order. */
    public @NotNull List<DisplayKeyframe> poses() {
        return poses;
    }

    /** How long the display lasts, in milliseconds. */
    public long lifeMillis() {
        return lifeMillis;
    }

    /**
     * The same motion with every pose turned first.
     *
     * <p>One ring of blades, each facing outwards: the animation is built once
     * and turned twelve times, rather than built twelve times.
     *
     * @param first the rotation applied before each pose's own
     * @return the turned motion
     */
    public @NotNull DisplayMotion turnedBy(@NotNull Rotation first) {
        if (first.isNone()) {
            return this;
        }
        List<DisplayKeyframe> turned = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            turned.add(pose.turnedBy(first));
        }
        return new DisplayMotion(turned, lifeMillis);
    }

    /**
     * The same motion, drifting somewhere over its life.
     *
     * <p>Added on top of everything else and spread across the poses, so it
     * composes with a spin and a fall rather than replacing them. What it is
     * for is the movement a shape's points cannot share: twelve blades that
     * each converge on the same spot are twelve different directions and one
     * animation.
     *
     * @param dx how far east by the end
     * @param dy how far up by the end
     * @param dz how far south by the end
     * @return the drifting motion
     */
    public @NotNull DisplayMotion drifting(double dx, double dy, double dz) {
        if ((dx == 0.0 && dy == 0.0 && dz == 0.0) || lifeMillis <= 0L) {
            return this;
        }
        List<DisplayKeyframe> drifted = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            double progress = (double) pose.atMillis() / lifeMillis;
            drifted.add(pose.movedBy(dx * progress, dy * progress, dz * progress));
        }
        return new DisplayMotion(drifted, lifeMillis);
    }

    /**
     * The same motion starting somewhere else.
     *
     * @param dx east
     * @param dy up
     * @param dz south
     * @return the moved motion
     */
    public @NotNull DisplayMotion movedBy(double dx, double dy, double dz) {
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return this;
        }
        List<DisplayKeyframe> moved = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            moved.add(pose.movedBy(dx, dy, dz));
        }
        return new DisplayMotion(moved, lifeMillis);
    }

    /**
     * Describes a movement in the terms configuration is written in.
     *
     * <p>Straight line from one offset to another, an optional fall on top of
     * it, an optional spin, and a size that grows or shrinks. Between them they
     * cover every effect anybody has actually asked for, and each one is a
     * number a server owner can picture.
     */
    public static final class Builder {

        private long lifeMillis = 1000L;
        private double fromX;
        private double fromY;
        private double fromZ;
        private double toX;
        private double toY;
        private double toZ;
        private double startScale = 1.0;
        private double endScale = 1.0;
        private Rotation base = Rotation.NONE;
        private Rotation.Axis spinAxis = Rotation.Axis.Y;
        private double spinTurns;
        private double gravity;

        private Builder() {
        }

        /** How long the display lasts, in milliseconds. */
        public @NotNull Builder life(long millis) {
            this.lifeMillis = Math.max(50L, millis);
            return this;
        }

        /** Where it starts, relative to where it was spawned. */
        public @NotNull Builder from(double x, double y, double z) {
            this.fromX = x;
            this.fromY = y;
            this.fromZ = z;
            return this;
        }

        /** Where it ends up, relative to where it was spawned. */
        public @NotNull Builder to(double x, double y, double z) {
            this.toX = x;
            this.toY = y;
            this.toZ = z;
            return this;
        }

        /** The size it starts and ends at, as a multiple of the model's own. */
        public @NotNull Builder scale(double start, double end) {
            this.startScale = start;
            this.endScale = end;
            return this;
        }

        /** A fixed rotation the model keeps for its whole life. */
        public @NotNull Builder rotation(@NotNull Rotation rotation) {
            this.base = rotation;
            return this;
        }

        /** Turns around an axis over the display's whole life. */
        public @NotNull Builder spin(@NotNull Rotation.Axis axis, double turns) {
            this.spinAxis = axis;
            this.spinTurns = turns;
            return this;
        }

        /**
         * Downward acceleration in blocks per second squared, on top of the line.
         *
         * <p>What turns a throw into an arc. Vanilla gravity is about 32; the
         * number is left open because an effect is choreography, not physics,
         * and a slower fall reads better on a short life.
         */
        public @NotNull Builder gravity(double blocksPerSecondSquared) {
            this.gravity = blocksPerSecondSquared;
            return this;
        }

        /** Works out the poses. */
        public @NotNull DisplayMotion build() {
            int poseCount = poseCount();
            List<DisplayKeyframe> poses = new ArrayList<>(poseCount);
            double seconds = lifeMillis / 1000.0;
            for (int index = 0; index < poseCount; index++) {
                double progress = poseCount == 1 ? 0.0 : (double) index / (poseCount - 1);
                double elapsed = progress * seconds;
                // The fall is added to the straight line rather than replacing
                // it, so "throw it four blocks east and let it drop" is two
                // independent numbers instead of one solved trajectory.
                double drop = gravity == 0.0 ? 0.0 : 0.5 * gravity * elapsed * elapsed;
                double scale = startScale + (endScale - startScale) * progress;
                poses.add(new DisplayKeyframe(
                        (long) (progress * lifeMillis),
                        (float) (fromX + (toX - fromX) * progress),
                        (float) (fromY + (toY - fromY) * progress - drop),
                        (float) (fromZ + (toZ - fromZ) * progress),
                        spinTurns == 0.0
                                ? base
                                : base.then(Rotation.around(spinAxis,
                                        progress * spinTurns * Math.PI * 2)),
                        (float) scale, (float) scale, (float) scale));
            }
            return new DisplayMotion(poses, lifeMillis);
        }

        /**
         * How many poses this movement needs.
         *
         * <p>A straight line needs two. A spin needs enough that the client
         * never has to guess which way round; a fall needs enough to look like
         * a curve. The most demanding of the three wins.
         */
        private int poseCount() {
            int needed = 2;
            if (spinTurns != 0.0) {
                needed = Math.max(needed,
                        (int) Math.ceil(Math.abs(spinTurns) * POSES_PER_TURN) + 1);
            }
            if (gravity != 0.0) {
                needed = Math.max(needed, POSES_PER_FALL);
            }
            return Math.min(needed, MAX_POSES);
        }
    }
}
