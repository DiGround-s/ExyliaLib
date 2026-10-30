package net.exylia.lib.spatial;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.Configs;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureClassLoader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin's layout under a loader that keeps its jar in memory (Lukittu): it is found, and a server
 * file that 1.406.0-1.411.0 filled with the library's generic wardrobe instead is repaired.
 */
class LibraryDefaultLayoutTest {

    private static final String SHIPPED = """
            title: '{primary}&lKILL EFFECTS'
            panels:
              header: {at: [0, 1.7, 0], size: [4.6, 0.34], title: '%header_title%'}
              info:
                at: [-2.05, 0.3, 0.45]
                turn: 24
                content: {type: TEXT, lines: ['%effect_name%']}
            carousel: {source: effects, at: [0, -1.05, 0.8], slots: 6}
            """;

    @TempDir
    Path folder;

    private static int made;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("SpatialRepair" + (++made), folder.toFile());
    }

    @AfterEach
    void tearDown() {
        SpatialMenus.release(plugin);
        Configs.releaseAll();
    }

    /** A loader that answers getResourceAsStream only, as Lukittu's does, with one class defined in it. */
    private static Class<?> inMemoryAnchor(Map<String, String> resources) throws Exception {
        final class Memory extends SecureClassLoader {
            Memory() {
                super(LibraryDefaultLayoutTest.class.getClassLoader());
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                String text = resources.get(name);
                return text == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(
                        text.getBytes(StandardCharsets.UTF_8));
            }

            Class<?> define(String name, byte[] bytes) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
        String name = Anchor.class.getName();
        byte[] bytes;
        try (InputStream in = Anchor.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            bytes = in.readAllBytes();
        }
        return new Memory().define(name, bytes);
    }

    /** Only here to be defined again by the in-memory loader. */
    static final class Anchor {
    }

    @Test
    void anInMemoryLayoutIsFound() throws Exception {
        Class<?> anchor = inMemoryAnchor(Map.of("spatial/effects.yml", SHIPPED));
        assertNull(anchor.getClassLoader().getResource("spatial/effects.yml"), "the old check's blind spot");
        assertTrue(SpatialMenus.packaged(anchor, "spatial/effects.yml"));
        assertFalse(SpatialMenus.packaged(anchor, "spatial/nothing.yml"));
    }

    @Test
    void theGenericWardrobeIsRecognised() {
        SpatialMenu effects = new SpatialMenu("effects");
        effects.source("effects", ctx -> List.of());
        assertTrue(SpatialMenus.isLibraryDefault(new SpatialLayout(), effects));
        // Positions moved between versions: 1.406.0's wardrobe still counts.
        SpatialLayout old = new SpatialLayout().withPanel("header",
                new SpatialLayout().panel("header").moved(0, 1.55, 0, 0, 4.6, 0.34));
        assertTrue(SpatialMenus.isLibraryDefault(old, effects));

        SpatialMenu wardrobe = new SpatialMenu("wardrobe");
        wardrobe.source("categories", ctx -> List.of());
        assertFalse(SpatialMenus.isLibraryDefault(new SpatialLayout(), wardrobe), "a menu built on the defaults");
        SpatialLayout own = new SpatialLayout().withCarousel(new SpatialLayout.Carousel("effects",
                List.of(0.0, -1.05, 0.8), 6, 0.62, 1.3, 0.15));
        assertFalse(SpatialMenus.isLibraryDefault(own, effects), "a layout of the plugin's own");
    }

    @Test
    void aStaleWardrobeIsReplacedByTheJarsLayout() throws Exception {
        // What a Lukittu server was left with: the library's wardrobe in the plugin's file.
        Configs.define(plugin, "spatial/effects", SpatialLayout.class).load();
        Configs.releaseAll();
        Path file = folder.resolve("spatial/effects.yml");
        assertTrue(Files.readString(file).contains("WARDROBE"));

        Class<?> anchor = inMemoryAnchor(Map.of("spatial/effects.yml", SHIPPED));
        SpatialMenus menus = SpatialMenus.of(plugin);
        menus.register("effects", anchor, menu -> menu.source("effects", ctx -> List.of()));

        SpatialLayout loaded = menus.layout("effects").get();
        assertEquals("{primary}&lKILL EFFECTS", loaded.title());
        assertEquals("effects", loaded.carousel().source());
        assertTrue(loaded.panels().containsKey("info"));
        assertFalse(loaded.panels().containsKey("left"));
        assertTrue(Files.isRegularFile(folder.resolve("spatial/effects.yml.library-default")), "the old file is kept");

        // A second start leaves the plugin's file alone.
        Configs.releaseAll();
        menus.register("effects", anchor, menu -> menu.source("effects", ctx -> List.of()));
        assertEquals("{primary}&lKILL EFFECTS", menus.layout("effects").get().title());
    }

    @Test
    void aMissingLayoutIsInstalledFromMemory() throws Exception {
        Class<?> anchor = inMemoryAnchor(Map.of("spatial/effects.yml", SHIPPED));
        SpatialMenus.of(plugin).register("effects", anchor, menu -> menu.source("effects", ctx -> List.of()));
        assertEquals("{primary}&lKILL EFFECTS", SpatialMenus.of(plugin).layout("effects").get().title());
        assertFalse(Files.exists(folder.resolve("spatial/effects.yml.library-default")));
    }
}
