package net.exylia.lib.input;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Asking for an item rather than for what an item looks like.
 *
 * <p>The window itself belongs to the insert-window tests. What is asserted
 * here is the request around it: that it is built per ask, and that it refuses
 * the two arguments a caller can get wrong before anybody sees a screen.
 */
class ItemInputTest {

    private PluginInputs inputs;
    private Player player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Plugin plugin = FakeServer.newPlugin("ItemTestPlugin", null);
        player = new FakePlayer("Steve").player();
        FakeServer.online(player);
        Inputs.releaseAll();
        inputs = Inputs.of(plugin);
    }

    @Test
    @DisplayName("every ask is its own request")
    void eachAskIsItsOwnRequest() {
        // Two screens open at once are two answers, and a shared builder would
        // make the second one overwrite the first.
        assertNotSame(inputs.item(player, "{primary}One"), inputs.item(player, "{primary}Two"));
        assertNotNull(inputs.item(player, "{primary}One"));
    }

    @Test
    @DisplayName("a request without a player or a prompt is refused")
    void badArgumentsAreRefused() {
        assertThrows(RuntimeException.class, () -> inputs.item(null, "{primary}Prompt"));
        assertThrows(RuntimeException.class, () -> inputs.item(player, "  "));
    }
}
