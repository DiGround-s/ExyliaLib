package net.exylia.lib.discord;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A template nests in a config record, and reads the same keys from a hand-written file. */
class WebhookTemplateConfigTest {

    public record Lang(WebhookTemplate started) {
        public Lang() {
            this(new WebhookTemplate("CAPTURE STARTED", "Zone **%zone%** is now open.")
                    .withFields(List.of("Duration|%duration% ⌚|inline")));
        }
    }

    @TempDir
    Path folder;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("WebhookConfig", folder.toFile());
    }

    @AfterEach
    void tearDown() {
        Configs.release(plugin);
        FakeServer.reset();
    }

    @Test
    void writesAndReadsTheRecordKeys() throws Exception {
        ConfigFile<Lang> file = Configs.define(plugin, "lang", Lang.class).load();
        assertEquals(new Lang(), file.get());
        String yaml = Files.readString(folder.resolve("lang.yml"));
        assertTrue(yaml.contains("avatar-url:"), yaml);
        assertTrue(yaml.contains("coalesce:"), yaml);
        assertFalse(yaml.contains("author:"), "an empty author is not written");

        YamlConfiguration written = YamlConfiguration.loadConfiguration(folder.resolve("lang.yml").toFile());
        assertEquals(file.get().started(), WebhookTemplate.read(written.getConfigurationSection("started")));
    }

    @Test
    void readsAHandWrittenSection() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("t.title", "Hi");
        yaml.set("t.author.name", "%player%");
        yaml.set("t.coalesce", "3s");
        yaml.set("t.fields", List.of("a|b"));
        WebhookTemplate template = WebhookTemplate.read(yaml.getConfigurationSection("t"));
        assertEquals("Hi", template.title());
        assertEquals("%player%", template.author().name());
        assertEquals(Duration.ofSeconds(3), template.coalesce());
        assertEquals(List.of("a|b"), template.fields());
        assertEquals("{primary}", template.color());
        assertTrue(template.enabled());
    }
}
