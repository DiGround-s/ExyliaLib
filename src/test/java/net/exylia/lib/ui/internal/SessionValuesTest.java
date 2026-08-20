package net.exylia.lib.ui.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which values a menu parses, and which it inserts as text.
 *
 * <p>The two are not the same kind of thing. A row value is one entry in a
 * list, and lists are full of names players chose. A context value describes
 * the whole screen and was written by whoever wrote the menu.
 */
class SessionValuesTest {

    private static Map<String, Object> context(String... pairs) {
        Map<String, Object> context = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            context.put(pairs[i], pairs[i + 1]);
        }
        return context;
    }

    @Test
    @DisplayName("a context value is parsed, so a menu's own colours reach the screen")
    void contextIsParsed() {
        // ExyliaShields, menus/slots.yml: the create button's name is written
        // in config.yml as "{success}&lNEW SHIELD". Inserted literally, the
        // player reads the token instead of a green button.
        Set<String> parsed = Session.parsed(
                context("create_name", "{success}&lNEW SHIELD"), Map.of(), Set.of());

        assertTrue(parsed.contains("create_name"),
                "a value the menu's own author wrote says what it looks like");
    }

    @Test
    @DisplayName("a row value stays literal, so a player cannot recolour a menu")
    void rowValuesStayLiteral() {
        Set<String> parsed = Session.parsed(
                context("kit", "boxing"), Map.of("player_name", "{error}Steve"), Set.of());

        assertFalse(parsed.contains("player_name"),
                "what a player typed is data, whatever it looks like");
    }

    @Test
    @DisplayName("a row can still ask for its value to be parsed")
    void rowsCanOptIn() {
        Set<String> parsed = Session.parsed(
                context("kit", "boxing"),
                Map.of("rank", "{highlight}MVP"),
                Set.of("rank"));

        assertTrue(parsed.contains("rank"));
    }

    @Test
    @DisplayName("a row naming a context key keeps the row's own choice")
    void theRowWinsOnHowItIsInserted() {
        // The row's value is what gets drawn, so it is the row that decides.
        // Otherwise a list of player names would inherit "parsed" from a
        // context key that happened to share its name.
        Set<String> parsed = Session.parsed(
                context("name", "{highlight}Arena"),
                Map.of("name", "{error}Steve"),
                Set.of());

        assertFalse(parsed.contains("name"),
                "the row shadowed the context, so the row's rule applies");
    }

    @Test
    @DisplayName("a row value shadows the context value of the same name")
    void theRowWinsOnValue() {
        Map<String, String> merged = Session.merged(
                context("name", "Arena", "kit", "boxing"), Map.of("name", "Steve"));

        assertEquals("Steve", merged.get("name"));
        assertEquals("boxing", merged.get("kit"), "and leaves the rest alone");
    }

    @Test
    @DisplayName("a title showing page numbers is not left showing their names")
    void titlePageNumbersAreFilled() {
        // How nearly every paginated menu in the ecosystem is written. A window
        // title is fixed when the window is created, so there is no later
        // moment to fill these in — and unfilled, the player reads the
        // placeholder instead of a page number.
        String title = Session.filledTitle(
                "{primary}MY SHIELDS {muted}%current_page%/%total_pages%", Map.of());

        assertFalse(title.contains("%current_page%"), title);
        assertFalse(title.contains("%total_pages%"), title);
        assertTrue(title.contains("1/1"), title);
    }

    @Test
    @DisplayName("a title still takes the values the menu was opened with")
    void titleTakesContext() {
        String title = Session.filledTitle("Editing slot %slot_label%",
                context("slot_label", "3"));

        assertEquals("Editing slot 3", title);
    }
}
