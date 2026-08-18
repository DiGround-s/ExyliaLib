package net.exylia.lib.item.internal;

import net.exylia.lib.FakeServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

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

    @BeforeEach
    void setUp() {
        FakeServer.install();
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
}
