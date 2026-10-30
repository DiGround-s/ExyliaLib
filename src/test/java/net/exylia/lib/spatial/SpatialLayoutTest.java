package net.exylia.lib.spatial;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialLayoutTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("SpatialTestPlugin", folder.toFile());
    }

    @Test
    void theDefaultLayoutRoundTrips() throws Exception {
        Configs.define(plugin, "spatial/wardrobe", SpatialLayout.class).load();
        Path written = folder.resolve("spatial/wardrobe.yml");
        assertTrue(Files.isRegularFile(written));
        String yaml = Files.readString(written);
        assertTrue(yaml.contains("hide-others"), yaml);
        assertTrue(yaml.contains("centre-gap"), yaml);
        Configs.releaseAll();
        ConfigFile<SpatialLayout> again = Configs.define(plugin, "spatial/wardrobe", SpatialLayout.class).load();
        assertTrue(again.issues().isEmpty(), "a generated file reads back clean: " + again.issues());
        assertEquals(new SpatialLayout(), again.get());
    }

    @Test
    void aShippedLayoutKeepsItsValuesAndGainsWhatItLacks() throws Exception {
        Path file = folder.resolve("spatial/trims.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                title: '{primary}&lARMOR TRIMS'
                anchor:
                  mode: FRONT
                  distance: 3.4
                panels:
                  header: {at: [0, 1.55, 0], size: [4.6, 0.32], title: '{primary}&lARMOR TRIMS', hint: '{muted}Sneak to leave'}
                  pieces:
                    at: [-2.05, 0.45, 0.45]
                    turn: 24
                    size: [1.35, 1.55]
                    title: '{secondary}&lPIECES'
                    content: {type: LIST, source: pieces, rows: 4}
                    footer: {label: '{error}✘ CLEAR', action: clear}
                carousel: {source: patterns, at: [0, -1.05, 1.1], slots: 6, spacing: 0.78, centre-gap: 1.2, curve: 0.15}
                """);
        SpatialLayout layout = Configs.define(plugin, "spatial/trims", SpatialLayout.class).load().get();
        assertEquals("{primary}&lARMOR TRIMS", layout.title());
        assertEquals(List.of("header", "pieces"), List.copyOf(layout.panels().keySet()), "the server's panels, not the examples");
        SpatialLayout.Panel pieces = layout.panel("pieces");
        assertEquals(-2.05, pieces.across(), 1e-9);
        assertEquals(24, pieces.turn(), 1e-9);
        assertEquals(SpatialLayout.Content.Type.LIST, pieces.content().type());
        assertEquals("clear", pieces.footer().action());
        assertEquals(0.0, layout.panel("header").across(), 1e-9, "written as an int");
        assertEquals(0.78, layout.carousel().spacing(), 1e-9);
        assertEquals(new SpatialTheme(), layout.theme(), "the look nobody wrote is the library's");
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("glass-alpha"), "missing keys are added: " + yaml);
    }
}
