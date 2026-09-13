package net.exylia.lib.ui;

import net.exylia.lib.FakeServer;
import net.exylia.lib.debug.DebugCapture;
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
 * Each test packages its fixtures under its own resource directory name.
 * The "packaged" side of {@link PluginMenus#refreshVersionedDirectory} is read
 * straight from this test class's build output directory (the file-based
 * branch of {@code extractBundledDirectory}, exercised the same way by
 * {@link PluginMenusBundledDirectoryTest}), which persists across test runs —
 * unlike {@code folder}, a fresh {@link TempDir} is not available for it, so a
 * shared directory name would leak fixtures from one test into another.
 */
class PluginMenusVersionedDirectoryTest {

    @TempDir
    Path folder;

    private Plugin plugin;
    private PluginMenus menus;
    private Path packagedRoot;

    @BeforeEach
    void setUp() throws Exception {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Menus", folder.toFile());
        menus = Menus.of(plugin);
        packagedRoot = Path.of(PluginMenusVersionedDirectoryTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
    }

    @AfterEach
    void tearDown() {
        Menus.releaseAll();
        DebugCapture.stop();
        FakeServer.reset();
    }

    private Path packaged(String testDirectory) throws Exception {
        Path directory = packagedRoot.resolve(testDirectory);
        Files.createDirectories(directory);
        return directory;
    }

    @Test
    void writesAFileNeverSeenBeforeAtItsPackagedVersion() throws Exception {
        String directory = "versioned-fresh-install";
        Files.writeString(packaged(directory).resolve("main.yml"), "menu-version: 1\ntitle: fresh");
        List<String> messages = DebugCapture.start();

        assertTrue(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory),
                messages::toString);

        Path target = folder.resolve(directory + "/main.yml");
        assertEquals("menu-version: 1\ntitle: fresh", Files.readString(target));
        assertTrue(messages.isEmpty());
    }

    @Test
    void replacesAFileWhoseOnDiskVersionIsOlder() throws Exception {
        String directory = "versioned-older-on-disk";
        Files.writeString(packaged(directory).resolve("main.yml"), "menu-version: 2\ntitle: new");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "menu-version: 1\ntitle: old");
        List<String> messages = DebugCapture.start();

        assertTrue(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory));

        assertEquals("menu-version: 2\ntitle: new", Files.readString(target));
        assertEquals("menu-version: 1\ntitle: old", Files.readString(target.resolveSibling("main.yml.v1")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("from version 1 to 2")), messages::toString);
    }

    @Test
    void updatesFilesInNestedDirectories() throws Exception {
        String directory = "versioned-nested";
        Path nested = packaged(directory).resolve("games");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("lobby.yml"), "menu-version: 2\ntitle: new");
        Path target = folder.resolve(directory + "/games/lobby.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "menu-version: 1\ntitle: old");

        assertTrue(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory));

        assertEquals("menu-version: 2\ntitle: new", Files.readString(target));
    }

    @Test
    void leavesAnUnreadableFileOnDiskUntouchedAndSaysSo() throws Exception {
        String directory = "versioned-unreadable-on-disk";
        Files.writeString(packaged(directory).resolve("main.yml"), "menu-version: 2\ntitle: new");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "menu-version: 1\ntitle: [unclosed");
        List<String> messages = DebugCapture.start();

        assertFalse(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory));

        assertEquals("menu-version: 1\ntitle: [unclosed", Files.readString(target));
        assertFalse(Files.exists(target.resolveSibling("main.yml.v1")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("could not be read")), messages::toString);
    }

    @Test
    void leavesAFileAlreadyAtThePackagedVersionExactlyAsItIs() throws Exception {
        String directory = "versioned-already-caught-up";
        Files.writeString(packaged(directory).resolve("main.yml"), "menu-version: 2\ntitle: new");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "menu-version: 2\ntitle: hand-edited by an admin");

        assertTrue(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory));

        assertEquals("menu-version: 2\ntitle: hand-edited by an admin", Files.readString(target));
    }

    @Test
    void leavesAFileAheadOfThePackagedVersionAlone() throws Exception {
        String directory = "versioned-ahead-of-packaged";
        Files.writeString(packaged(directory).resolve("main.yml"), "menu-version: 1\ntitle: rolled back");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "menu-version: 3\ntitle: ahead");

        assertTrue(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory));

        assertEquals("menu-version: 3\ntitle: ahead", Files.readString(target));
    }

    @Test
    void replacesAFileAlreadyOnDiskWithNoVersionKeyOfItsOwn() throws Exception {
        String directory = "versioned-predates-versioning";
        Files.writeString(packaged(directory).resolve("main.yml"), "menu-version: 1\ntitle: versioned now");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "title: predates versioning");

        assertTrue(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory));

        assertEquals("menu-version: 1\ntitle: versioned now", Files.readString(target));
        assertEquals("title: predates versioning", Files.readString(target.resolveSibling("main.yml.v0")));
    }

    @Test
    void installsAMissingFileThatDoesNotDeclareAVersion() throws Exception {
        String directory = "versioned-unversioned-missing";
        Files.writeString(packaged(directory).resolve("main.yml"), "title: unversioned");

        assertTrue(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory));

        assertEquals("title: unversioned", Files.readString(folder.resolve(directory + "/main.yml")));
    }

    @Test
    void neverTouchesAFileThatDoesNotDeclareAVersion() throws Exception {
        String directory = "versioned-opted-out";
        Files.writeString(packaged(directory).resolve("main.yml"), "title: unversioned");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "title: unversioned but hand-edited");

        assertTrue(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory));

        assertEquals("title: unversioned but hand-edited", Files.readString(target));
    }

    @Test
    void addsAFileThatIsNewInThisPackagedVersionWithoutTouchingItsSiblings() throws Exception {
        String directory = "versioned-new-sibling";
        Path root = packaged(directory);
        Files.writeString(root.resolve("main.yml"), "menu-version: 1\ntitle: old");
        Files.writeString(root.resolve("gridshot.yml"), "menu-version: 1\ntitle: new mode");
        Path target = folder.resolve(directory);
        Files.createDirectories(target);
        Files.writeString(target.resolve("main.yml"), "menu-version: 1\ntitle: old");

        assertTrue(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory));

        assertEquals("menu-version: 1\ntitle: old", Files.readString(target.resolve("main.yml")));
        assertEquals("menu-version: 1\ntitle: new mode", Files.readString(target.resolve("gridshot.yml")));
    }
}
