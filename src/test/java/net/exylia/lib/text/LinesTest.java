package net.exylia.lib.text;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a server owner can write, and what comes back.
 *
 * <p>Every block of YAML here is a shape deployed files are written in. The two
 * that matter are the ones plugins kept re-implementing: one long sentence
 * broken with {@code <nl>}, and a key that is a String in one file and a list in
 * the next.
 */
class LinesTest {

    private static YamlConfiguration config(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception invalid) {
            throw new IllegalStateException("test yaml is not valid", invalid);
        }
        return config;
    }

    @Test
    @DisplayName("a key written as one String is one line")
    void singleString() {
        assertEquals(List.of("Hits nearby players."),
                Lines.read(config("description: \"Hits nearby players.\"\n"), "description"));
    }

    @Test
    @DisplayName("a String carrying <nl> becomes the lines it asks for")
    void stringWithNewlines() {
        // The whole point: the owner writes the sentence beside the thing it
        // describes and says where it should break.
        assertEquals(List.of("Hits nearby players", "and knocks them back."),
                Lines.read(config("""
                        description: "Hits nearby players<nl>and knocks them back."
                        """), "description"));
    }

    @Test
    @DisplayName("real LF, CRLF, CR, and literal \\n breaks become lore lines")
    void alternateNewlines() {
        assertEquals(List.of("first", "second", ""), Lines.split("first\nsecond\n"));
        assertEquals(List.of("first", "second", ""), Lines.split("first\r\nsecond\r\n"));
        assertEquals(List.of("first", "second", ""), Lines.split("first\rsecond\r"));
        assertEquals(List.of("first", "second", ""), Lines.split("first\\nsecond\\n"));
    }

    @Test
    @DisplayName("a key written as a list is read entry by entry")
    void listOfLines() {
        assertEquals(List.of("{secondary}Info:", " {letters}Pick a kit"),
                Lines.read(config("""
                        lore:
                          - "{secondary}Info:"
                          - " {letters}Pick a kit"
                        """), "lore"));
    }

    @Test
    @DisplayName("one entry of a list can still carry <nl>")
    void listEntryWithNewlines() {
        assertEquals(List.of("first", "second", "third"),
                Lines.read(config("""
                        lore:
                          - "first<nl>second"
                          - "third"
                        """), "lore"));
    }

    @Test
    @DisplayName("mixed line separators in Strings and list entries preserve trailing blanks")
    void mixedNewlinesAndListEntries() {
        assertEquals(List.of("first", "second", "third", ""),
                Lines.read(config("description: |-\n  first\n  second<nl>third\\n\n"), "description"));
        assertEquals(List.of("first", "second", "third", ""), Lines.read(config("""
                lore:
                  - "first\\\\nsecond"
                  - "third<nl>"
                """), "lore"));
    }

    @Test
    @DisplayName("a key that is not there reads as nothing, not as null")
    void missingKey() {
        assertEquals(List.of(), Lines.read(config("material: STONE\n"), "lore"));
    }

    @Test
    @DisplayName("an empty String and an empty list both read as nothing")
    void emptyValues() {
        assertEquals(List.of(), Lines.read(config("lore: \"\"\n"), "lore"));
        assertEquals(List.of(), Lines.read(config("lore: []\n"), "lore"));
    }

    @Test
    @DisplayName("the first key the file carries answers")
    void keyPrecedence() {
        YamlConfiguration both = config("""
                description: "renamed"
                lore: "old"
                """);

        assertEquals(List.of("renamed"), Lines.read(both, "description", "lore"));
        assertEquals(List.of("old"), Lines.read(both, "lore", "description"));
    }

    @Test
    @DisplayName("a preferred key holding nothing falls through to the next")
    void emptyKeyFallsThrough() {
        // An empty list is a key that says nothing, so the file's other
        // spelling still gets its turn.
        assertEquals(List.of("old"), Lines.read(config("""
                description: []
                lore: "old"
                """), "description", "lore"));
    }

    @Test
    @DisplayName("a trailing blank line survives, because it is a separator")
    void blankLinesArePreserved() {
        // A lore block ends on a blank line on purpose; dropping it closes the
        // gap the owner asked for.
        assertEquals(List.of("Info", "", "Stats", ""),
                Lines.read(config("""
                        lore: "Info<nl><nl>Stats<nl>"
                        """), "lore"));
    }

    @Test
    @DisplayName("a blank list entry stays a blank line")
    void blankListEntry() {
        assertEquals(List.of("Info", "", "Stats"),
                Lines.read(config("""
                        lore:
                          - "Info"
                          - ""
                          - "Stats"
                        """), "lore"));
    }

    @Test
    @DisplayName("a list entry ending in <nl> keeps the blank line it asks for")
    void listEntryTrailingBlank() {
        // Same separator rule as a String, and the reason both splits keep the
        // trailing empties rather than only the one that happened to be tested.
        assertEquals(List.of("Info", "", "Stats"),
                Lines.read(config("""
                        lore:
                          - "Info<nl>"
                          - "Stats"
                        """), "lore"));
    }

    @Test
    @DisplayName("value() reads a key back as one row value")
    void valueJoins() {
        assertEquals("first<nl>second<nl>third", Lines.value(config("""
                lore:
                  - "first<nl>second"
                  - "third"
                """), "lore"));
        assertEquals("", Lines.value(config("material: STONE\n"), "lore"));
    }

    @Test
    @DisplayName("value() normalizes alternate breaks to the canonical token")
    void valueNormalizesAlternateNewlines() {
        assertEquals("first<nl>second", Lines.value(config("description: \"first\\\\nsecond\"\n"), "description"));
        assertEquals("first<nl>second", Lines.value(config("description: \"first\\nsecond\"\n"), "description"));
        assertEquals("first<nl>second", Lines.value(config("description: |-\n  first\n  second\n"), "description"));
    }

    @Test
    @DisplayName("split and join are the two halves of the same thing")
    void splitAndJoin() {
        assertEquals(List.of("one", "two"), Lines.split("one<nl>two"));
        assertEquals(List.of("plain"), Lines.split("plain"));
        assertEquals(List.of(), Lines.split(null));
        assertEquals(List.of(), Lines.split(""));

        assertEquals("one<nl>two", Lines.join(List.of("one", "two")));
        assertEquals("", Lines.join(null));
        assertEquals("", Lines.join(List.of()));
        assertEquals(List.of("one", "two"), Lines.split(Lines.join(List.of("one", "two"))));
    }

    @Test
    @DisplayName("what comes back cannot be modified by the caller")
    void resultIsImmutable() {
        List<String> lore = Lines.read(config("""
                lore:
                  - "first<nl>second"
                """), "lore");

        assertThrows(UnsupportedOperationException.class, () -> lore.add("third"));
    }

    @Test
    @DisplayName("the class is a namespace, not something to instantiate")
    void noInstances() {
        assertTrue(java.lang.reflect.Modifier.isFinal(Lines.class.getModifiers()));
    }
}
