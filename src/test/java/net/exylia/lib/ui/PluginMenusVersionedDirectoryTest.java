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

    private boolean refresh(String directory) {
        return menus.refreshVersionedDirectory(PluginMenusVersionedDirectoryTest.class, directory);
    }

    @Test
    void installsMissingFilesQuietly() throws Exception {
        String directory = "editable-fresh-install";
        Files.writeString(packaged(directory).resolve("main.yml"), "title: fresh");
        List<String> messages = DebugCapture.start();

        assertTrue(refresh(directory), messages::toString);

        assertEquals("title: fresh", Files.readString(folder.resolve(directory + "/main.yml")));
        assertTrue(messages.isEmpty(), messages::toString);
    }

    @Test
    void updatesAFileNobodyChangedSinceItWasInstalled() throws Exception {
        String directory = "editable-untouched";
        Path source = packaged(directory).resolve("main.yml");
        Files.writeString(source, "title: old");
        assertTrue(refresh(directory));

        Files.writeString(source, "title: new button");
        List<String> messages = DebugCapture.start();
        assertTrue(refresh(directory));

        Path target = folder.resolve(directory + "/main.yml");
        assertEquals("title: new button", Files.readString(target));
        assertFalse(Files.exists(target.resolveSibling("main.yml.new")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("Updated " + directory + "/main.yml")),
                messages::toString);
    }

    @Test
    void keepsAnEditedFileAndOffersTheNewContentOnce() throws Exception {
        String directory = "editable-edited";
        Path source = packaged(directory).resolve("main.yml");
        Files.writeString(source, "title: old");
        assertTrue(refresh(directory));
        Path target = folder.resolve(directory + "/main.yml");
        Files.writeString(target, "title: reworded by the owner");

        Files.writeString(source, "title: new button");
        List<String> messages = DebugCapture.start();
        assertTrue(refresh(directory));

        Path offered = target.resolveSibling("main.yml.new");
        assertEquals("title: reworded by the owner", Files.readString(target));
        assertEquals("title: new button", Files.readString(offered));
        assertTrue(messages.stream().anyMatch(line -> line.contains("main.yml.new")), messages::toString);

        Files.delete(offered);
        messages.clear();
        assertTrue(refresh(directory));

        assertFalse(Files.exists(offered));
        assertTrue(messages.isEmpty(), messages::toString);
    }

    @Test
    void offersAgainWhenThePluginShipsYetAnotherVersion() throws Exception {
        String directory = "editable-offered-twice";
        Path source = packaged(directory).resolve("main.yml");
        Files.writeString(source, "title: old");
        assertTrue(refresh(directory));
        Path target = folder.resolve(directory + "/main.yml");
        Files.writeString(target, "title: reworded by the owner");
        Files.writeString(source, "title: second");
        assertTrue(refresh(directory));

        Files.writeString(source, "title: third");
        assertTrue(refresh(directory));

        assertEquals("title: third", Files.readString(target.resolveSibling("main.yml.new")));
    }

    @Test
    void adoptsAFileThatMatchesThePackagedOneWithoutARecord() throws Exception {
        String directory = "editable-adopted";
        Path source = packaged(directory).resolve("main.yml");
        Files.writeString(source, "title: same");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "title: same");
        assertTrue(refresh(directory));

        Files.writeString(source, "title: new button");
        assertTrue(refresh(directory));

        assertEquals("title: new button", Files.readString(target));
    }

    @Test
    void treatsAnUnrecordedDifferentFileAsEdited() throws Exception {
        String directory = "editable-unrecorded";
        Files.writeString(packaged(directory).resolve("main.yml"), "title: new");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "title: from before");

        assertTrue(refresh(directory));

        assertEquals("title: from before", Files.readString(target));
        assertEquals("title: new", Files.readString(target.resolveSibling("main.yml.new")));
    }

    @Test
    void aHigherMenuVersionForcesItselfOverAnEditAndKeepsTheOldFile() throws Exception {
        String directory = "editable-forced";
        Files.writeString(packaged(directory).resolve("main.yml"), "menu-version: 2\ntitle: new");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "menu-version: 1\ntitle: edited");
        List<String> messages = DebugCapture.start();

        assertTrue(refresh(directory));

        assertEquals("menu-version: 2\ntitle: new", Files.readString(target));
        assertEquals("menu-version: 1\ntitle: edited", Files.readString(target.resolveSibling("main.yml.v1")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("from version 1 to 2")), messages::toString);
    }

    @Test
    void aFileWithNoMenuVersionCountsAsVersionZero() throws Exception {
        String directory = "editable-forced-from-zero";
        Files.writeString(packaged(directory).resolve("main.yml"), "menu-version: 1\ntitle: versioned now");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "title: predates versioning");

        assertTrue(refresh(directory));

        assertEquals("menu-version: 1\ntitle: versioned now", Files.readString(target));
        assertEquals("title: predates versioning", Files.readString(target.resolveSibling("main.yml.v0")));
    }

    @Test
    void anEditAtTheSameMenuVersionIsOfferedNotForced() throws Exception {
        String directory = "editable-same-version";
        Files.writeString(packaged(directory).resolve("main.yml"), "menu-version: 2\ntitle: new");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "menu-version: 2\ntitle: edited");

        assertTrue(refresh(directory));

        assertEquals("menu-version: 2\ntitle: edited", Files.readString(target));
        assertTrue(Files.exists(target.resolveSibling("main.yml.new")));
    }

    @Test
    void leavesAnUnreadableFileOnDiskUntouchedAndSaysSo() throws Exception {
        String directory = "editable-unreadable";
        Files.writeString(packaged(directory).resolve("main.yml"), "menu-version: 2\ntitle: new");
        Path target = folder.resolve(directory + "/main.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "menu-version: 1\ntitle: [unclosed");
        List<String> messages = DebugCapture.start();

        assertFalse(refresh(directory));

        assertEquals("menu-version: 1\ntitle: [unclosed", Files.readString(target));
        assertFalse(Files.exists(target.resolveSibling("main.yml.v1")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("could not be read")), messages::toString);
    }

    @Test
    void updatesFilesInNestedDirectories() throws Exception {
        String directory = "editable-nested";
        Path nested = packaged(directory).resolve("games");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("lobby.yml"), "title: old");
        assertTrue(refresh(directory));

        Files.writeString(nested.resolve("lobby.yml"), "title: new");
        assertTrue(refresh(directory));

        assertEquals("title: new", Files.readString(folder.resolve(directory + "/games/lobby.yml")));
    }
}
