package net.exylia.lib.screen;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.BundledFiles;
import net.exylia.lib.config.Languages;
import net.exylia.lib.debug.DebugCapture;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureClassLoader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin's files when it runs through the licence loader, which keeps its jar in memory. */
class ScreenSetUnderLoaderTest {

    private static final String HTML = "<screen id=\"hub\" routes=\"demo_hub\"><text t=\"title\">Hello</text></screen>";

    @TempDir
    Path data;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Demo", data.toFile());
        LoaderInMemory.RESOURCES.clear();
    }

    @AfterEach
    void tearDown() {
        DebugCapture.stop();
        FakeServer.reset();
    }

    @Test
    void installsTheFolderAndItsWords() throws Exception {
        byte[] picture = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xff};
        pack("pages/hub.html", HTML);
        pack("pages/theme.css", "panel { padding: 1 }");
        pack("pages/components/card.html", "<template name=\"demo_card\"><text>Card</text></template>");
        LoaderInMemory.RESOURCES.put("pages/img/logo.png", picture);
        pack("lang/en/pages/hub.yml", "title: Welcome\n");
        pack("lang/en/pages/shared.yml", "back: Back\n");
        List<String> warnings = DebugCapture.start();

        ScreenSet set = ScreenSet.of(plugin, anchor(), "pages");
        ScreenMarkup hub = set.markup("hub");

        assertEquals(HTML, Files.readString(data.resolve("pages/hub.html")));
        assertTrue(Files.isRegularFile(data.resolve("pages/theme.css")));
        assertTrue(Files.isRegularFile(data.resolve("pages/components/card.html")));
        assertArrayEquals(picture, Files.readAllBytes(data.resolve("pages/img/logo.png")));
        assertEquals("Welcome", yaml(data.resolve("lang/en/pages/hub.yml")).getString("title"));
        assertEquals("Back", yaml(data.resolve("lang/en/pages/shared.yml")).getString("back"));
        assertTrue(Files.isRegularFile(data.resolve(".defaults/files/pages/hub.html")));
        assertEquals(List.of("hub"), set.ids());
        assertEquals(List.of(), hub.problems());
        assertEquals(List.of(), warnings);
    }

    @Test
    void wordsFollowTheLanguageWithNoEnglishFile() throws Exception {
        Files.writeString(data.resolve("config.yml"), "language: es\n");
        pack("pages/hub.html", HTML);
        pack("lang/es/pages/hub.yml", "title: Hola\n");

        ScreenSet.of(plugin, anchor(), "pages").markup("hub");

        assertEquals("Hola", yaml(data.resolve("lang/es/pages/hub.yml")).getString("title"));
    }

    @Test
    void aPluginWithoutTheFolderIsLeftAlone() throws Exception {
        List<String> warnings = DebugCapture.start();

        ScreenSet set = ScreenSet.of(plugin, anchor(), "pages");

        assertFalse(Files.exists(data.resolve("pages")));
        assertEquals(List.of(), warnings);
        assertThrows(IllegalArgumentException.class, () -> set.markup("hub"));
    }

    @Test
    void cataloguesMoveAndInstallTranslated() throws Exception {
        Files.writeString(data.resolve("config.yml"), "language: es\n");
        pack("jobs/miner.yml", "name: Miner\npay: 1\n");
        pack("jobs/baker.yml", "name: Baker\npay: 2\n");
        pack("lang/es/jobs/miner.yml", "name: Minero\n");
        Path untouched = data.resolve("jobs/miner.yml");
        Path edited = data.resolve("jobs/baker.yml");
        Files.createDirectories(untouched.getParent());
        Files.writeString(untouched, "name: Miner\npay: 1\n");
        Files.writeString(edited, "name: Baker\npay: 9\n");

        Languages.adoptCatalogue(plugin, anchor(), "jobs");
        assertTrue(Languages.refresh(plugin, anchor(), "jobs"));

        YamlConfiguration miner = yaml(data.resolve("lang/es/jobs/miner.yml"));
        assertEquals("Minero", miner.getString("name"));
        assertEquals(1, miner.getInt("pay"));
        assertEquals(9, yaml(data.resolve("lang/es/jobs/baker.yml")).getInt("pay"));
        assertFalse(Files.exists(untouched));
        assertFalse(Files.exists(edited));
    }

    @Test
    void packagedSeesFilesAndFoldersOfTheTable() throws Exception {
        pack("pages/parts/card.html", "x");

        Class<?> anchor = anchor();
        assertTrue(BundledFiles.packaged(anchor, "pages"));
        assertTrue(BundledFiles.packaged(anchor, "pages/parts"));
        assertTrue(BundledFiles.packaged(anchor, "pages/parts/card.html"));
        assertFalse(BundledFiles.packaged(anchor, "screen"));
        assertFalse(BundledFiles.packaged(anchor, "pages/card.html"));
    }

    @Test
    void packagedStillSeesAnOrdinaryLoader() {
        assertTrue(BundledFiles.packaged(ScreenSetUnderLoaderTest.class, "net/exylia/lib/screen"));
        assertFalse(BundledFiles.packaged(ScreenSetUnderLoaderTest.class, "no/such/folder"));
    }

    private static void pack(String name, String text) {
        LoaderInMemory.RESOURCES.put(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static YamlConfiguration yaml(Path file) {
        return YamlConfiguration.loadConfiguration(file.toFile());
    }

    private static Class<?> anchor() throws IOException {
        return new LoaderInMemory().define(Anchor.class.getName());
    }

    static final class Anchor {
    }

    /**
     * The shape of Lukittu's class loader: classes defined from bytes, so their code source has no location, and a
     * table of files only {@code getResourceAsStream} answers from. {@code getResource} knows nothing of them, a
     * file or a directory alike, since it asks the parent only.
     */
    private static final class LoaderInMemory extends SecureClassLoader {

        private static final Map<String, byte[]> RESOURCES = new ConcurrentHashMap<>();

        private LoaderInMemory() {
            super(ScreenSetUnderLoaderTest.class.getClassLoader());
        }

        private Class<?> define(String name) throws IOException {
            try (InputStream bytecode = ScreenSetUnderLoaderTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                byte[] bytes = bytecode.readAllBytes();
                Class<?> defined = defineClass(name, bytes, 0, bytes.length);
                assertNull(defined.getProtectionDomain().getCodeSource().getLocation());
                return defined;
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] bytes = RESOURCES.get(name);
            return bytes != null ? new ByteArrayInputStream(bytes) : super.getResourceAsStream(name);
        }
    }
}
