package net.exylia.lib.input.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.input.ChoiceInput;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reading back which button was pressed.
 *
 * <p>A dialog draws a choice as one button per option, and a button can only
 * carry the option's position: the key is the plugin's own string and the
 * action id is a resource location, which does not accept one. So the position
 * has to survive the round trip, and a position that no longer means anything
 * has to be no answer rather than the wrong one.
 */
class ChoiceOptionsTest {

    private PluginInputs inputs;
    private Player player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Plugin plugin = FakeServer.newPlugin("ChoiceTestPlugin", null);
        player = new FakePlayer("Steve").player();
        FakeServer.online(player);
        Inputs.releaseAll();
        inputs = Inputs.of(plugin);
    }

    private ChoiceInput<String> ways() {
        return inputs.choice(player, "Choose an icon", List.of("material", "insert", "head"))
                .label(way -> way.toUpperCase(java.util.Locale.ROOT));
    }

    @Test
    @DisplayName("a button per option, labelled as the choice says")
    void labelsAreTheChoicesOwn() {
        assertEquals(List.of("MATERIAL", "INSERT", "HEAD"), ChoiceOptions.labels(ways()));
    }

    @Test
    @DisplayName("a position comes back as the option's key")
    void positionsResolveToKeys() {
        ChoiceInput<String> choice = ways();

        assertEquals("material", ChoiceOptions.keyAt(choice, "0"));
        assertEquals("insert", ChoiceOptions.keyAt(choice, "1"));
        assertEquals("head", ChoiceOptions.keyAt(choice, "2"));
    }

    @Test
    @DisplayName("a position that is not one of the options is not an answer")
    void unknownPositionsAreNoAnswer() {
        ChoiceInput<String> choice = ways();

        // A dialog left open across a reload answers with a position the list
        // no longer has; answering it would answer with something the player
        // never read.
        assertNull(ChoiceOptions.keyAt(choice, "3"));
        assertNull(ChoiceOptions.keyAt(choice, "-1"));
        assertNull(ChoiceOptions.keyAt(choice, ""));
        assertNull(ChoiceOptions.keyAt(choice, "held"));
    }
}
