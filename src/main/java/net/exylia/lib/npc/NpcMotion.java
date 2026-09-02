package net.exylia.lib.npc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * What a body does after it appears.
 *
 * <pre>{@code
 * NpcMotion thrown = NpcMotion.builder()
 *         .over(900)
 *         .to(0, 1.4, -2.5)
 *         .gravity(9)
 *         .turn(140)
 *         .collapsing(NpcPose.LYING, 600)
 *         .hurt(true)
 *         .build();
 * }</pre>
 *
 * <h2>Why a body needs one</h2>
 * A body that appears and disappears is a prop. A body that is thrown by the
 * blast, that slumps a moment after it is struck, or that sinks into the ground
 * it opened is the thing anybody actually notices. All three are the same few
 * numbers, and none of them is a model.
 *
 * <h2>It is not physics</h2>
 * Movement is written, not simulated. {@code gravity} is added on top of the
 * line rather than replacing it, so "thrown two blocks back and let it drop" is
 * two independent numbers instead of a launch velocity nobody can picture.
 *
 * <p>Immutable; built when a file is read and shared by every play.
 *
 * @since 1.88.3
 */
public final class NpcMotion {

    /** A body that appears where it is put and stays there. */
    private static final NpcMotion STILL = builder().build();

    /** How a movement is spread across its own time. */
    public enum Easing {

        /** The same rate throughout. */
        LINEAR,

        /** Slow, then fast. Something being taken. */
        IN,

        /** Fast, then settling. Something that was thrown. */
        OUT,

        /** Slow, fast, slow. A whole gesture. */
        IN_OUT;

        /** Reads an easing from configuration, defaulting to {@link #OUT}. */
        public static @NotNull Easing of(@NotNull String name) {
            return switch (name.trim().toUpperCase(Locale.ROOT)) {
                case "LINEAR", "NONE" -> LINEAR;
                case "IN", "ACCELERATE" -> IN;
                case "IN_OUT", "BOTH" -> IN_OUT;
                default -> OUT;
            };
        }

