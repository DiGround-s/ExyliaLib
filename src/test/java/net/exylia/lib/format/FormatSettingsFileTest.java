package net.exylia.lib.format;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code formats.yml} as a server owner meets it: generated from the record,
 * documented in the file itself, and live again after a reload.
 *
 * <p>This is the half of the module that the palette pattern buys. A test that
 * only exercised {@link Formats} in memory would pass while the file the owner
 * actually edits had the wrong keys in it.
 */
class FormatSettingsFileTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        Formats.reset();
        plugin = FakeServer.newPlugin("FormatFileTestPlugin", folder.toFile());
    }

    @AfterEach
    void tearDown() {
        Configs.releaseAll();
        Formats.reset();
    }

    private String yaml() throws IOException {
        return Files.readString(folder.resolve("formats.yml"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the file is generated from the record, with its documentation")
    void generatesTheFile() throws IOException {
        Configs.define(plugin, "formats", FormatSettings.class).load();

        String yaml = yaml();
        assertTrue(Files.exists(folder.resolve("formats.yml")), "the file should be created");

        // The keys an owner edits, in the shape the record declares them.
        assertTrue(yaml.contains("money:"), yaml);
        assertTrue(yaml.contains("symbol: $"), yaml);
        assertTrue(yaml.contains("symbol-position: before"), yaml);
        assertTrue(yaml.contains("space-after-symbol: false"), yaml);
        assertTrue(yaml.contains("compact-threshold: 1000000"), yaml);
        assertTrue(yaml.contains("compact:"), yaml);
        assertTrue(yaml.contains("lowercase-suffixes: false"), yaml);
        assertTrue(yaml.contains("percent:"), yaml);
        assertTrue(yaml.contains("show-plus: false"), yaml);
        assertTrue(yaml.contains("date:"), yaml);
        assertTrue(yaml.contains("style: date"), yaml);

        // The comments are the manual: this file is the only reason to open it.
        assertTrue(yaml.contains("# Number and date formats shared by every Exylia plugin."), yaml);
        assertTrue(yaml.contains("# before gives $1,250.00"), yaml);
    }

    @Test
    @DisplayName("every section says what it produces, above the section itself")
    void sectionsAreDocumented() throws IOException {
        Configs.define(plugin, "formats", FormatSettings.class).load();

        String yaml = yaml();

        // A worked example above each section is what makes the file readable
        // without the documentation open beside it. It has to be written on the
        // component rather than on the nested record: a comment on the type is
        // silently dropped for a section that the parent already documents, so
        // the examples would vanish from the file while every other test passed.
        assertTrue(yaml.contains("# These defaults produce: $1,250.00"), yaml);
        assertTrue(yaml.contains("# These defaults produce: 1.5K, 2.3M, 1.5B"), yaml);
        assertTrue(yaml.contains("# These defaults produce: 75% and 75.5%"), yaml);
        assertTrue(yaml.contains("# These defaults produce: 17/08/2026"), yaml);

        // Each example sits above its own section, not collected at the top.
        assertTrue(yaml.indexOf("# These defaults produce: $1,250.00") < yaml.indexOf("money:"), yaml);
        assertTrue(yaml.indexOf("# These defaults produce: 17/08/2026") < yaml.indexOf("date:"), yaml);
    }

    @Test
    @DisplayName("the defaults on disk are the defaults in code")
    void defaultsMatch() {
        ConfigFile<FormatSettings> file =
                Configs.define(plugin, "formats", FormatSettings.class).load();

        assertEquals(new FormatSettings(), file.get());
    }

    @Test
    @DisplayName("what an owner wrote is what the formats do")
    void readsEditedValues() throws IOException {
        Configs.define(plugin, "formats", FormatSettings.class).load();
        Configs.releaseAll();

        Files.writeString(folder.resolve("formats.yml"), """
                money:
                  symbol: '€'
                  symbol-position: after
                  space-after-symbol: true
                  decimals: 0
                  compact: false
                  compact-threshold: 1000000
                compact:
                  decimals: 1
                  lowercase-suffixes: true
                  threshold: 1000
                percent:
                  decimals: 1
                  show-plus: true
                date:
                  style: iso
                """, StandardCharsets.UTF_8);

        ConfigFile<FormatSettings> file =
                Configs.define(plugin, "formats", FormatSettings.class).load();
        Formats.apply(file.get());

        assertEquals("1,250 €", Formats.money(1250L));
        assertEquals("1.5k", Formats.compact(1_500L));
        assertEquals("+75%", Formats.percent(75));
    }

    @Test
    @DisplayName("a reload of the file re-applies the formats, the way the lifecycle wires it")
    void reloadReapplies() throws IOException {
        ConfigFile<FormatSettings> file =
                Configs.define(plugin, "formats", FormatSettings.class).load();
        // Exactly the three lines ExyliaLib.loadFormats() runs.
        Formats.apply(file.get());
        file.onReload(Formats::apply);

        assertEquals("$1,250.00", Formats.money(1250L));

        Files.writeString(folder.resolve("formats.yml"), """
                money:
                  symbol: 'coins '
                  symbol-position: after
                  space-after-symbol: false
                  decimals: 0
                  compact: false
                  compact-threshold: 1000000
                """, StandardCharsets.UTF_8);

        file.reload();

        assertEquals("1,250coins ", Formats.money(1250L));
    }

    @Test
    @DisplayName("a typo in the file uses the default rather than stopping the server")
    void badValueFallsBack() throws IOException {
        Configs.define(plugin, "formats", FormatSettings.class).load();
        Configs.releaseAll();

        Files.writeString(folder.resolve("formats.yml"), """
                money:
                  symbol: '$'
                  symbol-position: sideways
                  space-after-symbol: false
                  decimals: banana
                  compact: true
                  compact-threshold: 1000000
                """, StandardCharsets.UTF_8);

        ConfigFile<FormatSettings> file =
                Configs.define(plugin, "formats", FormatSettings.class).load();
        Formats.apply(file.get());

        // Reported, not fatal: a cosmetic setting must never be the reason a
        // server does not start.
        assertTrue(file.issues().size() >= 2, "both bad values should be reported");
        assertEquals("$1,250.00", Formats.money(1250L));
    }
}
