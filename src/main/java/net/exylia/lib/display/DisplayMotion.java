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

    /** Poses an eased movement needs before the curve reads as a curve. */
    private static final int POSES_PER_EASE = 12;

    /**
     * How much faster an eased movement runs at its fastest than a flat one.
     *
     * <p>A cubic ease spends a third of its distance in its last sixth, so a
     * spin that is comfortably cut up on a flat movement is not on an eased
     * one. The pose count is multiplied by this rather than by guesswork.
     */
    private static final int EASE_PEAK = 3;

    /**
     * How a movement is spread across its own life.
     *
     * <p>The difference between something moving and something striking.
     * Everything the client does between two poses is a straight line at a
     * constant rate; this is what puts the poses where they need to be for that
     * to add up to a blow.
     */
    public enum Easing {

        /** The same rate from start to finish. */
        LINEAR,

        /** Slow, then very fast. A wind-up and a strike. */
        IN,

        /** Fast, then settling. An impact coming to rest. */
        OUT,

        /** Slow, fast, slow. A whole gesture in one line. */
        IN_OUT;

        /** Reads an easing from configuration, defaulting to {@link #LINEAR}. */
        public static @NotNull Easing of(@NotNull String name) {
            return switch (name.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "IN", "ACCELERATE" -> IN;
                case "OUT", "DECELERATE" -> OUT;
                case "IN_OUT", "BOTH" -> IN_OUT;
                default -> LINEAR;
            };
        }

        /** Where the movement has got to, at a given fraction of its life. */
        double at(double progress) {
            return switch (this) {
                case IN -> progress * progress * progress;
                case OUT -> 1 - Math.pow(1 - progress, 3);
                case IN_OUT -> progress < 0.5
                        ? 4 * progress * progress * progress
                        : 1 - Math.pow(-2 * progress + 2, 3) / 2;
                default -> progress;
            };
        }
    }

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
     * and turned twelve times, rather than built twelve times. Applied after
     * each pose's own rotation &mdash; see {@link DisplayKeyframe#turnedBy}.
     *
     * @param last the rotation applied after each pose's own
     * @return the turned motion
     */
    public @NotNull DisplayMotion turnedBy(@NotNull Rotation last) {
        if (last.isNone()) {
            return this;
        }
        List<DisplayKeyframe> turned = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            turned.add(pose.turnedBy(last));
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
     * The same motion, carried round the anchor as it goes.
     *
     * <p>The one movement a straight line cannot express and the one every
     * effect eventually wants: a ring that turns, a swarm that circles, debris
     * that curves away instead of leaving on a rail. It is worked out per point
     * because each point starts somewhere different, and it is added to
     * whatever the point was already doing.
     *
     * @param x       where the point sits, east of the anchor
     * @param z       where the point sits, south of the anchor
     * @param turns   turns about the anchor over the whole life
     * @param facing  whether the model turns with the orbit, so a blade stays
     *                tangent to the circle rather than sliding round sideways
     * @return the orbiting motion
     */
    public @NotNull DisplayMotion orbiting(double x, double z, double turns, boolean facing) {
        if (turns == 0.0 || (x == 0.0 && z == 0.0) || lifeMillis <= 0L) {
            return this;
        }
        List<DisplayKeyframe> orbited = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            double progress = (double) pose.atMillis() / lifeMillis;
            double angle = turns * Math.PI * 2 * progress;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            DisplayKeyframe moved = pose.movedBy(
                    x * cos + z * sin - x, 0.0, -x * sin + z * cos - z);
            orbited.add(facing ? moved.turnedBy(Rotation.around(Rotation.Axis.Y, angle)) : moved);
        }
        return new DisplayMotion(orbited, lifeMillis);
    }

    /**
     * The same motion at a different size throughout.
     *
     * <p>For giving the pieces of one shape sizes that differ. A dozen
     * fragments cut to exactly the same size read as a pattern; the same dozen
     * varying by a fifth read as rubble.
     *
     * @param factor what to multiply every pose's size by
     * @return the resized motion
     */
    public @NotNull DisplayMotion scaledBy(double factor) {
        if (factor == 1.0) {
            return this;
        }
        List<DisplayKeyframe> resized = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            resized.add(new DisplayKeyframe(pose.atMillis(), pose.x(), pose.y(), pose.z(),
                    pose.rotation(),
                    (float) (pose.scaleX() * factor),
                    (float) (pose.scaleY() * factor),
                    (float) (pose.scaleZ() * factor)));
        }
        return new DisplayMotion(resized, lifeMillis);
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
        private double[] startScale = {1.0, 1.0, 1.0};
        private double[] endScale = {1.0, 1.0, 1.0};
        private Rotation base = Rotation.NONE;
        private double spinX;
        private double spinY;
        private double spinZ;
        private double gravity;
        private Easing easing = Easing.LINEAR;

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
            return scale(new double[]{start, start, start}, new double[]{end, end, end});
        }

        /**
         * The same, per axis.
         *
         * <p>What a cube cannot say on its own. A block flattened to a tenth of
         * its height is a plate, and a plate growing outwards is a shockwave; a
         * block stretched along one axis is a pillar, a beam or the blade of
         * something far too large to be an item. The models are the twenty
         * blocks a server already has, and the shape comes from here.
         *
         * @param start width, height and depth it starts at
         * @param end   width, height and depth it ends at
         * @return this builder
         */
        public @NotNull Builder scale(double @NotNull [] start, double @NotNull [] end) {
            this.startScale = new double[]{start[0], start[1], start[2]};
            this.endScale = new double[]{end[0], end[1], end[2]};
            return this;
        }

        /** A fixed rotation the model keeps for its whole life. */
        public @NotNull Builder rotation(@NotNull Rotation rotation) {
            this.base = rotation;
            return this;
        }

        /** Turns around one axis over the display's whole life. */
        public @NotNull Builder spin(@NotNull Rotation.Axis axis, double turns) {
            return switch (axis) {
                case X -> spin(turns, 0, 0);
                case Y -> spin(0, turns, 0);
                case Z -> spin(0, 0, turns);
            };
        }

        /**
         * Turns around all three axes at once.
         *
         * <p>One axis is a wheel and reads as machinery. Two or three at
         * different rates is a tumble, and a tumble is what a thrown thing
         * actually does: nothing in the world spins about exactly one axis, and
         * the eye knows it even when it cannot say why.
         *
         * @param x turns about the pitch axis
         * @param y turns about the vertical
         * @param z turns about the roll axis
         * @return this builder
         */
        public @NotNull Builder spin(double x, double y, double z) {
            this.spinX = x;
            this.spinY = y;
            this.spinZ = z;
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

        /**
         * How the movement is spread across its life.
         *
         * <p>A straight line at a constant rate is a thing sliding. The same
         * line eased in is the same thing arriving.
         */
        public @NotNull Builder ease(@NotNull Easing easing) {
            this.easing = easing;
            return this;
        }

        /** Works out the poses. */
        public @NotNull DisplayMotion build() {
            int poseCount = poseCount();
            List<DisplayKeyframe> poses = new ArrayList<>(poseCount);
            double seconds = lifeMillis / 1000.0;
            for (int index = 0; index < poseCount; index++) {
                double elapsedFraction = poseCount == 1 ? 0.0 : (double) index / (poseCount - 1);
                // The poses are evenly spaced in time and unevenly spaced along
                // the movement. That is what easing is: the client still draws
                // a straight line between two poses, and the poses are where
                // the acceleration lives.
                double progress = easing.at(elapsedFraction);
                double elapsed = elapsedFraction * seconds;
                // The fall is added to the straight line rather than replacing
                // it, so "throw it four blocks east and let it drop" is two
                // independent numbers instead of one solved trajectory.
                double drop = gravity == 0.0 ? 0.0 : 0.5 * gravity * elapsed * elapsed;
                poses.add(new DisplayKeyframe(
                        (long) (elapsedFraction * lifeMillis),
                        (float) (fromX + (toX - fromX) * progress),
                        (float) (fromY + (toY - fromY) * progress - drop),
                        (float) (fromZ + (toZ - fromZ) * progress),
                        spinning(base, progress),
                        (float) (startScale[0] + (endScale[0] - startScale[0]) * progress),
                        (float) (startScale[1] + (endScale[1] - startScale[1]) * progress),
                        (float) (startScale[2] + (endScale[2] - startScale[2]) * progress)));
            }
            return new DisplayMotion(poses, lifeMillis);
        }

        /** The base rotation with this moment's share of every spin on top. */
        private Rotation spinning(Rotation from, double progress) {
            Rotation turned = from;
            if (spinX != 0.0) {
                turned = turned.then(Rotation.around(Rotation.Axis.X,
                        progress * spinX * Math.PI * 2));
            }
            if (spinY != 0.0) {
                turned = turned.then(Rotation.around(Rotation.Axis.Y,
                        progress * spinY * Math.PI * 2));
            }
            if (spinZ != 0.0) {
                turned = turned.then(Rotation.around(Rotation.Axis.Z,
                        progress * spinZ * Math.PI * 2));
            }
            return turned;
        }

        /**
         * How many poses this movement needs.
         *
         * <p>A straight line needs two. A spin needs enough that the client
         * never has to guess which way round; a fall needs enough to look like
         * a curve. The most demanding of the three wins.
         */
        private int poseCount() {
            int peak = easing == Easing.LINEAR ? 1 : EASE_PEAK;
            int needed = 2;
            double turns = Math.abs(spinX) + Math.abs(spinY) + Math.abs(spinZ);
            if (turns != 0.0) {
                // The sum, not the largest: two axes turning at once can put
                // more angle between two poses than either does alone.
                needed = Math.max(needed,
                        (int) Math.ceil(turns * POSES_PER_TURN * peak) + 1);
            }
            if (gravity != 0.0) {
                needed = Math.max(needed, POSES_PER_FALL);
            }
            if (easing != Easing.LINEAR) {
                needed = Math.max(needed, POSES_PER_EASE);
            }
            return Math.min(needed, MAX_POSES);
        }
    }
}
