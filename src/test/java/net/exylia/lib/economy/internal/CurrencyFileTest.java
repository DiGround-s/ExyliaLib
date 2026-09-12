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
        assertEquals(2, read.stored().size());
        CurrencyFile.Stored coins = read.stored().get("coins");
        assertNotNull(coins);
        assertEquals("Coins", coins.info().namePlural());
        assertEquals(0, coins.info().decimals());
        assertTrue(coins.aliases().contains("coins"));
        assertEquals(new BigDecimal("0.01"), coins.rates().get("gems"));
        assertFalse(read.stored().get("gems").transferable());
        assertEquals(1, read.items().size());
        assertEquals("EMERALD", read.items().get("emeralds").item());
        assertTrue(read.experienceLevels());
        assertEquals("coins", read.vaultProvide());
        assertNotNull(read.overlay("vault"));
        assertEquals("$", read.overlay("vault").symbol());
    }

    @Test
    @DisplayName("the file is the owner's: an edit survives, a bad block is skipped")
    void edits() throws Exception {
        CurrencyFile.load(plugin, Logger.getLogger("test"));
        Path file = folder.resolve("currencies.yml");
        String yaml = Files.readString(file)
                .replace("    name: Coin\n", "    name: Buck\n")
                + "  bad id!:\n    name: Nope\n";
        Files.writeString(file, yaml);

        CurrencyFile.Contents read = CurrencyFile.load(plugin, Logger.getLogger("test"));
        assertEquals("Buck", read.stored().get("coins").info().name());
        assertEquals(2, read.stored().size());
    }
}
