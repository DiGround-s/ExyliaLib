package net.exylia.lib.spatial;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.BundledFiles;
import net.exylia.lib.config.Configs;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin raising its layout's menu-version replaces a server's file nobody edited, although reading it as a
 * record filled in keys the jar never shipped (and an older library's defaults); an edited one stays.
 */
class LayoutUpgradeTest {

    private static final String NEW = """
            menu-version: 1
            title: '{primary}&lNEW'
            anchor: {mode: FRONT, distance: 6.0, lift: 0.0, clear-view: true}
            panels:
              hint: {at: [0, -2.8, 0.4], size: [6.0, 0.24], frame: false, content: {type: TEXT, lines: ['%catalog_hint%']}}
            carousel: {source: effects, style: SHELF, at: [0, -1.2, 0.4], slots: 8, rows: 2, spacing: 0.62, row-gap: 0.72,
                       centre-gap: 0, curve: 0.35}
            """;

    private static final String OLD = """
            title: '{primary}&lOLD'
            anchor:
              mode: FRONT
              distance: 3.4
            panels:
              header: {at: [0, 1.7, 0], size: [4.6, 0.34], title: '{primary}&lOLD', hint: '{muted}Sneak to leave'}
            carousel: {source: effects, at: [0, -1.05, 0.8], slots: 6, spacing: 0.7, centre-gap: 1.3, curve: 0.15}
            """;

    @TempDir
    Path folder;

    private Plugin plugin;
    private Path disk;

    @BeforeEach
    void setUp() throws Exception {
        FakeServer.install();
        FakeServer.reset();
        net.exylia.lib.config.internal.DefaultUpdates.releaseAll();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("UpgradePlugin", folder.toFile());
        // The new jar's layout, where the test classes are packaged (BundledFiles reads the anchor's code source).
        Path packaged = Path.of(LayoutUpgradeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("spatial/upgrade-test.yml");
        Files.createDirectories(packaged.getParent());
        Files.writeString(packaged, NEW);
        disk = folder.resolve("spatial/upgrade-test.yml");
        Path reviewed = folder.resolve(".defaults/files/spatial/upgrade-test.yml");
        Files.createDirectories(disk.getParent());
        Files.createDirectories(reviewed.getParent());
        // What the old jar installed, and what the record then made of it on the server.
        Files.writeString(disk, OLD);
        Files.writeString(reviewed, OLD);
        Configs.define(plugin, "spatial/upgrade-test", SpatialLayout.class).load();
        Configs.releaseAll();
        // Written by an older library, whose default this was.
        String file = Files.readString(disk);
        Files.writeString(disk, file.replace("hat|0.7|1.0", "hat|1.0|1.0"));
        assertTrue(Files.readString(disk).contains("hat|1.0|1.0"));
    }

    private void upgrade() {
        BundledFiles.recordWrote(plugin, LayoutUpgradeTest.class, "spatial/upgrade-test.yml", SpatialLayout.class,
                Map.of("theme.select-sound", List.of("minecraft:block.note_block.hat|1.0|1.0")));
        assertTrue(BundledFiles.refresh(plugin, LayoutUpgradeTest.class, "spatial/upgrade-test.yml"));
    }

    @Test
    void anUntouchedFileTakesTheNewLayout() throws Exception {
        upgrade();
        String now = Files.readString(disk);
        assertTrue(now.contains("{primary}&lNEW") && now.contains("SHELF"), now);
        assertTrue(Files.exists(disk.resolveSibling("upgrade-test.yml.v0")), "the old file is kept");
        SpatialLayout layout = Configs.define(plugin, "spatial/upgrade-test", SpatialLayout.class).load().get();
        assertEquals(1, layout.menuVersion(), "the version survives the record's own write");
        assertEquals(6.0, layout.anchor().distance(), 1e-9);
    }

    @Test
    void anEditedFileKeepsItsLayout() throws Exception {
        Files.writeString(disk, Files.readString(disk).replace("distance: 3.4", "distance: 4.0"));
        upgrade();
        String now = Files.readString(disk);
        assertTrue(now.contains("{primary}&lOLD") && now.contains("distance: 4.0"), now);
        assertFalse(Files.exists(disk.resolveSibling("upgrade-test.yml.v0")));
    }
}
