package net.exylia.lib.text;

import net.exylia.lib.config.Languages;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The library's own screens and prompts in the server's language.
 *
 * <p>Every line the library draws in code — editor buttons, field labels,
 * prompts — is written in English and passed through {@link #tr(String)},
 * which looks the English up in {@code lang/<code>/phrases.yml}: the English
 * line is the key, the translation the value. A line missing from the table
 * stays English, so a new button is never blank, only untranslated.
 *
 * <pre>{@code
 * Icons.button(Material.EMERALD, Phrases.tr("{success}&lADD"), ...);
 * Phrases.tr("{letters_black}▎ {letters}Copy every entry here {letters_black}({0})", entries.size());
 * }</pre>
 *
 * <p>The language is ExyliaLib's own {@code language} in its {@code config.yml}.
 * The table ships in the jar; a server owner can override any line with a file
 * of the same shape at {@code plugins/ExyliaLib/lang/<code>/phrases.yml}, which
 * is also how a language the library does not ship is added.
 *
 * <p>Text a plugin sends belongs in that plugin's {@code messages.yml}, where
 * an owner can edit it. This table is only for the library's own words.
 *
 * @since 1.215.0
 */
public final class Phrases {

    private static final String FILE = "phrases.yml";
    private static final Pattern SLOT = Pattern.compile("\\{(\\d+)}");

    private static volatile Map<String, String> table = Map.of();

    private Phrases() {
    }

    /**
     * Reads the table for the library's current language.
     *
     * <p>Called by ExyliaLib on start and on reload.
     *
     * @param library ExyliaLib itself
     */
    public static void load(@NotNull Plugin library) {
        String code = Languages.code(library);
        Map<String, String> loaded = new HashMap<>();
        String resource = "lang/" + code + "/" + FILE;
        try (InputStream packaged = Phrases.class.getClassLoader().getResourceAsStream(resource)) {
            if (packaged != null) {
                read(new InputStreamReader(packaged, StandardCharsets.UTF_8), loaded);
            }
            Path override = library.getDataFolder().toPath().resolve(resource);
            if (Files.isRegularFile(override)) {
                try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                    read(reader, loaded);
                }
            }
        } catch (IOException | RuntimeException failure) {
            // A broken table means English, never a crash: every line has its
            // English right there in the code.
            library.getLogger().log(Level.WARNING, "Could not read " + resource + "; the library speaks English.",
                    failure);
        }
        table = Map.copyOf(loaded);
    }

    /**
     * The line in the library's language.
     *
     * @param english the line as the code writes it
     * @return its translation, or the line itself when there is none
     */
    public static @NotNull String tr(@NotNull String english) {
        return table.getOrDefault(english, english);
    }

    /**
     * The line in the library's language, with {@code {0}}, {@code {1}}... filled in.
     *
     * <p>Numbered slots rather than concatenation, so a translation can put the
     * value where its own grammar wants it.
     *
     * @param english the line as the code writes it
     * @param values  what goes into each numbered slot
     * @return the filled translation, or the filled English
     */
    public static @NotNull String tr(@NotNull String english, @NotNull Object... values) {
        // One pass, so a value that itself reads {1} is never filled again.
        Matcher slot = SLOT.matcher(tr(english));
        StringBuilder line = new StringBuilder();
        while (slot.find()) {
            int index = Integer.parseInt(slot.group(1));
            String value = index < values.length ? String.valueOf(values[index]) : slot.group();
            slot.appendReplacement(line, Matcher.quoteReplacement(value));
        }
        return slot.appendTail(line).toString();
    }

    /** Replaces the table, for tests. */
    static void use(@NotNull Map<String, String> phrases) {
        table = Map.copyOf(phrases);
    }

    private static void read(Reader reader, Map<String, String> into) {
        Object root = new Yaml().load(reader);
        if (root instanceof Map<?, ?> map) {
            map.forEach((english, translated) -> {
                if (english != null && translated != null) {
                    into.put(english.toString(), translated.toString());
                }
            });
        }
    }
}
