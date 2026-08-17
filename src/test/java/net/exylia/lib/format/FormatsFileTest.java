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
 * The file a server owner actually opens.
 *
 * <p>Every other test here goes through {@link FormatSettings} as a record,
 * which proves the formatting and nothing about the YAML. The owner never sees
 * the record: they see {@code plugins/ExyliaLib/formats.yml}, and if a key is
 * named differently there than the documentation says, or an example in a
 * comment does not match what the defaults produce, the module is wrong in the
 * only place that is hard to notice.
 */
class FormatsFileTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Configs.releaseAll();
        Formats.reset();
        plugin = FakeServer.newPlugin("ExyliaLib", folder.toFile());
    }

    @AfterEach
    void tearDown() {
        Formats.reset();
        Configs.releaseAll();
    }

    private String generate() throws IOException {
        Configs.define(plugin, "formats", FormatSettings.class).load();
        return Files.readString(folder.resolve("formats.yml"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the file is generated with every documented key")
    void generatesEveryKey() throws IOException {
        String yaml = generate();

        for (String key : new String[] {
                "money:", "symbol:", "symbol-position:", "space-after-symbol:",
                "decimals:", "compact:", "compact-threshold:",
                "lowercase-suffixes:", "threshold:",
                "percent:", "show-plus:",
                "date:", "style:"}) {
            assertTrue(yaml.contains(key),
                    () -> "docs/formats.md documents " + key + ", generated file:\n" + yaml);
        }
    }

    @Test
    @DisplayName("the defaults on disk are the ones documented")
    void documentedDefaults() throws IOException {
        String yaml = generate();

        assertTrue(yaml.contains("symbol: '$'") || yaml.contains("symbol: \"$\"")
                        || yaml.contains("symbol: $"),
                () -> "expected a $ default:\n" + yaml);
        assertTrue(yaml.contains("symbol-position: before"), yaml);
        assertTrue(yaml.contains("compact-threshold: 1000000"), yaml);
        assertTrue(yaml.contains("threshold: 1000"), yaml);
        assertTrue(yaml.contains("style: date"), yaml);
    }

    @Test
    @DisplayName("every key carries a comment for the owner")
    void everyKeyIsDocumented() throws IOException {
        // A setting with no explanation is one a server owner changes by
        // guessing, and then reports the result as a bug.
        String yaml = generate();
        String[] lines = yaml.split("\n");

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("#") || !line.contains(":")) {
                continue;
            }
            boolean documented = false;
            for (int back = index - 1; back >= 0; back--) {
                String previous = lines[back].strip();
                if (previous.isEmpty()) {
                    break;
                }
                if (previous.startsWith("#")) {
                    documented = true;
                    break;
                }
                break;
            }
            assertTrue(documented, "undocumented key on line " + (index + 1) + ": " + line);
        }
    }

    @Test
    @DisplayName("the file loads back into the defaults it was written from")
    void roundTrips() throws IOException {
        generate();
        Configs.releaseAll();

        ConfigFile<FormatSettings> reread =
                Configs.define(plugin, "formats", FormatSettings.class).load();

        assertEquals(new FormatSettings(), reread.get(),
                "a freshly generated file must read back as the defaults");
    }

    @Test
    @DisplayName("an owner's edit reaches every plugin's output")
    void editingTheFileChangesOutput() throws IOException {
        // The end-to-end promise of the module: edit one file, the whole server
        // follows. Everything else here is an implementation detail of that.
        generate();
        Configs.releaseAll();

        Path file = folder.resolve("formats.yml");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replace("symbol: '$'", "symbol: '€'")
                .replace("symbol: $", "symbol: '€'"), StandardCharsets.UTF_8);

        ConfigFile<FormatSettings> edited =
                Configs.define(plugin, "formats", FormatSettings.class).load();
        Formats.apply(edited.get());

        assertEquals("€1,250.00", Formats.money(1250L));
    }

    @Test
    @DisplayName("a nonsense value is clamped rather than stopping the server")
    void nonsenseIsClamped() throws IOException {
        // A server that will not start because somebody typed an extra zero in
        // a cosmetic setting is a worse outcome than a number with too many
        // decimals.
        generate();
        Configs.releaseAll();

        Path file = folder.resolve("formats.yml");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replace("decimals: 2", "decimals: 3000"), StandardCharsets.UTF_8);

        ConfigFile<FormatSettings> broken =
                Configs.define(plugin, "formats", FormatSettings.class).load();
        Formats.apply(broken.get());

        String rendered = Formats.money(1250L);
        assertTrue(rendered.length() < 40,
                "three thousand decimals should have been clamped, got: " + rendered);
    }
}
