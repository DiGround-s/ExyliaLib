package net.exylia.lib.spatial;

import net.exylia.lib.util.preview.PreviewSettings;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialPreviewTest {

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    @Test
    void liveNpcsWithTheSameSkinAreOnePreview() {
        Player viewer = player();
        // A menu builds a fresh supplier on every redraw: that must not redraw the body.
        assertEquals(SpatialPreview.npc(viewer, Map::of), SpatialPreview.npc(viewer, () -> Map.of()));
        assertFalse(SpatialPreview.npc(viewer, Map::of).equals(SpatialPreview.npc(player(), Map::of)));
    }

    @Test
    void mannequinSkinsDefaultToOthersThenDefaults() {
        assertEquals(List.of("others", "defaults"), new PreviewSettings.Mannequin().skins());
        assertEquals(List.of("viewer"), new PreviewSettings.Mannequin(List.of(), 30, 12, false, 1, List.of("viewer")).skins());
        assertTrue(new PreviewSettings.Mannequin(List.of(), 30, 12, false, 1).skins().contains("others"));
    }
}
