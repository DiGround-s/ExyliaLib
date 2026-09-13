package net.exylia.lib.economy.internal;

import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyFileTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("ExyliaLib", folder.toFile());
    }

    @Test
    @DisplayName("a fresh server gets the defaults, and they read back")
    void defaults() throws Exception {
        CurrencyFile.Contents read = CurrencyFile.load(plugin, Logger.getLogger("test"));

        assertTrue(Files.exists(folder.resolve("currencies.yml")));
        assertEquals(1, read.stored().size());
        CurrencyFile.Stored shards = read.stored().get("shards");
        assertNotNull(shards);
        assertEquals("Shards", shards.info().namePlural());
        assertEquals(0, shards.info().decimals());
        assertTrue(shards.aliases().contains("shards"));
        assertTrue(shards.transferable());
        assertFalse(shards.exchangeable());
        assertTrue(shards.rates().isEmpty());
        assertEquals(1, read.items().size());
        assertEquals("NETHERITE_INGOT", read.items().get("netherite_ingots").item());
        assertFalse(read.experienceLevels());
        assertTrue(read.experiencePoints());
        assertEquals("", read.vaultProvide());
        assertNotNull(read.overlay("vault"));
        assertEquals("$", read.overlay("vault").symbol());
    }

    @Test
    @DisplayName("the file is the owner's: an edit survives, a bad block is skipped")
    void edits() throws Exception {
        CurrencyFile.load(plugin, Logger.getLogger("test"));
        Path file = folder.resolve("currencies.yml");
        String yaml = Files.readString(file)
                .replace("    name: Shard\n", "    name: Buck\n")
                + "  bad id!:\n    name: Nope\n";
        Files.writeString(file, yaml);

        CurrencyFile.Contents read = CurrencyFile.load(plugin, Logger.getLogger("test"));
        assertEquals("Buck", read.stored().get("shards").info().name());
        assertEquals(1, read.stored().size());
    }
}
