package net.exylia.lib.replay.internal;

import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.replay.ReplayFrame;
import org.jetbrains.annotations.ApiStatus;

import java.util.Arrays;
import java.util.Locale;

/**
 * Where one actor was on every tick it was there for.
 *
 * <p>Parallel primitive arrays rather than an object per tick: a three minute
 * recording is 3600 frames per actor, and the codec writes these arrays as
 * deltas without ever building a frame object.
 *
 * <h2>What a frame carries</h2>
 * Position to a thousandth of a block, relative to the anchor of the scene the
 * tick belongs to. Body yaw, pitch and head yaw in 65536 steps a turn (format 4;
 * format 3 kept 256, the protocol's own). A flag word with the pose, sprinting,
 * on the ground, present, which hand is raised, on fire, invisible and glowing.
 * Health to the nearest half point. For a player of the black box, the seen lag
 * of every tick: how many ticks behind the server their screen was.
 */
@ApiStatus.Internal
public final class MotionTrack {

    /** Thousandths of a block. */
    static final double SCALE = 1000.0;

    static final int POSE_MASK = 0x1F;
    static final int SPRINTING = 1 << 5;
    static final int ON_GROUND = 1 << 6;
    static final int PRESENT = 1 << 7;
    static final int USING = 1 << 8;
    static final int USING_OFF_HAND = 1 << 9;
    static final int ON_FIRE = 1 << 10;
    static final int INVISIBLE = 1 << 11;
    static final int GLOWING = 1 << 12;

    /**
     * Every pose a recording can hold, by the protocol's name. The index is what
     * the flag word stores, so this list only ever grows at the end.
     */
    static final String[] POSES = {
            "STANDING", "FALL_FLYING", "SLEEPING", "SWIMMING", "SPIN_ATTACK", "CROUCHING",
            "LONG_JUMPING", "DYING", "CROAKING", "USING_TONGUE", "SITTING", "ROARING",
            "SNIFFING", "EMERGING", "DIGGING", "SLIDING", "SHOOTING", "INHALING"};

    private final int firstTick;

    final int[] x;
    final int[] y;
    final int[] z;
    final short[] yaw;
    final short[] pitch;
    final short[] head;
    final int[] flags;
    final byte[] health;
    /**
     * Seen lag per tick, {@link #LAG_UNKNOWN} where nobody measured it, with
     * {@link #LAG_ESTIMATED} set where it came from the ping rather than from
     * the client's own answer; {@code null} for a track with none at all.
     */
    final short @org.jetbrains.annotations.Nullable [] lag;

    /** No seen lag on this tick. */
    static final int LAG_UNKNOWN = 0xFF;
    /** The seen lag is the ping's estimate, not a measurement. */
    static final int LAG_ESTIMATED = 0x100;

    MotionTrack(int firstTick, int[] x, int[] y, int[] z, short[] yaw, short[] pitch, short[] head,
                int[] flags, byte[] health) {
        this(firstTick, x, y, z, yaw, pitch, head, flags, health, null);
    }

    MotionTrack(int firstTick, int[] x, int[] y, int[] z, short[] yaw, short[] pitch, short[] head,
                int[] flags, byte[] health, short @org.jetbrains.annotations.Nullable [] lag) {
        this.firstTick = firstTick;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.head = head;
        this.flags = flags;
        this.health = health;
        this.lag = lag;
    }

    /** The first tick this track has a frame for. */
    public int firstTick() {
        return firstTick;
    }

    /** How many frames it holds. */
    public int length() {
        return x.length;
    }

    /** One past the last tick it has a frame for. */
    public int lastTick() {
        return firstTick + x.length;
    }

    public boolean present(int tick) {
        int at = tick - firstTick;
        return at >= 0 && at < flags.length && (flags[at] & PRESENT) != 0;
    }

    public double x(int tick) {
        return x[tick - firstTick] / SCALE;
    }

    public double y(int tick) {
        return y[tick - firstTick] / SCALE;
    }

    public double z(int tick) {
        return z[tick - firstTick] / SCALE;
    }

    public float yaw(int tick) {
        return yaw[tick - firstTick] * 360f / 65536f;
    }

    public float pitch(int tick) {
        return pitch[tick - firstTick] * 360f / 65536f;
    }

    public float headYaw(int tick) {
        return head[tick - firstTick] * 360f / 65536f;
    }

    /** How many ticks behind the server this player's screen was, or -1 when unknown. */
    public int seenLag(int tick) {
        int at = tick - firstTick;
        if (lag == null || at < 0 || at >= lag.length) return -1;
        int value = lag[at] & 0xFF;
        return value == LAG_UNKNOWN ? -1 : value;
    }

    /** Whether {@link #seenLag} on this tick is the ping's estimate rather than a measurement. */
    public boolean lagEstimated(int tick) {
        int at = tick - firstTick;
        return lag != null && at >= 0 && at < lag.length && (lag[at] & LAG_ESTIMATED) != 0;
    }

    public int flags(int tick) {
        return flags[tick - firstTick];
    }

    /** The protocol's name for the pose on this tick. */
    public String poseName(int tick) {
        int index = flags[tick - firstTick] & POSE_MASK;
        return index < POSES.length ? POSES[index] : "STANDING";
    }

    public NpcPose pose(int tick) {
        return switch (poseName(tick)) {
            case "SLEEPING", "DYING" -> NpcPose.LYING;
            case "SWIMMING" -> NpcPose.CRAWLING;
            case "CROUCHING" -> NpcPose.SNEAKING;
            case "SPIN_ATTACK" -> NpcPose.SPINNING;
            default -> NpcPose.STANDING;
        };
    }

    public boolean onGround(int tick) {
        return (flags[tick - firstTick] & ON_GROUND) != 0;
    }

