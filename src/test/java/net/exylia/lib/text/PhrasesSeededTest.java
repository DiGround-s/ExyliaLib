package net.exylia.lib.text;

import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Seeded words follow the language; what an owner renamed stays theirs. */
class PhrasesSeededTest {

    @TempDir
    Path folder;

    @Test
    void translatesWhatTheTableHolds() throws Exception {
        FakeServer.install();
        Files.writeString(folder.resolve("config.yml"), "language: es\n");
        Plugin plugin = FakeServer.newPlugin("Seeded", folder.toFile());
        UnaryOperator<String> seeded = Phrases.seeded(plugin, PhrasesSeededTest.class);

        assertEquals("Recompensa diaria", seeded.apply("Daily reward. Every day"));
        assertEquals("Renamed by the owner", seeded.apply("Renamed by the owner"));
        assertNull(seeded.apply(null));

        Files.createDirectories(folder.resolve("lang/es"));
        Files.writeString(folder.resolve("lang/es/seeded.yml"), "'Daily reward. Every day': Mi diaria\n");
        assertEquals("Mi diaria", Phrases.seeded(plugin, PhrasesSeededTest.class).apply("Daily reward. Every day"));
    }
}
