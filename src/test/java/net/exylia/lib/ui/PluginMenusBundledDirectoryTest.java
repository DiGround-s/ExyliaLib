package net.exylia.lib.ui;

import net.exylia.lib.FakeServer;
import net.exylia.lib.debug.DebugCapture;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMenusBundledDirectoryTest {

    @TempDir
    Path folder;

    private Plugin plugin;
    private PluginMenus menus;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Menus", folder.toFile());
        menus = Menus.of(plugin);
    }

    @AfterEach
    void tearDown() {
        Menus.releaseAll();
        DebugCapture.stop();
        FakeServer.reset();
    }

    @Test
    void recursivelyReplacesDirectoryAndRemovesDeletedFiles() throws Exception {
        Path packaged = Path.of(PluginMenusBundledDirectoryTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).resolve("bundled-menus");
        Files.createDirectories(packaged.resolve("nested"));
        Files.writeString(packaged.resolve("main.yml"), "new main");
        Files.writeString(packaged.resolve("nested/settings.yml"), "new settings");
        Path target = folder.resolve("bundled-menus");
        Files.createDirectories(target.resolve("nested"));
        Files.writeString(target.resolve("old.yml"), "removed");
        Files.writeString(target.resolve("nested/settings.yml"), "old settings");
        List<String> messages = DebugCapture.start();

        assertTrue(menus.refreshBundledDirectory(PluginMenusBundledDirectoryTest.class, "bundled-menus"), messages::toString);

        assertEquals("new main", Files.readString(target.resolve("main.yml")));
        assertEquals("new settings", Files.readString(target.resolve("nested/settings.yml")));
        assertFalse(Files.exists(target.resolve("old.yml")));
        assertTrue(messages.isEmpty());
    }

    @Test
    void readsPackagedFilesFromAnOrdinaryJar() throws Exception {
        Path jar = jar("menus/admin/main.yml", "jar main", "menus/admin/nested/settings.yml", "jar settings");
        List<String> messages = DebugCapture.start();
        try (JarClassLoader loader = new JarClassLoader(jar.toUri().toURL(), JarAnchor.class.getName())) {
            Class<?> anchor = loader.loadClass(JarAnchor.class.getName());

            assertTrue(menus.refreshBundledDirectory(anchor, "menus/admin"), messages::toString);
        }

        assertEquals("jar main", Files.readString(folder.resolve("menus/admin/main.yml")));
        assertEquals("jar settings", Files.readString(folder.resolve("menus/admin/nested/settings.yml")));
    }

    @Test
    void rejectsBlankAbsoluteAndTraversalPaths() {
        for (String path : List.of("", " ", ".", "menus/..", "/menus/admin", "../menus/admin", "menus/../../outside")) {
            assertThrows(IllegalArgumentException.class,
                    () -> menus.refreshBundledDirectory(DirectoryAnchor.class, path));
        }
    }

    @Test
    void preservesExistingDirectoryWhenExtractionFails() throws IOException {
        Path target = folder.resolve("menus/admin");
        Files.createDirectories(target);
        Files.writeString(target.resolve("kept.yml"), "keep me");
        List<String> messages = DebugCapture.start();

        assertFalse(menus.refreshBundledDirectory(DirectoryAnchor.class, "missing-directory"));

        assertEquals("keep me", Files.readString(target.resolve("kept.yml")));
        assertEquals(1, messages.size());
    }

    private Path jar(String firstName, String firstContents, String secondName, String secondContents) throws IOException {
        Path jar = folder.resolve("anchor.jar");
        String anchorName = JarAnchor.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            write(output, anchorName, JarAnchor.class.getResourceAsStream("/" + anchorName));
            write(output, firstName, firstContents.getBytes());
            write(output, secondName, secondContents.getBytes());
        }
        return jar;
    }

    private static void write(JarOutputStream output, String name, InputStream contents) throws IOException {
        output.putNextEntry(new JarEntry(name));
        contents.transferTo(output);
        output.closeEntry();
    }

    private static void write(JarOutputStream output, String name, byte[] contents) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(contents);
        output.closeEntry();
    }

    static final class DirectoryAnchor {
    }

    static final class JarAnchor {
    }

    private static final class JarClassLoader extends URLClassLoader {
        private final String childFirst;

        private JarClassLoader(URL location, String childFirst) {
            super(new URL[]{location}, PluginMenusBundledDirectoryTest.class.getClassLoader());
            this.childFirst = childFirst;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(childFirst)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
            return super.loadClass(name, resolve);
        }
    }
}
