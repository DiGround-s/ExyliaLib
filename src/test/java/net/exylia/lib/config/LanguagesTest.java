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
            this(Languages.ENGLISH, 3);
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
        assertEquals(Languages.ENGLISH, config().get().language());
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

    @Test
    void aCodeThatIsNotOneFallsBackToEnglish() throws Exception {
        onDisk("config.yml", "language: '../../etc'\n");

        assertEquals(Languages.ENGLISH, Languages.code(plugin));
    }
}
