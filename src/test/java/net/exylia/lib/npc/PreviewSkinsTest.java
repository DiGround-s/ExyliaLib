package net.exylia.lib.npc;

import net.exylia.lib.skull.internal.Textures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewSkinsTest {

    @Test
    @DisplayName("every default skin gets fresh UUIDs the client hashes back to it, in the client's order")
    void uuidMapsToDefault() {
        for (DefaultSkin skin : DefaultSkin.values()) {
            Set<UUID> seen = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                UUID id = skin.uuid();
                assertSame(skin, DefaultSkin.of(id));
                assertEquals(skin.ordinal(), Math.floorMod(id.hashCode(), 18));
                assertTrue(seen.add(id), "a UUID repeated");
            }
        }
        assertTrue(DefaultSkin.ALEX_SLIM.slim());
        assertFalse(DefaultSkin.STEVE_WIDE.slim());
        assertEquals(15, DefaultSkin.STEVE_WIDE.ordinal());
        assertEquals("http://textures.minecraft.net/texture/" + DefaultSkin.STEVE_WIDE.textureId(),
                Textures.urlOf(DefaultSkin.STEVE_WIDE.textures()));
        assertTrue(Textures.slim(DefaultSkin.ZURI_SLIM.textures()));
        assertFalse(Textures.slim(DefaultSkin.ZURI_WIDE.textures()));
    }

    @Test
    @DisplayName("the policy reads names in order, skips typos and duplicates, and defaults to [others, defaults]")
    void parse() {
        assertEquals(List.of(PreviewSkins.Source.VIEWER, PreviewSkins.Source.DEFAULTS),
                PreviewSkins.of(List.of(" Viewer", "nobody", "defaults", "viewer")).sources());
        assertSame(PreviewSkins.DEFAULT, PreviewSkins.of(null));
        assertSame(PreviewSkins.DEFAULT, PreviewSkins.of(List.of("typo")));
        assertEquals(List.of(PreviewSkins.Source.OTHERS, PreviewSkins.Source.DEFAULTS), PreviewSkins.DEFAULT.sources());
    }

    @Test
    @DisplayName("others never picks the viewer, keeps signed skins, and gives a skinless player their UUID's default")
    void others() {
        UUID viewer = UUID.randomUUID();
        UUID signed = UUID.randomUUID();
        UUID skinless = DefaultSkin.KAI_WIDE.uuid();
        assertNull(PreviewSkins.other(viewer, Map.of(viewer, new String[]{"v", "s"})));

        PreviewSkins.Body only = PreviewSkins.other(viewer, Map.of(viewer, new String[]{"v", "s"},
                signed, new String[]{"tex", "sig"}));
        assertNotNull(only);
        assertEquals("tex", only.texture());
        assertNull(only.defaultSkin());

        PreviewSkins.Body fallback = PreviewSkins.other(viewer, Map.of(skinless, new String[]{null, null}));
        assertNotNull(fallback);
        assertNull(fallback.texture());
        assertSame(DefaultSkin.KAI_WIDE, fallback.defaultSkin());

        Set<String> picked = new HashSet<>();
        Map<UUID, String[]> two = Map.of(viewer, new String[]{"v", null}, signed, new String[]{"a", null},
                UUID.randomUUID(), new String[]{"b", null});
        for (int i = 0; i < 200; i++) picked.add(PreviewSkins.other(viewer, two).texture());
        assertEquals(Set.of("a", "b"), picked, "varies between plays and never the viewer");
    }

    @Test
    @DisplayName("defaults walk through all eighteen vanilla skins")
    void defaultsRotate() {
        Set<DefaultSkin> seen = new HashSet<>();
        for (int i = 0; i < 18; i++) seen.add(PreviewSkins.nextDefault().defaultSkin());
        assertEquals(new HashSet<>(Arrays.asList(DefaultSkin.values())), seen);
    }
}
