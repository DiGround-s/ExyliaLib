package net.exylia.lib.internal;

import net.exylia.lib.database.DatabaseSettings;
import net.exylia.lib.text.Palette;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupNetworkTest {

    private static final SetupNetwork.Target MYSQL = new SetupNetwork.Target("mysql", "10.0.0.5", 0, "network",
            "exylia", "s3cret", "lobby-1", false, "localhost", 6379, "", "");

    @Test
    void writesTheSharedDatabaseAndKeepsEverythingElse(@TempDir Path folder) throws Exception {
        File file = folder.resolve("database.yml").toFile();
        Files.writeString(file.toPath(), """
                config-version: 2
                database:
                  type: h2
                  h2:
                    file: database/custom
                  settings:
                    pool-size: 6
                """);
        SetupNetwork.write(file, MYSQL);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        assertEquals("mysql", yaml.getString("database.type"));
        assertEquals("10.0.0.5", yaml.getString("database.mysql.host"));
        assertEquals("s3cret", yaml.getString("database.mysql.password"));
        assertEquals("database", yaml.getString("database.cross-server"));
        assertFalse(yaml.getBoolean("database.redis.enabled"));
        assertEquals("lobby-1", yaml.getString("database.redis.server-id"));
        // What the owner set stays.
        assertEquals("database/custom", yaml.getString("database.h2.file"));
        assertEquals(6, yaml.getInt("database.settings.pool-size"));
    }

    @Test
    void aNewFileIsMarkedAtTheCurrentLayout(@TempDir Path folder) {
        File file = folder.resolve("ExyliaLib/database.yml").toFile();
        SetupNetwork.write(file, MYSQL);
        assertEquals(2, YamlConfiguration.loadConfiguration(file).getInt("config-version"));
    }

    @Test
    void theSettingsTestedAreTheOnesWritten() {
        DatabaseSettings settings = SetupNetwork.settings(MYSQL);
        assertEquals("mysql", settings.engine());
        assertEquals("10.0.0.5", settings.database().mysql().host());
        assertTrue(settings.database().crossServerByDatabase());
        assertEquals("lobby-1", settings.database().redis().serverId());
    }

    @Test
    void aFailureNeverRepeatsThePassword() {
        RuntimeException failure = new RuntimeException("pool",
                new IllegalStateException("Access denied for user 'exylia' using password s3cret"));
        String reason = SetupNetwork.reason(failure, MYSQL);
        assertFalse(reason.contains("s3cret"), reason);
        assertTrue(reason.startsWith("Access denied"), reason);
    }

    @Test
    void aThemeKeepsTheColoursThatCarryMeaning() {
        Palette before = new Palette();
        SetupMenu.Theme ocean = SetupMenu.THEMES.get(1);
        Palette after = ocean.on(before);
        assertTrue(ocean.worn(after));
        assertEquals(before.error(), after.error());
        assertEquals(before.success(), after.success());
        assertEquals(before.serverIp(), after.serverIp());
        assertFalse(SetupMenu.THEMES.get(0).worn(after));
    }
}
