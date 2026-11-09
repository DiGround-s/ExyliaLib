package net.exylia.lib.text;

import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhrasesPluginLanguageTest {

    @TempDir
    Path folder;

    @Test
    void aPluginsScreensSpeakItsLanguageNotTheServers() throws Exception {
        FakeServer.install();
        Path libraryFolder = Files.createDirectories(folder.resolve("ExyliaLib"));
        Path spanish = Files.createDirectories(folder.resolve("Spanish"));
        Path server = Files.createDirectories(folder.resolve("Server"));
        Files.writeString(libraryFolder.resolve("config.yml"), "language: en\n");
        Files.writeString(spanish.resolve("config.yml"), "language: es\n");
        Files.writeString(server.resolve("config.yml"), "language: default\n");

        Phrases.load(FakeServer.newPlugin("ExyliaLib", libraryFolder.toFile()));

        assertEquals("Leave", Phrases.tr("Leave"));
        Plugin plugin = FakeServer.newPlugin("Spanish", spanish.toFile());
        assertEquals("Salir", Phrases.of(plugin).apply("Leave"));
        assertEquals("Not a phrase", Phrases.of(plugin).apply("Not a phrase"));
        assertEquals("Leave", Phrases.of(FakeServer.newPlugin("Server", server.toFile())).apply("Leave"));
        Phrases.use(java.util.Map.of());
    }
}
