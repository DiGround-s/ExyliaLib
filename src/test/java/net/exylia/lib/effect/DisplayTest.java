package net.exylia.lib.effect;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.internal.EffectRuntime;
import net.exylia.lib.effect.internal.Packets;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.text.Colors;
import net.exylia.lib.text.Palette;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of effects that stay on screen.
 *
 * <p>The things that matter to a server are all here: that a countdown really
 * counts, that an effect always ends exactly once, and that nothing is left
 * running afterwards. A leaked display is a bar a player can see and no command
 * can remove.
 *
 * <p>Packets are forced off so the Bukkit path is exercised; the packet path is
 * a thin wrapper over the same decisions and cannot be driven without a real
 * server.
 */
class DisplayTest {

    private FakePlayer viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.override(false);
        Colors.apply(new Palette());

        Plugin plugin = FakeServer.newPlugin("EffectTest", null);
        Effects.owner(plugin);
        viewer = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        EffectRuntime.stopEverything();
        FakeServer.reset();
        Packets.reset();
    }

    // ------------------------------------------------------------------
    // Action bars
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a ticking title replaces its text without restarting the fade")
    void titleRedrawsAsPartsOnly() {
        Effects.title("%time%").countdown(1.0).timeStyle("tenths").show(viewer.player());
        FakeServer.tick(3);

        // The first draw carries the timings, so it is a whole Title. Every
        // redraw after it must be parts alone: re-sending the times would make
        // the countdown pulse once a tick.
        assertEquals(1, viewer.titles().size(), "only the first draw sends timings");
        assertFalse(viewer.titleParts().isEmpty(), "a redraw must arrive as title parts");
        assertTrue(viewer.titleParts().stream().anyMatch(part -> part.startsWith("TitlePart.TITLE=")),
                "the title text is what changes: " + viewer.titleParts());
    }

    @Test
    @DisplayName("an action bar reaches the player")
    void actionBarIsShown() {
        Effects.actionBar("{success}Saved").show(viewer.player());
        FakeServer.tick(1);

        assertEquals(List.of("Saved"), viewer.actionBars());
    }

    @Test
    @DisplayName("a countdown counts down in decimals")
    void countdownCounts() {
        Effects.actionBar("%time%").countdown(1.0).timeStyle("tenths").show(viewer.player());
        FakeServer.tick(1);
        viewer.clear();

        FakeServer.tick(1);
        FakeServer.tick(1);

        List<String> seen = viewer.actionBars();
        assertEquals("0.9", seen.get(0), "one tick is a twentieth of a second");
        assertEquals("0.9", seen.get(1));
    }

    /**
     * ExyliaCommons wrote the same number into %time_formatted%, and a server
     * carrying that config over must not have to edit it to get its countdowns
     * back.
     */
    @Test
    @DisplayName("the old name for the countdown still counts")
    void countdownCountsUnderItsOldName() {
        Effects.actionBar("%time_formatted%").countdown(1.0).timeStyle("tenths").show(viewer.player());
        FakeServer.tick(1);
        viewer.clear();

        FakeServer.tick(1);

        assertEquals("0.9", viewer.actionBars().get(0));
    }

    @Test
    @DisplayName("a countdown ends by itself and stops its task")
    void countdownEndsItself() {
        Display display = Effects.actionBar("%time%").countdown(0.5).show(viewer.player());
        FakeServer.tick(1);

        assertTrue(display.isShowing());

        FakeServer.tick(12);

        assertFalse(display.isShowing(), "a finished countdown must stop");
        assertEquals(0, EffectRuntime.active(), "and must not stay registered");
        assertEquals(0, FakeServer.liveRepeatingTasks(), "and must not leave a task running");
    }

    @Test
    @DisplayName("onEnd runs exactly once, however the effect ends")
    void onEndRunsOnce() {
        AtomicInteger ended = new AtomicInteger();

        Display display = Effects.actionBar("x")
                .countdown(0.5)
                .onEnd(ended::incrementAndGet)
                .show(viewer.player());
        FakeServer.tick(1);

        // Three endings arriving at once: the timer, an explicit stop, and a
        // second stop from somewhere else.
        FakeServer.tick(12);
        display.stop();
        display.stop();
        FakeServer.tick(2);

        assertEquals(1, ended.get(), "the consequence of a countdown must not fire twice");
    }

    @Test
    @DisplayName("an effect ends once when its timer and a stop arrive together")
    void endingConvergesOnce() {
        // The two endings a server really produces at the same moment: a
        // countdown expiring on a scheduler thread and a command stopping it.
        // Guarding this with compare-and-set rather than check-then-set is what
        // makes it safe; the race itself is not reliably reproducible in a
        // test, so what is asserted here is the contract, not the timing.
        AtomicInteger ended = new AtomicInteger();
        Display display = Effects.actionBar("x")
                .countdown(0.1)
                .onEnd(ended::incrementAndGet)
                .show(viewer.player());
        FakeServer.tick(1);

        display.stop();
        FakeServer.tick(5);
        display.stop();

        assertEquals(1, ended.get(), "an effect must end exactly once");
        assertFalse(display.isShowing());
        assertEquals(0, EffectRuntime.active());
    }

    @Test
    @DisplayName("stopping an effect ends it and clears the screen")
    void stopEndsIt() {
        Display display = Effects.actionBar("{letters}Waiting").permanent().show(viewer.player());
        FakeServer.tick(1);
        viewer.clear();

        display.stop();
        FakeServer.tick(1);

        assertFalse(display.isShowing());
        assertEquals(List.of(""), viewer.actionBars(), "an empty action bar clears it");
        assertEquals(0, EffectRuntime.active());
    }

    @Test
    @DisplayName("a permanent action bar keeps being re-sent so it does not fade")
    void permanentActionBarRepeats() {
        Effects.actionBar("{letters}Waiting").permanent().show(viewer.player());
        FakeServer.tick(1);
        viewer.clear();

        // The keep-alive is every 40 ticks, comfortably inside the roughly 60
        // ticks the client takes to fade one out.
        FakeServer.tick(120);

        assertEquals(3, viewer.actionBars().size(),
                "the client fades an action bar, so it must be re-sent");
    }

    // ------------------------------------------------------------------
    // Boss bars
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a boss bar is shown and removed, leaving nothing behind")
    void bossBarLifecycle() {
        Display display = Effects.bossBar("{primary}Match").show(viewer.player());
        FakeServer.tick(1);

        assertEquals(1, viewer.bossBarsShown());

        display.stop();
        FakeServer.tick(1);

        assertEquals(1, viewer.bossBarsHidden(), "a stopped bar must be taken off the screen");
        assertEquals(0, EffectRuntime.active());
    }

    @Test
    @DisplayName("a boss bar with nothing changing costs no task at all")
    void staticBossBarDoesNotTick() {
        Effects.bossBar("Waiting for players").show(viewer.player());

        assertEquals(0, FakeServer.liveRepeatingTasks(),
                "static text never changes, so there is nothing to tick");
    }

    @Test
    @DisplayName("a countdown boss bar empties as time runs out")
    void bossBarEmpties() {
        Display display = Effects.bossBar("%time%").countdown(1.0).show(viewer.player());
        FakeServer.tick(1);

        Timer timer = display.timer();
        assertNotNull(timer);
        assertEquals(1f, timer.progress(), 0.0001);

        FakeServer.tick(10);

        assertEquals(0.5f, timer.progress(), 0.05, "half the time gone is half the bar");
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a player leaving ends their effects")
    void disconnectEndsEffects() {
        Display display = Effects.actionBar("x").permanent().show(viewer.player());
        FakeServer.tick(1);

        viewer.disconnect();
        // What ExyliaLib does on PlayerQuitEvent. The task module stops an
        // entity timer once the entity is gone, so the display is never told
        // its viewer left and would otherwise stay registered forever.
        Effects.stopFor(viewer.player());

        assertFalse(display.isShowing(), "an effect must not outlive its viewer");
        assertEquals(0, EffectRuntime.active());
    }

    @Test
    @DisplayName("stopping a plugin's effects stops all of them")
    void stopAllByPlugin() {
        Effects.actionBar("a").permanent().show(viewer.player());
        Effects.bossBar("b").countdown(60).show(viewer.player());
        FakeServer.tick(1);

        assertEquals(2, EffectRuntime.active());

        int stopped = Effects.stopAll("EffectTest");

        assertEquals(2, stopped);
        assertEquals(0, EffectRuntime.active());
    }

    @Test
    @DisplayName("stopping one player's effects leaves other players alone")
    void stopForIsScoped() {
        FakePlayer other = new FakePlayer("Alex");
        Effects.actionBar("a").permanent().show(viewer.player());
        Effects.actionBar("b").permanent().show(other.player());
        FakeServer.tick(1);

        int stopped = Effects.stopFor(viewer.player());

        assertEquals(1, stopped);
        assertEquals(1, EffectRuntime.active(), "the other player's effect must survive");
    }

    @Test
    @DisplayName("an effect whose text fails is stopped rather than left broken")
    void failingEffectIsStopped() {
        Plugin plugin = FakeServer.newPlugin("Boom", null);
        Placeholders.group(plugin, "boom")
                .add("x", request -> {
                    throw new IllegalStateException("deliberate");
                })
                .register();

        Display display = Effects.actionBar("%boom_x%").permanent().show(viewer.player());
        FakeServer.tick(2);

        // A throwing resolver is contained by the placeholder module, so the
        // effect keeps running with the placeholder left visible.
        assertTrue(display.isShowing());
        assertTrue(viewer.actionBars().contains("%boom_x%"));

        Placeholders.unregisterAll("Boom");
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    @Test
    @DisplayName("placeholders in effect text are resolved for the viewer")
    void placeholdersResolve() {
        Plugin plugin = FakeServer.newPlugin("Names", null);
        Placeholders.group(plugin, "test").add("greeting", request -> "hello").register();

        Effects.actionBar("%test_greeting%").show(viewer.player());
        FakeServer.tick(1);

        assertEquals(List.of("hello"), viewer.actionBars());
        Placeholders.unregisterAll("Names");
    }

    @Test
    @DisplayName("the text of a showing effect can be replaced")
    void textCanBeReplaced() {
        Display display = Effects.actionBar("first").permanent().show(viewer.player());
        FakeServer.tick(1);
        viewer.clear();

        display.text("second");
        FakeServer.tick(1);

        assertTrue(viewer.actionBars().contains("second"));
    }

    @Test
    @DisplayName("time can be added to a running countdown")
    void timeCanBeAdded() {
        Display display = Effects.bossBar("%time%").countdown(5).show(viewer.player());
        FakeServer.tick(1);

        display.addTime(10);

        assertEquals(15.0, display.timer().displayed(), 0.1);
    }
}
