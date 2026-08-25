package net.exylia.lib.input;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The note that says what a valid answer looks like.
 *
 * <p>A field labelled "Command the console runs" leaves the player guessing
 * whether the player is {@code %player%} or {@code %player_name%}, and a wrong
 * guess is only found later, in a reward that silently does nothing. The hint
 * is what a transport draws — a Bedrock placeholder, a muted line under a
 * dialog label — so what is asserted here is that the request carries it.
 */
class HintTest {

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
    @DisplayName("a request carries no hint unless one was written")
    void noneByDefault() {
        assertNull(inputs().text(player.player(), "Command").hint());
        assertNull(FormField.text(FormKey.text("command"), "Command").hint());
    }

    @Test
    @DisplayName("a text request carries the hint it was given")
    void carriesTheHint() {
        assertEquals("Use %player_name% for the player",
                inputs().text(player.player(), "Command")
                        .hint("Use %player_name% for the player")
                        .hint());
    }

    @Test
    @DisplayName("a form field carries its own hint, field by field")
    void perField() {
        FormField<String> command = FormField.text(FormKey.text("command"), "Command")
                .hint("No leading slash");
        FormField<String> name = FormField.text(FormKey.text("name"), "Name");

        assertEquals("No leading slash", command.hint());
        assertNull(name.hint());
    }

    @Test
    @DisplayName("a blank hint is no hint, not an empty line under the label")
    void blankIsNone() {
        assertNull(inputs().text(player.player(), "Command").hint("   ").hint());
        assertNull(FormField.text(FormKey.text("command"), "Command").hint("").hint());
    }
}
