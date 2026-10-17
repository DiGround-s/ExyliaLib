package net.exylia.lib.schematic.internal;

import com.sk89q.jnbt.ByteArrayTag;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.IntArrayTag;
import com.sk89q.jnbt.IntTag;
import com.sk89q.jnbt.NBTInputStream;
import com.sk89q.jnbt.NBTOutputStream;
import com.sk89q.jnbt.NamedTag;
import com.sk89q.jnbt.ShortTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.jnbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in engine's file format, with no server.
 *
 * <p>The fixtures are laid out exactly as FastAsyncWorldEdit 2.8.4's
 * {@code FastSchematicWriter} lays its own out — root named {@code Schematic},
 * {@code Offset} as the box's absolute minimum, the paste offset in
 * {@code Metadata.WEOffset*} — and the cross-checks run FAWE's own NBT streams
 * from the jar, which is as far as FAWE runs without a platform behind it.
 * Those streams are deprecated for removal in FAWE's newer API, and 2.8.4's
 * reader is still built on them, which is the version this has to match.
 */
@SuppressWarnings({"removal", "deprecation"})
class SpongeSchematicTest {

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every tag type survives a round trip")
    void nbtRoundTrip() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("b", (byte) -3);
        root.put("s", (short) 300);
        root.put("i", 70_000);
        root.put("l", 1L << 40);
        root.put("f", 1.5f);
        root.put("d", 2.25);
        root.put("ba", new byte[]{1, 2, 3});
        root.put("str", "héllo");
        root.put("list", new Nbt.ListTag(Nbt.INT, List.of(1, 2, 3)));
        root.put("empty", new Nbt.ListTag(Nbt.END, List.of()));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("x", "y");
        root.put("compound", nested);
        root.put("ia", new int[]{4, 5});
        root.put("la", new long[]{6L, 7L});

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Nbt.write(new DataOutputStream(bytes), "root", root);
        Nbt.Root back = Nbt.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                1 << 20);

        assertEquals("root", back.name());
        Map<String, Object> value = back.value();
        assertEquals((byte) -3, value.get("b"));
        assertEquals((short) 300, value.get("s"));
        assertEquals(70_000, value.get("i"));
        assertEquals(1L << 40, value.get("l"));
        assertEquals(1.5f, value.get("f"));
        assertEquals(2.25, value.get("d"));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) value.get("ba"));
        assertEquals("héllo", value.get("str"));
        assertEquals(new Nbt.ListTag(Nbt.INT, List.of(1, 2, 3)), value.get("list"));
        assertEquals(new Nbt.ListTag(Nbt.END, List.of()), value.get("empty"));
        assertEquals(nested, value.get("compound"));
        assertArrayEquals(new int[]{4, 5}, (int[]) value.get("ia"));
        assertArrayEquals(new long[]{6L, 7L}, (long[]) value.get("la"));
        assertEquals(List.copyOf(root.keySet()), List.copyOf(value.keySet()), "order is kept");
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a version 2 file FAWE wrote reads back block for block")
    void readsVersionTwo() throws IOException {
        SpongeSchematic schematic = SpongeSchematic.read(new ByteArrayInputStream(gzip("Schematic",
                faweVersionTwo(new int[]{0, 0, 0}))));

        assertEquals(3, schematic.width());
        assertEquals(2, schematic.height());
        assertEquals(2, schematic.length());
        assertEquals(3953, schematic.dataVersion());
        assertEquals(0, schematic.offsetX(), "FaweEngine saves with origin = minimum");
        assertEquals("minecraft:stone", state(schematic, 0, 0, 0));
        assertEquals("minecraft:chest[facing=north,type=single,waterlogged=false]",
                state(schematic, 1, 0, 0));
        assertEquals("minecraft:air", state(schematic, 2, 1, 1));

        SpongeSchematic.BlockEntity chest = schematic.blockEntities().get(schematic.index(1, 0, 0));
        assertEquals("minecraft:chest", chest.id());
        assertTrue(chest.data().containsKey("Items"), "a v2 block entity is flattened");
        assertTrue(!chest.data().containsKey("Pos") && !chest.data().containsKey("Id"));

        SpongeSchematic.EntityEntry stand = schematic.entities().get(0);
        // Written in clipboard space (minimum 100,64,200), read relative.
        assertEquals(1.5, stand.x(), 1e-9);
        assertEquals(1.0, stand.y(), 1e-9);
        assertEquals(0.5, stand.z(), 1e-9);
    }

    @Test
    @DisplayName("a version 2 paste offset is WEOffset, never the absolute Offset tag")
    void versionTwoOffsetIsTheMetadata() throws IOException {
        SpongeSchematic schematic = SpongeSchematic.read(new ByteArrayInputStream(gzip("Schematic",
                faweVersionTwo(new int[]{-2, 1, 3}))));

        assertEquals(-2, schematic.offsetX());
        assertEquals(1, schematic.offsetY());
        assertEquals(3, schematic.offsetZ());
    }

    @Test
    @DisplayName("a version 3 file reads, with Offset as the paste offset and nested Data")
    void readsVersionThree() throws IOException {
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("Palette", palette("minecraft:air", "minecraft:glass"));
        blocks.put("Data", varints(1, 0, 0, 1));
        Map<String, Object> sign = new LinkedHashMap<>();
        sign.put("Pos", new int[]{1, 0, 0});
        sign.put("Id", "minecraft:sign");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("is_waxed", (byte) 1);
        sign.put("Data", data);
        blocks.put("BlockEntities", new Nbt.ListTag(Nbt.COMPOUND, List.of(sign)));
        Map<String, Object> schematic = new LinkedHashMap<>();
        schematic.put("Version", 3);
        schematic.put("DataVersion", 4189);
        schematic.put("Width", (short) 2);
        schematic.put("Height", (short) 1);
        schematic.put("Length", (short) 2);
        schematic.put("Offset", new int[]{5, -1, 7});
        schematic.put("Blocks", blocks);
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("Id", "minecraft:armor_stand");
        entity.put("Pos", new Nbt.ListTag(Nbt.DOUBLE, List.of(0.5, 0.0, 1.5)));
        entity.put("Data", new LinkedHashMap<>(Map.of("Invisible", (byte) 1)));
        schematic.put("Entities", new Nbt.ListTag(Nbt.COMPOUND, List.of(entity)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Schematic", schematic);

        SpongeSchematic read = SpongeSchematic.read(new ByteArrayInputStream(gzip("", root)));

        assertEquals(5, read.offsetX());
        assertEquals(-1, read.offsetY());
        assertEquals(7, read.offsetZ());
        assertEquals("minecraft:glass", state(read, 0, 0, 0));
        assertEquals("minecraft:air", state(read, 1, 0, 0));
        assertEquals("minecraft:glass", state(read, 1, 0, 1));
        assertEquals(data, read.blockEntities().get(read.index(1, 0, 0)).data());
        assertEquals(1.5, read.entities().get(0).z(), 1e-9, "v3 entities are already relative");
        assertEquals(Map.of("Invisible", (byte) 1), read.entities().get(0).data());
    }

    @Test
    @DisplayName("a palette past 32767 states keeps every id, through a read and a write")
    void largePalette() throws IOException {
        int states = 40_000;
        String[] names = new String[states];
        int[] blocks = new int[states];
        for (int i = 0; i < states; i++) {
            names[i] = "minecraft:test_" + i;
            blocks[i] = i;
        }
        Map<String, Object> root = versionTwo(200, 1, 200, palette(names), varints(blocks));

        SpongeSchematic read = SpongeSchematic.read(new ByteArrayInputStream(gzip("Schematic", root)));
        assertEquals("minecraft:test_39999", state(read, 199, 0, 199));
        assertEquals("minecraft:test_32768", state(read, 168, 0, 163));

        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
        read.write(rewritten);
        SpongeSchematic again = SpongeSchematic.read(new ByteArrayInputStream(rewritten.toByteArray()));
        for (int i = 0; i < states; i++) {
            assertEquals(i, again.stateAt(i));
        }
    }

    // ------------------------------------------------------------------
    // Refusing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bytes that are not a schematic are refused, not half-read")
    void refusesGarbage() {
        assertThrows(IOException.class, () -> SpongeSchematic.read(
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6})));
        byte[] valid = gzip("Schematic", faweVersionTwo(new int[3]));
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length / 2);
        assertThrows(IOException.class, () -> SpongeSchematic.read(new ByteArrayInputStream(truncated)));
    }

    @Test
    @DisplayName("a volume past the cap is refused before anything is allocated")
    void refusesHugeVolume() {
        Map<String, Object> root = versionTwo(0xFFFF, 0xFFFF, 0xFFFF, palette("minecraft:air"),
                new byte[]{0});
        IOException refused = assertThrows(IOException.class,
                () -> SpongeSchematic.read(new ByteArrayInputStream(gzip("Schematic", root))));
        assertTrue(refused.getMessage().contains("blocks"), refused.getMessage());
    }

    @Test
    @DisplayName("a block naming a palette id the palette lacks is refused")
    void refusesUnknownPaletteId() {
        Map<String, Object> root = versionTwo(2, 1, 1, palette("minecraft:air"), varints(0, 7));
        assertThrows(IOException.class,
                () -> SpongeSchematic.read(new ByteArrayInputStream(gzip("Schematic", root))));
    }

    @Test
    @DisplayName("block data shorter than the box is refused")
    void refusesShortData() {
        Map<String, Object> root = versionTwo(4, 4, 4, palette("minecraft:air"), varints(0, 0));
        assertThrows(IOException.class,
                () -> SpongeSchematic.read(new ByteArrayInputStream(gzip("Schematic", root))));
    }

    @Test
    @DisplayName("an array claiming two gigabytes is refused before it is allocated")
    void refusesLyingLength() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(Nbt.COMPOUND);
        out.writeUTF("Schematic");
        out.writeByte(Nbt.BYTE_ARRAY);
        out.writeUTF("BlockData");
        out.writeInt(Integer.MAX_VALUE);
        out.flush();
        assertThrows(IOException.class,
                () -> SpongeSchematic.read(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test
    @DisplayName("nesting deeper than vanilla allows is refused, not a stack overflow")
    void refusesDeepNesting() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(Nbt.COMPOUND);
        out.writeUTF("");
        for (int i = 0; i < 2_000; i++) {
            out.writeByte(Nbt.COMPOUND);
            out.writeUTF("n");
        }
        out.flush();
        IOException refused = assertThrows(IOException.class,
                () -> SpongeSchematic.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(refused.getMessage().contains("deeper"), refused.getMessage());
    }

    // ------------------------------------------------------------------
    // FAWE's own streams
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a file the built-in engine writes is what FAWE 2.8.4's reader looks for")
    void faweReadsWhatWeWrite() throws IOException {
        SpongeSchematic ours = SpongeSchematic.read(new ByteArrayInputStream(gzip("Schematic",
                faweVersionTwo(new int[3]))));
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        ours.write(written);

        try (NBTInputStream in = new NBTInputStream(
                new GZIPInputStream(new ByteArrayInputStream(written.toByteArray())))) {
            NamedTag root = in.readNamedTag();
            // FAWE's StreamDelegate is keyed by this name: a v3 nameless root
            // would not be read at all.
            assertEquals("Schematic", root.getName());
            Map<String, Tag> value = ((CompoundTag) root.getTag()).getValue();
            assertEquals(2, (int) ((IntTag) value.get("Version")).getValue());
            assertEquals((short) 3, ((ShortTag) value.get("Width")).getValue());
            assertArrayEquals(new int[]{100, 64, 200}, ((IntArrayTag) value.get("Offset")).getValue());
            Map<String, Tag> metadata = ((CompoundTag) value.get("Metadata")).getValue();
            assertEquals(0, (int) ((IntTag) metadata.get("WEOffsetX")).getValue());
            Map<String, Tag> palette = ((CompoundTag) value.get("Palette")).getValue();
            assertEquals(1, (int) ((IntTag) palette.get("minecraft:stone")).getValue());
            assertInstanceOf(ByteArrayTag.class, value.get("BlockData"));
            CompoundTag chest = (CompoundTag) ((com.sk89q.jnbt.ListTag) value.get("BlockEntities"))
                    .getValue().get(0);
            assertEquals("minecraft:chest", ((StringTag) chest.getValue().get("Id")).getValue());
            assertArrayEquals(new int[]{1, 0, 0}, ((IntArrayTag) chest.getValue().get("Pos")).getValue());
        }
    }

    @Test
    @DisplayName("a file written through FAWE's own stream reads back identically")
    void weReadWhatFaweWrites() throws IOException {
        // FastSchematicWriter's sequence of named tags, through FAWE's stream.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream out = new NBTOutputStream(new GZIPOutputStream(bytes))) {
            out.writeLazyCompoundTag("Schematic", schematic -> {
                schematic.writeNamedTag("DataVersion", 3953);
                schematic.writeNamedTag("Version", 2);
                schematic.writeNamedTag("Width", (short) 2);
                schematic.writeNamedTag("Height", (short) 1);
                schematic.writeNamedTag("Length", (short) 1);
                schematic.writeNamedTag("Offset", new int[]{10, 20, 30});
                schematic.writeLazyCompoundTag("Metadata", metadata -> {
                    metadata.writeNamedTag("WEOffsetX", 0);
                    metadata.writeNamedTag("WEOffsetY", 0);
                    metadata.writeNamedTag("WEOffsetZ", 0);
                    metadata.writeNamedTag("FAWEVersion", 1);
                });
                schematic.writeNamedTag("PaletteMax", 2);
                schematic.writeLazyCompoundTag("Palette", palette -> {
                    palette.writeNamedTag("minecraft:air", 0);
                    palette.writeNamedTag("minecraft:oak_planks", 1);
                });
                schematic.writeNamedTag("BlockData", new byte[]{1, 0});
                schematic.writeNamedEmptyList("BlockEntities");
                schematic.writeNamedEmptyList("Entities");
            });
        }

        SpongeSchematic read = SpongeSchematic.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("minecraft:oak_planks", state(read, 0, 0, 0));
        assertEquals("minecraft:air", state(read, 1, 0, 0));
        assertEquals(0, read.offsetX());
        assertTrue(read.blockEntities().isEmpty());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A 3×2×2 box saved at minimum (100,64,200): stone, a chest, air above,
     * and an armour stand.
     */
    private static Map<String, Object> faweVersionTwo(int[] weOffset) {
        Map<String, Object> root = versionTwo(3, 2, 2,
                palette("minecraft:air", "minecraft:stone",
                        "minecraft:chest[facing=north,type=single,waterlogged=false]"),
                varints(1, 2, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0));
        root.put("Offset", new int[]{100, 64, 200});
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("WEOffsetX", weOffset[0]);
        metadata.put("WEOffsetY", weOffset[1]);
        metadata.put("WEOffsetZ", weOffset[2]);
        metadata.put("FAWEVersion", 1);
        root.put("Metadata", metadata);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("Slot", (byte) 4);
        item.put("id", "minecraft:diamond_sword");
        item.put("count", 1);
        Map<String, Object> chest = new LinkedHashMap<>();
        chest.put("Pos", new int[]{1, 0, 0});
        chest.put("Id", "minecraft:chest");
        chest.put("Items", new Nbt.ListTag(Nbt.COMPOUND, List.of(item)));
        root.put("BlockEntities", new Nbt.ListTag(Nbt.COMPOUND, List.of(chest)));

        Map<String, Object> stand = new LinkedHashMap<>();
        stand.put("Id", "minecraft:armor_stand");
        stand.put("Pos", new Nbt.ListTag(Nbt.DOUBLE, List.of(101.5, 65.0, 200.5)));
        root.put("Entities", new Nbt.ListTag(Nbt.COMPOUND, List.of(stand)));
        return root;
    }

    private static Map<String, Object> versionTwo(int width, int height, int length,
                                                  Map<String, Object> palette, byte[] data) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 2);
        root.put("DataVersion", 3953);
        root.put("Width", (short) width);
        root.put("Height", (short) height);
        root.put("Length", (short) length);
        root.put("PaletteMax", palette.size());
        root.put("Palette", palette);
        root.put("BlockData", data);
        return root;
    }

    private static Map<String, Object> palette(String... states) {
        Map<String, Object> palette = new LinkedHashMap<>();
        for (int i = 0; i < states.length; i++) {
            palette.put(states[i], i);
        }
        return palette;
    }

    private static byte[] varints(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int value : values) {
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
        }
        return out.toByteArray();
    }

    private static byte[] gzip(String name, Map<String, Object> root) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                Nbt.write(out, name, root);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String state(SpongeSchematic schematic, int x, int y, int z) {
        return schematic.palette()[schematic.stateAt(schematic.index(x, y, z))];
    }
}
