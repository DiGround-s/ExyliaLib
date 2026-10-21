package net.exylia.lib.util.crate.internal;

import net.exylia.lib.FakeServer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminScreensTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path folder;

    @BeforeAll
    static void install() {
        FakeServer.install();
        FakeServer.packageMainResources("lang");
        AdminScreens.anchor(FakeServer.class);
    }

    @Test
    @DisplayName("every screen compiles into the library's menus with nothing reported")
    void screensCompile() {
        List<String> messages = net.exylia.lib.debug.DebugCapture.start();
        try {
            Plugin library = FakeServer.newPlugin("ExyliaLib", folder.resolve("lib").toFile());
            AdminScreens.init(library, "exylialib");
            var menus = net.exylia.lib.ui.Menus.of(library, "exylialib");
            AdminScreens.load(menus);
            for (String id : AdminScreens.SCREENS) assertTrue(menus.definition(id).isPresent(), id);
            assertTrue(messages.isEmpty(), messages::toString);
        } finally {
            AdminScreens.release();
            net.exylia.lib.ui.Menus.releaseAll();
            net.exylia.lib.action.Actions.releaseAll();
            net.exylia.lib.debug.DebugCapture.stop();
        }
    }

    @Test
    @DisplayName("every language ships every screen with the same slots and actions as English")
    void translationsMatch() throws Exception {
        for (String code : List.of("es", "fr", "pt", "ru", "tr")) {
            for (String id : AdminScreens.SCREENS) {
                YamlConfiguration english = packaged("en", id);
                YamlConfiguration translated = packaged(code, id);
                assertEquals(english.getInt("size"), translated.getInt("size"), code + " " + id);
                for (String key : english.getKeys(true)) {
                    if (key.endsWith(".slot") || key.endsWith(".actions") || key.endsWith(".condition")) {
                        assertEquals(english.get(key), translated.get(key), code + " " + id + " " + key);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a plugin is called by its name without the suite's prefix")
    void title() {
        assertEquals("Arrows", AdminScreens.title("ExyliaArrows"));
        assertEquals("Exylia", AdminScreens.title("Exylia"));
        assertEquals("Shop", AdminScreens.title("Shop"));
    }

    private static YamlConfiguration packaged(String code, String id) throws Exception {
        String resource = "lang/" + code + "/" + AdminScreens.FOLDER + "/" + id + ".yml";
        try (InputStream stream = AdminScreens.class.getClassLoader().getResourceAsStream(resource)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(Objects.requireNonNull(stream, resource).readAllBytes(), StandardCharsets.UTF_8));
            return yaml;
        }
    }
}
