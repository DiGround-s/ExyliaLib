package net.exylia.lib.schematic.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A Sponge schematic in memory: the {@code .schem} files FastAsyncWorldEdit
 * reads and writes, read and written without it.
 *
 * <h2>Versions</h2>
 * Reads versions 1, 2 and 3. Writes version 2, because that is what
 * FastAsyncWorldEdit 2.8.4 writes ({@code BuiltInClipboardFormat.FAST} is
 * {@code FastSchematicWriter}, version 2) and what its reader expects: a root
 * compound <em>named</em> {@code Schematic}. A version 3 file — a nameless root
 * holding a {@code Schematic} compound — is something that FAWE does not
 * recognise, so writing one would make the built-in engine's arenas unreadable
 * the day FAWE is installed.
 *
 * <h2>Where a paste lands</h2>
 * Relative block {@code (0,0,0)} lands at the paste point plus
 * {@link #offsetX()}/{@code Y}/{@code Z}, which is exactly where FAWE's
 * {@code ClipboardHolder.createPaste(...).to(point)} puts it:
 * <ul>
 *   <li>version 2: the {@code Offset} tag is the box's absolute minimum and
 *   says nothing about the paste; the paste offset is
 *   {@code Metadata.WEOffsetX/Y/Z} (minimum minus clipboard origin), zero when
 *   absent. {@code FaweEngine} saves with origin = minimum, so its files carry
 *   zeros and land with their corner on the point.</li>
 *   <li>version 3: {@code Offset} <em>is</em> minimum minus origin.</li>
 * </ul>
 *
 * <h2>Untrusted input</h2>
 * Sizes are bounds-checked before anything is allocated, the volume is capped
 * at {@link #MAX_VOLUME}, the NBT at {@link #MAX_NBT_BYTES}, and every palette
 * index is checked against the palette. A corrupt or crafted file is an
 * {@link IOException}, never an out-of-memory error or a block from the wrong
 * palette slot.
 */
final class SpongeSchematic {

    /**
     * The most blocks one schematic may hold: 2<sup>26</sup>, a 512×256×512
     * box. Past that the built-in engine refuses rather than holding hundreds
     * of megabytes; FastAsyncWorldEdit, with its disk-backed clipboards, is the
     * answer for builds that size.
     */
    static final long MAX_VOLUME = 1L << 26;

    /** The most decoded NBT one file may produce. */
    static final long MAX_NBT_BYTES = 256L << 20;

    /** Palette ids past this are a crafted file, not a build. */
    static final int MAX_PALETTE = 1 << 22;

    /** The version {@link #write} produces. */
    static final int WRITE_VERSION = 2;

    static final String AIR = "minecraft:air";

    private final int width;
    private final int height;
    private final int length;
    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;
    private final int[] recordedMin;
    private final int dataVersion;
    private final String[] palette;
    private final Indices blocks;
    private final Map<Integer, BlockEntity> blockEntities;
    private final List<EntityEntry> entities;

    /**
     * A block entity: the vanilla id and its data, without position or id.
     *
     * @param id   the block entity id, such as {@code minecraft:chest}
     * @param data everything else, as vanilla wrote it
     */
    record BlockEntity(@NotNull String id, @NotNull Map<String, Object> data) {
    }

    /**
     * A loose entity, positioned relative to the box's minimum corner.
     *
     * @param id   the entity type id
     * @param x    relative x, fractional
     * @param y    relative y
     * @param z    relative z
     * @param data everything else, without id, position or UUID
     */
    record EntityEntry(@NotNull String id, double x, double y, double z,
                       @NotNull Map<String, Object> data) {
    }

    SpongeSchematic(int width, int height, int length, int offsetX, int offsetY, int offsetZ,
                    int[] recordedMin, int dataVersion, String[] palette, Indices blocks,
                    Map<Integer, BlockEntity> blockEntities, List<EntityEntry> entities) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.recordedMin = recordedMin;
        this.dataVersion = dataVersion;
        this.palette = palette;
        this.blocks = blocks;
        this.blockEntities = blockEntities;
        this.entities = entities;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int length() {
        return length;
    }

    /** Where relative x 0 lands, relative to the paste point. */
    int offsetX() {
        return offsetX;
    }

    int offsetY() {
        return offsetY;
    }

    int offsetZ() {
        return offsetZ;
    }

    /** The data version the file was written by, or {@code -1} when it does not say. */
    int dataVersion() {
        return dataVersion;
    }

    /** Palette strings by id; an id no block uses may be {@code null}. */
    String[] palette() {
        return palette;
    }

    /** Block entities by block index. */
    Map<Integer, BlockEntity> blockEntities() {
        return blockEntities;
    }

    List<EntityEntry> entities() {
        return entities;
    }

    /** Sponge order: x fastest, then z, then y. */
    int index(int x, int y, int z) {
        return (y * length + z) * width + x;
    }

    /** The palette id of a block. */
    int stateAt(int index) {
        return blocks.get(index);
    }

    /** Roughly what this holds in memory, for the cache's weigher. */
    long bytes() {
        return blocks.bytes() + 64L * palette.length
                + 256L * blockEntities.size() + 256L * entities.size();
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Reads a schematic, gzipped or not.
     *
     * @param raw the file's bytes; not closed
     * @return the schematic
     * @throws IOException when it is not a Sponge schematic this can read, or
     *                     is larger than the built-in engine accepts
     */
    static @NotNull SpongeSchematic read(@NotNull InputStream raw) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(raw);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        InputStream in = first == 0x1f && second == 0x8b ? new GZIPInputStream(buffered) : buffered;
        Map<String, Object> root = Nbt.read(new DataInputStream(in), MAX_NBT_BYTES).value();
        return parse(root);
    }

    static @NotNull SpongeSchematic parse(@NotNull Map<String, Object> root) throws IOException {
        // Version 3 nests everything one level down; 1 and 2 are the root.
        Map<String, Object> schematic = compound(root, "Schematic");
        if (schematic == null) {
            schematic = root;
        }
        int version = (int) number(schematic, "Version", -1);
        if (version < 1 || version > 3) {
            throw new IOException("unsupported Sponge schematic version " + version);
        }
        int width = dimension(schematic, "Width");
        int height = dimension(schematic, "Height");
        int length = dimension(schematic, "Length");
        long volume = (long) width * height * length;
        if (volume > MAX_VOLUME) {
            throw new IOException("the schematic holds " + volume + " blocks, more than the "
                    + MAX_VOLUME + " the built-in engine accepts");
        }
        int dataVersion = (int) number(schematic, "DataVersion", -1);
        int[] offsetTag = schematic.get("Offset") instanceof int[] ints && ints.length == 3
                ? ints : new int[3];

        int offsetX;
        int offsetY;
        int offsetZ;
        Map<String, Object> blockContainer;
        String dataKey;
        if (version == 3) {
            offsetX = offsetTag[0];
            offsetY = offsetTag[1];
            offsetZ = offsetTag[2];
            blockContainer = compound(schematic, "Blocks");
            if (blockContainer == null) {
                throw new IOException("the schematic has no Blocks");
            }
            dataKey = "Data";
        } else {
            Map<String, Object> metadata = compound(schematic, "Metadata");
            offsetX = metadata == null ? 0 : (int) number(metadata, "WEOffsetX", 0);
            offsetY = metadata == null ? 0 : (int) number(metadata, "WEOffsetY", 0);
            offsetZ = metadata == null ? 0 : (int) number(metadata, "WEOffsetZ", 0);
            blockContainer = schematic;
            dataKey = "BlockData";
        }

        String[] palette = palette(blockContainer);
        if (!(blockContainer.get(dataKey) instanceof byte[] data)) {
            throw new IOException("the schematic has no " + dataKey);
        }
        Indices blocks = decode(data, (int) volume, palette);

        Map<Integer, BlockEntity> blockEntities = new LinkedHashMap<>();
        Object listed = blockContainer.get("BlockEntities");
        if (listed == null) {
            listed = blockContainer.get("TileEntities");
        }
        for (Map<String, Object> entry : compounds(listed)) {
            if (!(entry.get("Pos") instanceof int[] pos) || pos.length != 3) {
                continue;
            }
            int x = pos[0];
            int y = pos[1];
            int z = pos[2];
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                continue;
            }
            String id = string(entry, "Id");
            if (id == null) {
                id = string(entry, "id");
            }
            if (id == null) {
                continue;
            }
            Map<String, Object> body;
            if (version == 3) {
                Map<String, Object> nested = compound(entry, "Data");
                body = nested == null ? new LinkedHashMap<>() : new LinkedHashMap<>(nested);
            } else {
                body = new LinkedHashMap<>(entry);
            }
            body.remove("Pos");
            body.remove("Id");
            body.remove("id");
            body.remove("x");
            body.remove("y");
            body.remove("z");
            blockEntities.put((y * length + z) * width + x, new BlockEntity(id, body));
        }

        List<EntityEntry> entities = new ArrayList<>();
        for (Map<String, Object> entry : compounds(schematic.get("Entities"))) {
            double[] pos = doubles(entry.get("Pos"));
            String id = string(entry, "Id");
            if (id == null) {
                id = string(entry, "id");
            }
            if (pos == null || id == null) {
                continue;
            }
            Map<String, Object> body;
            if (version == 3) {
                Map<String, Object> nested = compound(entry, "Data");
                body = nested == null ? new LinkedHashMap<>() : new LinkedHashMap<>(nested);
                body.remove("Pos");
            } else {
                body = new LinkedHashMap<>(entry);
                body.remove("Pos");
                // Version 2 keeps entities in clipboard space, whose minimum
                // is the Offset tag — FAWE's reader subtracts it the same way.
                pos[0] -= offsetTag[0];
                pos[1] -= offsetTag[1];
                pos[2] -= offsetTag[2];
            }
            body.remove("Id");
            body.remove("id");
            body.remove("UUID");
            entities.add(new EntityEntry(id, pos[0], pos[1], pos[2], body));
        }

        return new SpongeSchematic(width, height, length, offsetX, offsetY, offsetZ,
                offsetTag, dataVersion, palette, blocks, blockEntities, entities);
    }

    private static int dimension(Map<String, Object> schematic, String key) throws IOException {
        long value = number(schematic, key, -1);
        // Sponge stores these as shorts read unsigned.
        if (schematic.get(key) instanceof Short shortValue) {
            value = shortValue & 0xFFFF;
        }
        if (value < 1 || value > 0xFFFF) {
            throw new IOException("the schematic's " + key + " is " + value);
        }
        return (int) value;
    }

    private static String[] palette(Map<String, Object> container) throws IOException {
        Map<String, Object> entries = compound(container, "Palette");
        if (entries == null || entries.isEmpty()) {
            throw new IOException("the schematic has no Palette");
        }
        int highest = -1;
        for (Object id : entries.values()) {
            if (!(id instanceof Integer value) || value < 0 || value >= MAX_PALETTE) {
                throw new IOException("a palette id is out of range: " + id);
            }
            highest = Math.max(highest, value);
        }
        String[] palette = new String[highest + 1];
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            palette[(Integer) entry.getValue()] = entry.getKey();
        }
        return palette;
    }

    /**
     * Decodes the varint block array.
     *
     * <p>Every value is checked against the palette as it is read: an id with
     * no state behind it is a corrupt file, and pasting it as air would
     * silently carve a hole in the arena.
     */
    private static Indices decode(byte[] data, int volume, String[] palette) throws IOException {
        // Every block takes at least one byte, so a short array is refused
        // before the index array it would have to fill is allocated.
        if (data.length < volume) {
            throw new IOException("the block data holds " + data.length + " bytes for "
                    + volume + " blocks");
        }
        Indices blocks = Indices.of(palette.length, volume);
        int position = 0;
        for (int index = 0; index < volume; index++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (position >= data.length) {
                    throw new IOException("the block data ends after " + index + " of "
                            + volume + " blocks");
                }
                byte next = data[position++];
                value |= (next & 0x7F) << shift;
                if ((next & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("a block id is not a valid varint");
                }
            }
            if (value < 0 || value >= palette.length || palette[value] == null) {
                throw new IOException("block " + index + " names palette id " + value
                        + ", which the palette does not have");
            }
            blocks.set(index, value);
        }
        return blocks;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Writes this as a gzipped Sponge version 2 file, laid out the way
     * FastAsyncWorldEdit 2.8.4 lays its own out.
     *
     * @param raw where to write; closed when done
     * @throws IOException when the stream fails
     */
    void write(@NotNull OutputStream raw) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", WRITE_VERSION);
        root.put("DataVersion", dataVersion);
        root.put("Width", (short) width);
        root.put("Height", (short) height);
        root.put("Length", (short) length);
        root.put("Offset", recordedMin.clone());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("WEOffsetX", offsetX);
        metadata.put("WEOffsetY", offsetY);
        metadata.put("WEOffsetZ", offsetZ);
        root.put("Metadata", metadata);
        root.put("PaletteMax", palette.length);
        Map<String, Object> paletteTag = new LinkedHashMap<>();
        for (int id = 0; id < palette.length; id++) {
            if (palette[id] != null) {
                paletteTag.put(palette[id], id);
            }
        }
        root.put("Palette", paletteTag);
        root.put("BlockData", encode());

        List<Object> blockEntityTags = new ArrayList<>(blockEntities.size());
        for (Map.Entry<Integer, BlockEntity> entry : blockEntities.entrySet()) {
            int index = entry.getKey();
            int x = index % width;
            int z = (index / width) % length;
            int y = index / (width * length);
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("Pos", new int[]{x, y, z});
            tag.put("Id", entry.getValue().id());
            tag.putAll(entry.getValue().data());
            blockEntityTags.add(tag);
        }
        root.put("BlockEntities", new Nbt.ListTag(Nbt.COMPOUND, blockEntityTags));

        if (!entities.isEmpty()) {
            List<Object> entityTags = new ArrayList<>(entities.size());
            for (EntityEntry entity : entities) {
                Map<String, Object> tag = new LinkedHashMap<>(entity.data());
                tag.put("Id", entity.id());
                tag.put("Pos", new Nbt.ListTag(Nbt.DOUBLE, List.of(
                        entity.x() + recordedMin[0],
                        entity.y() + recordedMin[1],
                        entity.z() + recordedMin[2])));
                entityTags.add(tag);
            }
            root.put("Entities", new Nbt.ListTag(Nbt.COMPOUND, entityTags));
        }

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(raw)))) {
            Nbt.write(out, "Schematic", root);
        }
    }

    private byte[] encode() {
        int volume = width * height * length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(volume + 16);
        for (int index = 0; index < volume; index++) {
            int value = blocks.get(index);
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // Tag access
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static @Nullable Map<String, Object> compound(Map<String, Object> parent, String key) {
        return parent.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    static @Nullable String string(Map<String, Object> parent, String key) {
        return parent.get(key) instanceof String value ? value : null;
    }

    static long number(Map<String, Object> parent, String key, long fallback) {
        return parent.get(key) instanceof Number value ? value.longValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> compounds(Object list) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (list instanceof Nbt.ListTag tag) {
            for (Object item : tag.items()) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
        }
        return out;
    }

    private static double @Nullable [] doubles(Object list) {
        if (!(list instanceof Nbt.ListTag tag) || tag.items().size() != 3) {
            return null;
        }
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            if (!(tag.items().get(i) instanceof Number value)) {
                return null;
            }
            out[i] = value.doubleValue();
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    /**
     * Palette ids, one per block, as narrow as the palette allows.
     *
     * <p>A byte per block for the usual arena, a char past 256 states and an
     * int past 65536 — never a short, which is how ExyliaCommons truncated
     * every palette past 32767 states into the wrong blocks.
     */
    interface Indices {

        int get(int index);

        void set(int index, int value);

        long bytes();

        static Indices of(int paletteSize, int volume) {
            if (paletteSize <= 1 << 8) {
                byte[] values = new byte[volume];
                return new Indices() {
                    public int get(int index) { return values[index] & 0xFF; }
                    public void set(int index, int value) { values[index] = (byte) value; }
                    public long bytes() { return values.length; }
                };
            }
            if (paletteSize <= 1 << 16) {
                char[] values = new char[volume];
                return new Indices() {
                    public int get(int index) { return values[index]; }
                    public void set(int index, int value) { values[index] = (char) value; }
                    public long bytes() { return 2L * values.length; }
                };
            }
            int[] values = new int[volume];
            return new Indices() {
                public int get(int index) { return values[index]; }
                public void set(int index, int value) { values[index] = value; }
                public long bytes() { return 4L * values.length; }
            };
        }
    }
}
