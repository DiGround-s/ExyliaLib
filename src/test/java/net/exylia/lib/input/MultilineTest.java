package net.exylia.lib.input;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Room to read what you are editing.
 *
 * <p>A display name is a dozen colour tokens around six words, and editing one
 * in a box that shows twenty characters at a time is editing blind. The height
 * is a hint a transport may ignore — chat has no notion of one — so what is
 * asserted here is that the request carries it and refuses a nonsense value,
 * not what any particular client drew.
 */
class MultilineTest {

    private FakePlayer player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    private PluginInputs inputs() {
        return Inputs.of(FakeServer.newPlugin("Events", null));
    }

    @Test
    @DisplayName("a request is one line tall unless somebody asks for more")
    void defaultsToOneLine() {
        assertEquals(1, inputs().text(player.player(), "Name").lines());
    }

    @Test
    @DisplayName("a text request carries the height it was given")
    void carriesTheHeight() {
        assertEquals(4, inputs().text(player.player(), "Lore").lines(4).lines());
    }

    @Test
    @DisplayName("a form field carries its own height, field by field")
    void perField() {
        FormField<String> name = FormField.text(FormKey.text("name"), "Name");
        FormField<String> lore = FormField.text(FormKey.text("lore"), "Lore").lines(5);

        assertEquals(1, name.lines());
        assertEquals(5, lore.lines());
    }

    @Test
    @DisplayName("a box shorter than one line is a mistake, not a zero-height box")
    void refusesNonsense() {
        assertThrows(InputException.class, () -> inputs().text(player.player(), "Name").lines(0));
        assertThrows(InputException.class,
                () -> FormField.text(FormKey.text("name"), "Name").lines(-1));
    }

    @Test
    @DisplayName("the value being edited comes back prefilled")
    void prefilled() {
        TextInput request = inputs().text(player.player(), "New display name")
                .defaultValue("{primary}&lARENA ONE")
                .lines(3);

        // What a dialog puts in the box, and what makes editing a name a
        // correction rather than retyping it from memory.
        assertEquals("{primary}&lARENA ONE", request.defaultValue());
        assertEquals(3, request.lines());
    }
}
