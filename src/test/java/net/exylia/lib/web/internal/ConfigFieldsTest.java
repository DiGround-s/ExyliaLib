package net.exylia.lib.web.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.config.Time;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a record's keys become in-game controls, and that a click writes back what the file holds. */
class ConfigFieldsTest {

    enum Mode { SOFT_RESET, HARD }

    record Limits(@Comment("How far apart, in blocks.") int minDistance,
                  double ratio,
                  @Time int cooldownSeconds,
                  Duration lasts) {
        Limits() {
            this(4, 0.5, 30, Duration.ofSeconds(90));
        }
    }

    record Settings(boolean enabled, Mode mode, String title, Material icon, List<String> lines,
                    Map<String, Integer> weights, Limits limits) {
        Settings() {
            this(true, Mode.SOFT_RESET, "Hi", Material.STONE, List.of("a"), Map.of("x", 1), new Limits());
        }
    }

    @TempDir
    Path folder;

    private Plugin plugin;
    private ConfigFile<Settings> file;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("ExyliaMines", folder.toFile());
        file = Configs.define(plugin, "config", Settings.class).load();
    }

    @AfterEach
    void tearDown() {
        Configs.release(plugin);
    }

    @Test
    void theScreensCompileInEveryLanguage() {
        List<String> messages = net.exylia.lib.debug.DebugCapture.start();
        try {
            Plugin library = FakeServer.newPlugin("ExyliaLib", folder.resolve("lib").toFile());
            FakeServer.packageMainResources("lang");
            ConfigMenu.anchor(FakeServer.class);
            ConfigMenu.init(library, "exylialib");
            var menus = net.exylia.lib.ui.Menus.of(library, "exylialib");
            for (String code : List.of("en", "es", "fr", "pt", "ru", "tr")) {
                net.exylia.lib.config.Languages.use(library, code);
                ConfigMenu.load(menus);
                for (String id : List.of(ConfigMenu.PLUGINS, ConfigMenu.FILES, ConfigMenu.FIELDS)) {
                    assertTrue(menus.definition(id).isPresent(), code + " " + id);
                }
                menus.unload();
            }
            assertTrue(messages.isEmpty(), messages::toString);
        } finally {
            ConfigMenu.release();
            net.exylia.lib.ui.Menus.releaseAll();
            net.exylia.lib.action.Actions.releaseAll();
            net.exylia.lib.debug.DebugCapture.stop();
        }
    }

    @Test
    void everyTypeGetsItsControl() {
        Map<String, ConfigFields.Kind> kinds = new java.util.LinkedHashMap<>();
        for (ConfigFields.Field field : ConfigFields.fields(file.schema(), "")) kinds.put(field.key(), field.kind());
        for (ConfigFields.Field field : ConfigFields.fields(file.schema(), "limits")) kinds.put(field.key(), field.kind());
        assertEquals(ConfigFields.Kind.TOGGLE, kinds.get("enabled"));
        assertEquals(ConfigFields.Kind.CHOICE, kinds.get("mode"));
        assertEquals(ConfigFields.Kind.TEXT, kinds.get("title"));
        assertEquals(ConfigFields.Kind.MATERIAL, kinds.get("icon"));
        assertEquals(ConfigFields.Kind.LINES, kinds.get("lines"));
        assertEquals(ConfigFields.Kind.LOCKED, kinds.get("weights"));
        assertEquals(ConfigFields.Kind.SECTION, kinds.get("limits"));
        assertEquals(ConfigFields.Kind.INTEGER, kinds.get("limits.min-distance"));
        assertEquals(ConfigFields.Kind.DECIMAL, kinds.get("limits.ratio"));
        assertEquals(ConfigFields.Kind.TIME, kinds.get("limits.cooldown-seconds"));
        assertEquals(ConfigFields.Kind.TIME, kinds.get("limits.lasts"));
        assertNull(ConfigFields.field(file.schema(), "limits.nothing"));
        assertTrue(ConfigFields.about(ConfigFields.field(file.schema(), "limits.min-distance").schema().comments())
                .contains("How far apart"));
    }

    @Test
    void clicksWriteWhatTheFileHoldsAndReadBack() throws Exception {
        ConfigFields.Field distance = ConfigFields.field(file.schema(), "limits.min-distance");
        Object up = ConfigFields.step(distance, ConfigFields.read(file.schema(), file.get(), distance.key()), 1, true);
        assertEquals(14, up);
        assertTrue(file.set(distance.key(), up).isEmpty());
        assertEquals(14, file.get().limits().minDistance());

        ConfigFields.Field mode = ConfigFields.field(file.schema(), "mode");
        Object next = ConfigFields.step(mode, file.get().mode(), 1, false);
        assertEquals("hard", next);
        file.set(mode.key(), next);
        assertEquals(Mode.HARD, file.get().mode());
        assertEquals("soft-reset", ConfigFields.step(mode, file.get().mode(), 1, false), "wraps around");

        ConfigFields.Field cooldown = ConfigFields.field(file.schema(), "limits.cooldown-seconds");
        assertEquals(Duration.ofSeconds(30), ConfigFields.duration(cooldown, file.get().limits().cooldownSeconds()));
        file.set(cooldown.key(), ConfigFields.time(cooldown, Duration.ofMinutes(2)));
        assertEquals(120, file.get().limits().cooldownSeconds(), "a @Time number stays in its unit");

        ConfigFields.Field lasts = ConfigFields.field(file.schema(), "limits.lasts");
        file.set(lasts.key(), ConfigFields.time(lasts, Duration.ofSeconds(75)));
        assertEquals(Duration.ofSeconds(75), file.get().limits().lasts());

        ConfigFields.Field ratio = ConfigFields.field(file.schema(), "limits.ratio");
        assertEquals(0.4, ConfigFields.step(ratio, 0.5, -1, false));

        ConfigFields.Field enabled = ConfigFields.field(file.schema(), "enabled");
        file.set(enabled.key(), ConfigFields.step(enabled, file.get().enabled(), 1, false));
        assertEquals(false, file.get().enabled());

        file.set("lines", ConfigFields.lines("one\ntwo"));
        assertEquals(List.of("one", "two"), file.get().lines());

        String yaml = Files.readString(folder.resolve("config.yml"));
        assertTrue(yaml.contains("mode: hard"), yaml);
        assertTrue(yaml.contains("lasts: 1m15s"), yaml);
    }
}
