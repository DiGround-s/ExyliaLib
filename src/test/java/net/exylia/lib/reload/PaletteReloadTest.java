package net.exylia.lib.reload;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.effect.internal.Packets;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What actually changes colour when the palette is reloaded.
 *
 * <p>The audit behind {@code docs/reload.md}: every module that renders text
 * has to pick up a new palette, and the ones that cache a parsed component
 * are the ones that can silently fail to.
 */
class PaletteReloadTest {

    /** A palette whose primary is unmistakably different from the default. */
    private static final Palette RECOLOURED = new Palette(
            "#ff0000",  // primary
            "#aa76de",  // secondary
            "#b48fd9",  // secondaryLight
            "#e7cfff",  // letters
            "#a89ab5",  // lettersBlack
            "#a33b53",  // error
            "#8fffc1",  // success
            "#a1ffc3",  // successLight
            "#ff9500",  // warning
            "#ffd2a8",  // warningLight
            "#59a4ff",  // info
            "#7db7ff",  // infoLight
            "#ff6b9d",  // accent
            "#6c757d",  // neutral
            "#ffd700",  // highlight
            "#868e96"); // muted

    private FakePlayer viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.override(false);
        Colors.apply(new Palette());

        Plugin plugin = FakeServer.newPlugin("ReloadAudit", null);
        Effects.owner(plugin);
        viewer = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        EffectRuntime.stopEverything();
        Colors.apply(new Palette());
        FakeServer.reset();
        Packets.reset();
    }

    private static TextColor colourOf(Component component) {
        if (component.color() != null) {
            return component.color();
        }
        return component.children().isEmpty() ? null : component.children().get(0).color();
    }

    @Test
    @DisplayName("text parsed after a reload uses the new palette")
    void textPicksUpNewColours() {
        TextColor before = colourOf(Text.component("{primary}Hello"));

        Colors.apply(RECOLOURED);

        TextColor after = colourOf(Text.component("{primary}Hello"));
        assertNotEquals(before, after, "the parse cache must not survive a recolour");
        assertEquals(TextColor.color(0xff0000), after);
    }

    @Test
    @DisplayName("a palette token resolves to the new colour")
    void colorsGetReflectsNewPalette() {
        Colors.apply(RECOLOURED);

        assertEquals(TextColor.color(0xff0000), Colors.get("primary"));
    }

    @Test
    @DisplayName("a static effect is re-drawn, having been drawn once and left alone")
    void staticEffectIsRecoloured() {
        // The saving that makes a permanent bar cheap is exactly what would
        // leave it showing last week's colours.
        Effects.actionBar("{primary}Waiting").show(viewer.player());
        FakeServer.tick(1);
        TextColor before = colourOf(viewer.actionBarComponents().get(0));
        viewer.clear();

        Colors.apply(RECOLOURED);
        EffectRuntime.invalidateAll();
        FakeServer.tick(1);

        assertTrue(viewer.actionBarComponents().size() >= 1,
                "a static effect must be re-sent after a recolour");
        TextColor after = colourOf(viewer.actionBarComponents().get(0));
        assertNotEquals(before, after);
        assertEquals(TextColor.color(0xff0000), after);
    }

    @Test
    @DisplayName("the effect keeps its text; only what it parses into changes")
    void recolourDoesNotChangeText() {
        Effects.actionBar("{primary}Waiting").show(viewer.player());
        FakeServer.tick(1);
        viewer.clear();

        Colors.apply(RECOLOURED);
        EffectRuntime.invalidateAll();
        FakeServer.tick(1);

        assertEquals("Waiting", viewer.actionBars().get(0));
    }

    @Test
    @DisplayName("re-drawing everything reports how many it touched")
    void invalidateAllCounts() {
        // Two players, because one player has one action bar: a second one for
        // the same screen replaces the first rather than joining it.
        FakePlayer other = new FakePlayer("Alex");
        Effects.actionBar("{primary}One").show(viewer.player());
        Effects.actionBar("{primary}Two").show(other.player());
        FakeServer.tick(1);

        assertEquals(2, EffectRuntime.invalidateAll());
    }

    @Test
    @DisplayName("a dynamic effect was never at risk: it re-parses every cycle")
    void dynamicEffectAlreadyRecolours() {
        Effects.actionBar("{primary}%time%").countdown(5).show(viewer.player());
        FakeServer.tick(1);
        viewer.clear();

        Colors.apply(RECOLOURED);
        FakeServer.tick(1);

        assertEquals(TextColor.color(0xff0000),
                colourOf(viewer.actionBarComponents().get(0)),
                "no invalidation needed — it builds its text again each time");
    }
}
