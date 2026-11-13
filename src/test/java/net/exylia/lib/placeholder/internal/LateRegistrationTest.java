package net.exylia.lib.placeholder.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Template;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A template held by its caller (a hologram line) compiled before the plugin that owns its placeholder enabled
 * still resolves once that plugin registers: the name and its arguments are split again.
 */
class LateRegistrationTest {

    private Plugin lobby;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Registry.clear();
        lobby = FakeServer.newPlugin("ExyliaLobby");
    }

    @AfterEach
    void tearDown() {
        Registry.clear();
    }

    @Test
    void aHeldTemplateResolvesAfterALateRegistration() {
        Template held = Placeholders.compile("6. %exylialobby_lobby_parkour_top_endless_6_name%");

        Placeholders.group(lobby, "lobby").add("parkour_top", request -> request.arg(1, "?")).register();

        assertEquals("6. 6", held.render());
    }
}
