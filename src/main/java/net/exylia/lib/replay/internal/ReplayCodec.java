package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayChunks;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.replay.ReplayScene;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

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
 * Position is quantised to a thousandth of a block, which is finer than anything
 * a client can show and exactly what the track holds in memory, so the round
 * trip is lossless. Each tick is written as the difference from the tick before
 * it, zig-zagged and varint-encoded, so standing still costs one byte an axis
 * and walking costs two. The whole thing is gzipped, which is where the long
 * runs of identical flag and rotation bytes go.
 *
 * <h2>Seeking is not the file's problem</h2>
 * A whole recording is decoded into memory the moment it is read, and a seek is
 * then an array index. Keyframes would buy nothing.
 *
 * <h2>Formats</h2>
 * Format 3 (1.241.0) adds scenes, head yaw, a wider flag word, what non-player
 * actors look like, and the terrain. Format 2 (1.176.0) is still read: a duel
 * recorded before the upgrade plays back as it did, with the head following the
 * body and one scene.
 */
@ApiStatus.Internal
public final class ReplayCodec {

    /** "EXRP". */
    private static final int MAGIC = 0x45585250;
    private static final byte VERSION = 3;
    private static final byte LEGACY = 2;

    /** How a piece of terrain is carried. */
    private static final int INLINE = 0;
    private static final int STORED = 1;

    private ReplayCodec() {
    }

    public static byte[] write(Replay replay, @Nullable ReplayChunks chunks) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(16 * 1024);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeLong(replay.id().getMostSignificantBits());
            out.writeLong(replay.id().getLeastSignificantBits());
            out.writeLong(replay.createdAt());
            writeVarInt(out, replay.frames());

            List<ReplayScene> scenes = replay.scenes();
            writeVarInt(out, scenes.size());
            for (ReplayScene scene : scenes) {
                writeVarInt(out, scene.fromTick());
                writeOptional(out, scene.world());
                out.writeDouble(scene.x());
                out.writeDouble(scene.y());
                out.writeDouble(scene.z());
            }

            List<ReplayActor> actors = replay.actors();
            writeVarInt(out, actors.size());
            for (ReplayActor actor : actors) {
                out.writeLong(actor.id().getMostSignificantBits());
                out.writeLong(actor.id().getLeastSignificantBits());
                out.writeUTF(actor.name());
                writeOptional(out, actor.texture());
                writeOptional(out, actor.signature());
                writeOptional(out, actor.entityType());
                writeBytes(out, actor.appearance());
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
                // "nobody" is a single zero byte instead of sixteen.
                writeVarInt(out, indexOf(actors, mark.actor()) + 1);
                writeBytes(out, mark.data());
            }

