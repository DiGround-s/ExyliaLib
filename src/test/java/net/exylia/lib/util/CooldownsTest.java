package net.exylia.lib.util;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
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
 * Per-player cooldowns.
 *
 * <p>Time is a variable here, not a wait: the clock is replaced so a test that
 * checks a sixteen-second cooldown runs in microseconds.
 */
class CooldownsTest {

    private FakePlayer player;
    private FakePlayer other;

    /** The fake clock, in milliseconds. */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        player = new FakePlayer("Steve");
        other = new FakePlayer("Alex");
        FakeServer.online(player.player(), other.player());

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

    /** Moves the clock forward. */
    private void advance(Duration by) {
        now.addAndGet(by.toMillis());
    }

    // ------------------------------------------------------------------
    // The basics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a fresh cooldown is active")
    void freshIsActive() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));

        assertTrue(Cooldowns.isActive(player.player(), "pearl"));
    }

    @Test
    @DisplayName("a key that was never started is not active")
    void unknownIsNotActive() {
        assertFalse(Cooldowns.isActive(player.player(), "never-used"));
        assertEquals(0L, Cooldowns.remaining(player.player().getUniqueId(), "never-used"));
    }

    @Test
    @DisplayName("a cooldown expires exactly when its time is up")
    void expiresOnTime() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));

        advance(Duration.ofSeconds(15));
        assertTrue(Cooldowns.isActive(player.player(), "pearl"), "15s in, still running");

        advance(Duration.ofSeconds(1));
        assertFalse(Cooldowns.isActive(player.player(), "pearl"), "16s in, done");
    }

    @Test
    @DisplayName("what is left counts down as time passes")
    void remainingCountsDown() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(10));

        advance(Duration.ofSeconds(4));

        assertEquals(6_000L, Cooldowns.remaining(player.player().getUniqueId(), "pearl"));
    }

    @Test
    @DisplayName("whole seconds are rounded up, so 0.4s left reads as 1")
    void secondsRoundUp() {
        Cooldowns.start(player.player(), "pearl", Duration.ofMillis(400));

        // Rounding down would tell the player "0 seconds" while still
        // refusing the action.
        assertEquals(1L, Cooldowns.remainingWholeSeconds(player.player(), "pearl"));
    }

    @Test
    @DisplayName("seconds left keep their decimals, which whole seconds throw away")
    void secondsKeepDecimals() {
        Cooldowns.start(player.player(), "pearl", Duration.ofMillis(400));

        assertEquals(0.4, Cooldowns.remainingSeconds(player.player(), "pearl"), 0.001);
    }

    // ------------------------------------------------------------------
    // Isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two players' cooldowns are independent")
    void playersAreIndependent() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));

        assertTrue(Cooldowns.isActive(player.player(), "pearl"));
        assertFalse(Cooldowns.isActive(other.player(), "pearl"));
    }

    @Test
    @DisplayName("two keys on one player are independent")
    void keysAreIndependent() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Cooldowns.start(player.player(), "gapple", Duration.ofSeconds(4));

        advance(Duration.ofSeconds(5));

        assertTrue(Cooldowns.isActive(player.player(), "pearl"), "the long one survives");
        assertFalse(Cooldowns.isActive(player.player(), "gapple"), "the short one is done");
    }

    @Test
    @DisplayName("starting again replaces the old expiry rather than stacking")
    void restartReplaces() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        advance(Duration.ofSeconds(8));
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(2));

        advance(Duration.ofSeconds(3));

        assertFalse(Cooldowns.isActive(player.player(), "pearl"),
                "the new, shorter cooldown decides — it does not add to the old one");
    }

    // ------------------------------------------------------------------
    // tryStart
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tryStart succeeds when nothing is running and starts it")
    void tryStartWhenFree() {
        assertTrue(Cooldowns.tryStart(player.player(), "pearl", Duration.ofSeconds(16)));
        assertTrue(Cooldowns.isActive(player.player(), "pearl"));
    }

    @Test
    @DisplayName("tryStart refuses while the cooldown is running")
    void tryStartWhenBusy() {
        Cooldowns.tryStart(player.player(), "pearl", Duration.ofSeconds(16));
        advance(Duration.ofSeconds(4));

        assertFalse(Cooldowns.tryStart(player.player(), "pearl", Duration.ofSeconds(16)));
    }

    @Test
    @DisplayName("a refused tryStart does not extend the running cooldown")
    void refusedTryStartDoesNotExtend() {
        Cooldowns.tryStart(player.player(), "pearl", Duration.ofSeconds(10));
        advance(Duration.ofSeconds(6));

        Cooldowns.tryStart(player.player(), "pearl", Duration.ofSeconds(10));

        // Had the refusal restarted it, 4 more seconds would not be enough.
        advance(Duration.ofSeconds(4));
        assertFalse(Cooldowns.isActive(player.player(), "pearl"));
    }

    // ------------------------------------------------------------------
    // Zero and negative
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a zero duration is not a cooldown at all")
    void zeroIsNoCooldown() {
        Cooldowns.start(player.player(), "pearl", Duration.ZERO);

        assertFalse(Cooldowns.isActive(player.player(), "pearl"));
    }

    @Test
    @DisplayName("a zero duration clears one that was already running")
    void zeroClearsExisting() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Cooldowns.start(player.player(), "pearl", Duration.ZERO);

        assertFalse(Cooldowns.isActive(player.player(), "pearl"),
                "a stale entry must not outlive its own expiry");
    }

    @Test
    @DisplayName("a negative duration is treated as zero")
    void negativeIsNoCooldown() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(-5));

        assertFalse(Cooldowns.isActive(player.player(), "pearl"));
    }

    // ------------------------------------------------------------------
    // Clearing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearing one key leaves the others alone")
    void clearOne() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Cooldowns.start(player.player(), "gapple", Duration.ofSeconds(16));

        Cooldowns.clear(player.player(), "pearl");

        assertFalse(Cooldowns.isActive(player.player(), "pearl"));
        assertTrue(Cooldowns.isActive(player.player(), "gapple"));
    }

    @Test
    @DisplayName("clearing all leaves the other player alone")
    void clearAllIsPerPlayer() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Cooldowns.start(other.player(), "pearl", Duration.ofSeconds(16));

        Cooldowns.clearAll(player.player());

        assertFalse(Cooldowns.isActive(player.player(), "pearl"));
        assertTrue(Cooldowns.isActive(other.player(), "pearl"));
    }

    // ------------------------------------------------------------------
    // Memory
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an expired cooldown is dropped from the map when it is read")
    void expiredEntriesAreDropped() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        assertEquals(1, Cooldowns.trackedOwners());

        advance(Duration.ofSeconds(17));
        Cooldowns.isActive(player.player(), "pearl");

        assertEquals(0, Cooldowns.trackedOwners(),
                "nothing sweeps this map, so reading is the only chance to notice");
    }

    @Test
    @DisplayName("the entry is dropped at the exact moment of expiry, not a tick later")
    void droppedAtExactExpiry() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));

        advance(Duration.ofSeconds(16));
        Cooldowns.isActive(player.player(), "pearl");

        // At exactly the expiry instant the cooldown is over, so its entry has
        // no reason to survive the read that noticed.
        assertEquals(0, Cooldowns.trackedOwners());
    }

    @Test
    @DisplayName("a zero duration never touches the map")
    void zeroNeverStores() {
        Cooldowns.start(player.player(), "pearl", Duration.ZERO);

        // Storing an already-expired entry would work, but it writes a map
        // entry for something that was over before it began.
        assertEquals(0, Cooldowns.trackedOwners());
    }

    @Test
    @DisplayName("a zero duration on an existing cooldown leaves nothing behind")
    void zeroLeavesNothingBehind() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Cooldowns.start(player.player(), "pearl", Duration.ZERO);

        assertEquals(0, Cooldowns.trackedOwners());
    }

    @Test
    @DisplayName("a player who leaves is forgotten entirely")
    void forgetDropsThePlayer() {
        Cooldowns.start(player.player(), "pearl", Duration.ofSeconds(16));
        Cooldowns.start(other.player(), "pearl", Duration.ofSeconds(16));

        Cooldowns.forget(player.player().getUniqueId());

        assertEquals(1, Cooldowns.trackedOwners());
        assertTrue(Cooldowns.isActive(other.player(), "pearl"));
    }

    // ------------------------------------------------------------------
    // Convenience
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ticks are converted at fifty milliseconds each")
    void ticksConvert() {
        Cooldowns.startTicks(player.player(), "pearl", 20);

        assertEquals(1_000L, Cooldowns.remaining(player.player().getUniqueId(), "pearl"));
    }

    @Test
    @DisplayName("seconds are converted whole")
    void secondsConvert() {
        Cooldowns.startSeconds(player.player(), "pearl", 3);

        assertEquals(3_000L, Cooldowns.remaining(player.player().getUniqueId(), "pearl"));
    }
}
