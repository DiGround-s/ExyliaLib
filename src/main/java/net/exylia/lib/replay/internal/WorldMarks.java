package net.exylia.lib.replay.internal;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * What a block change and an explosion carry, as bytes.
 *
 * <p>Written and read only here, so a plugin records one by calling
 * {@link net.exylia.lib.replay.ReplayRecorder#block} and never by packing a
 * payload. That is the same bargain the equipment mark makes, and for the same
 * reason: the shape is the module's to change.
 *
 * <h2>Whole blocks, not thousandths</h2>
 * A block change is at a block, so its position is three plain integers
 * relative to the anchor rather than the thousandths a movement frame uses. An
 * arena is tens of blocks across, so each one is a byte or two once zig-zagged.
 */
@ApiStatus.Internal
public final class WorldMarks {

    /** Explosion power, stored in tenths so it fits a byte. */
    private static final float POWER_SCALE = 10f;

    private WorldMarks() {
    }

    /** Packs a block change, relative to the anchor. */
    public static byte[] block(Location anchor, Location at, @Nullable BlockData became) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        writeVarInt(out, zigzag(at.getBlockX() - anchor.getBlockX()));
        writeVarInt(out, zigzag(at.getBlockY() - anchor.getBlockY()));
        writeVarInt(out, zigzag(at.getBlockZ() - anchor.getBlockZ()));
        // Air is the empty string rather than "minecraft:air", because a break
        // is the commonest change there is and three bytes each adds up over a
        // match somebody spent digging.
        byte[] data = became == null || became.getMaterial() == Material.AIR
                ? new byte[0]
                : became.getAsString().getBytes(StandardCharsets.UTF_8);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    /** Where a block change happened, in the world a playback is running in. */
    public static @Nullable Location blockAt(Location anchor, byte[] data) {
        if (data == null || data.length == 0) return null;
        Cursor cursor = new Cursor(data);
        int x = unzigzag(cursor.varInt());
        int y = unzigzag(cursor.varInt());
        int z = unzigzag(cursor.varInt());
        if (cursor.broken) return null;
        return new Location(anchor.getWorld(), anchor.getBlockX() + x, anchor.getBlockY() + y,
                anchor.getBlockZ() + z);
    }

    /** What it became, or air when the block was taken away. */
    public static BlockData blockData(byte[] data) {
        if (data == null || data.length == 0) return Material.AIR.createBlockData();
        Cursor cursor = new Cursor(data);
        cursor.varInt();
        cursor.varInt();
        cursor.varInt();
        if (cursor.broken || cursor.at >= data.length) return Material.AIR.createBlockData();
        String written = new String(data, cursor.at, data.length - cursor.at,
                StandardCharsets.UTF_8);
        try {
            return org.bukkit.Bukkit.createBlockData(written);
        } catch (IllegalArgumentException gone) {
            // A block this server no longer has, or a recording from a newer
            // version. The rest of the replay is still worth watching.
            return Material.AIR.createBlockData();
        }
    }

    /** Packs an explosion, relative to the anchor. */
    public static byte[] explosion(Location anchor, Location at, float power) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        writeVarInt(out, zigzag((int) Math.round((at.getX() - anchor.getX()) * MotionTrack.SCALE)));
        writeVarInt(out, zigzag((int) Math.round((at.getY() - anchor.getY()) * MotionTrack.SCALE)));
        writeVarInt(out, zigzag((int) Math.round((at.getZ() - anchor.getZ()) * MotionTrack.SCALE)));
        out.write((byte) Math.clamp(Math.round(power * POWER_SCALE), 0, 255));
        return out.toByteArray();
    }

    /** Where an explosion went off, in the world a playback is running in. */
    public static @Nullable Location explosionAt(Location anchor, byte[] data) {
        if (data == null || data.length == 0) return null;
        Cursor cursor = new Cursor(data);
        double x = unzigzag(cursor.varInt()) / MotionTrack.SCALE;
        double y = unzigzag(cursor.varInt()) / MotionTrack.SCALE;
        double z = unzigzag(cursor.varInt()) / MotionTrack.SCALE;
        if (cursor.broken) return null;
        return anchor.clone().add(x, y, z);
    }

    /** How big it was. */
    public static float explosionPower(byte[] data) {
        if (data == null || data.length == 0) return 0f;
        Cursor cursor = new Cursor(data);
        cursor.varInt();
        cursor.varInt();
        cursor.varInt();
        if (cursor.broken || cursor.at >= data.length) return 0f;
        return (data[cursor.at] & 0xFF) / POWER_SCALE;
    }

    /** A position in a byte array, and whether reading it ran off the end. */
    private static final class Cursor {

        private final byte[] data;
        private int at;
        private boolean broken;

        Cursor(byte[] data) {
            this.data = data;
        }

        int varInt() {
            int value = 0;
            int shift = 0;
            while (at < data.length) {
                byte part = data[at++];
                value |= (part & 0x7F) << shift;
                if ((part & 0x80) == 0) return value;
                shift += 7;
                if (shift > 35) break;
            }
            broken = true;
            return 0;
        }
    }

    private static int zigzag(int value) {
        return (value << 1) ^ (value >> 31);
    }

    private static int unzigzag(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int rest = value;
        while ((rest & 0xFFFFFF80) != 0) {
            out.write((rest & 0x7F) | 0x80);
            rest >>>= 7;
        }
        out.write(rest);
    }
}
