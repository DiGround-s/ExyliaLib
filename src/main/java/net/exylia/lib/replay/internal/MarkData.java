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

    private MarkData() {
    }

    /** Who was hit, and how. */
    public static byte[] attack(UUID victim, int how) {
        return ByteBuffer.allocate(17).putLong(victim.getMostSignificantBits())
                .putLong(victim.getLeastSignificantBits()).put((byte) how).array();
    }

    public static @Nullable UUID attackVictim(byte @Nullable [] data) {
        return data == null || data.length < 16 ? null : uuid(data);
    }

    public static int attackHow(byte @Nullable [] data) {
        return data == null || data.length < 17 ? 0 : data[16] & 0xFF;
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
