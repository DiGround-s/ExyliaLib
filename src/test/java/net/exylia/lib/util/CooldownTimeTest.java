package net.exylia.lib.util;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decimals on cooldowns, and reading one through a timer.
 */
class CooldownTimeTest {

    private FakePlayer player;
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve");
        FakeServer.online(player.player());

        Cooldowns.removeStore();
        Cooldowns.clearEverything();
        Cooldowns.setClock(now::get);
    }

    @AfterEach
    void tearDown() {
        Cooldowns.clearEverything();
        Cooldowns.resetClock();
        FakeServer.reset();
    }

    private void advance(Duration by) {
        now.addAndGet(by.toMillis());
    }

    // ------------------------------------------------------------------
    // Decimals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("seconds left keep their decimals")
    void secondsHaveDecimals() {
        Cooldowns.start(player.player(), "pearl", Duration.ofMillis(3300));

        assertEquals(3.3, Cooldowns.remainingSeconds(player.player(), "pearl"), 0.001);
    }

    @Test
    @DisplayName("decimals come from the state, which was always in milliseconds")
    void decimalsAreNotInvented() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        advance(Duration.ofMillis(12_700));

        assertEquals(3.3, Cooldowns.remainingSeconds(player.player(), "pearl"), 0.001);
    }

    @Test
    @DisplayName("whole seconds still round up, for a message that refuses somebody")
    void wholeSecondsRoundUp() {
        Cooldowns.start(player.player(), "pearl", Duration.ofMillis(3300));

        assertEquals(4L, Cooldowns.remainingWholeSeconds(player.player(), "pearl"));
    }

    @Test
    @DisplayName("nothing running reads as zero, not as a negative")
    void nothingIsZero() {
        assertEquals(0.0, Cooldowns.remainingSeconds(player.player(), "never"), 0.001);
        assertEquals(0L, Cooldowns.remainingWholeSeconds(player.player(), "never"));
        assertEquals("0.0", Cooldowns.remainingFormatted(player.player(), "never"));
    }

    // ------------------------------------------------------------------
    // Formatted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("formatted time is ready to show a player")
    void formatted() {
        Cooldowns.start(player.player(), "pearl", Duration.ofMillis(3300));

        assertEquals("3.3", Cooldowns.remainingFormatted(player.player(), "pearl"));
    }

    @Test
    @DisplayName("a long cooldown formats as a clock without being asked")
    void formattedLong() {
        Cooldowns.start(player.player(), "raid", Duration.ofMinutes(2));

        assertEquals("2:00", Cooldowns.remainingFormatted(player.player(), "raid"));
    }

    @Test
    @DisplayName("a style can be named at the call site")
    void formattedWithStyle() {
        Cooldowns.start(player.player(), "daily", Duration.ofHours(6));

        assertEquals("6h", Cooldowns.remainingFormatted(player.player(), "daily",
                TimeFormats.Style.FULL));
    }

    @Test
    @DisplayName("a namespaced view formats too")
    void namespacedFormats() {
        PluginCooldowns kits = Cooldowns.namespaced("kits");
        kits.start(player.player(), "pearl", Duration.ofMillis(3300));

        assertEquals("3.3", kits.remainingFormatted(player.player(), "pearl"));
        assertEquals(3.3, kits.remainingSeconds(player.player(), "pearl"), 0.001);
    }

    @Test
    @DisplayName("an item cooldown formats the same way as any other")
    void itemFormats() {
        ItemCooldowns.setOverlay((p, m, t) -> { });
        ItemCooldowns.start(player.player(), "wand", org.bukkit.Material.BLAZE_ROD,
                Duration.ofMillis(3300));

        assertEquals("3.3", ItemCooldowns.remainingFormatted(player.player(), "wand"));
        ItemCooldowns.resetOverlay();
    }

    // ------------------------------------------------------------------
    // Reading a cooldown through a timer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a timer reads what the cooldown has left")
    void timerReadsCooldown() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Timer timer = Timer.ofCooldown(player.player(), "pearl");

        advance(Duration.ofMillis(12_700));

        assertEquals(3.3, timer.remaining(), 0.001);
        assertEquals(3.3, timer.displayed(), 0.001);
    }

    @Test
    @DisplayName("a timer over a cooldown finishes when the cooldown does")
    void timerFinishesWithCooldown() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Timer timer = Timer.ofCooldown(player.player(), "pearl");

        assertFalse(timer.finished());

        advance(Duration.ofSeconds(16));

        assertTrue(timer.finished(), "which is what makes the boss bar close itself");
    }

    @Test
    @DisplayName("progress empties as the cooldown runs down")
    void progressEmpties() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(10));
        Timer timer = Timer.ofCooldown(player.player(), "pearl");

        assertEquals(1.0f, timer.progress(), 0.01f);

        advance(Duration.ofSeconds(5));
        assertEquals(0.5f, timer.progress(), 0.01f);

        advance(Duration.ofSeconds(5));
        assertEquals(0.0f, timer.progress(), 0.01f);
    }

    @Test
    @DisplayName("advancing a cooldown timer does nothing, because it owns no clock")
    void advanceDoesNothing() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Timer timer = Timer.ofCooldown(player.player(), "pearl");

        // The display calls this every cycle. It must not double-count.
        timer.advance(100);
        timer.advance(100);

        assertEquals(16.0, timer.remaining(), 0.001,
                "the cooldown is the only thing that moves this");
    }

    @Test
    @DisplayName("extending through the timer does nothing; the cooldown decides")
    void extendDoesNothing() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Timer timer = Timer.ofCooldown(player.player(), "pearl");

        timer.extend(200);

        assertEquals(16.0, timer.remaining(), 0.001);
    }

    @Test
    @DisplayName("restarting the cooldown is seen by the timer watching it")
    void restartIsSeen() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Timer timer = Timer.ofCooldown(player.player(), "pearl");

        advance(Duration.ofSeconds(10));
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));

        assertEquals(16.0, timer.remaining(), 0.001,
                "the cooldown is the truth and the display just looks at it");
    }

    @Test
    @DisplayName("a total can be named for a bar built after the cooldown started")
    void explicitTotal() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        advance(Duration.ofSeconds(8));

        // Built late: without a total it would think 8s is a full bar.
        Timer timer = Timer.ofCooldown(player.player(), "pearl", 16.0);

        assertEquals(0.5f, timer.progress(), 0.01f);
        assertEquals(16.0, timer.total(), 0.001);
    }

    @Test
    @DisplayName("a timer can read a cooldown that belongs to the whole server")
    void globalCooldownTimer() {
        Cooldowns.start(CooldownScope.GLOBAL, "world-boss", Duration.ofMinutes(4));
        Timer timer = Timer.ofCooldown(CooldownScope.GLOBAL, "world-boss");

        assertEquals(240.0, timer.remaining(), 0.001);
        assertFalse(timer.finished());
    }

    @Test
    @DisplayName("a timer over nothing is finished from the start")
    void timerOverNothing() {
        Timer timer = Timer.ofCooldown(player.player(), "never-started");

        assertTrue(timer.finished());
        assertEquals(0.0, timer.remaining(), 0.001);
        assertEquals(0f, timer.progress(), 0.001f);
    }

    @Test
    @DisplayName("progress never leaves the 0-to-1 range, even past the end")
    void progressIsBounded() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(10));
        Timer timer = Timer.ofCooldown(player.player(), "pearl");

        advance(Duration.ofSeconds(20));

        assertTrue(timer.finished());
        assertTrue(timer.progress() >= 0f, "a bar past its end reads empty, not beyond");
        assertTrue(timer.progress() <= 1f);
        assertEquals(0f, timer.progress(), 0.001f);
    }

    @Test
    @DisplayName("elapsed never goes below zero even when the total is unknown")
    void elapsedIsBounded() {
        Timer timer = Timer.ofCooldown(player.player(), "never-started");

        assertEquals(0.0, timer.elapsed(), 0.001);
    }
}
