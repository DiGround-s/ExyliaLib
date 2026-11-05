package net.exylia.lib.model.internal;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who is never sent a model's carrier.
 *
 * <p>The report: spectators and every camera view (killcam, replays, cinematics)
 * saw a translucent pig under a golem model, because a carrier was hidden with
 * invisibility alone and those clients draw an invisible entity see-through.
 */
class SeeThroughTest {

    private static Player inMode(GameMode mode) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(SeeThroughTest.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGameMode" -> mode;
                    case "getUniqueId" -> id;
                    default -> net.exylia.lib.FakeServer.defaultValue(method.getReturnType());
                });
    }

    @Test
    @DisplayName("a spectator's client draws invisible bodies see-through, a player's does not")
    void spectatorsSeeThrough() {
        assertTrue(ModelRuntime.seesThrough(inMode(GameMode.SPECTATOR)));
        assertFalse(ModelRuntime.seesThrough(inMode(GameMode.SURVIVAL)));
        assertFalse(ModelRuntime.seesThrough(inMode(GameMode.ADVENTURE)));
    }
}
