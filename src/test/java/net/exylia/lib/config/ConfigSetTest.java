package net.exylia.lib.config;

import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A value set from code, such as a reset to its default, is written the way the loader reads it back. */
class ConfigSetTest {

    record Hidden(String mode, int rows) {
        Hidden() {
            this("KEEP", 2);
        }
    }

    record Settings(Duration openCooldown, Hidden hiddenSlots) {
        Settings() {
            this(Duration.ofSeconds(5), new Hidden());
        }
    }

    @TempDir
    Path folder;

    private Plugin plugin;

    @AfterEach
    void tearDown() {
        Configs.release(plugin);
    }

    @Test
    void aDurationOrARecordSetRawIsWrittenAsPlainYaml() throws Exception {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("ExyliaPlayerVaults", folder.toFile());
        ConfigFile<Settings> file = Configs.define(plugin, "config", Settings.class).load();

        assertTrue(file.set("open-cooldown", Duration.ofSeconds(30)).isEmpty());
        assertTrue(file.set("hidden-slots", new Hidden("RETURN", 3)).isEmpty());

        String yaml = Files.readString(folder.resolve("config.yml"));
        assertFalse(yaml.contains("!!"), yaml);
        assertEquals(Duration.ofSeconds(30), file.get().openCooldown());
        assertEquals(new Hidden("RETURN", 3), file.get().hiddenSlots());
    }
}
