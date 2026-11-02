package net.exylia.lib.block.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A wall's file is what brings it down after a crash: it has to read back exactly what was written. */
class WallFileTest {

    @Test
    @DisplayName("a wall's blocks read back as they were written, block data with spaces included")
    void roundTrip(@TempDir Path folder) throws Exception {
        WallFile.Contents written = new WallFile.Contents("duel:arena 1", "world", "BARRIER", List.of(
                new WallFile.Placed(-4, 64, 12, "minecraft:air"),
                new WallFile.Placed(5, -60, -3, "minecraft:oak_stairs[facing=north,half=bottom]")));
        File file = WallFile.of(folder.toFile(), "duel:arena 1");
        WallFile.write(file, written);

        assertEquals(written, WallFile.read(file));
        assertEquals(List.of(file), WallFile.list(folder.toFile()));
        assertTrue(file.getName().matches("[A-Za-z0-9_-]+\\.wall"), "any id makes a valid file name");
    }

    @Test
    @DisplayName("a damaged block line costs that block, not the wall")
    void damagedLineIsSkipped() {
        WallFile.Contents read = WallFile.decode("room\nworld\nBARRIER\n1 2 3 minecraft:air\nbroken\nx 2 3 minecraft:air");
        assertEquals(List.of(new WallFile.Placed(1, 2, 3, "minecraft:air")), read.blocks());
        assertNull(WallFile.decode("only an id"));
    }

    @Test
    @DisplayName("a folder that does not exist lists nothing")
    void missingFolder(@TempDir Path folder) throws Exception {
        assertEquals(List.of(), WallFile.list(folder.resolve("walls").toFile()));
        Files.createDirectories(folder.resolve("walls"));
        assertEquals(List.of(), WallFile.list(folder.resolve("walls").toFile()));
    }
}
