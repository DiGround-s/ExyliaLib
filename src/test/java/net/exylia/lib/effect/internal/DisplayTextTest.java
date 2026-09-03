package net.exylia.lib.effect.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import net.exylia.lib.text.Text;
import net.exylia.lib.text.internal.TextEngine;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a bar driven from a plugin's own timer costs.
 *
 * <p>The profile that drove this: every match bar pushed a fully substituted
 * string twenty times a second, each one a new string, each one a fresh
 * MiniMessage parse, and the parse cache full of strings that would never be
 * seen again.
 */
class DisplayTextTest {

    private FakePlayer viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.override(false);
        Colors.apply(new Palette());
        Plugin plugin = FakeServer.newPlugin("Practice", null);
        Effects.owner(plugin);
        viewer = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        EffectRuntime.stopEverything();
        FakeServer.reset();
        Packets.reset();
    }

    @Test
    @DisplayName("the same string pushed again is neither parsed nor redrawn")
    void unchangedStringIsANoOp() {
        Display bar = Effects.actionBar("Vida: 14.3").permanent().show(viewer.player());
        FakeServer.tick(1);
        assertEquals(1, viewer.actionBars().size());

        bar.text("Vida: 14.3");
        bar.text("Vida: 14.3");
        FakeServer.tick(1);

        assertEquals(1, viewer.actionBars().size(), "an unchanged bar must not be re-sent");

        bar.text("Vida: 13.0");
        FakeServer.tick(1);
        assertEquals(2, viewer.actionBars().size());
        assertEquals("Vida: 13.0", viewer.actionBars().get(1));
    }

    @Test
    @DisplayName("a Text with values parses its template once, however often the values change")
    void textWithValuesKeepsTheTemplateCached() {
        Display bar = Effects.actionBar("{primary}Vida: %hp%").permanent().show(viewer.player());
        FakeServer.tick(1);
        TextEngine.invalidate();

        for (int health = 20; health > 0; health--) {
            bar.text(Text.of("{primary}Vida: %hp%").with("%hp%", health));
            FakeServer.tick(1);
        }

        assertEquals("Vida: 1", viewer.actionBars().get(viewer.actionBars().size() - 1));
        assertTrue(TextEngine.cacheSize() <= 1,
                "one template must cost one cache entry, not one per value: "
                        + TextEngine.cacheSize());
    }

    @Test
    @DisplayName("a substituted string, pushed as a string, costs a parse per value")
    void plainStringsMissTheCache() {
        // The shape the profile caught, kept as the contrast the fix is measured
        // against: this is what a plugin pays for doing its own replace().
        Display bar = Effects.actionBar("{primary}Vida: 20").permanent().show(viewer.player());
        FakeServer.tick(1);
        TextEngine.invalidate();

        for (int health = 19; health > 0; health--) {
            bar.text("{primary}Vida: " + health);
            FakeServer.tick(1);
        }

        assertEquals(19, TextEngine.cacheSize());
    }
}
