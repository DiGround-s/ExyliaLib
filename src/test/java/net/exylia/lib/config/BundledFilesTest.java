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
    void aKeyTheLibraryWroteBeforeTheJarShippedItIsOfferedNotKept() throws Exception {
        pack("bundled-library/spatial.yml", "preview:\n  scale: 0.8\n");
        assertTrue(refresh("bundled-library/spatial.yml"));
        // The newer library's record adds its keys with its own values, the old jar still in place.
        onDisk("bundled-library/spatial.yml", "preview:\n  scale: 0.8\n  turn-controls: false\n  turn-pause: 8.0\n");
        // The plugin's new jar ships its own value for one of them.
        pack("bundled-library/spatial.yml", "preview:\n  scale: 0.8\n  turn-controls: true\n");

        BundledFiles.libraryWrote(plugin, BundledFilesTest.class, "bundled-library/spatial.yml",
                java.util.Map.of("preview.turn-controls", false, "preview.turn-pause", 8));
        assertTrue(refresh("bundled-library/spatial.yml"));

        assertEquals(false, value("bundled-library/spatial.yml", "preview.turn-controls"));
        assertEquals(List.of("preview.turn-controls"), pendingKeys());
    }

    @Test
    void anOwnersValueForSuchAKeyStaysTheirs() throws Exception {
        pack("bundled-library-own/spatial.yml", "preview:\n  scale: 0.8\n");
        assertTrue(refresh("bundled-library-own/spatial.yml"));
        onDisk("bundled-library-own/spatial.yml", "preview:\n  scale: 0.8\n  turn-controls: true\n");
        pack("bundled-library-own/spatial.yml", "preview:\n  scale: 0.8\n  turn-controls: false\n");

        BundledFiles.libraryWrote(plugin, BundledFilesTest.class, "bundled-library-own/spatial.yml",
                java.util.Map.of("preview.turn-controls", false));
        assertTrue(refresh("bundled-library-own/spatial.yml"));

        assertEquals(true, value("bundled-library-own/spatial.yml", "preview.turn-controls"));
        assertTrue(DefaultUpdates.pending().isEmpty());
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
    void aFileRewrittenFromARecordIsStillOfferedItsChangedDefaults() throws Exception {
        pack("bundled-record/spatial/menu.yml", "header:\n  at: [0, 1.98, 0]\n  size: [4.6, 0.34]\n");
        assertTrue(refresh("bundled-record/spatial/menu.yml"));
        // What a spatial layout looks like once its record has written it back: every number a double.
        onDisk("bundled-record/spatial/menu.yml", "header:\n  at: [0.0, 1.98, 0.0]\n  size: [4.6, 0.34]\n");

        pack("bundled-record/spatial/menu.yml", "header:\n  at: [0, 2.1, 0]\n  size: [2.7, 0.4]\n");
        assertTrue(refresh("bundled-record/spatial/menu.yml"));
        // The same file followed as a config, with nothing of its own pending, leaves those alone.
        DefaultUpdates.track(plugin, "bundled-record/spatial/menu.yml", folder.resolve("bundled-record/spatial/menu.yml"),
                folder.resolve(".defaults/configs/bundled-record/spatial/menu.yml"), "", null, List.of());

        assertEquals(List.of("header.at", "header.size"), pendingKeys());
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
    void aFileTheOwnerDeletedIsWrittenAgain() throws Exception {
        pack("bundled-deleted/extra.yml", "title: extra");
        assertTrue(refresh("bundled-deleted"));
        Files.delete(folder.resolve("bundled-deleted/extra.yml"));

        assertTrue(refresh("bundled-deleted"));

        assertEquals("extra", value("bundled-deleted/extra.yml", "title"));
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
    void aHigherMenuVersionReplacesAnUntouchedFileAndKeepsTheOldOne() throws Exception {
        pack("bundled-forced/main.yml", "menu-version: 1\ntitle: old\n");
        assertTrue(refresh("bundled-forced"));

        pack("bundled-forced/main.yml", "menu-version: 2\ntitle: new\n");
        assertTrue(refresh("bundled-forced"));

        assertEquals("new", value("bundled-forced/main.yml", "title"));
        assertEquals("menu-version: 1\ntitle: old\n", read("bundled-forced/main.yml.v1"));
        assertTrue(DefaultUpdates.pending().isEmpty());
    }

    @Test
    void aHigherMenuVersionWaitsForAnEditedFileUntilItIsApplied() throws Exception {
        pack("bundled-layout/main.yml", "menu-version: 2\ntitle: new\n");
        onDisk("bundled-layout/main.yml", "title: edited\n");

        assertTrue(refresh("bundled-layout"));

        assertEquals("title: edited\n", read("bundled-layout/main.yml"));
        assertEquals(List.of("menu-version"), pendingKeys());

        assertEquals(1, DefaultUpdates.decide(pending -> true, true).applied());

        assertEquals("new", value("bundled-layout/main.yml", "title"));
        assertEquals("title: edited\n", read("bundled-layout/main.yml.v0"));
        assertTrue(DefaultUpdates.pending().isEmpty());
        assertTrue(refresh("bundled-layout"));
        assertTrue(DefaultUpdates.pending().isEmpty());
    }

    @Test
    void anEditedFileThatKeepsItsLayoutIsNotAskedAgain() throws Exception {
        pack("bundled-kept/main.yml", "menu-version: 1\ntitle: old\n");
        assertTrue(refresh("bundled-kept"));
        onDisk("bundled-kept/main.yml", "menu-version: 1\ntitle: edited\n");

        pack("bundled-kept/main.yml", "menu-version: 2\ntitle: new\nextra: 1\n");
        assertTrue(refresh("bundled-kept"));
        assertEquals(1, DefaultUpdates.decide(pending -> true, false).kept());

        assertEquals("edited", value("bundled-kept/main.yml", "title"));
        assertEquals(2, value("bundled-kept/main.yml", "menu-version"));
        assertTrue(refresh("bundled-kept"));
        assertEquals("edited", value("bundled-kept/main.yml", "title"));
        assertTrue(DefaultUpdates.pending().isEmpty());
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

    @Test
    void aFileThatIsNotYamlFollowsTheJarUntilTheOwnerEditsIt() throws Exception {
        pack("bundled-html/hub.html", "<screen>one</screen>");
        assertTrue(refresh("bundled-html"));
        pack("bundled-html/hub.html", "<screen>two</screen>");
        assertTrue(refresh("bundled-html"));
        assertEquals("<screen>two</screen>", read("bundled-html/hub.html"), "untouched follows the jar");

        onDisk("bundled-html/hub.html", "<screen>mine</screen>");
        pack("bundled-html/hub.html", "<screen>three</screen>");
        assertTrue(refresh("bundled-html"));
        assertEquals("<screen>mine</screen>", read("bundled-html/hub.html"), "an edit stays");
        assertEquals("<screen>three</screen>", read("bundled-html/hub.html.new"), "the new one waits beside it");
        assertEquals(1, DefaultUpdates.pending().size());

        DefaultUpdates.decide(pending -> true, true);
        assertEquals("<screen>three</screen>", read("bundled-html/hub.html"));
        assertEquals("<screen>mine</screen>", read("bundled-html/hub.html.old"));
        assertTrue(DefaultUpdates.pending().isEmpty());
        assertTrue(refresh("bundled-html"));
        assertTrue(DefaultUpdates.pending().isEmpty(), "applied is reviewed");
    }

    @Test
    void aRetiredFileGoesUnlessTheOwnerEditedIt() throws Exception {
        pack("bundled-retire/a.html", "<screen>a</screen>");
        pack("bundled-retire/b.html", "<screen>b</screen>");
        assertTrue(refresh("bundled-retire"));
        onDisk("bundled-retire/b.html", "<screen>mine</screen>");

        assertTrue(BundledFiles.retire(plugin, "bundled-retire/a.html"));
        assertFalse(java.nio.file.Files.exists(folder.resolve("bundled-retire/a.html")));
        assertFalse(BundledFiles.retire(plugin, "bundled-retire/b.html"));
        assertEquals("<screen>mine</screen>", read("bundled-retire/b.html"));
        assertTrue(BundledFiles.retire(plugin, "bundled-retire/never.html"));
    }
}
