package net.exylia.lib.text;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhrasesTest {

    /** A tr( call whose first argument is one string literal, the only shape the table can key. */
    private static final Pattern CALL = Pattern.compile("Phrases\\.tr\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SLOT = Pattern.compile("\\{\\d+}");
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path LANGUAGES = Path.of("src/main/resources/lang");

    @AfterEach
    void tearDown() {
        Phrases.use(Map.of());
    }

    @Test
    void aLineWithNoTranslationStaysEnglish() {
        Phrases.use(Map.of("{success}&lADD", "{success}&lAÑADIR"));

        assertEquals("{success}&lAÑADIR", Phrases.tr("{success}&lADD"));
        assertEquals("{error}&lDELETE", Phrases.tr("{error}&lDELETE"));
    }

    @Test
    void slotsAreFilledWhereTheTranslationPutsThem() {
        Phrases.use(Map.of("Copy {0} entries to {1}", "Copiar a {1} {0} entradas"));

        assertEquals("Copiar a main 3 entradas", Phrases.tr("Copy {0} entries to {1}", 3, "main"));
        assertEquals("Paste 2", Phrases.tr("Paste {0}", 2), "untranslated lines are filled too");
        assertEquals("Find {1} in main", Phrases.tr("Find {0} in {1}", "{1}", "main"), "a value is never filled again");
    }

    /**
     * Every line the code passes through the table is translated in every
     * language the jar ships, with the same numbered slots — so a new button
     * cannot reach a release in English only, or lose the value it shows.
     */
    @Test
    void everyLineIsTranslatedInEveryShippedLanguage() throws IOException {
        Set<String> lines = linesInCode();
        assertTrue(!lines.isEmpty(), "no Phrases.tr call was found under " + SOURCES.toAbsolutePath());

        try (Stream<Path> languages = Files.list(LANGUAGES)) {
            for (Path language : languages.filter(Files::isDirectory).toList()) {
                Path file = language.resolve("phrases.yml");
                if (language.getFileName().toString().equals("en") || !Files.exists(file)) {
                    continue;
                }
                Map<String, String> table = table(file);
                var missing = new ArrayList<String>();
                var mismatched = new ArrayList<String>();
                for (String line : lines) {
                    String translated = table.get(line);
                    if (translated == null) {
                        missing.add(line);
                    } else if (!slots(line).equals(slots(translated))) {
                        mismatched.add(line + "  ->  " + translated);
                    }
                }
                assertTrue(missing.isEmpty(), file + " misses " + missing.size() + " lines:\n"
                        + String.join("\n", missing));
                assertTrue(mismatched.isEmpty(), file + " changes the slots of:\n" + String.join("\n", mismatched));
            }
        }
    }

    static Set<String> linesInCode() throws IOException {
        Set<String> lines = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = CALL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    lines.add(unescape(matcher.group(1)));
                }
            }
        }
        return lines;
    }

    private static Set<String> slots(String line) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = SLOT.matcher(line);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> table(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            return root instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
        }
    }

    private static String unescape(String literal) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c != '\\' || i + 1 == literal.length()) {
                out.append(c);
                continue;
            }
            char next = literal.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(literal.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
