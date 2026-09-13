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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packaged side is written into this class's build output directory, the
 * file-based branch of resource extraction. That directory persists across
 * runs, so every test packages under a name of its own.
 */
class BundledFilesTest {

    @TempDir
    Path folder;

    private Plugin plugin;
    private Path packagedRoot;

    @BeforeEach
    void setUp() throws Exception {
        FakeServer.install();
        FakeServer.reset();
        DefaultUpdates.releaseAll();
        plugin = FakeServer.newPlugin("Bundled", folder.toFile());
        packagedRoot = Path.of(BundledFilesTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @AfterEach
    void tearDown() {
        DebugCapture.stop();
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

    private Object value(String path, String key) throws Exception {
        return YamlConfiguration.loadConfiguration(folder.resolve(path).toFile()).get(key);
    }

    private boolean refresh(String resource) {
        return BundledFiles.refresh(plugin, BundledFilesTest.class, resource);
    }

    private static List<String> pendingKeys() {
        return DefaultUpdates.pending().stream().map(pending -> pending.change().dotted()).toList();
    }

    @Test
    void installsMissingFilesQuietly() throws Exception {
        pack("bundled-fresh/main.yml", "title: fresh");
        pack("bundled-fresh/games/lobby.yml", "title: lobby");
        List<String> messages = DebugCapture.start();

        assertTrue(refresh("bundled-fresh"), messages::toString);

        assertEquals("title: fresh", read("bundled-fresh/main.yml"));
        assertEquals("title: lobby", read("bundled-fresh/games/lobby.yml"));
        assertTrue(messages.isEmpty(), messages::toString);
    }

    @Test
    void refreshesASingleFile() throws Exception {
        pack("bundled-single/effects.yml", "flame:\n  particle: FLAME\n");

        assertTrue(refresh("bundled-single/effects.yml"));

        assertEquals("FLAME", value("bundled-single/effects.yml", "flame.particle"));
    }

    @Test
    void addsNewKeysAndKeepsTheOwnersEdits() throws Exception {
        pack("bundled-added/effects.yml", "flame:\n  speed: 1\n");
        assertTrue(refresh("bundled-added"));
        onDisk("bundled-added/effects.yml", "flame:\n  speed: 5\n");

        pack("bundled-added/effects.yml", "flame:\n  speed: 1\n# The newest effect.\nsnow:\n  speed: 2\n");
        assertTrue(refresh("bundled-added"));

        assertEquals(5, value("bundled-added/effects.yml", "flame.speed"));
        assertEquals(2, value("bundled-added/effects.yml", "snow.speed"));
        assertTrue(read("bundled-added/effects.yml").contains("# The newest effect."));
        assertTrue(DefaultUpdates.pending().isEmpty());
    }

    @Test
    void aChangedDefaultWaitsUntilItIsApplied() throws Exception {
        pack("bundled-changed/main.yml", "items:\n  close:\n    slot: 49\n");
        assertTrue(refresh("bundled-changed"));

        pack("bundled-changed/main.yml", "items:\n  close:\n    slot: 53\n");
        assertTrue(refresh("bundled-changed"));

        assertEquals(49, value("bundled-changed/main.yml", "items.close.slot"));
        assertEquals(List.of("items.close.slot"), pendingKeys());

        assertEquals(1, DefaultUpdates.decide(pending -> true, true).applied());

        assertEquals(53, value("bundled-changed/main.yml", "items.close.slot"));
        assertTrue(DefaultUpdates.pending().isEmpty());
        assertTrue(refresh("bundled-changed"));
        assertTrue(DefaultUpdates.pending().isEmpty());
    }

    @Test
    void aFileFromBeforeTrackingIsOfferedWhatItLacks() throws Exception {
        pack("bundled-predates/main.yml", "cooldown: 30\nrange: 12\n");
        onDisk("bundled-predates/main.yml", "cooldown: 45\n");

        assertTrue(refresh("bundled-predates"));
        assertTrue(refresh("bundled-predates"));

        assertEquals(45, value("bundled-predates/main.yml", "cooldown"));
        assertEquals(null, value("bundled-predates/main.yml", "range"));
        assertEquals(List.of("range"), pendingKeys());

        assertEquals(1, DefaultUpdates.decide(pending -> true, true).applied());
        assertEquals(12, value("bundled-predates/main.yml", "range"));
    }

    @Test
    void aFileTheOwnerDeletedIsNotWrittenAgain() throws Exception {
        pack("bundled-deleted/extra.yml", "title: extra");
        assertTrue(refresh("bundled-deleted"));
        Files.delete(folder.resolve("bundled-deleted/extra.yml"));

        assertTrue(refresh("bundled-deleted"));

        assertFalse(Files.exists(folder.resolve("bundled-deleted/extra.yml")));
    }

    @Test
    void aFileTheLedgerSaysWasUntouchedStartsFromWhatItHolds() throws Exception {
        pack("bundled-ledger/main.yml", "cooldown: 35\n");
        onDisk("bundled-ledger/main.yml", "cooldown: 30\n");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(folder.resolve("bundled-ledger/main.yml"))));
        onDisk(".bundled-files", "bundled-ledger/main.yml=" + hash + "\n");

        assertTrue(refresh("bundled-ledger"));

        assertEquals(30, value("bundled-ledger/main.yml", "cooldown"));
        assertEquals(List.of("cooldown"), pendingKeys());
    }

    @Test
    void aHigherMenuVersionReplacesTheFileAndKeepsTheOldOne() throws Exception {
        pack("bundled-forced/main.yml", "menu-version: 2\ntitle: new\n");
        onDisk("bundled-forced/main.yml", "menu-version: 1\ntitle: edited\n");

        assertTrue(refresh("bundled-forced"));

        assertEquals("new", value("bundled-forced/main.yml", "title"));
        assertEquals("menu-version: 1\ntitle: edited\n", read("bundled-forced/main.yml.v1"));
    }

    @Test
    void anUnreadableFileIsLeftUntouched() throws Exception {
        pack("bundled-broken/main.yml", "title: new\nextra: 1\n");
        onDisk("bundled-broken/main.yml", "title: [unclosed");
        List<String> messages = DebugCapture.start();

        assertFalse(refresh("bundled-broken"));

        assertEquals("title: [unclosed", read("bundled-broken/main.yml"));
        assertTrue(messages.stream().anyMatch(line -> line.contains("could not be read")), messages::toString);
    }
}
