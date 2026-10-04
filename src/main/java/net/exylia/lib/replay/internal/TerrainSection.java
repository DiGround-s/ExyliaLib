package net.exylia.lib.replay.internal;

import org.jetbrains.annotations.ApiStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Sixteen by sixteen by sixteen blocks of the ground a recording happened on.
 *
 * <p>Stored the way the game stores a section: a palette of the block states it
 * holds, and one index into it per block. A section of solid stone or of air is
 * a palette of one and nothing else, which is most of any world.
 *
 * @param scene   which scene of the recording it belongs to
 * @param chunkX  chunks east of the chunk the scene's anchor is in
 * @param chunkZ  chunks south of it
 * @param sectionY which section, counted the way the game counts them
 *                 ({@code blockY >> 4}), so the ground keeps its real height
 * @param palette block states, by their full string
 * @param indices one per block, {@code (y << 8) | (z << 4) | x}; {@code null}
 *                when the palette has a single entry
 */
@ApiStatus.Internal
public record TerrainSection(int scene, int chunkX, int chunkZ, int sectionY,
                             String[] palette, short[] indices) {

    /** Blocks in a section. */
    static final int VOLUME = 4096;

    /** The state of one block, by its coordinates inside the section. */
    public String at(int x, int y, int z) {
        return indices == null ? palette[0] : palette[indices[(y << 8) | (z << 4) | x]];
    }

    /** Whether every block in it is air. */
    public boolean isEmpty() {
        return indices == null && palette[0].equals("minecraft:air");
    }

    /** The content alone, without where it is: what the key is made from. */
    byte[] encode() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(indices == null ? 32 : VOLUME + 256);
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeShort(palette.length);
            for (String state : palette) out.writeUTF(state);
            if (indices != null) {
                boolean wide = palette.length > 256;
                for (short index : indices) {
                    if (wide) out.writeShort(index);
                    else out.writeByte(index);
                }
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return raw.toByteArray();
    }

    /** Puts content read back from {@link #encode} where it belongs. */
    static TerrainSection decode(int scene, int chunkX, int chunkZ, int sectionY, byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int size = in.readUnsignedShort();
            String[] palette = new String[size];
            for (int index = 0; index < size; index++) palette[index] = in.readUTF();
            short[] indices = null;
            if (size > 1) {
                indices = new short[VOLUME];
                boolean wide = size > 256;
                for (int index = 0; index < VOLUME; index++) {
                    indices[index] = wide ? in.readShort() : (short) in.readUnsignedByte();
                }
            }
            return new TerrainSection(scene, chunkX, chunkZ, sectionY, palette, indices);
        } catch (IOException broken) {
            throw new IllegalArgumentException("A piece of terrain could not be read", broken);
        }
    }

    /** The key a piece of content is stored under: its SHA-1, in hex. */
    static String keyOf(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * A section that is still being changed: read from a chunk, then walked
     * back through every change made to it since the moment wanted.
     */
    static final class Builder {

        private final List<String> palette = new ArrayList<>();
        private final Map<String, Short> lookup = new HashMap<>();
        private final short[] indices = new short[VOLUME];

        Builder(String fill) {
            index(fill);
        }

        String get(int x, int y, int z) {
            return palette.get(indices[(y << 8) | (z << 4) | x]);
        }

        void set(int x, int y, int z, String state) {
            indices[(y << 8) | (z << 4) | x] = index(state);
        }

        private short index(String state) {
            Short known = lookup.get(state);
            if (known != null) return known;
            short added = (short) palette.size();
            palette.add(state);
            lookup.put(state, added);
            return added;
        }

        /** The finished section, with the palette trimmed to what is used. */
        TerrainSection build(int scene, int chunkX, int chunkZ, int sectionY) {
            int[] remap = new int[palette.size()];
            java.util.Arrays.fill(remap, -1);
            List<String> used = new ArrayList<>();
            for (short index : indices) {
                if (remap[index] < 0) {
                    remap[index] = used.size();
                    used.add(palette.get(index));
                }
            }
            if (used.size() == 1) {
                return new TerrainSection(scene, chunkX, chunkZ, sectionY,
                        new String[] {used.getFirst()}, null);
            }
            short[] packed = new short[VOLUME];
            for (int index = 0; index < VOLUME; index++) packed[index] = (short) remap[indices[index]];
            return new TerrainSection(scene, chunkX, chunkZ, sectionY,
                    used.toArray(String[]::new), packed);
        }
    }
}
