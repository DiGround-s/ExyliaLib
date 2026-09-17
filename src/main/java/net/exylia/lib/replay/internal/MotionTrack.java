package net.exylia.lib.replay.internal;

import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.replay.ReplayFrame;
import org.jetbrains.annotations.ApiStatus;

import java.util.Arrays;

/**
 * One actor's whole recording, as arrays rather than a frame per tick.
 *
 * <p>Six minutes of a duel is seven thousand ticks, and a frame object each
 * would be tens of thousands of objects to walk twenty times a second while
 * somebody watches. Kept this way it is a handful of array reads per actor per
 * tick with nothing allocated, and a seek is an index rather than a search.
 *
 * <h2>Only the ticks it was there for</h2>
 * A track starts at {@link #firstTick()} and runs for as long as its actor
 * lasted, not for as long as the match did. That is what makes recording an
 * arrow affordable: a flight is sixty ticks, so its track is sixty frames
 * rather than the seven thousand the fight around it ran for. A crystal match
 * can put several hundred short-lived things in a recording and still fit in a
 * database column.
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
    private static final int USING = 0x40;

    private static final NpcPose[] POSES = NpcPose.values();

    /** Which tick of the recording the first frame belongs to. */
    private final int firstTick;

    final int[] x;
    final int[] y;
    final int[] z;
    final byte[] yaw;
    final byte[] pitch;
    final byte[] flags;
    final byte[] health;

    MotionTrack(int firstTick, int[] x, int[] y, int[] z, byte[] yaw, byte[] pitch, byte[] flags,
                byte[] health) {
        this.firstTick = firstTick;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.flags = flags;
        this.health = health;
    }

    /** Which tick of the recording this track starts at. */
    public int firstTick() {
        return firstTick;
    }

    /** How many ticks it covers, from {@link #firstTick()}. */
    public int length() {
        return x.length;
    }

    /** One past the last tick it covers. */
    public int lastTick() {
        return firstTick + x.length;
    }

    /** Whether this actor was anywhere on this tick. */
    public boolean present(int tick) {
        int at = tick - firstTick;
        return at >= 0 && at < flags.length && (flags[at] & PRESENT) != 0;
    }

    /** Blocks east of the anchor. */
    public double x(int tick) {
        return x[tick - firstTick] / SCALE;
    }

    /** Blocks above the anchor. */
    public double y(int tick) {
        return y[tick - firstTick] / SCALE;
    }

    /** Blocks south of the anchor. */
    public double z(int tick) {
        return z[tick - firstTick] / SCALE;
    }

    /** Degrees, as Minecraft counts them. */
    public float yaw(int tick) {
        return yaw[tick - firstTick] * 360f / 256f;
    }

    /** Degrees, negative being up. */
    public float pitch(int tick) {
        return pitch[tick - firstTick] * 360f / 256f;
    }

    /** How the actor was holding itself. */
    public NpcPose pose(int tick) {
        return POSES[flags[tick - firstTick] & POSE_MASK];
    }

    /** Whether it was drawing a bow, raising a shield or eating. */
    public boolean using(int tick) {
        return (flags[tick - firstTick] & USING) != 0;
    }

    /** This tick, as the numbers it was sampled from. */
    public ReplayFrame frame(int tick) {
        if (!present(tick)) {
            return ReplayFrame.ABSENT;
        }
        int at = tick - firstTick;
        return new ReplayFrame(x(tick), y(tick), z(tick), yaw(tick), pitch(tick),
                (health[at] & 0xFF) / 2.0, pose(tick),
                (flags[at] & SPRINTING) != 0, (flags[at] & ON_GROUND) != 0,
                (flags[at] & USING) != 0, true);
    }

    /** Packs the five things that share a byte. */
    static byte flagsOf(NpcPose pose, boolean sprinting, boolean onGround, boolean using,
                        boolean present) {
        int packed = pose.ordinal() & POSE_MASK;
        if (sprinting) packed |= SPRINTING;
        if (onGround) packed |= ON_GROUND;
        if (using) packed |= USING;
        if (present) packed |= PRESENT;
        return (byte) packed;
    }

    /**
     * Collects one actor's frames while a recording runs.
     *
     * <p>Grows by doubling and is cut to length once, at the end. A recording
     * does not know how long it will be, and growing by one tick at a time is
     * thousands of copies of the whole thing over one duel.
     */
    static final class Builder {

        private int[] x = new int[64];
        private int[] y = new int[64];
        private int[] z = new int[64];
        private byte[] yaw = new byte[64];
        private byte[] pitch = new byte[64];
        private byte[] flags = new byte[64];
        private byte[] health = new byte[64];

        /** Unset until the first frame, which is what decides where this starts. */
        private int firstTick = -1;
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
            if (firstTick < 0) {
                firstTick = tick;
            }
            int at = tick - firstTick;
            if (at < 0) {
                // A sample from before this actor existed, which only a clock
                // running backwards produces. Dropped rather than shifting
                // everything already written.
                return;
            }
            ensure(at + 1);
            carryTo(at);
            x[at] = (int) Math.round(px * SCALE);
            y[at] = (int) Math.round(py * SCALE);
            z[at] = (int) Math.round(pz * SCALE);
            yaw[at] = (byte) Math.round(aYaw * 256f / 360f);
            pitch[at] = (byte) Math.round(aPitch * 256f / 360f);
            flags[at] = packed;
            health[at] = (byte) Math.clamp(Math.round(hearts * 2), 0, 255);
            length = Math.max(length, at + 1);
        }

        /**
         * Marks every tick up to this one as one nobody was there for.
         *
         * <p>The difference between this and a gap matters. A tick the server
         * was too busy to sample is an actor standing still, because that is
         * what everybody watching saw. A tick after a player logged out is
         * nobody, and carrying the last frame forward through it would leave a
         * body standing in the arena for the rest of the recording.
         */
        void absentUntil(int tick) {
            if (firstTick < 0) {
                return;
            }
            int at = tick - firstTick;
            if (at <= length) {
                return;
            }
            ensure(at);
            for (int gap = length; gap < at; gap++) {
                x[gap] = gap == 0 ? 0 : x[gap - 1];
                y[gap] = gap == 0 ? 0 : y[gap - 1];
                z[gap] = gap == 0 ? 0 : z[gap - 1];
                yaw[gap] = 0;
                pitch[gap] = 0;
                flags[gap] = 0;
                health[gap] = 0;
            }
            length = at;
        }

        /** Repeats the last frame up to, but not including, this index. */
        private void carryTo(int at) {
            for (int gap = length; gap < at; gap++) {
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
            length = Math.max(length, at);
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

        /** Whether anything was ever written. */
        boolean isEmpty() {
            return firstTick < 0 || length == 0;
        }

        /** One past the last tick written, in the recording's own numbering. */
        int lastTick() {
            return firstTick < 0 ? 0 : firstTick + length;
        }

        /**
         * Cuts every array to what was written and freezes it.
         *
         * <p>Trailing absence is cut rather than kept: an actor that stopped
         * being recorded simply ends there, and the playback reads a tick past
         * the end of a track as nobody.
         */
        MotionTrack build() {
            int end = length;
            while (end > 0 && flags[end - 1] == 0) {
                end--;
            }
            int start = Math.max(0, firstTick);
            return new MotionTrack(start, Arrays.copyOf(x, end), Arrays.copyOf(y, end),
                    Arrays.copyOf(z, end), Arrays.copyOf(yaw, end), Arrays.copyOf(pitch, end),
                    Arrays.copyOf(flags, end), Arrays.copyOf(health, end));
        }
    }
}
