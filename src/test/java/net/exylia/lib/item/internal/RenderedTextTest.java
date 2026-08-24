package net.exylia.lib.item.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.FakePlayer;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.internal.Registry;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a row's values do to the line they are substituted into.
 *
 * <p>Only the text, because an {@code ItemStack} cannot be built without the
 * server's registry. That is enough: every way this can go wrong is a wrong
 * answer here — a value that should have been literal arriving as formatting,
 * or a display name from a config printing its own tags to the screen.
 */
class RenderedTextTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Registry.clear();
        plugin = FakeServer.newPlugin("TestPlugin");
    }

    @AfterEach
    void tearDown() {
        Registry.clear();
    }

    private static String legacy(String written, Map<String, String> values,
                                 Set<String> formatted) {
        return LegacyComponentSerializer.legacySection()
                .serialize(ItemRenderer.text(written, null, values, formatted));
    }

    @Test
    @DisplayName("a value a player typed cannot recolour the line it lands on")
    void literalValuesCannotInject() {
        // The reason literal is the default. A kit somebody named "{error}X"
        // must not repaint the row, and a name is data rather than formatting.
        String drawn = legacy("{letters}Killed by %name%",
                Map.of("name", "{error}&lHacker"), Set.of());

        assertTrue(drawn.contains("{error}&lHacker"),
                "the typed text is shown exactly as typed: " + drawn);
    }

    @Test
    @DisplayName("a value the owner wrote arrives as the formatting it describes")
    void formattedValuesAreParsed() {
        // A rank display name out of a config: it is formatting, and printing
        // its tags to the screen is the bug this door exists to close.
        String drawn = legacy("%rank% {letters}joined",
                Map.of("rank", "{highlight}&lMVP"), Set.of("rank"));

        assertFalse(drawn.contains("{highlight}"), "the token is resolved: " + drawn);
        assertFalse(drawn.contains("&l"), "and so is the legacy code: " + drawn);
        assertTrue(drawn.contains("MVP"), drawn);
    }

    @Test
    @DisplayName("a formatted UI display name renders its MiniMessage styling")
    void formattedDisplayNameIsParsed() {
        String drawn = legacy("%arena_name%", Map.of(
                "arena_name", "<white><gold><bold> NethPot"), Set.of("arena_name"));

        assertTrue(drawn.contains("NethPot"), drawn);
        assertFalse(drawn.contains("<white>"), "the colour tag is formatting, not text: " + drawn);
        assertFalse(drawn.contains("<gold>"), "the colour tag is formatting, not text: " + drawn);
        assertFalse(drawn.contains("<bold>"), "the style tag is formatting, not text: " + drawn);
    }

    @Test
    @DisplayName("naming one value formatted leaves the others alone")
    void onlyTheNamedValueIsParsed() {
        // The distinction is per value, not per item: the same row carries a
        // rank the owner wrote and a name a player chose.
        String drawn = legacy("%rank% %name%",
                Map.of("rank", "{highlight}MVP", "name", "{error}Steve"),
                Set.of("rank"));

        assertFalse(drawn.contains("{highlight}"), "the owner's value is parsed: " + drawn);
        assertTrue(drawn.contains("{error}Steve"), "the player's value is not: " + drawn);
    }

    @Test
    @DisplayName("a literal row player name overrides the viewer placeholder")
    void literalRowPlayerNameOverridesResolver() {
        FakePlayer viewer = new FakePlayer("Viewer");
        Placeholders.register(plugin, "player_name", request -> request.requireViewer().getName());

        String drawn = LegacyComponentSerializer.legacySection().serialize(ItemRenderer.text("%player_name%",
                viewer.player(), Map.of("player_name", "Row"), Set.of()));

        assertTrue(drawn.contains("Row"), drawn);
        assertFalse(drawn.contains("Viewer"), drawn);
    }

    @Test
    @DisplayName("a formatted row player name overrides the viewer placeholder")
    void formattedRowPlayerNameOverridesResolver() {
        FakePlayer viewer = new FakePlayer("Viewer");
        Placeholders.register(plugin, "player_name", request -> request.requireViewer().getName());

        String drawn = LegacyComponentSerializer.legacySection().serialize(ItemRenderer.text("%player_name%",
                viewer.player(), Map.of("player_name", "{accent}Row"), Set.of("player_name")));

        assertTrue(drawn.contains("Row"), drawn);
        assertFalse(drawn.contains("Viewer"), drawn);
        assertFalse(drawn.contains("{accent}"), drawn);
    }

    private static List<String> lore(List<String> written, Map<String, String> values,
                                     Set<String> formatted) {
        return ItemRenderer.lore(written, null, values, formatted).stream()
                .map(LegacyComponentSerializer.legacySection()::serialize)
                .toList();
    }

    @Test
    @DisplayName("a row value spanning several lines becomes several lore lines")
    void multiLineValuesExpand() {
        // ItemReader splits <nl> when the file is read, which cannot reach a
        // value that only exists at render time. Returning one line dropped
        // every other one silently, which is what was reported.
        List<String> drawn = lore(List.of("{muted}%description%"),
                Map.of("description", "First line<nl>Second line"), Set.of());

        assertEquals(2, drawn.size(), "both lines are drawn: " + drawn);
        assertTrue(drawn.get(0).contains("First line"), drawn.toString());
        assertTrue(drawn.get(1).contains("Second line"), drawn.toString());
    }

    @Test
    @DisplayName("every expanded line keeps what the template puts around it")
    void expandedLinesKeepTheirTemplate() {
        // The reason this is worth doing here rather than in the plugin: the
        // second line is as much a lore line as the first, bullet included.
        List<String> drawn = lore(List.of("{muted} | {letters}%description%"),
                Map.of("description", "One<nl>Two"), Set.of());

        assertTrue(drawn.get(0).contains("|"), "the first keeps the bullet: " + drawn);
        assertTrue(drawn.get(1).contains("|"), "and so does the second: " + drawn);
    }

    @Test
    @DisplayName("a multi-line value stretches only the lines that mention it")
    void otherLinesAreNotStretched() {
        // A description belonging to one line must not multiply an unrelated
        // one, or a five-line tooltip would silently become fifteen.
        List<String> drawn = lore(List.of("{letters}Effect: %id%", "{muted}%description%"),
                Map.of("id", "fire_trail", "description", "One<nl>Two"), Set.of());

        assertEquals(3, drawn.size(), "one line plus two: " + drawn);
        assertTrue(drawn.get(0).contains("fire_trail"), drawn.toString());
    }

    @Test
    @DisplayName("a single-line value beside a multi-line one survives every line")
    void neighbouringValuesRepeat() {
        // Dropping it after the first line would blank text the file asked for,
        // and nothing on screen would say why.
        List<String> drawn = lore(List.of("{letters}%label%: %description%"),
                Map.of("label", "Effect", "description", "One<nl>Two"), Set.of());

        assertTrue(drawn.get(0).contains("Effect"), drawn.toString());
        assertTrue(drawn.get(1).contains("Effect"), "the label is on both: " + drawn);
    }

    @Test
    @DisplayName("an expanded line is still literal unless the owner wrote the value")
    void expansionDoesNotBypassLiteralValues() {
        // Expanding must not become a second door into the parser: a player who
        // types a newline marker still cannot recolour anything.
        List<String> drawn = lore(List.of("{letters}%name%"),
                Map.of("name", "{error}&lOne<nl>{error}&lTwo"), Set.of());

        assertEquals(2, drawn.size(), drawn.toString());
        assertTrue(drawn.get(0).contains("{error}&lOne"), "still literal: " + drawn);
        assertTrue(drawn.get(1).contains("{error}&lTwo"), "on both lines: " + drawn);
    }

    @Test
    @DisplayName("an owner's multi-line value is parsed on every line")
    void expandedFormattedValuesAreParsed() {
        // The reported case: a description written in effects.yml with colour
        // codes, spanning two lines.
        List<String> drawn = lore(List.of("{muted}%description%"),
                Map.of("description", "&7One<nl>&7Two"), Set.of("description"));

        assertEquals(2, drawn.size(), drawn.toString());
        assertFalse(drawn.get(0).contains("&7"), "the code is resolved: " + drawn);
        assertFalse(drawn.get(1).contains("&7"), "on the second line too: " + drawn);
    }

    @Test
    @DisplayName("a literal \\n in a row value expands the way <nl> does")
    void literalBackslashNExpands() {
        // A value read from a config arrives with the spelling commons used.
        // Folding it in one place keeps every plugin from normalising its own.
        List<String> drawn = lore(List.of("{muted}%description%"),
                Map.of("description", "First line\\nSecond line"), Set.of());

        assertEquals(2, drawn.size(), "the literal \\n becomes two lines: " + drawn);
        assertTrue(drawn.get(0).contains("First line"), drawn.toString());
        assertTrue(drawn.get(1).contains("Second line"), drawn.toString());
        assertFalse(drawn.toString().contains("\\n"), "no raw marker survives: " + drawn);
    }
}
