package net.exylia.lib.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PluginUpdaterJarTest {

    @TempDir
    Path plugins;

    @Test
    void aJarInPluginsIsItself() throws IOException {
        Path jar = Files.createFile(plugins.resolve("Thing.jar"));
        assertEquals(jar, PluginUpdater.installed(jar, plugins));
    }

    @Test
    void aPaperRemappedCopyPointsAtTheOriginal() throws IOException {
        Path original = Files.createFile(plugins.resolve("Thing-1.0.jar"));
        Path remapped = Files.createDirectories(plugins.resolve(".paper-remapped")).resolve("Thing-1.0.jar");
        Files.createFile(remapped);
        assertEquals(original, PluginUpdater.installed(remapped, plugins));
    }

    @Test
    void aRemappedCopyWithoutItsOriginalIsSkipped() throws IOException {
        Path remapped = Files.createDirectories(plugins.resolve(".paper-remapped")).resolve("Gone.jar");
        Files.createFile(remapped);
        assertNull(PluginUpdater.installed(remapped, plugins));
    }

    @Test
    void aJarElsewhereIsSkipped(@TempDir Path elsewhere) throws IOException {
        Path jar = Files.createFile(elsewhere.resolve("Thing.jar"));
        assertNull(PluginUpdater.installed(jar, plugins));
    }
}
