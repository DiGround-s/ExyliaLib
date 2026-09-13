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

        assertTrue(menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory));

        assertEquals("menu-version: 2\ntitle: new", Files.readString(target));
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
