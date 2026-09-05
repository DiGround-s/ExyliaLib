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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    /** A schema holding a list of records, which is one row per element. */
    record Quiz(
            @Comment("Every question needs four answers.")
            List<Question> questions
    ) {
        Quiz() {
            this(List.of(new Question("How tall is an Enderman?", List.of("2", "3"), 1)));
        }

        record Question(String question, List<String> answers, int correct) {
        }
    }

    /** A schema whose two blocks are named by the server owner, not by the code. */
    record Limits(
            @Comment("Per-world multiplier. Add the worlds this server has.")
            Map<String, Double> worlds,
            Map<String, Item> items
    ) {
        Limits() {
            this(Map.of("world", 7.0), Map.of("ender-pearl", new Item()));
        }

        record Item(double cooldown, int maxUses, String trigger) {
            Item() {
                this(14.0, 1, "USE");
            }
        }
    }

    /** A schema with a section that only earns its place when it says something. */
    record Screen(
            @Comment("The banner shown on join.")
            Banner banner,
            @Comment("The banner shown on leave.")
            Banner farewell
    ) {
        Screen() {
            this(new Banner("Welcome", 3.0), new Banner());
        }

        record Banner(String text, double seconds) implements Sparse {
            Banner() {
                this("", 3.0);
            }

            @Override
            public boolean isEmpty() {
                return text.isEmpty();
            }
        }
    }

    /** A schema whose compact constructor rejects what the file says. */
    record Ranged(int amount) {
        Ranged() {
            this(1);
        }

        Ranged {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must not be negative, got " + amount);
            }
        }
    }

    record BadKeys(Map<Integer, String> byNumber) {
        BadKeys() {
            this(Map.of());
        }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("ConfigTestPlugin", folder.toFile());
    }

    @Test
    @DisplayName("a plugin reloaded in place reads its own file, not the previous load's")
    void reloadInPlaceDropsThePreviousLoad() {
        ConfigFile<Settings> first = Configs.define(plugin, "config", Settings.class).load();

        // What a reload tool does: a second Plugin object under the same name,
        // enabled before the first one's cleanup has had a tick to run. Handing
        // back the first load's handle here is a ClassCastException in the
        // consumer, between two versions of the same record class.
        Plugin reloaded = FakeServer.newPlugin("ConfigTestPlugin", folder.toFile());
        ConfigFile<Settings> second = Configs.define(reloaded, "config", Settings.class).load();
        assertNotSame(first, second);

        // And the cleanup that follows a tick later lets go of the load that
        // died, not of the one that replaced it.
        Configs.release(plugin);
        assertSame(second, Configs.define(reloaded, "config", Settings.class).load());
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
    @DisplayName("writes a list of records as plain YAML and reads it back")
    void listOfRecordsRoundTrips() throws IOException {
        Configs.define(plugin, "quiz", Quiz.class).load();
        Configs.releaseAll();

        String yaml = contents("quiz");
        assertFalse(yaml.contains("!!"), "a YAML tag cannot be read back:\n" + yaml);
        assertTrue(yaml.contains("question: How tall is an Enderman?"), yaml);

        Files.writeString(file("quiz"), yaml.replace("correct: 1", "correct: 0"));
        Quiz values = Configs.define(plugin, "quiz", Quiz.class).load().get();

        assertEquals(1, values.questions().size());
        assertEquals("How tall is an Enderman?", values.questions().getFirst().question());
        assertEquals(List.of("2", "3"), values.questions().getFirst().answers());
        assertEquals(0, values.questions().getFirst().correct(), "the edit should be read back");
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
    @DisplayName("removes keys the schema does not own, and says so")
    void prunesUnknownKeys() throws IOException {
        Files.writeString(file("config"), "pool-size: 5\nsomething-else: keep me\n");

        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        assertFalse(contents("config").contains("something-else"),
                "a key the schema does not define is pruned, like commons did");
        assertTrue(config.issues().stream().anyMatch(i -> i.type() == ConfigIssue.Type.UNKNOWN_KEY
                        && i.message().contains("removed")),
                "and the log says what left");
    }

    @Test
    @DisplayName("prunes a whole stale section from an older layout")
    void prunesStaleSection() throws IOException {
        // The sections a commons-era file carries that no record declares.
        Files.writeString(file("config"),
                "pool-size: 5\ntasks:\n  interval: 20\ndebug: false\n");

        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        String written = contents("config");
        assertFalse(written.contains("tasks:"), written);
        assertFalse(written.contains("debug:"), written);
        assertEquals(2, config.issues().stream()
                .filter(i -> i.type() == ConfigIssue.Type.UNKNOWN_KEY).count());
    }

    @Test
    @DisplayName("a second load finds nothing left to prune")
    void pruneHappensOnce() throws IOException {
        Files.writeString(file("config"), "pool-size: 5\nstale: true\n");
        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        // Pruned means pruned: the file stays clean, and the warning does not
        // become a boot-time tradition.
        assertFalse(config.reload().stream().anyMatch(i -> i.type() == ConfigIssue.Type.UNKNOWN_KEY),
                "once removed, a stale key must not warn on every boot");
    }

    @Test
    @DisplayName("a stale key found during reload is removed from disk too")
    void reloadPruneIsPersisted() throws IOException {
        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();
        Files.writeString(file("config"), contents("config") + "\nstale-after-enable: true\n");

        config.reload();

        assertFalse(contents("config").contains("stale-after-enable"),
                "pruning only the in-memory YAML would let the key return next boot");
    }

    @Test
    @DisplayName("the version marker survives pruning")
    void versionMarkerSurvives() throws IOException {
        Files.writeString(file("config"), "pool-size: 5\nstale: true\n");
        Configs.define(plugin, "config", Settings.class).load();
        assertTrue(contents("config").contains("config-version"),
                "the library's own bookkeeping key is not a stale key");
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
    @DisplayName("never binds a group of settings as if it were a value")
    void aSectionIsNotAValue() throws IOException {
        // A file whose layout moved on has a block where the schema now expects
        // a value. String.valueOf would happily render it as
        // "MemorySection[path='server-name', root='YamlConfiguration']" and the
        // save that follows writes that into the file, as though the owner had
        // typed it there.
        Files.writeString(file("config"), """
                server-name:
                  host: localhost
                  port: 25565
                """);

        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();

        assertEquals("lobby", config.get().serverName(), "the default should be used");
        assertFalse(contents("config").contains("MemorySection"),
                "a section was stringified into a value: " + contents("config"));
        assertTrue(config.issues().stream().anyMatch(i -> i.type() == ConfigIssue.Type.INVALID_VALUE),
                "the problem should be reported");
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

    // ------------------------------------------------------------------
    // Maps: the blocks whose keys the server owner chooses
    // ------------------------------------------------------------------

    @Test
    @DisplayName("writes the default entries of a map on first run")
    void generatesMapEntries() throws IOException {
        ConfigFile<Limits> config = Configs.define(plugin, "limits", Limits.class).load();

        assertEquals(7.0, config.get().worlds().get("world"));
        assertEquals(1, config.get().items().get("ender-pearl").maxUses());

        String yaml = contents("limits");
        assertTrue(yaml.contains("world: 7.0"), "a leaf map writes one key per entry:\n" + yaml);
        assertTrue(yaml.contains("ender-pearl:"), "a record map writes one block per entry:\n" + yaml);
        assertTrue(yaml.contains("max-uses: 1"), "and the block holds the record's keys:\n" + yaml);
    }

    @Test
    @DisplayName("keeps the entries a server owner invented")
    void keepsOwnerInventedEntries() throws IOException {
        Configs.define(plugin, "limits", Limits.class).load();
        Configs.releaseAll();

        Files.writeString(file("limits"), """
                worlds:
                  arena: 2.5
                  survival: 9.0
                items:
                  totem-of-undying:
                    cooldown: 30.0
                    max-uses: 4
                """);

        ConfigFile<Limits> config = Configs.define(plugin, "limits", Limits.class).load();

        assertEquals(Map.of("arena", 2.5, "survival", 9.0), config.get().worlds(),
                "every key the owner wrote is an entry, and no default is re-added");
        assertEquals(4, config.get().items().get("totem-of-undying").maxUses());
        assertFalse(config.get().items().containsKey("ender-pearl"),
                "an entry the owner deleted stays deleted");

        assertTrue(config.issues().stream().noneMatch(i -> i.type() == ConfigIssue.Type.UNKNOWN_KEY),
                "a key inside a map is the owner's to choose, so nothing is pruned: " + config.issues());
        assertTrue(contents("limits").contains("survival: 9.0"),
                "and the written file still holds it:\n" + contents("limits"));
    }

    @Test
    @DisplayName("fills in what a new entry left out, from the record's own defaults")
    void newEntryGetsRecordDefaults() throws IOException {
        Files.writeString(file("limits"), """
                items:
                  mace:
                    cooldown: 12.0
                """);

        ConfigFile<Limits> config = Configs.define(plugin, "limits", Limits.class).load();

        Limits.Item mace = config.get().items().get("mace");
        assertEquals(12.0, mace.cooldown());
        assertEquals(1, mace.maxUses(), "the key the owner omitted comes from the record's defaults");
        assertEquals("USE", mace.trigger());
    }

    @Test
    @DisplayName("skips an unreadable entry and keeps the rest")
    void skipsBrokenEntry() throws IOException {
        Files.writeString(file("limits"), """
                worlds:
                  arena: banana
                  survival: 9.0
                """);

        ConfigFile<Limits> config = Configs.define(plugin, "limits", Limits.class).load();

        assertEquals(Map.of("survival", 9.0), config.get().worlds(),
                "one bad entry costs that entry, not the block");
        assertTrue(config.issues().stream().anyMatch(i -> i.type() == ConfigIssue.Type.INVALID_VALUE
                        && i.path().equals("worlds.arena")),
                "and it is reported by path: " + config.issues());
    }

    @Test
    @DisplayName("an emptied map stays empty")
    void emptiedMapStaysEmpty() throws IOException {
        Configs.define(plugin, "limits", Limits.class).load();
        Configs.releaseAll();

        Files.writeString(file("limits"), "worlds: {}\nitems: {}\n");

        ConfigFile<Limits> config = Configs.define(plugin, "limits", Limits.class).load();

        assertTrue(config.get().worlds().isEmpty(),
                "putting the examples back would make the block impossible to clear");
        assertTrue(config.get().items().isEmpty());
    }

    @Test
    @DisplayName("a map keyed by something other than String is rejected at declaration")
    void rejectsNonStringKeys() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Configs.define(plugin, "bad", BadKeys.class).load());

        assertTrue(failure.getMessage().contains("keyed by String"), failure.getMessage());
    }

    // ------------------------------------------------------------------
    // Sections that do nothing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a section that does nothing is left out of the file, and stays out")
    void anEmptySparseSectionIsNotWritten() throws IOException {
        // Fifteen effects in a plugin's config wrote a title, an action bar, a
        // sound, particles and a firework each, every one of them empty and
        // every key of them commented: a thousand lines describing nothing.
        ConfigFile<Screen> config = Configs.define(plugin, "screen", Screen.class).load();

        assertTrue(contents("screen").contains("banner:"), "the one that says something stays");
        assertFalse(contents("screen").contains("farewell:"),
                "the one that does nothing is not written:\n" + contents("screen"));

        // And it is not reported missing, or the next load would write the
        // whole empty block straight back.
        assertFalse(config.issues().stream()
                        .anyMatch(issue -> issue.path().startsWith("farewell")),
                "an omitted section is not a missing key: " + config.issues());

        Configs.releaseAll();
        Configs.define(plugin, "screen", Screen.class).load();
        assertFalse(contents("screen").contains("farewell:"), "and it stays out on reload");
    }

    @Test
    @DisplayName("an owner who fills the section in keeps it, values and all")
    void aFilledSparseSectionIsKept() throws IOException {
        Files.writeString(file("screen"),
                "banner:\n  text: Hello\n  seconds: 1.0\nfarewell:\n  text: Bye\n  seconds: 2.0\n");

        ConfigFile<Screen> config = Configs.define(plugin, "screen", Screen.class).load();

        assertEquals("Bye", config.get().farewell().text());
        assertEquals(2.0, config.get().farewell().seconds());
        assertTrue(contents("screen").contains("farewell:"), contents("screen"));
    }

    @Test
    @DisplayName("a section that ships with something in it stays cleared once an owner clears it")
    void aClearedSparseSectionWithRealDefaultsStaysCleared() throws IOException {
        // Emptying the block is how a title is turned off. Leaving it out of
        // the file is how a default is asked for, so a cleared section that is
        // not written is a section that comes back on the next boot.
        Files.writeString(file("screen"),
                "banner:\n  text: ''\n  seconds: 3.0\n");

        ConfigFile<Screen> config = Configs.define(plugin, "screen", Screen.class).load();
        assertEquals("", config.get().banner().text());
        assertTrue(contents("screen").contains("banner:"),
                "it has a default, so it has to stay in the file:\n" + contents("screen"));

        Configs.releaseAll();
        ConfigFile<Screen> again = Configs.define(plugin, "screen", Screen.class).load();
        assertEquals("", again.get().banner().text(),
                "the default came back after a restart:\n" + contents("screen"));
    }

    // ------------------------------------------------------------------
    // reloadAll — what a plugin's /reload command actually calls
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reloadAll republishes every file the plugin owns, not just the first")
    void reloadAllRepublishesEveryFile() throws IOException {
        ConfigFile<Settings> config = Configs.define(plugin, "config", Settings.class).load();
        ConfigFile<Screen> messages = Configs.define(plugin, "messages", Screen.class).load();

        Files.writeString(file("config"), contents("config").replace("pool-size: 10", "pool-size: 77"));
        Files.writeString(file("messages"), contents("messages").replace("Welcome", "Bienvenido"));

        Configs.reloadAll(plugin);

        assertEquals(77, config.get().poolSize());
        assertEquals("Bienvenido", messages.get().banner().text(),
                "the second file has to reload too:\n" + contents("messages"));
    }

    @Test
    @DisplayName("one file that cannot be bound does not stop the others reloading")
    void reloadAllSurvivesOneFileThrowing() throws IOException {
        ConfigFile<Ranged> ranged = Configs.define(plugin, "ranged", Ranged.class).load();
        ConfigFile<Screen> messages = Configs.define(plugin, "messages", Screen.class).load();

        // A compact constructor that rejects the value: Binder.read throws.
        Files.writeString(file("ranged"), "amount: -5\n");
        Files.writeString(file("messages"), contents("messages").replace("Welcome", "Bienvenido"));

        Configs.reloadAll(plugin);

        assertEquals("Bienvenido", messages.get().banner().text(),
                "a plugin's other files must still reload:\n" + contents("messages"));
        assertEquals(1, ranged.get().amount(), "the rejected file keeps what was in use");
    }
}
