package net.exylia.lib.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a plugin's test asserts about a catalogue's language files, so a translation can never drift from the base:
 *
 * <pre>{@code
 * assertEquals(List.of(), CatalogueCheck.problems(MyPlugin.class, "effects.yml", List.of("es", "fr", "pt", "ru", "tr"),
 *         Pattern.compile("(^|\\.)(name|description)$"), 40));
 * }</pre>
 *
 * <p>For {@code lang/<code>/<resource>} against {@code <resource>} in the jar: every key matching {@code text} is
 * translated and not blank, every key is one the base has, every token of the base line is still there and none
 * was added ({@code %placeholder%}, {@code {token}}, {@code <tag>}, {@code &x}, {@code [sound:...]}), and no line of
 * a description or lore is wider than {@code maxLine} visible characters.
 *
 * <p>Needs no server, only {@code paper-api} and this library on the test classpath.
 *
 * @since 1.533.0
 */
public final class CatalogueCheck {

    private static final Pattern TOKEN =
            Pattern.compile("%[^%\\s]+%|\\{[^{}\\s]+}|&[0-9a-fk-or]|</?[^<>\\s][^<>]*>|\\[sound:[^]]*]");
    private static final String BREAK = "<nl>";

    private CatalogueCheck() {
    }

    /**
     * Every problem of a catalogue's language files, each naming language, file and key.
     *
     * @param anchor    a class packaged with the resources
     * @param resource  the base file in the jar, such as {@code effects.yml}
     * @param languages the language codes that must be packaged
     * @param text      which dotted keys hold text a player reads
     * @param maxLine   the widest line of a {@code description} or {@code lore}, in visible characters
     * @return the problems, empty when everything holds
     */
    public static @NotNull List<String> problems(@NotNull Class<?> anchor, @NotNull String resource,
                                                 @NotNull List<String> languages, @NotNull Pattern text, int maxLine) {
        List<String> problems = new ArrayList<>();
        YamlConfiguration base = packaged(anchor, resource);
        if (base == null) {
            return List.of(resource + " is not packaged");
        }
        for (String code : languages) {
            String where = code + "/" + resource + " ";
            YamlConfiguration language = packaged(anchor, "lang/" + code + "/" + resource);
            if (language == null) {
                problems.add(where + "is not packaged");
                continue;
            }
            for (String key : base.getKeys(true)) {
                if (base.isConfigurationSection(key) || !text.matcher(key).find() || !hasWords(base.get(key))) {
                    continue;
                }
                if (!language.contains(key)) {
                    problems.add(where + key + " is not translated");
                }
            }
            for (String key : language.getKeys(true)) {
                if (language.isConfigurationSection(key)) {
                    continue;
                }
                if (!base.contains(key)) {
                    problems.add(where + key + " is not in the base");
                    continue;
                }
                check(where + key, base.get(key), language.get(key), key, maxLine, problems);
            }
        }
        return problems;
    }

    private static void check(String where, Object english, Object translated, String key, int maxLine, List<String> problems) {
        if (String.valueOf(translated).isBlank() || "[]".equals(String.valueOf(translated))) {
            problems.add(where + " is empty");
        }
        Set<String> theirs = tokens(translated);
        Set<String> ours = tokens(english);
        for (String token : ours) {
            // A plural's forms are the language's own, and a language with one form has none at all.
            if (!theirs.contains(token) && !token.contains(":")) {
                problems.add(where + " lost " + token);
            }
        }
        for (String token : theirs) {
            if (!ours.contains(token) && !token.contains(":")) {
                problems.add(where + " added " + token);
            }
        }
        if (key.endsWith("description") || key.endsWith("lore")) {
            for (String line : String.valueOf(translated instanceof List<?> list ? String.join(BREAK, list.stream().map(String::valueOf).toList()) : translated)
                    .split(BREAK)) {
                if (visible(line) > maxLine) {
                    problems.add(where + " is too wide: " + line);
                }
            }
        }
    }

    private static boolean hasWords(Object value) {
        String plain = TOKEN.matcher(String.valueOf(value)).replaceAll("");
        return plain.codePoints().filter(Character::isLetter).count() > 1;
    }

    private static int visible(String line) {
        return TOKEN.matcher(line).replaceAll("").length();
    }

    private static Set<String> tokens(Object value) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = TOKEN.matcher(String.valueOf(value));
        while (matcher.find()) {
            if (!matcher.group().equals(BREAK)) {
                // A plural's words are the language's; only its count is shared.
                int forms = matcher.group().startsWith("%") ? matcher.group().indexOf(':') : -1;
                tokens.add(forms < 0 ? matcher.group() : matcher.group().substring(0, forms) + ":");
            }
        }
        return tokens;
    }

    private static YamlConfiguration packaged(Class<?> anchor, String name) {
        try (InputStream in = anchor.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException unreadable) {
            return null;
        }
    }
}