    public boolean using(int tick) {
        return (flags[tick - firstTick] & (USING | USING_OFF_HAND)) != 0;
    }

    public double health(int tick) {
        return (health[tick - firstTick] & 0xFF) / 2.0;
    }

    public ReplayFrame frame(int tick) {
        if (!present(tick)) {
            return ReplayFrame.ABSENT;
        }
        int at = tick - firstTick;
        return new ReplayFrame(x(tick), y(tick), z(tick), yaw(tick), pitch(tick),
                health(tick), pose(tick), (flags[at] & SPRINTING) != 0,
                (flags[at] & ON_GROUND) != 0, using(tick), true);
    }

    /** The index a protocol pose name is stored under, standing when unknown. */
    static int poseIndex(String name) {
        String wanted = name.toUpperCase(Locale.ROOT);
        if (wanted.equals("SNEAKING")) wanted = "CROUCHING";
        for (int index = 0; index < POSES.length; index++) {
            if (POSES[index].equals(wanted)) return index;
        }
        return 0;
    }

    /** Packs one frame's flags. */
    static int flagsOf(int pose, boolean sprinting, boolean onGround, boolean using,
                       boolean usingOffHand, boolean onFire, boolean invisible,
                       boolean glowing, boolean present) {
        int packed = pose & POSE_MASK;
        if (sprinting) packed |= SPRINTING;
        if (onGround) packed |= ON_GROUND;
        if (using) packed |= USING;
        if (usingOffHand) packed |= USING_OFF_HAND;
        if (onFire) packed |= ON_FIRE;
        if (invisible) packed |= INVISIBLE;
        if (glowing) packed |= GLOWING;
        if (present) packed |= PRESENT;
        return packed;
    }

    /** An angle in the protocol's 256 steps a turn. */
    static byte angle(float degrees) {
        return (byte) Math.round(degrees * 256f / 360f);
    }

    /** An angle in 65536 steps a turn, which is what a track holds since format 4. */
    static short fine(float degrees) {
        return (short) Math.round(degrees * 65536f / 360f);
    }

    /**
     * Builds a track one tick at a time.
     *
     * <p>A tick that is skipped is filled with a copy of the one before it, and
     * a stretch the actor was away for with frames that are not present.
     */
    static final class Builder {

        private int[] x = new int[64];
        private int[] y = new int[64];
        private int[] z = new int[64];
        private short[] yaw = new short[64];
        private short[] pitch = new short[64];
        private short[] head = new short[64];
        private int[] flags = new int[64];
        private byte[] health = new byte[64];
        private short[] lag = new short[64];
        /** Whether any tick had a seen lag: a track without one keeps no column. */
        private boolean lagged;

        private int firstTick = -1;
        private int length;

        /**
         * Writes one tick.
         *
         * @param px relative to the anchor, in blocks
         */
        void put(int tick, double px, double py, double pz, float aYaw, float aPitch,
                 float aHead, double hearts, int packed) {
            put(tick, px, py, pz, aYaw, aPitch, aHead, hearts, packed, LAG_UNKNOWN);
        }

        /** Same, with the seen lag of the tick: {@link #LAG_UNKNOWN}, or ticks and maybe {@link #LAG_ESTIMATED}. */
        void put(int tick, double px, double py, double pz, float aYaw, float aPitch,
                 float aHead, double hearts, int packed, int seen) {
            if (firstTick < 0) {
                firstTick = tick;
            }
            int at = tick - firstTick;
            if (at < 0) {
                return;
            }
            ensure(at + 1);
            carryTo(at);
            x[at] = (int) Math.round(px * SCALE);
            y[at] = (int) Math.round(py * SCALE);
            z[at] = (int) Math.round(pz * SCALE);
            yaw[at] = fine(aYaw);
            pitch[at] = fine(aPitch);
            head[at] = fine(aHead);
            flags[at] = packed;
            health[at] = (byte) Math.clamp(Math.round(hearts * 2), 0, 255);
            lag[at] = (short) seen;
            if ((seen & 0xFF) != LAG_UNKNOWN) lagged = true;
            length = Math.max(length, at + 1);
        }

        /** Marks every tick from the last one written up to this one as absent. */
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
                head[gap] = 0;
                flags[gap] = 0;
                health[gap] = 0;
                lag[gap] = LAG_UNKNOWN;
            }
            length = at;
        }

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
                head[gap] = head[gap - 1];
                flags[gap] = flags[gap - 1];
                health[gap] = health[gap - 1];
                lag[gap] = lag[gap - 1];
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
            head = Arrays.copyOf(head, grown);
            flags = Arrays.copyOf(flags, grown);
            health = Arrays.copyOf(health, grown);
            int old = lag.length;
            lag = Arrays.copyOf(lag, grown);
            Arrays.fill(lag, old, grown, (short) LAG_UNKNOWN);
        }

        boolean isEmpty() {
            return firstTick < 0 || length == 0;
        }

        int lastTick() {
            return firstTick < 0 ? 0 : firstTick + length;
        }

        /** The finished track, with trailing absent frames trimmed. */
        MotionTrack build() {
            int end = length;
            while (end > 0 && (flags[end - 1] & PRESENT) == 0) {
                end--;
            }
            int start = Math.max(0, firstTick);
            return new MotionTrack(start, Arrays.copyOf(x, end), Arrays.copyOf(y, end),
                    Arrays.copyOf(z, end), Arrays.copyOf(yaw, end), Arrays.copyOf(pitch, end),
                    Arrays.copyOf(head, end), Arrays.copyOf(flags, end),
                    Arrays.copyOf(health, end), lagged ? Arrays.copyOf(lag, end) : null);
        }
    }
}