            List<TerrainSection> terrain = replay.terrain();
            writeVarInt(out, terrain == null ? 0 : terrain.size());
            if (terrain != null) {
                for (TerrainSection section : terrain) {
                    writeVarInt(out, section.scene());
                    writeVarInt(out, zigzag(section.chunkX()));
                    writeVarInt(out, zigzag(section.chunkZ()));
                    writeVarInt(out, zigzag(section.sectionY()));
                    byte[] content = section.encode();
                    if (chunks == null) {
                        out.writeByte(INLINE);
                        writeBytes(out, content);
                    } else {
                        String key = TerrainSection.keyOf(content);
                        // Compressed on its own: the blob's gzip does not reach a
                        // piece that lives in somebody's table.
                        chunks.put(key, deflate(content));
                        out.writeByte(STORED);
                        out.writeUTF(key);
                    }
                }
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException("A recording could not be written", impossible);
        }
        return raw.toByteArray();
    }

    public static Replay read(byte[] bytes, @Nullable ReplayChunks chunks) {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("Not a recording");
            }
            byte version = in.readByte();
            if (version != VERSION && version != LEGACY) {
                throw new IllegalArgumentException("This recording is in format " + version
                        + " and this ExyliaLib reads formats " + LEGACY + " and " + VERSION);
            }
            boolean legacy = version == LEGACY;
            UUID id = new UUID(in.readLong(), in.readLong());
            long createdAt = in.readLong();
            int frames = readVarInt(in);

            List<ReplayScene> scenes = new ArrayList<>();
            if (!legacy) {
                int sceneCount = readVarInt(in);
                for (int index = 0; index < sceneCount; index++) {
                    scenes.add(new ReplayScene(readVarInt(in), readOptional(in),
                            in.readDouble(), in.readDouble(), in.readDouble()));
                }
            }

            int actorCount = readVarInt(in);
            List<ReplayActor> actors = new ArrayList<>(actorCount);
            for (int index = 0; index < actorCount; index++) {
                UUID who = new UUID(in.readLong(), in.readLong());
                String name = in.readUTF();
                String texture = readOptional(in);
                String signature = readOptional(in);
                String type = readOptional(in);
                byte[] appearance = legacy ? null : readBytes(in);
                actors.add(new ReplayActor(who, name, texture, signature, type, appearance));
            }
            List<MotionTrack> tracks = new ArrayList<>(actorCount);
            for (int index = 0; index < actorCount; index++) {
                tracks.add(legacy ? readLegacyTrack(in) : readTrack(in));
            }

            int markCount = readVarInt(in);
            List<ReplayMark> marks = new ArrayList<>(markCount);
            for (int index = 0; index < markCount; index++) {
                int tick = readVarInt(in);
                String kind = in.readUTF();
                int actor = readVarInt(in) - 1;
                byte[] data = readBytes(in);
                marks.add(new ReplayMark(tick, kind,
                        actor < 0 || actor >= actors.size() ? null : actors.get(actor).id(), data));
            }

            List<TerrainSection> terrain = null;
            if (!legacy) {
                int sectionCount = readVarInt(in);
                if (sectionCount > 0) terrain = new ArrayList<>(sectionCount);
                for (int index = 0; index < sectionCount; index++) {
                    int scene = readVarInt(in);
                    int chunkX = unzigzag(readVarInt(in));
                    int chunkZ = unzigzag(readVarInt(in));
                    int sectionY = unzigzag(readVarInt(in));
                    int carried = in.readByte();
                    byte[] content;
                    if (carried == INLINE) {
                        content = readBytes(in);
                    } else {
                        String key = in.readUTF();
                        content = chunks == null ? null : inflate(chunks.get(key));
                    }
                    // A piece the store no longer has is a hole in the ground,
                    // not a recording that cannot be watched.
                    if (content != null) {
                        terrain.add(TerrainSection.decode(scene, chunkX, chunkZ, sectionY, content));
                    }
                }
            }
            return new Replay(id, createdAt, frames, actors, tracks, marks, scenes, terrain);
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
            out.writeByte(track.head[tick]);
            writeVarInt(out, track.flags[tick]);
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
        byte[] head = new byte[frames];
        int[] flags = new int[frames];
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
            head[tick] = in.readByte();
            flags[tick] = readVarInt(in);
            health[tick] = in.readByte();
        }
        return new MotionTrack(firstTick, x, y, z, yaw, pitch, head, flags, health);
    }

    /**
     * Format 2's track: no head yaw, and a one-byte flag word whose low three
     * bits were the old five poses.
     */
    private static MotionTrack readLegacyTrack(DataInputStream in) throws IOException {
        int firstTick = readVarInt(in);
        int frames = readVarInt(in);
        int[] x = new int[frames];
        int[] y = new int[frames];
        int[] z = new int[frames];
        byte[] yaw = new byte[frames];
        byte[] pitch = new byte[frames];
        int[] flags = new int[frames];
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
            flags[tick] = legacyFlags(in.readByte());
            health[tick] = in.readByte();
        }
        return new MotionTrack(firstTick, x, y, z, yaw, pitch, yaw.clone(), flags, health);
    }

    /** Format 2's flag byte, in format 3's word. */
    static int legacyFlags(byte old) {
        String pose = switch (old & 0x07) {
            case 1 -> "SLEEPING";
            case 2 -> "SWIMMING";
            case 3 -> "CROUCHING";
            case 4 -> "SPIN_ATTACK";
            default -> "STANDING";
        };
        return MotionTrack.flagsOf(MotionTrack.poseIndex(pose), (old & 0x08) != 0,
                (old & 0x10) != 0, (old & 0x40) != 0, false, false, false, false,
                (old & 0x20) != 0);
    }

    private static byte[] deflate(byte[] raw) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 16);
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte @Nullable [] inflate(byte @Nullable [] packed) {
        if (packed == null) return null;
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(packed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(packed.length * 4 + 16);
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buffer, 0, read);
            }
            // Cut short: a hole in the ground rather than half a section.
            return inflater.finished() ? out.toByteArray() : null;
        } catch (java.util.zip.DataFormatException broken) {
            // A damaged piece is a hole in the ground, like a missing one.
            return null;
        } finally {
            inflater.end();
        }
    }

    private static int indexOf(List<ReplayActor> actors, UUID actor) {
        if (actor == null) return -1;
        for (int index = 0; index < actors.size(); index++) {
            if (actors.get(index).id().equals(actor)) return index;
        }
        return -1;
    }

    private static void writeBytes(DataOutputStream out, byte @Nullable [] data) throws IOException {
        writeVarInt(out, data == null ? 0 : data.length + 1);
        if (data != null) out.write(data);
    }

    private static byte @Nullable [] readBytes(DataInputStream in) throws IOException {
        int length = readVarInt(in) - 1;
        if (length < 0) return null;
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    private static void writeOptional(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    private static String readOptional(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    static int zigzag(int value) {
        return (value << 1) ^ (value >> 31);
    }

    static int unzigzag(int value) {
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
            if ((part & 0x80) == 0) return value;
            shift += 7;
            if (shift > 35) throw new IOException("A number in the recording never ended");
        }
    }
}
