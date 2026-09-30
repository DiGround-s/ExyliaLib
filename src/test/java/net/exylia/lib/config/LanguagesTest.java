package net.exylia.lib.config;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.internal.DefaultUpdates;
import net.exylia.lib.debug.DebugCapture;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packaged files are written into this class's build output directory, which
 * persists across runs, so every test packages under a name of its own.
 */
class LanguagesTest {

    @TempDir
    Path folder;

    private Plugin plugin;
    private Path packagedRoot;

    record Settings(String language, int size) {
        Settings() {
            this(Languages.DEFAULT, 3);
        }
    }

    record Messages(String greeting, String farewell) {
        Messages() {
            this("Hello", "Bye");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        FakeServer.install();
        FakeServer.reset();
        Configs.releaseAll();
        DefaultUpdates.releaseAll();
        plugin = FakeServer.newPlugin("Languages", folder.toFile());
        packagedRoot = Path.of(LanguagesTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @AfterEach
    void tearDown() {
        DebugCapture.stop();
        Configs.releaseAll();
        DefaultUpdates.releaseAll();
        FakeServer.reset();
    }

    private void pack(String path, String text) throws Exception {
        Path file = packagedRoot.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private void onDisk(String path, String text) throws Exception {
        Path file = folder.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private String read(String path) throws Exception {
        return Files.readString(folder.resolve(path));
    }

    private ConfigFile<Settings> config() {
        return Configs.define(plugin, "config", Settings.class)
                .version(2).migration(1, Languages.ADOPT_EXISTING).load();
    }

    @Test
    void aFreshInstallIsEnglish() {
        assertEquals(Languages.DEFAULT, config().get().language());
        assertEquals(Languages.ENGLISH, Languages.code(plugin), "no library language set means English");
        assertEquals("lang/en/menus/main.yml", Languages.path(plugin, "menus/main.yml"));
    }

    @Test
    void aServerThatRanThePluginBeforeKeepsItsFiles() throws Exception {
        onDisk("config.yml", "size: 5\n");
        onDisk("lang-adopt/main.yml", "title: mine");
        onDisk(".defaults/files/lang-adopt/main.yml", "title: shipped");

        assertEquals(Languages.CUSTOM, config().get().language());
        assertEquals(5, config().get().size());
        assertTrue(Languages.file(plugin, "lang-adopt/main.yml").isFile());

        assertEquals("title: mine", read("lang/custom/lang-adopt/main.yml"));
        assertEquals("title: shipped", read(".defaults/files/lang/custom/lang-adopt/main.yml"));
        assertFalse(Files.exists(folder.resolve("lang-adopt")), "the emptied folder is removed");
    }

    @Test
    void anAdoptedFileNeverOverwritesACustomTranslation() throws Exception {
        onDisk("lang-kept/main.yml", "title: old place");
        onDisk("lang/custom/lang-kept/main.yml", "title: custom");

        Languages.path(plugin, "lang-kept/main.yml");

        assertEquals("title: custom", read("lang/custom/lang-kept/main.yml"));
        assertEquals("title: old place", read("lang-kept/main.yml"));
    }

    @Test
    void aDirectoryAdoptedPieceByPieceStillArrivesWhole() throws Exception {
        onDisk("lang-parts/admin/main.yml", "title: admin");
        onDisk("lang-parts/shop.yml", "title: mine");

        Languages.path(plugin, "lang-parts/admin");
        Languages.path(plugin, "lang-parts");

        assertEquals("title: admin", read("lang/custom/lang-parts/admin/main.yml"));
        assertEquals("title: mine", read("lang/custom/lang-parts/shop.yml"));
        assertFalse(Files.exists(folder.resolve("lang-parts")));
    }

    @Test
    void aTranslationIsLaidOverEnglish() throws Exception {
        pack("lang/en/lang-layers/main.yml", "title: Main\nfooter: Close\n");
        pack("lang/en/lang-layers/other.yml", "title: Other\n");
        pack("lang/es/lang-layers/main.yml", "title: Principal\n");
        onDisk("config.yml", "language: es\n");
        List<String> messages = DebugCapture.start();

        assertTrue(Languages.refresh(plugin, LanguagesTest.class, "lang-layers"), messages::toString);

        YamlConfiguration main = YamlConfiguration.loadConfiguration(
                folder.resolve("lang/es/lang-layers/main.yml").toFile());
        assertEquals("Principal", main.getString("title"));
        assertEquals("Close", main.getString("footer"), "an untranslated key arrives in English");
        assertEquals("title: Other\n", read("lang/es/lang-layers/other.yml"), "an untranslated file too");
    }

    @Test
    void englishFilesMayStillSitAtTheRootOfTheJar() throws Exception {
        pack("lang-root/main.yml", "title: Root\n");
        pack("lang/es/lang-root/main.yml", "title: Raiz\n");
        onDisk("config.yml", "language: custom\n");

        assertTrue(Languages.refresh(plugin, LanguagesTest.class, "lang-root"));

        assertEquals("title: Root\n", read("lang/custom/lang-root/main.yml"), "custom starts from English");
    }

    @Test
    void replacingDropsEditsInTheCurrentLanguage() throws Exception {
        pack("lang/en/lang-admin/main.yml", "title: Admin\n");
        pack("lang/es/lang-admin/main.yml", "title: Administrar\n");
        onDisk("config.yml", "language: es\n");
        onDisk("lang/es/lang-admin/main.yml", "title: edited\n");
        onDisk("lang/es/lang-admin/stale.yml", "title: gone\n");

        assertTrue(Languages.replace(plugin, LanguagesTest.class, "lang-admin"));

        assertEquals("title: Administrar\n", read("lang/es/lang-admin/main.yml"));
        assertFalse(Files.exists(folder.resolve("lang/es/lang-admin/stale.yml")));
    }

    @Test
    void translatedConfigsUsePackagedDefaultsAndFollowTheLanguage() throws Exception {
        pack("lang/es/lang-messages.yml", "greeting: Hola\n");
        onDisk("config.yml", "language: es\n");

        ConfigFile<Messages> messages = Configs.define(plugin, "lang-messages", Messages.class).translated().load();

        assertEquals("Hola", messages.get().greeting());
        assertEquals("Bye", messages.get().farewell(), "an untranslated key keeps the English default");
        assertTrue(Files.isRegularFile(folder.resolve("lang/es/lang-messages.yml")));

        onDisk("config.yml", "language: en\n");
        messages.reload();

        assertEquals("Hello", messages.get().greeting());
        assertTrue(Files.isRegularFile(folder.resolve("lang/en/lang-messages.yml")));
    }

    @Test
    void anOldMessagesFileMovesWithItsEdits() throws Exception {
        onDisk("config.yml", "language: custom\n");
        onDisk("lang-old.yml", "greeting: Howdy\n");

        ConfigFile<Messages> messages = Configs.define(plugin, "lang-old", Messages.class).translated().load();

        assertEquals("Howdy", messages.get().greeting());
        assertFalse(Files.exists(folder.resolve("lang-old.yml")));
        assertTrue(read("lang/custom/lang-old.yml").contains("Howdy"));
    }

    record Source(int size) {
        Source() {
            this(3);
        }
    }

    private ConfigFile<Source> source() {
        return Configs.define(plugin, "lang-source", Source.class)
                .version(2)
                .migration(1, Configs.moveTo(plugin, "lang-target", java.util.Map.of("bar", "farewell")))
                .load();
    }

    @Test
    void movedTextReachesATargetReadBefore() throws Exception {
        onDisk("lang-source.yml", "size: 5\nbar: Later\n");
        ConfigFile<Messages> target = Configs.define(plugin, "lang-target", Messages.class).load();

        source();

        assertEquals("Later", target.get().farewell());
        assertFalse(read("lang-source.yml").contains("bar:"));
        assertTrue(read("lang-target.yml").contains("Later"));
    }

    @Test
    void movedTextReachesATargetReadAfter() throws Exception {
        onDisk("lang-source.yml", "size: 5\nbar: Later\n");
        onDisk("lang-target.yml", "greeting: Hi\n");

        source();
        ConfigFile<Messages> target = Configs.define(plugin, "lang-target", Messages.class).load();

        assertEquals("Later", target.get().farewell());
        assertEquals("Hi", target.get().greeting());
    }

    @Test
    void anUntouchedEnglishDefaultNeverCoversATranslation() throws Exception {
        pack("lang/es/lang-moved.yml", "farewell: Adios\n");
        onDisk("config.yml", "language: es\n");
        onDisk("lang-source.yml", "size: 5\nbar: Bye\n");

        Configs.define(plugin, "lang-source", Source.class)
                .version(2)
                .migration(1, Configs.moveTo(plugin, "lang-moved", java.util.Map.of("bar", "farewell")))
                .load();
        ConfigFile<Messages> target = Configs.define(plugin, "lang-moved", Messages.class).translated().load();

        assertEquals("Adios", target.get().farewell());
    }

    @Test
    void aPluginOnDefaultFollowsTheLibrary() throws Exception {
        Path library = folder.getParent().resolve("ExyliaLib");
        Files.createDirectories(library);
        Files.writeString(library.resolve("config.yml"), "language: pt\n");
        try {
            onDisk("config.yml", "language: default\n");
            assertEquals("pt", Languages.code(plugin));

            onDisk("config.yml", "language: es\n");
            assertEquals("es", Languages.code(plugin), "a plugin's own language wins");
        } finally {
            Files.delete(library.resolve("config.yml"));
            Files.delete(library);
        }
    }

    @Test
    void aCodeThatIsNotOneFallsBackToEnglish() throws Exception {
        onDisk("config.yml", "language: '../../etc'\n");

        assertEquals(Languages.ENGLISH, Languages.code(plugin));
    }
}
