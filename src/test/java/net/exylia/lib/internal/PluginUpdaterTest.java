package net.exylia.lib.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginUpdaterTest {

    @TempDir
    Path folder;

    @Test
    void comparesVersions() {
        assertTrue(PluginUpdater.isNewer("1.4.0", "1.3.9"));
        assertTrue(PluginUpdater.isNewer("2.0.0", "1.30.0"));
        assertFalse(PluginUpdater.isNewer("1.3.0", "1.3.0"));
        assertFalse(PluginUpdater.isNewer("1.2.9", "1.3.0"));
        assertTrue(PluginUpdater.isNewer("1.3.1", "1.3.0-SNAPSHOT"));
        assertFalse(PluginUpdater.isNewer("garbage", "1.0.0"));
    }

    @Test
    void readsWhatAJarDeclares() throws IOException {
        Path jar = jar("name: ExyliaMines\nversion: '1.4.0'\nexylia-lib: '1.230.0'   # oldest library\n");
        PluginUpdater.Descriptor read = PluginUpdater.describe(jar);
        assertEquals("ExyliaMines", read.name());
        assertEquals("1.4.0", read.version());
        assertEquals("1.230.0", read.library());
    }

    @Test
    void aBrokenDownloadDeclaresNothing() throws IOException {
        Path broken = folder.resolve("broken.jar");
        Files.writeString(broken, "not a zip");
        assertNull(PluginUpdater.describe(broken).version());
        assertNull(PluginUpdater.describe(folder.resolve("missing.jar")).name());
    }

    private Path jar(String pluginYml) throws IOException {
        Path jar = folder.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("plugin.yml"));
            out.write(pluginYml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
