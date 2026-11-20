package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.ListPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server-free parts of block palettes: the state table, the section rewrite and the shape check. */
class PalettesTest {

    private static final PaletteTable TABLE = PaletteTable.of(Map.of(10, 20, 11, 21, 5, 5));

    @Test
    void tableRemapsOnlyItsSwaps() {
        assertEquals(2, TABLE.swapped());
        assertEquals(20, TABLE.remap(10));
        assertEquals(21, TABLE.remap(11));
        assertEquals(5, TABLE.remap(5));
        assertEquals(0, TABLE.remap(0));
        assertEquals(99_999, TABLE.remap(99_999));
        assertTrue(PaletteTable.of(Map.of(3, 3)).isEmpty());
        assertTrue(PaletteTable.EMPTY.isEmpty());
    }

    @Test
    void singletonSectionIsSwapped() {
        DataPalette data = new DataPalette(new SingletonPalette(10), null, PaletteType.CHUNK);
        assertTrue(PalettePackets.section(data, TABLE));
        assertEquals(20, data.palette.idToState(0));

        DataPalette plain = new DataPalette(new SingletonPalette(7), null, PaletteType.CHUNK);
        assertFalse(PalettePackets.section(plain, TABLE));
    }

    @Test
    void listSectionSwapsEntriesAndKeepsStorage() {
        BitStorage storage = new BitStorage(4, 4096);
        storage.set(0, 1);
        DataPalette data = new DataPalette(new ListPalette(4, new int[]{0, 10, 7}), storage, PaletteType.CHUNK);
        assertTrue(PalettePackets.section(data, TABLE));
        assertEquals(0, data.palette.idToState(0));
        assertEquals(20, data.palette.idToState(1));
        assertEquals(7, data.palette.idToState(2));
        assertEquals(4, data.palette.getBits());
        assertSame(storage, data.storage);
        assertEquals(1, storage.get(0));

        DataPalette plain = new DataPalette(new ListPalette(4, new int[]{0, 7}), storage, PaletteType.CHUNK);
        assertFalse(PalettePackets.section(plain, TABLE));
    }

    @Test
    void globalSectionSwapsEveryBlock() {
        BitStorage storage = new BitStorage(15, 4096);
        storage.set(0, 10);
        storage.set(4095, 11);
        storage.set(100, 7);
        DataPalette data = new DataPalette(new GlobalPalette(), storage, PaletteType.CHUNK);
        assertTrue(PalettePackets.section(data, TABLE));
        assertEquals(20, storage.get(0));
        assertEquals(21, storage.get(4095));
        assertEquals(7, storage.get(100));
        assertFalse(PalettePackets.section(data, TABLE));
    }

    @Test
    void shapesCompareAsSetsOfBoxes() {
        BoundingBox bottom = new BoundingBox(0, 0, 0, 1, 0.5, 1);
        BoundingBox step = new BoundingBox(0, 0.5, 0.5, 1, 1, 1);
        assertTrue(PaletteRules.sameShape(List.of(bottom, step), List.of(step.clone(), bottom.clone())));
        assertTrue(PaletteRules.sameShape(List.of(), List.of()));
        assertFalse(PaletteRules.sameShape(List.of(bottom), List.of(bottom, step)));
        assertFalse(PaletteRules.sameShape(List.of(bottom), List.of(new BoundingBox(0, 0, 0, 1, 1, 1))));
    }
}
