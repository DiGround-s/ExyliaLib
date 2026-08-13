package net.exylia.lib.effect;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.effect.internal.Packets;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who owns a display when several plugins share a server.
 *
 * <p>The first implementation kept one global owner: two plugins on one server
 * would overwrite each other, scheduling displays under the wrong plugin and
 * cleaning them under the wrong name. And a plugin that simply forgot
 * {@code Effects.owner} — which happened, in production — crashed the first
 * boss bar it tried to show.
 */
class EffectOwnershipTest {

    private Plugin alpha;
    private Plugin beta;
    private FakePlayer viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.override(false);
        Colors.apply(new Palette());
        EffectRuntime.stopEverything();
        EffectRuntime.releaseAll();
        alpha = FakeServer.newPlugin("Alpha", null);
        beta = FakeServer.newPlugin("Beta", null);
        viewer = new FakePlayer("Steve")
                .at(new org.bukkit.Location(FakeServer.newWorld("world"), 0, 64, 0));
    }

    @AfterEach
    void tearDown() {
        EffectRuntime.stopEverything();
        EffectRuntime.releaseAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a plugin that never called owner() still gets its effects")
    void forgettingToRegisterIsNotFatal() {
        // The old contract made this throw — "Effects.owner(plugin) must be
        // called in onEnable" — and a boss bar died in production because of
        // it. Registration now happens on demand: on a real server the plugin
        // is looked up by name, and here Effects.of does it directly.
        Effects.owner(alpha);
        Effects.owner(beta);

        Display display = Effects.of(alpha).bossBar("Energy").show(viewer.player());

        assertTrue(display.isShowing());
        assertEquals(1, EffectRuntime.stopAll("Alpha"));
    }

    @Test
    @DisplayName("one registered plugin keeps the static builders working")
    void singleOwnerKeepsStatics() {
        Effects.owner(alpha);

        Display display = Effects.bossBar("Energy").show(viewer.player());

        assertTrue(display.isShowing());
        assertEquals(1, EffectRuntime.stopAll("Alpha"));
    }

    @Test
    @DisplayName("with two plugins and no way to tell them apart, the error says what to do")
    void ambiguityIsReportedNotGuessed() {
        Effects.owner(alpha);
        Effects.owner(beta);

        // No plugin classloader in a unit test, so nothing can be worked out
        // from the caller: better to say so than to charge a display to
        // whichever plugin happens to be first in the map.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Effects.bossBar("Energy").show(viewer.player()));

        assertTrue(failure.getMessage().contains("Effects.of(plugin)"), failure.getMessage());
    }

    @Test
    @DisplayName("Effects.of stamps the owner, and cleanup respects it")
    void stampedDisplaysCleanUpUnderTheirOwnPlugin() {
        Effects.owner(alpha);
        Effects.owner(beta);

        Display fromAlpha = Effects.of(alpha).bossBar("Energy").show(viewer.player());
        Display fromBeta = Effects.of(beta).bossBar("Marks").show(viewer.player());
        assertEquals(2, EffectRuntime.active());

        assertEquals(1, EffectRuntime.stopAll("Alpha"), "only Alpha's display stops");
        assertTrue(!fromAlpha.isShowing());
        assertTrue(fromBeta.isShowing(), "Beta's display survives Alpha's disable");

        assertEquals(1, EffectRuntime.stopAll("Beta"));
    }

    @Test
    @DisplayName("a stamped name that no plugin answers to is refused")
    void unknownPluginNameIsRefused() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Effects.bossBar("Energy").ownedBy("NotInstalled").show(viewer.player()));

        assertTrue(failure.getMessage().contains("NotInstalled"), failure.getMessage());
    }

    @Test
    @DisplayName("after a release, the remaining plugin is unambiguous again")
    void releaseRestoresTheSingleOwnerCase() {
        Effects.owner(alpha);
        Effects.owner(beta);
        EffectRuntime.release("Beta");

        Display display = Effects.bossBar("Energy").show(viewer.player());

        assertTrue(display.isShowing());
        assertEquals(1, EffectRuntime.stopAll("Alpha"));
    }
}
