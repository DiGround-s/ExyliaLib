package net.exylia.lib.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a form key may be called.
 *
 * <p>A dialog sends the key of every input to the client, which validates it
 * and refuses anything outside letters, digits and underscores. Refusing it
 * there fails the decode of the whole packet — so a single hyphen in a key does
 * not drop one field, it disconnects the player who opened the form. This is
 * the test that keeps that from being discovered on a live server again.
 */
class FormKeyTest {

    @Test
    @DisplayName("an ordinary name is accepted")
    void plainNames() {
        assertNotNull(FormKey.text("name"));
        assertNotNull(FormKey.integer("minPlayers"));
        assertNotNull(FormKey.integer("min_players"));
        assertNotNull(FormKey.flag("enabled2"));
    }

    @Test
    @DisplayName("a hyphen is refused, because the client refuses it")
    void hyphenRefused() {
        InputException thrown = assertThrows(InputException.class,
                () -> FormKey.integer("min-players"));
        assertTrue(thrown.getMessage().contains("min-players"), thrown.getMessage());
        // And it says what to write instead, rather than only what is wrong.
        assertTrue(thrown.getMessage().contains("min_players"), thrown.getMessage());
    }

    @Test
    @DisplayName("the other things a config author reaches for are refused too")
    void otherSeparatorsRefused() {
        for (String name : new String[] {"min players", "min.players", "min:players",
                "min/players", "%player%"}) {
            assertThrows(InputException.class, () -> FormKey.text(name), name);
        }
    }

    @Test
    @DisplayName("a blank name is still refused, and says so differently")
    void blankRefused() {
        InputException thrown = assertThrows(InputException.class, () -> FormKey.text("  "));
        assertTrue(thrown.getMessage().contains("needs a name"), thrown.getMessage());
    }

}
