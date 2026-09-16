package net.exylia.lib.replay.internal;

import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.replay.ReplayFrame;
import org.jetbrains.annotations.ApiStatus;

import java.util.Arrays;

/**
 * One person's whole recording, as six arrays rather than a frame per tick.
 *
 * <p>Six minutes of a duel is seven thousand ticks and two people, and a frame
 * object each would be fourteen thousand objects to walk twenty times a second
 * while somebody watches. Kept this way it is six array reads per actor per
 * tick with nothing allocated, and a seek is an index rather than a search.
 *
 * <h2>Whole numbers, on purpose</h2>
 * Position is held in thousandths of a block as an {@code int}, which is the
 * same resolution it is written to disk in. A double would be bigger, and a
 * float would drift a little further from the file every time a recording was
 * loaded and saved again &mdash; this round-trips exactly.
 */
@ApiStatus.Internal
public final class MotionTrack {

    /** Thousandths of a block, the resolution both here and on disk. */
    static final double SCALE = 1000.0;

    /** Where the pose lives in the flags byte. */
    private static final int POSE_MASK = 0x07;
    private static final int SPRINTING = 0x08;
    private static final int ON_GROUND = 0x10;
    private static final int PRESENT = 0x20;

    private static final NpcPose[] POSES = NpcPose.values();

    final int[] x;
    final int[] y;
    final int[] z;
    final byte[] yaw;
    final byte[] pitch;
    final byte[] flags;
    final byte[] health;

    MotionTrack(int[] x, int[] y, int[] z, byte[] yaw, byte[] pitch, byte[] flags,
                byte[] health) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.flags = flags;
        this.health = health;
    }

    /** How many ticks long it is. */
    public int frames() {
        return x.length;
    }

    /** Whether anybody was there on this tick. */
    public boolean present(int tick) {
        return tick >= 0 && tick < flags.length && (flags[tick] & PRESENT) != 0;
    }

    /** Blocks east of the anchor. */
    public double x(int tick) {
        return x[tick] / SCALE;
    }

    /** Blocks above the anchor. */
    public double y(int tick) {
        return y[tick] / SCALE;
    }

    /** Blocks south of the anchor. */
    public double z(int tick) {
        return z[tick] / SCALE;
    }

    /** Degrees, as Minecraft counts them. */
    public float yaw(int tick) {
        return yaw[tick] * 360f / 256f;
    }

    /** Degrees, negative being up. */
    public float pitch(int tick) {
        return pitch[tick] * 360f / 256f;
    }

    /** How they were holding themselves. */
    public NpcPose pose(int tick) {
        return POSES[flags[tick] & POSE_MASK];
    }

    /** This tick, as the numbers it was sampled from. */
    public ReplayFrame frame(int tick) {
        if (!present(tick)) {
            return ReplayFrame.ABSENT;
        }
        return new ReplayFrame(x(tick), y(tick), z(tick), yaw(tick), pitch(tick),
                (health[tick] & 0xFF) / 2.0, pose(tick),
                (flags[tick] & SPRINTING) != 0, (flags[tick] & ON_GROUND) != 0, true);
    }

    /** Packs the four things that share a byte. */
    static byte flagsOf(NpcPose pose, boolean sprinting, boolean onGround, boolean present) {
        int packed = pose.ordinal() & POSE_MASK;
        if (sprinting) {
            packed |= SPRINTING;
        }
        if (onGround) {
            packed |= ON_GROUND;
        }
        if (present) {
            packed |= PRESENT;
        }
        return (byte) packed;
    }

    /**
     * Collects one person's frames while a recording runs.
     *
     * <p>Grows by doubling and is cut to length once, at the end. A recording
     * does not know how long it will be, and growing by one tick at a time is
     * seven thousand copies of the whole thing over a six minute duel.
     */
    static final class Builder {

        private int[] x = new int[512];
        private int[] y = new int[512];
        private int[] z = new int[512];
        private byte[] yaw = new byte[512];
        private byte[] pitch = new byte[512];
        private byte[] flags = new byte[512];
        private byte[] health = new byte[512];
        private int length;

        /**
         * Writes one tick, filling in any that were missed.
         *
         * <p>A tick the server was too busy to sample is left holding the frame
         * before it rather than a hole, so a lagging server plays back as
         * somebody standing still &mdash; which is what everybody watching saw
         * at the time.
         */
        void put(int tick, double px, double py, double pz, float aYaw, float aPitch,
                 double hearts, byte packed) {
            ensure(tick + 1);
            carryTo(tick);
            x[tick] = (int) Math.round(px * SCALE);
            y[tick] = (int) Math.round(py * SCALE);
            z[tick] = (int) Math.round(pz * SCALE);
            yaw[tick] = (byte) Math.round(aYaw * 256f / 360f);
            pitch[tick] = (byte) Math.round(aPitch * 256f / 360f);
            flags[tick] = packed;
            health[tick] = (byte) Math.clamp(Math.round(hearts * 2), 0, 255);
            length = Math.max(length, tick + 1);
        }

        /**
         * Marks every tick up to this one as one nobody was there for.
         *
         * <p>The difference between this and a gap matters. A tick the server
         * was too busy to sample is a player standing still, because that is
         * what everybody watching saw. A tick after they logged out is nobody,
         * and carrying the last frame forward through it would leave a body
         * standing in the arena for the rest of the recording.
         */
        void absentUntil(int tick) {
            if (tick <= length) {
                return;
            }
            ensure(tick);
            for (int gap = length; gap < tick; gap++) {
                x[gap] = gap == 0 ? 0 : x[gap - 1];
                y[gap] = gap == 0 ? 0 : y[gap - 1];
                z[gap] = gap == 0 ? 0 : z[gap - 1];
                yaw[gap] = 0;
                pitch[gap] = 0;
                flags[gap] = 0;
                health[gap] = 0;
            }
            length = tick;
        }

        /** Repeats the last frame up to, but not including, this tick. */
        private void carryTo(int tick) {
            for (int gap = length; gap < tick; gap++) {
                if (gap == 0) {
                    continue;
                }
                x[gap] = x[gap - 1];
                y[gap] = y[gap - 1];
                z[gap] = z[gap - 1];
                yaw[gap] = yaw[gap - 1];
                pitch[gap] = pitch[gap - 1];
                flags[gap] = flags[gap - 1];
                health[gap] = health[gap - 1];
            }
            length = Math.max(length, tick);
        }

        private void ensure(int wanted) {
            if (wanted <= x.length) {
                return;
            }
            int grown = Math.max(wanted, x.length * 2);
            x = Arrays.copyOf(x, grown);
            y = Arrays.copyOf(y, grown);
            z = Arrays.copyOf(z, grown);
            yaw = Arrays.copyOf(yaw, grown);
            pitch = Arrays.copyOf(pitch, grown);
            flags = Arrays.copyOf(flags, grown);
            health = Arrays.copyOf(health, grown);
        }

        /** How many ticks have been written. */
        int length() {
            return length;
        }

        /**
         * Cuts every array to the given length and freezes it.
         *
         * <p>Anything past the last sample is absent, not carried: a recording
         * that runs on after somebody stopped being followed is a recording
         * they are not in any more.
         */
        MotionTrack build(int frames) {
            ensure(frames);
            absentUntil(frames);
            return new MotionTrack(Arrays.copyOf(x, frames), Arrays.copyOf(y, frames),
                    Arrays.copyOf(z, frames), Arrays.copyOf(yaw, frames),
                    Arrays.copyOf(pitch, frames), Arrays.copyOf(flags, frames),
                    Arrays.copyOf(health, frames));
        }
    }
}
