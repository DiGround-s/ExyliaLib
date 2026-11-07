package net.exylia.lib.overlay.internal;

import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayCommandDataTest {

    @Test
    void aPressOnNothingGivesCommandsNothing() {
        assertTrue(OverlayView.commandData(null).isEmpty());
    }

    @Test
    void aPressOnAnEntityNamesItForCommands() {
        UUID id = UUID.randomUUID();
        Entity entity = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Steve";
                    case "getUniqueId" -> id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        assertEquals(Map.of("target", "Steve", "target_uuid", id.toString()), OverlayView.commandData(entity));
    }
}
