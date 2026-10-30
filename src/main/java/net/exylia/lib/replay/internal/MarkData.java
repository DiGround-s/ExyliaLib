package net.exylia.lib.replay.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * The payloads of the marks the module writes and draws itself.
 *
 * <p>A plugin never builds or reads these: they are the module's own shape, kept
 * in one place so the recorder and the playback cannot disagree about it.
 */
@ApiStatus.Internal
public final class MarkData {

    /** An attack that landed as a critical. */
    public static final int CRIT = 1;
    /** An attack that was the sweep of a sword. */
    public static final int SWEEP = 2;
    /** A sprinting hit, which knocks back further. */
    public static final int KNOCKBACK = 4;
    /** A fully charged swing. */
    public static final int STRONG = 8;
    /** A hit taken on a raised shield. */
    public static final int BLOCKED = 16;
    /** A hit that did no damage at all. */
    public static final int NO_DAMAGE = 32;
    /**
     * An attack the client sent that the server never counted: no damage event
     * went with it (out of reach, invulnerable, cancelled). Nothing is drawn
     * for it but the swing that came with it.
     */
    public static final int UNCONFIRMED = 64;

    private MarkData() {
    }

    /** Who was hit, and how. */
    public static byte[] attack(UUID victim, int how) {
        return ByteBuffer.allocate(17).putLong(victim.getMostSignificantBits())
                .putLong(victim.getLeastSignificantBits()).put((byte) how).array();
    }

    /** Who was hit, how, and how much it took, {@code NaN} when not known. */
    public static byte[] attack(UUID victim, int how, float damage) {
        return new Hit(victim, how, damage, false, Float.NaN, Float.NaN, -1, false,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN).write();
    }

    public static @Nullable UUID attackVictim(byte @Nullable [] data) {
        return data == null || data.length < 16 ? null : uuid(data);
    }

    public static int attackHow(byte @Nullable [] data) {
        return data == null || data.length < 17 ? 0 : data[16] & 0xFF;
    }

    /**
     * An attack with everything known about it: the 17 bytes of
     * {@link #attack(UUID, int)} and then, from byte 17, what the attacker sent
     * and what the analysis made of it, so a reader of the old layout still
     * finds the victim and the flags where they were.
     *
     * @param sent        whether the rotation is the one the client sent with the attack
     *                    rather than the server's sample of the tick
     * @param seenLag     ticks behind the server the attacker's screen was, -1 unknown
     * @param reachServer eyes to the target's hitbox by the server's positions, {@code NaN} unknown
     * @param reachSeen   the same, with the target where the attacker's screen had it
     * @param angleOff    degrees between the look and the hitbox, 0 when the look goes through it
     * @param flick       the largest turn in the three ticks before, degrees
     */
    public record Hit(UUID victim, int how, float damage, boolean sent, float yaw, float pitch,
                      int seenLag, boolean estimated, float reachServer, float reachSeen,
                      float angleOff, float flick) {

        private static final int LENGTH = 48;
        private static final int SENT = 1;
        private static final int ESTIMATED = 2;

        public byte[] write() {
            return ByteBuffer.allocate(LENGTH).putLong(victim.getMostSignificantBits())
                    .putLong(victim.getLeastSignificantBits()).put((byte) how)
                    .put((byte) ((sent ? SENT : 0) | (estimated ? ESTIMATED : 0)))
                    .putFloat(yaw).putFloat(pitch).putShort((short) seenLag).putFloat(damage)
                    .putFloat(reachServer).putFloat(reachSeen).putFloat(angleOff).putFloat(flick)
                    .array();
        }

        /** What an attack mark says, whichever layout it was written in; {@code null} without a victim. */
        public static @Nullable Hit read(byte @Nullable [] data) {
            UUID victim = attackVictim(data);
            if (victim == null) return null;
            int how = attackHow(data);
            if (data.length < LENGTH) {
                return new Hit(victim, how, Float.NaN, false, Float.NaN, Float.NaN, -1, false,
                        Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            }
            ByteBuffer in = ByteBuffer.wrap(data, 17, LENGTH - 17);
            int info = in.get();
            float yaw = in.getFloat();
            float pitch = in.getFloat();
            int lag = in.getShort();
            float damage = in.getFloat();
            return new Hit(victim, how, damage, (info & SENT) != 0, yaw, pitch, lag, (info & ESTIMATED) != 0,
                    in.getFloat(), in.getFloat(), in.getFloat(), in.getFloat());
        }

        /** Whether the analysis was run on it: a mark of an older recording has none. */
        public boolean analysed() {
            return !Float.isNaN(reachServer);
        }
    }

    /**
     * An anticheat flag: how many times it went off in the second it covers,
     * then the check and the detail, each as a length and its UTF-8 bytes. A
     * detail of length -1 is none.
     */
    public static byte[] flag(String check, @Nullable String detail, int count) {
        byte[] name = check.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] more = detail == null ? null : detail.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(4 + 2 + name.length + 2 + (more == null ? 0 : more.length));
        out.putInt(count).putShort((short) name.length).put(name);
        out.putShort((short) (more == null ? -1 : more.length));
        if (more != null) out.put(more);
        return out.array();
    }

    /**
     * What {@link #flag} wrote: {@code {check, detail, count}} with the count as
     * text, or {@code null} for anything else.
     */
    public static String @Nullable [] flag(byte @Nullable [] data) {
        if (data == null || data.length < 8) return null;
        try {
            ByteBuffer in = ByteBuffer.wrap(data);
            int count = in.getInt();
            byte[] name = new byte[in.getShort()];
            in.get(name);
            int length = in.getShort();
            String detail = null;
            if (length >= 0) {
                byte[] more = new byte[length];
                in.get(more);
                detail = new String(more, java.nio.charset.StandardCharsets.UTF_8);
            }
            return new String[] {new String(name, java.nio.charset.StandardCharsets.UTF_8), detail,
                    Integer.toString(count)};
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** Which way a hit came from, relative to the body, as the game measures it. */
    public static byte[] hurt(float direction) {
        return new byte[] {MotionTrack.angle(direction)};
    }

    public static float hurtDirection(byte @Nullable [] data) {
        return data == null || data.length < 1 ? 0f : data[0] * 360f / 256f;
    }

    /** Another actor: a vehicle, an item being picked up. */
    public static byte[] other(UUID other, int amount) {
        return ByteBuffer.allocate(20).putLong(other.getMostSignificantBits())
                .putLong(other.getLeastSignificantBits()).putInt(amount).array();
    }

    public static @Nullable UUID other(byte @Nullable [] data) {
        return data == null || data.length < 16 ? null : uuid(data);
    }

    public static int amount(byte @Nullable [] data) {
        return data == null || data.length < 20 ? 1 : ByteBuffer.wrap(data, 16, 4).getInt();
    }

    private static UUID uuid(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, 16);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
