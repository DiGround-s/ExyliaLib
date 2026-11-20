package net.exylia.lib.web.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A settings card reaches the values inside one entry of a map of records, and a new entry starts from its defaults. */
class ConfigEntriesTest {

    record Tiers(long fullUpTo, double reducedPercent) {
        Tiers() {
            this(250_000, 50);
        }
    }

    record Group(List<String> materials, boolean ownTiers, Tiers tiers) {
        Group() {
            this(List.of(), false, new Tiers());
        }
    }

    record Daily(Map<String, Group> groups) {
        Daily() {
            this(Map.of("fungus", new Group(List.of("CRIMSON_FUNGUS"), true, new Tiers(100_000, 40))));
        }
    }

    record Settings(Daily daily) {
        Settings() {
            this(new Daily());
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
        plugin = FakeServer.newPlugin("ExyliaMarket", folder.toFile());
        file = Configs.define(plugin, "config", Settings.class).load();
    }

    @AfterEach
    void tearDown() {
        Configs.release(plugin);
    }

    @Test
    void keysReachInsideAnEntry() {
        ConfigFields.Field full = ConfigFields.field(file.schema(), "daily.groups.fungus.tiers.full-up-to");
        assertEquals(ConfigFields.Kind.INTEGER, full.kind());
        assertEquals(ConfigFields.Kind.TOGGLE, ConfigFields.field(file.schema(), "daily.groups.any.own-tiers").kind());
        assertEquals(ConfigFields.Kind.ENTRIES, ConfigFields.field(file.schema(), "daily.groups").kind());
        assertNull(ConfigFields.field(file.schema(), "daily.groups.fungus.nothing"));
        assertNull(ConfigFields.section(file.schema(), "daily.groups"), "the map itself is no section");

        assertEquals(100_000L, ConfigFields.read(file.schema(), file.get(), full.key()));
        assertNull(ConfigFields.read(file.schema(), file.get(), "daily.groups.basalt.tiers.full-up-to"));
        assertEquals(List.of("fungus"), ConfigMenu.entries(file, "daily.groups"));
        assertTrue(ConfigMenu.entries(file, "daily").isEmpty());
    }

    @Test
    void anEntryTheDefaultsLackReadsAsItsRecordDefaults() {
        Settings defaults = new Settings();
        assertEquals(250_000L, ConfigFields.readDefault(file.schema(), defaults, "daily.groups.basalt.tiers.full-up-to"));
        assertEquals(100_000L, ConfigFields.readDefault(file.schema(), defaults, "daily.groups.fungus.tiers.full-up-to"));
        assertEquals(false, ConfigFields.readDefault(file.schema(), defaults, "daily.groups.basalt.own-tiers"));
    }

    @Test
    void aNewEntryIsFilledAndWrittenOutAndAWriteInsideItSticks() throws Exception {
        assertTrue(file.set("daily.groups.basalt", new LinkedHashMap<String, Object>()).stream()
                .allMatch(issue -> issue.type() == net.exylia.lib.config.ConfigIssue.Type.MISSING_KEY));
        assertEquals(new Group(), file.get().daily().groups().get("basalt"));
        assertTrue(Files.readString(folder.resolve("config.yml")).contains("full-up-to: 250000"),
                () -> "the defaults are written into the file");

        file.set("daily.groups.basalt.tiers.full-up-to", 5_000L);
        file.set("daily.groups.basalt.materials", List.of("BASALT"));
        Group basalt = file.get().daily().groups().get("basalt");
        assertEquals(5_000L, basalt.tiers().fullUpTo());
        assertEquals(List.of("BASALT"), basalt.materials());

        file.set("daily.groups.basalt", null);
        assertFalse(file.get().daily().groups().containsKey("basalt"));
        assertTrue(file.get().daily().groups().containsKey("fungus"));
    }
}
