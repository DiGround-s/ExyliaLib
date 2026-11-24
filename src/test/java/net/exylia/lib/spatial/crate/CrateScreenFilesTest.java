package net.exylia.lib.spatial.crate;

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

/** The crate opening screen as shipped: it reads, its canvas matches the reel's maths, and it speaks every language. */
class CrateScreenFilesTest {

    private static final Path DIR = Path.of("src/main/resources/crate");

    @Test
    void readsWithoutProblems() throws IOException {
        ScreenMarkup markup = ScreenMarkup.load(DIR.resolve("opening.html"), DIR.resolve("opening.css"));
        // values and actions are bound by CrateScreen at run time
        List<String> problems = markup.problems().stream()
                .filter(problem -> !problem.contains("crate_")).toList();
        assertEquals(List.of(), problems);
        String css = Files.readString(DIR.resolve("opening.css"));
        assertTrue(css.contains("width: " + (int) CrateScreen.WIDTH + "px; height: " + (int) CrateScreen.HEIGHT + "px"), "canvas size");
    }

    @Test
    void everyLanguageHasEveryWord() throws IOException {
        String markup = Files.readString(DIR.resolve("opening.html")).replaceAll("(?s)<!--.*?-->", "");
        List<String> keys = Pattern.compile("\\bt(?:-[a-z]+)?=\"([a-z_.]+)\"").matcher(markup).results()
                .map(match -> match.group(1)).distinct().toList();
        assertEquals(9, keys.size(), "found " + keys);
        for (String code : List.of("en", "es", "de", "fr", "pt", "ru", "tr")) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    Path.of("src/main/resources/lang", code, "crate", "opening.yml").toFile());
            for (String key : keys) assertTrue(yaml.isString(key), code + " misses " + key);
        }
    }
}