        /** Where the movement has got to, at a given fraction of its time. */
        public double at(double progress) {
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

    private final double fromX;
    private final double fromY;
    private final double fromZ;
    private final double toX;
    private final double toY;
    private final double toZ;
    private final double gravity;
    private final long overMillis;
    private final Easing easing;
    private final double turnDegrees;
    private final NpcPose pose;
    private final NpcPose poseThen;
    private final long poseAfterMillis;
    private final boolean hurt;

    private NpcMotion(Builder builder) {
        this.fromX = builder.fromX;
        this.fromY = builder.fromY;
        this.fromZ = builder.fromZ;
        this.toX = builder.toX;
        this.toY = builder.toY;
        this.toZ = builder.toZ;
        this.gravity = builder.gravity;
        this.overMillis = builder.overMillis;
        this.easing = builder.easing;
        this.turnDegrees = builder.turnDegrees;
        this.pose = builder.pose;
        this.poseThen = builder.poseThen;
        this.poseAfterMillis = builder.poseAfterMillis;
        this.hurt = builder.hurt;
    }

    /** A body that stays exactly where it was put. */
    public static @NotNull NpcMotion still() {
        return STILL;
    }

    /** A movement described in the terms configuration is written in. */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** Whether anything happens at all, so a still body costs no driving. */
    public boolean isStill() {
        return fromX == 0 && fromY == 0 && fromZ == 0
                && toX == 0 && toY == 0 && toZ == 0
                && gravity == 0 && turnDegrees == 0 && poseThen == null;
    }

    /** Where the body is, relative to where it was put, at a moment in its life. */
    public double @NotNull [] at(long elapsedMillis) {
        double progress = overMillis <= 0 ? 1.0
                : Math.clamp(elapsedMillis / (double) overMillis, 0.0, 1.0);
        double eased = easing.at(progress);
        double seconds = Math.min(elapsedMillis, overMillis) / 1000.0;
        double drop = gravity == 0.0 ? 0.0 : 0.5 * gravity * seconds * seconds;
        return new double[]{
                fromX + (toX - fromX) * eased,
                fromY + (toY - fromY) * eased - drop,
                fromZ + (toZ - fromZ) * eased};
    }

    /** How far the body has turned from the yaw it was put at. */
    public float turnedBy(long elapsedMillis) {
        if (turnDegrees == 0.0) {
            return 0f;
        }
        double progress = overMillis <= 0 ? 1.0
                : Math.clamp(elapsedMillis / (double) overMillis, 0.0, 1.0);
        return (float) (turnDegrees * easing.at(progress));
    }

    /** How long the movement lasts. */
    public long overMillis() {
        return overMillis;
    }

    /** The pose it appears in. */
    public @NotNull NpcPose pose() {
        return pose;
    }

    /** The pose it changes to, or {@code null} when it never does. */
    public @Nullable NpcPose poseThen() {
        return poseThen;
    }

    /** How long after appearing the pose changes. */
    public long poseAfterMillis() {
        return poseAfterMillis;
    }

    /** Whether it flinches as it appears. */
    public boolean hurt() {
        return hurt;
    }

    /** Describes what a body does. */
    public static final class Builder {

        private double fromX;
        private double fromY;
        private double fromZ;
        private double toX;
        private double toY;
        private double toZ;
        private double gravity;
        private long overMillis = 700L;
        private Easing easing = Easing.OUT;
        private double turnDegrees;
        private NpcPose pose = NpcPose.LYING;
        private NpcPose poseThen;
        private long poseAfterMillis;
        private boolean hurt;

        private Builder() {
        }

        /** Where it starts, relative to where it was put. */
        public @NotNull Builder from(double x, double y, double z) {
            this.fromX = x;
            this.fromY = y;
            this.fromZ = z;
            return this;
        }

        /** Where it ends up, relative to where it was put. */
        public @NotNull Builder to(double x, double y, double z) {
            this.toX = x;
            this.toY = y;
            this.toZ = z;
            return this;
        }

        /**
         * Downward acceleration in blocks per second squared, on top of the line.
         *
         * <p>What turns a shove into a body being thrown. Vanilla is about 32;
         * a body reads better at half that, because a real one is heavier than
         * the eye expects and slower than the number says.
         */
        public @NotNull Builder gravity(double blocksPerSecondSquared) {
            this.gravity = blocksPerSecondSquared;
            return this;
        }

        /** How long the movement takes. */
        public @NotNull Builder over(long millis) {
            this.overMillis = Math.max(0L, millis);
            return this;
        }

        /** How the movement is spread across that time. */
        public @NotNull Builder ease(@NotNull Easing easing) {
            this.easing = easing;
            return this;
        }

        /** Degrees it turns on the spot over the movement. */
        public @NotNull Builder turn(double degrees) {
            this.turnDegrees = degrees;
            return this;
        }

        /** The pose it appears in. */
        public @NotNull Builder pose(@NotNull NpcPose pose) {
            this.pose = pose;
            return this;
        }

        /**
         * A second pose, a moment after the first.
         *
         * <p>The difference between a corpse and somebody dying: one is already
         * on the floor when you look, the other goes down while you watch.
         *
         * @param pose  what it becomes
         * @param after how long after appearing
         * @return this builder
         */
        public @NotNull Builder collapsing(@NotNull NpcPose pose, long after) {
            this.poseThen = pose;
            this.poseAfterMillis = Math.max(0L, after);
            return this;
        }

        /** Whether it flinches red as it appears. */
        public @NotNull Builder hurt(boolean hurt) {
            this.hurt = hurt;
            return this;
        }

        /** Works out the motion. */
        public @NotNull NpcMotion build() {
            return new NpcMotion(this);
        }
    }
}
