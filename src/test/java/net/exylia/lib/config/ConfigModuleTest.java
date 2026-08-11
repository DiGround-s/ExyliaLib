package net.exylia.lib.config;

import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the config module end to end against real files on disk.
 *
 * <p>These are the promises the module makes to consumers: the file is generated
 * from the schema, user edits survive upgrades, bad values never stop a server,
 * and renames carry values over.
 */
class ConfigModuleTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    // ------------------------------------------------------------------
    // Schemas used by the tests
    // ------------------------------------------------------------------

    @Comment("Settings for the test plugin.")
    record Settings(
            @Comment("Connections kept open.")
            int poolSize,
            String serverName,
            boolean enabled,
            double multiplier,
            List<String> worlds,
            Mode mode,
            Nested nested
    ) {
        Settings() {
            this(10, "lobby", true, 1.5, List.of("world", "nether"), Mode.FAST, new Nested());
        }

        record Nested(int ttlMinutes, String message) {
            Nested() {
                this(30, "hello");
            }
        }
    }

    enum Mode {
        FAST, SAFE, VERY_SAFE
    }

    record Renamed(int poolSize) {
        Renamed() {
            this(10);
        }
    }

    record NoDefaults(int value) {
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("ConfigTestPlugin", folder.toFile());
    }

    private Path file(String name) {
        return folder.resolve(name + ".yml");
    }

    private String contents(String name) throws IOException {
        return Files.readString(file(name), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("creates the file from the schema on first run")
    void generatesFileFromSchema() throws IOException {
        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        assertTrue(Files.exists(file("config")), "the file should be created");

        Settings values = config.get();
        assertEquals(10, values.poolSize());
        assertEquals("lobby", values.serverName());
        assertEquals(Mode.FAST, values.mode());
        assertEquals(30, values.nested().ttlMinutes());
        assertEquals(List.of("world", "nether"), values.worlds());

        String yaml = contents("config");
        assertTrue(yaml.contains("pool-size: 10"), "camelCase should become kebab-case:\n" + yaml);
        assertTrue(yaml.contains("# Connections kept open."), "comments should be written:\n" + yaml);
        assertTrue(yaml.contains("# Settings for the test plugin."), "header should be written:\n" + yaml);
        assertTrue(yaml.contains("mode: fast"), "enums should be readable:\n" + yaml);
    }

    @Test
    @DisplayName("reads back the values a user edited")
    void readsUserValues() throws IOException {
        Configs.define(plugin, "config", Settings.class).load();
        Configs.releaseAll();

        String edited = contents("config")
                .replace("pool-size: 10", "pool-size: 42")
                .replace("server-name: lobby", "server-name: arena");
        Files.writeString(file("config"), edited);

        Settings values = Configs.define(plugin, "config", Settings.class).load().get();

        assertEquals(42, values.poolSize());
        assertEquals("arena", values.serverName());
    }

    // ------------------------------------------------------------------
    // Automatic updating
    // ------------------------------------------------------------------

    @Test
    @DisplayName("adds keys added to the schema without touching user edits")
    void addsNewKeysKeepingUserEdits() throws IOException {
        // A file written by an older version of the plugin: one key, edited.
        Files.writeString(file("config"), "pool-size: 99\n");

        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        assertEquals(99, config.get().poolSize(), "the user's value must survive");
        assertEquals("lobby", config.get().serverName(), "missing keys fall back to defaults");

        String yaml = contents("config");
        assertTrue(yaml.contains("pool-size: 99"), "the user's value must stay in the file:\n" + yaml);
        assertTrue(yaml.contains("server-name: lobby"), "new keys must be written:\n" + yaml);
        assertTrue(yaml.contains("ttl-minutes: 30"), "new nested keys must be written:\n" + yaml);
    }

    @Test
    @DisplayName("leaves keys it does not own untouched")
    void preservesUnknownKeys() throws IOException {
        Files.writeString(file("config"), "pool-size: 5\nsomething-else: keep me\n");

        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        assertTrue(contents("config").contains("something-else: keep me"),
                "a key the schema does not define must not be deleted");
        assertTrue(config.issues().stream().anyMatch(i -> i.type() == ConfigIssue.Type.UNKNOWN_KEY),
                "but it should be reported so a typo is noticeable");
    }

    // ------------------------------------------------------------------
    // Bad input
    // ------------------------------------------------------------------

    @Test
    @DisplayName("falls back to the default when a value has the wrong type")
    void invalidValueFallsBackAndReports() throws IOException {
        Files.writeString(file("config"), "pool-size: not-a-number\n");

        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        assertEquals(10, config.get().poolSize(), "the default should be used");

        ConfigIssue issue = config.issues().stream()
                .filter(i -> i.type() == ConfigIssue.Type.INVALID_VALUE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the problem should be reported"));

        assertEquals("pool-size", issue.path());
        assertTrue(issue.describe().contains("whole number"), "the message should say what was expected");
        assertTrue(issue.describe().contains("not-a-number"), "and what was found: " + issue.describe());
    }

    @Test
    @DisplayName("survives a file that is not valid YAML")
    void brokenFileKeepsDefaults() throws IOException {
        Files.writeString(file("config"), "pool-size: 10\n  bad indentation: [\n");

        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        assertEquals(10, config.get().poolSize(), "defaults should be used rather than failing");
        assertTrue(config.issues().stream().anyMatch(i -> i.type() == ConfigIssue.Type.BROKEN_FILE),
                "the parse failure should be reported");
        assertTrue(contents("config").contains("bad indentation"),
                "the unreadable file must not be overwritten, or the user loses their edits");
    }

    @Test
    @DisplayName("accepts the spellings people actually write")
    void acceptsForgivingSpellings() throws IOException {
        Files.writeString(file("config"), """
                pool-size: "25"
                enabled: yes
                multiplier: 2
                mode: very-safe
                worlds: solo
                """);

        Settings values = Configs.define(plugin, "config", Settings.class).load().get();

        assertEquals(25, values.poolSize(), "a quoted number is still a number");
        assertTrue(values.enabled(), "yes means true");
        assertEquals(2.0, values.multiplier(), "a whole number is a valid decimal");
        assertEquals(Mode.VERY_SAFE, values.mode(), "kebab-case should map to the enum constant");
        assertEquals(List.of("solo"), values.worlds(), "a single value is a one element list");
    }

    @Test
    @DisplayName("rejects a decimal where a whole number belongs")
    void rejectsDecimalForInt() throws IOException {
        // Rounding 0.5 to 0 silently would be worse than saying so.
        Files.writeString(file("config"), "pool-size: 2.5\n");

        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        assertEquals(10, config.get().poolSize());
        assertTrue(config.issues().stream().anyMatch(i -> i.type() == ConfigIssue.Type.INVALID_VALUE));
    }

    // ------------------------------------------------------------------
    // Reloading
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reload publishes new values and notifies listeners")
    void reloadPublishesAndNotifies() throws IOException {
        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();
        AtomicReference<Integer> seen = new AtomicReference<>();
        config.onReload(values -> seen.set(values.poolSize()));

        Files.writeString(file("config"), contents("config").replace("pool-size: 10", "pool-size: 77"));
        config.reload();

        assertEquals(77, config.get().poolSize(), "get() must return the new value");
        assertEquals(77, seen.get(), "the listener must see the new value");
    }

    @Test
    @DisplayName("a broken edit during reload keeps the values already in use")
    void reloadKeepsPreviousValuesWhenFileBreaks() throws IOException {
        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();
        config.update(current -> new Settings(55, current.serverName(), current.enabled(),
                current.multiplier(), current.worlds(), current.mode(), current.nested()));

        Files.writeString(file("config"), "this: [is not: valid\n");
        config.reload();

        assertEquals(55, config.get().poolSize(),
                "a running server must not silently revert to defaults");
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("update writes to disk and is visible immediately")
    void updateWritesAndPublishes() throws IOException {
        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        config.update(current -> new Settings(64, "changed", current.enabled(),
                current.multiplier(), current.worlds(), current.mode(), current.nested()));

        assertEquals(64, config.get().poolSize());
        assertTrue(contents("config").contains("pool-size: 64"), "the change must reach the file");
        assertTrue(contents("config").contains("server-name: changed"));
    }

    // ------------------------------------------------------------------
    // Migrations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a rename carries the user's value to the new key")
    void migrationRenamesKeepingValue() throws IOException {
        Files.writeString(file("db"), "config-version: 1\npool: 33\n");

        ConfigFile<Renamed> config = Configs.define(plugin, "db", Renamed.class)
                .version(2)
                .migration(1, Migration.rename("pool", "pool-size"))
                .load();

        assertEquals(33, config.get().poolSize(), "the value the user set must be carried over");

        String yaml = contents("db");
        assertTrue(yaml.contains("pool-size: 33"), "the new key holds the value:\n" + yaml);
        assertFalse(yaml.contains("\npool:"), "the old key is gone:\n" + yaml);
        assertTrue(yaml.contains("config-version: 2"), "the file records the version it reached");
    }

    @Test
    @DisplayName("migrations do not run twice")
    void migrationsRunOnlyOnce() throws IOException {
        Files.writeString(file("db"), "config-version: 1\npool: 33\n");

        var builder = Configs.define(plugin, "db", Renamed.class)
                .version(2)
                .migration(1, Migration.rename("pool", "pool-size"));
        builder.load();
        Configs.releaseAll();

        // Someone edits the migrated file; re-running the rename must not undo it.
        Files.writeString(file("db"), contents("db").replace("pool-size: 33", "pool-size: 44"));

        ConfigFile<Renamed> reopened = Configs.define(plugin, "db", Renamed.class)
                .version(2)
                .migration(1, Migration.rename("pool", "pool-size"))
                .load();

        assertEquals(44, reopened.get().poolSize(), "the already migrated value must be left alone");
    }

    @Test
    @DisplayName("transform rewrites a value whose unit changed")
    void migrationTransformsValue() throws IOException {
        Files.writeString(file("db"), "config-version: 1\npool-size: 120\n");

        ConfigFile<Renamed> config = Configs.define(plugin, "db", Renamed.class)
                .version(2)
                .migration(1, Migration.transform("pool-size", seconds -> ((Number) seconds).intValue() / 60))
                .load();

        assertEquals(2, config.get().poolSize(), "120 seconds should become 2 minutes");
    }

    // ------------------------------------------------------------------
    // Misuse
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a schema without defaults fails loudly at declaration time")
    void schemaWithoutDefaultsIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Configs.define(plugin, "bad", NoDefaults.class).load());

        assertTrue(failure.getMessage().contains("no-argument constructor"),
                "the message should say exactly what is missing: " + failure.getMessage());
    }

    @Test
    @DisplayName("declaring the same file twice returns the same handle")
    void definingTwiceReturnsSameHandle() {
        ConfigFile<Settings> first = Configs.define(plugin, "config", Settings.class).load();
        ConfigFile<Settings> second = Configs.define(plugin, "config", Settings.class).load();

        assertTrue(first == second, "the file should not be read twice");
    }

    @Test
    @DisplayName("supports files in subfolders")
    void supportsSubfolders() {
        Configs.define(plugin, "menus/main", Settings.class).load();

        assertTrue(Files.exists(folder.resolve("menus").resolve("main.yml")),
                "the subfolder should be created");
    }

    @Test
    @DisplayName("the generated file reads cleanly")
    void generatedFileIsReadable() throws IOException {
        Configs.define(plugin, "config", Settings.class).load();

        List<String> lines = Files.readAllLines(file("config"));

        assertEquals("# Settings for the test plugin.", lines.get(0),
                "the schema's own comment becomes the file header");

        int keyLine = lines.indexOf("pool-size: 10");
        assertTrue(keyLine > 0, "the key should be written:\n" + String.join("\n", lines));
        assertEquals("# Connections kept open.", lines.get(keyLine - 1),
                "a key's comment sits directly above it");

        // Sections are separated by a blank line so the file stays scannable.
        int sectionLine = lines.indexOf("nested:");
        assertTrue(sectionLine > 0, "the nested record becomes a section");
        assertTrue(lines.subList(0, sectionLine).contains(""),
                "there should be blank lines separating blocks");
    }
}
