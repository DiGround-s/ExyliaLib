package net.exylia.lib.schematic.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.exylia.lib.FakeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in engine's decisions, with no server: which blocks a chunk has to
 * write, how the writes are spread over ticks, and which engine is bound.
 *
 * <p>What is left untested here is the part that is only Bukkit calls — taking
 * a snapshot, setting a block — and it is kept thin on purpose.
 */
class NativeEngineTest {

    @AfterEach
    void tearDown() {
        Engines.unbind();
    }

    // ------------------------------------------------------------------
    // Diff
    // ------------------------------------------------------------------

    @Test
    @DisplayName("only blocks that differ are written, air included")
    void diffKeepsOnlyChanges() {
        // 3×2×3 of stone with a dirt block in the middle of the bottom layer;
        // the top layer is air.
        SpongeSchematic schematic = box(3, 2, 3,
                (x, y, z) -> y == 1 ? 0 : (x == 1 && z == 1 ? 2 : 1),
                "minecraft:air", "minecraft:stone", "minecraft:dirt");
        Map<String, String> world = new HashMap<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                world.put(x + ":64:" + z, "minecraft:stone");
                world.put(x + ":65:" + z, "minecraft:air");
            }
        }
        // What the last match did: one block mined, one block placed.
        world.put("2:64:2", "minecraft:air");
        world.put("0:65:0", "minecraft:cobblestone");

        int[] changes = ChunkDiff.diff(schematic, schematic.palette(), 0, 0, 0, 64, 0, -64, 320,
                (x, y, z) -> world.getOrDefault(x + ":" + y + ":" + z, "minecraft:air"));

        List<String> written = decode(changes, schematic, 0, 0, -64);
        assertEquals(List.of("1:64:1=minecraft:dirt", "2:64:2=minecraft:stone", "0:65:0=minecraft:air"),
                written);
    }

    @Test
    @DisplayName("a box straddling chunks is split along their borders")
    void diffStaysInsideItsChunk() {
        SpongeSchematic schematic = box(4, 1, 1, (x, y, z) -> 1, "minecraft:air", "minecraft:stone");

        int[] west = ChunkDiff.diff(schematic, schematic.palette(), 0, 0, 14, 0, 0, -64, 320,
                (x, y, z) -> "minecraft:air");
        int[] east = ChunkDiff.diff(schematic, schematic.palette(), 1, 0, 14, 0, 0, -64, 320,
                (x, y, z) -> "minecraft:air");

        assertEquals(List.of("14:0:0=minecraft:stone", "15:0:0=minecraft:stone"),
                decode(west, schematic, 0, 0, -64));
        assertEquals(List.of("16:0:0=minecraft:stone", "17:0:0=minecraft:stone"),
                decode(east, schematic, 1, 0, -64));
    }

    @Test
    @DisplayName("an unknown state and anything past the world's height are left alone")
    void diffSkipsUnknownAndOutOfWorld() {
        // Stone, dirt, then air.
        SpongeSchematic schematic = box(1, 3, 1, (x, y, z) -> y == 2 ? 0 : y + 1,
                "minecraft:air", "minecraft:stone", "minecraft:dirt");
        String[] palette = schematic.palette().clone();
        palette[2] = null; // the server does not know dirt

        // Pasted at y 318 in a world whose height limit is 320: the third
        // layer would be y 320.
        int[] changes = ChunkDiff.diff(schematic, palette, 0, 0, 0, 318, 0, -64, 320,
                (x, y, z) -> "minecraft:glass");

        assertEquals(List.of("0:318:0=minecraft:stone"), decode(changes, schematic, 0, 0, -64));
    }

    // ------------------------------------------------------------------
    // Budget
    // ------------------------------------------------------------------

    @Test
    @DisplayName("writes stop when the tick's budget is spent and resume next tick")
    void budgetSplitsAcrossTicks() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        TickBudget budget = new TickBudget(clock::get);
        List<Integer> written = new ArrayList<>();
        // Each write costs 0.1 ms: a batch of 16 is 1.6 ms, so the second
        // batch crosses the 2.5 ms budget and the third never starts.
        int stopped = budget.run(0, 1_000, 16, unit -> {
            written.add(unit);
            clock.addAndGet(100_000L);
        });
        assertEquals(32, stopped);
        assertEquals(32, written.size());

        // Same tick: nothing more.
        assertEquals(32, budget.run(32, 1_000, 16, unit -> written.add(unit)));
        assertEquals(32, written.size());

        // Next tick: the window refills and the writes continue where they stopped.
        clock.addAndGet(TickBudget.WINDOW_NANOS);
        int next = budget.run(stopped, 1_000, 16, unit -> {
            written.add(unit);
            clock.addAndGet(100_000L);
        });
        assertEquals(64, next);
        assertEquals(32, written.get(32), "resumes at the first unit not written");
    }

    @Test
    @DisplayName("time charged outside the writes, such as a snapshot, counts against the budget")
    void chargedTimeCounts() {
        AtomicLong clock = new AtomicLong();
        TickBudget budget = new TickBudget(clock::get);
        assertTrue(budget.hasTime());
        budget.charge(TickBudget.BUDGET_NANOS);
        assertEquals(0, budget.run(0, 10, 1, unit -> { }));
    }

    // ------------------------------------------------------------------
    // Binding
    // ------------------------------------------------------------------

    @Test
    @DisplayName("without FAWE the built-in engine is bound, and the module is supported")
    void bindsNativeWithoutFawe(@TempDir Path folder) {
        FakeServer.install();
        FakeServer.reset();
        Engines.bind(FakeServer.newPlugin("NativeBind", folder.toFile()));

        assertTrue(Engines.isSupported());
        assertTrue(Engines.isNative());
        assertEquals(Engines.NATIVE, Engines.reason());
    }

    // ------------------------------------------------------------------
    // Sign text
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a text component survives JSON to 1.21.5 NBT and back")
    void componentNbtRoundTrip() {
        JsonElement json = JsonParser.parseString(
                "{\"text\":\"Arena \",\"bold\":true,\"color\":\"gold\","
                        + "\"extra\":[\"one\",{\"text\":\"two\",\"italic\":false}]}");

        Object nbt = NativeCodec.toNbt(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> compound = (Map<String, Object>) nbt;
        assertEquals((byte) 1, compound.get("bold"));
        Nbt.ListTag extra = (Nbt.ListTag) compound.get("extra");
        assertEquals(Nbt.COMPOUND, extra.type(), "a mixed list is promoted to compounds");

        Map<String, Object> promoted = new LinkedHashMap<>();
        promoted.put("text", "one");
        assertEquals(promoted, extra.items().get(0));
        assertEquals(JsonParser.parseString(
                        "{\"text\":\"Arena \",\"bold\":true,\"color\":\"gold\","
                                + "\"extra\":[{\"text\":\"one\"},{\"text\":\"two\",\"italic\":false}]}"),
                NativeCodec.toJson(nbt));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Which palette id a fixture block holds. */
    @FunctionalInterface
    private interface Shape {
        int at(int x, int y, int z);
    }

    private static SpongeSchematic box(int width, int height, int length, Shape shape, String... palette) {
        SpongeSchematic.Indices blocks = SpongeSchematic.Indices.of(palette.length, width * height * length);
        SpongeSchematic schematic = new SpongeSchematic(width, height, length, 0, 0, 0, new int[3], 3953,
                palette, blocks, Map.of(), List.of());
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    blocks.set(schematic.index(x, y, z), shape.at(x, y, z));
                }
            }
        }
        return schematic;
    }

    private static List<String> decode(int[] changes, SpongeSchematic schematic, int chunkX, int chunkZ,
                                       int minY) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < changes.length; i += 2) {
            int packed = changes[i];
            int x = (chunkX << 4) | (packed & 15);
            int z = (chunkZ << 4) | ((packed >>> 4) & 15);
            int y = (packed >>> 8) + minY;
            out.add(x + ":" + y + ":" + z + "=" + schematic.palette()[changes[i + 1]]);
        }
        return out;
    }
}
