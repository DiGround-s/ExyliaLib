package net.exylia.lib.settings.internal;

import net.exylia.lib.screen.ScreenMarkup;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The /settings screen as shipped: it reads, and speaks every language. */
class SettingsScreenTest {

    private static final Path DIR = Path.of("src/main/resources/settings");

    @Test
    void readsWithoutProblems() throws IOException {
        // the actions are the library's, which only a server registers
        List<String> problems = ScreenMarkup.load(DIR.resolve("settings.html"), DIR.resolve("theme.css")).problems().stream()
                .filter(problem -> !problem.contains("no action \"exylialib:"))
                .toList();
        assertEquals(List.of(), problems);
    }

    @Test
    void everyLanguageHasEveryWord() throws IOException {
        String markup = Files.readString(DIR.resolve("settings.html")).replaceAll("(?s)<!--.*?-->", "");
        List<String> keys = Pattern.compile("\\bt(?:-[a-z]+)?=\"([a-z_.]+)\"").matcher(markup).results()
                .map(match -> match.group(1)).distinct().toList();
        assertTrue(keys.size() > 15, "found " + keys.size());
        for (String code : List.of("en", "es", "fr", "pt", "ru", "tr")) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    Path.of("src/main/resources/lang", code, "settings", "settings.yml").toFile());
            for (String key : keys) assertTrue(yaml.isString(key), code + " misses " + key);
        }
    }
}
