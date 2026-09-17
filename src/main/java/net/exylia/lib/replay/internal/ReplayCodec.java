package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayMark;
import org.jetbrains.annotations.ApiStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Turns a recording into bytes and back.
 *
 * <h2>Why it is small</h2>
 * Three things, in this order. Position is quantised to a thousandth of a
 * block, which is finer than anything a client can show and exactly what the
 * track holds in memory, so the round trip is lossless. Each tick is then
 * written as the difference from the tick before it, zig-zagged so that a step
 * backwards is as cheap as a step forwards, and varint-encoded so that standing
 * still costs one byte an axis and walking costs two. Finally the whole thing
 * is gzipped, which is where the long runs of identical flag and rotation bytes
 * go.
 *
 * <p>Together that is about twenty kilobytes for a three minute duel &mdash;
 * small enough that where to keep a recording stops being a question.
 *
 * <h2>Seeking is not the file's problem</h2>
 * Delta encoding usually costs you the ability to jump to the middle, and the
 * usual answer is keyframes and an index. There are none here, because a whole
 * recording is decoded into memory the moment it is read: a six minute duel is
 * a couple of hundred kilobytes of arrays, and a seek is then an array index
 * rather than anything the format has to support.
 */
@ApiStatus.Internal
public final class ReplayCodec {

    /** What a recording starts with, so a wrong blob fails loudly. */
    private static final int MAGIC = 0x45585250;

    /**
     * Bumped when the layout below changes in a way an old reader cannot follow.
     *
     * <p>Two added sparse tracks and actors that are not players. Nothing reads
     * a format-1 recording: the format was never on a live server, and carrying
     * a reader for a shape that no file was ever written in is a branch that
     * can only ever rot.
     */
    private static final byte VERSION = 2;

    private ReplayCodec() {
    }

    /** Writes a recording out, compressed. */
    public static byte[] write(Replay replay) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(16 * 1024);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeLong(replay.id().getMostSignificantBits());
            out.writeLong(replay.id().getLeastSignificantBits());
            out.writeLong(replay.createdAt());
            writeVarInt(out, replay.frames());

            List<ReplayActor> actors = replay.actors();
            writeVarInt(out, actors.size());
            for (ReplayActor actor : actors) {
                out.writeLong(actor.id().getMostSignificantBits());
                out.writeLong(actor.id().getLeastSignificantBits());
                out.writeUTF(actor.name());
                writeOptional(out, actor.texture());
                writeOptional(out, actor.signature());
                writeOptional(out, actor.entityType());
            }
            for (MotionTrack track : replay.tracks()) {
                writeTrack(out, track);
            }

            List<ReplayMark> marks = replay.marks();
            writeVarInt(out, marks.size());
            for (ReplayMark mark : marks) {
                writeVarInt(out, mark.tick());
                out.writeUTF(mark.kind());
                // The index rather than the UUID, and one more than it, so that
                // "nobody" is a single zero byte instead of sixteen wasted ones.
                writeVarInt(out, indexOf(actors, mark.actor()) + 1);
                byte[] data = mark.data();
                writeVarInt(out, data == null ? 0 : data.length + 1);
                if (data != null) {
                    out.write(data);
                }
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException("A recording could not be written", impossible);
        }
        return raw.toByteArray();
    }

    /** Reads one back. */
    public static Replay read(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("Not a recording");
            }
            byte version = in.readByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("This recording is in format " + version
                        + " and this ExyliaLib reads format " + VERSION);
            }
            UUID id = new UUID(in.readLong(), in.readLong());
            long createdAt = in.readLong();
            int frames = readVarInt(in);

            int actorCount = readVarInt(in);
            List<ReplayActor> actors = new ArrayList<>(actorCount);
            for (int index = 0; index < actorCount; index++) {
                UUID who = new UUID(in.readLong(), in.readLong());
                actors.add(new ReplayActor(who, in.readUTF(), readOptional(in),
                        readOptional(in), readOptional(in)));
            }
            List<MotionTrack> tracks = new ArrayList<>(actorCount);
            for (int index = 0; index < actorCount; index++) {
                tracks.add(readTrack(in));
            }

            int markCount = readVarInt(in);
            List<ReplayMark> marks = new ArrayList<>(markCount);
            for (int index = 0; index < markCount; index++) {
                int tick = readVarInt(in);
                String kind = in.readUTF();
                int actor = readVarInt(in) - 1;
                int length = readVarInt(in) - 1;
                byte[] data = null;
                if (length >= 0) {
                    data = new byte[length];
                    in.readFully(data);
                }
                marks.add(new ReplayMark(tick, kind,
                        actor < 0 || actor >= actors.size() ? null : actors.get(actor).id(),
                        data));
            }
            return new Replay(id, createdAt, frames, actors, tracks, marks);
        } catch (IOException broken) {
            throw new IllegalArgumentException("A recording could not be read", broken);
        }
    }

    private static void writeTrack(DataOutputStream out, MotionTrack track) throws IOException {
        writeVarInt(out, track.firstTick());
        writeVarInt(out, track.length());
        int lastX = 0;
        int lastY = 0;
        int lastZ = 0;
        for (int tick = 0; tick < track.length(); tick++) {
            writeVarInt(out, zigzag(track.x[tick] - lastX));
            writeVarInt(out, zigzag(track.y[tick] - lastY));
            writeVarInt(out, zigzag(track.z[tick] - lastZ));
            lastX = track.x[tick];
            lastY = track.y[tick];
            lastZ = track.z[tick];
            out.writeByte(track.yaw[tick]);
            out.writeByte(track.pitch[tick]);
            out.writeByte(track.flags[tick]);
            out.writeByte(track.health[tick]);
        }
    }

    private static MotionTrack readTrack(DataInputStream in) throws IOException {
        int firstTick = readVarInt(in);
        int frames = readVarInt(in);
        int[] x = new int[frames];
        int[] y = new int[frames];
        int[] z = new int[frames];
        byte[] yaw = new byte[frames];
        byte[] pitch = new byte[frames];
        byte[] flags = new byte[frames];
        byte[] health = new byte[frames];
        int lastX = 0;
        int lastY = 0;
        int lastZ = 0;
        for (int tick = 0; tick < frames; tick++) {
            lastX += unzigzag(readVarInt(in));
            lastY += unzigzag(readVarInt(in));
            lastZ += unzigzag(readVarInt(in));
            x[tick] = lastX;
            y[tick] = lastY;
            z[tick] = lastZ;
            yaw[tick] = in.readByte();
            pitch[tick] = in.readByte();
            flags[tick] = in.readByte();
            health[tick] = in.readByte();
        }
        return new MotionTrack(firstTick, x, y, z, yaw, pitch, flags, health);
    }

    private static int indexOf(List<ReplayActor> actors, UUID actor) {
        if (actor == null) {
            return -1;
        }
        for (int index = 0; index < actors.size(); index++) {
            if (actors.get(index).id().equals(actor)) {
                return index;
            }
        }
        return -1;
    }

    private static void writeOptional(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeUTF(value);
        }
    }

    private static String readOptional(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    /** Maps a signed number onto an unsigned one small numbers stay small in. */
    private static int zigzag(int value) {
        return (value << 1) ^ (value >> 31);
    }

    private static int unzigzag(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int rest = value;
        while ((rest & 0xFFFFFF80) != 0) {
            out.writeByte((rest & 0x7F) | 0x80);
            rest >>>= 7;
        }
        out.writeByte(rest);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            byte part = in.readByte();
            value |= (part & 0x7F) << shift;
            if ((part & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 35) {
                throw new IOException("A number in the recording never ended");
            }
        }
    }
}
