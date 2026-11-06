package net.exylia.lib.text.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconsTest {

    @Test
    void interfaceIconsResolveWithTheirGlyph() {
        Map<String, String> sprites = Map.ofEntries(
                Map.entry("click", "toast/mouse"), Map.entry("right_click", "toast/right_click"),
                Map.entry("on", "pending_invite/accept"), Map.entry("off", "pending_invite/reject"),
                Map.entry("locked", "widget/locked_button"), Map.entry("unlocked", "widget/unlocked_button"),
                Map.entry("up", "statistics/sort_up"), Map.entry("down", "statistics/sort_down"),
                Map.entry("warning", "world_list/warning"), Map.entry("music", "icon/music_notes"),
                Map.entry("signal_1", "server_list/ping_1"), Map.entry("signal_5", "server_list/ping_5"));
        sprites.forEach((name, sprite) -> {
            Icons.Icon icon = Icons.parse(name);
            assertNotNull(icon, name);
            assertEquals("gui", icon.atlas(), name);
            assertEquals(sprite, icon.sprite(), name);
            assertTrue(Icons.draw(name).contains(icon.glyph()) || Icons.draw(name).contains(sprite), name);
        });
        assertEquals("»", Icons.parse("click").glyph());
        assertEquals("»", Icons.parse("right_click").glyph());
        assertEquals("✔", Icons.parse("on").glyph());
        assertEquals("✘", Icons.parse("off").glyph());
        assertEquals("▲", Icons.parse("up").glyph());
        assertEquals("▼", Icons.parse("down").glyph());
        assertEquals("⚠", Icons.parse("warning").glyph());
        assertEquals("♪", Icons.parse("music").glyph());
        for (int level = 1; level <= 5; level++) {
            assertEquals("▮", Icons.parse("signal_" + level).glyph());
        }
    }

    @Test
    void aWrittenGlyphOverridesTheStandIn() {
        assertEquals("✧", Icons.parse("click|✧").glyph());
    }
}
