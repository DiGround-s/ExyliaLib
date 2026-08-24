package net.exylia.lib.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four things the module has to get right, each of which was a bug before
 * it existed.
 */
class SessionsTest {

    private PluginSessions ffa;
    private PluginSessions practice;
    private UUID player;

    @BeforeEach
    void setUp() {
        Sessions.releaseAll();
        ffa = new PluginSessions("ExyliaFFA");
        practice = new PluginSessions("ExyliaPracticeCore");
        player = UUID.randomUUID();
    }

    @Test
    void onlyOnePluginCanHoldAPlayer() {
        assertTrue(ffa.claim(player, "arena", null).isPresent());
        assertTrue(practice.claim(player, "queue", null).isEmpty());
        assertFalse(Sessions.isFree(player));
        assertEquals("ExyliaFFA", Sessions.holder(player).orElseThrow().plugin());
    }

    @Test
    void aPluginReclaimingItsOwnPlayerKeepsTheSameToken() {
        Claim first = practice.claim(player, "queue", null).orElseThrow();
        Claim again = practice.claim(player, "loading", null).orElseThrow();
        assertSame(first, again);
        assertEquals(first.token(), again.token());
        assertEquals("loading", first.kind());
        assertTrue(first.isCurrent());
    }

    @Test
    void aStaleClaimNeverRunsItsCallback() {
        Claim first = ffa.claim(player, "arena", null).orElseThrow();
        assertTrue(first.release());

        Claim second = ffa.claim(player, "arena", null).orElseThrow();
        assertNotEquals(first.token(), second.token());

        AtomicInteger ran = new AtomicInteger();
        first.ifCurrent(ran::incrementAndGet);
        second.ifCurrent(ran::incrementAndGet);
        assertEquals(1, ran.get(), "only the claim in force may act on the player");

        // And a second release by the stale claim must not take the live one.
        assertFalse(first.release());
        assertTrue(second.isCurrent());
    }

    @Test
    void evictionAsksTheOwnerAndNeverLoops() {
        AtomicInteger handlerRuns = new AtomicInteger();
        Claim owned = ffa.claim(player, "arena", () -> {
            handlerRuns.incrementAndGet();
            // A handler that reaches eviction again through some other path
            // must not send the module round in circles.
            Sessions.release(player);
            ffa.release(player);
            return true;
        }).orElseThrow();

        assertTrue(Sessions.release(player));
        assertEquals(1, handlerRuns.get());
        assertTrue(Sessions.isFree(player));
        assertFalse(owned.isCurrent());
    }

    @Test
    void anAcceptedEvictionIsReportedYesEvenWhileStillInFlight() {
        // Every real handler is asynchronous: it teleports, restores an
        // inventory, and releases from a callback some ticks later. Reporting
        // that as a refusal told every asker the mode had said no.
        AtomicInteger handlerRuns = new AtomicInteger();
        Claim owned = ffa.claim(player, "arena", () -> {
            handlerRuns.incrementAndGet();
            return true;
        }).orElseThrow();

        assertTrue(Sessions.release(player), "accepted, even though nothing has finished yet");
        assertTrue(owned.isCurrent(), "and the claim stands until the owner lets go");

        // Asking again must not start a second departure.
        assertTrue(Sessions.release(player));
        assertEquals(1, handlerRuns.get());

        owned.release();
        assertTrue(Sessions.isFree(player));
    }

    @Test
    void aHandlerThatRefusesLeavesTheClaimStanding() {
        AtomicInteger asked = new AtomicInteger();
        ffa.claim(player, "arena", () -> {
            asked.incrementAndGet();
            return false; // combat tagged
        }).orElseThrow();

        assertFalse(Sessions.release(player));
        assertFalse(Sessions.isFree(player));
        // A refusal is retryable: the player may stop being tagged.
        assertFalse(Sessions.release(player));
        assertEquals(2, asked.get());
    }

    @Test
    void concurrentClaimsElectExactlyOneWinner() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            PluginSessions who = new PluginSessions("Plugin" + i);
            pool.submit(() -> {
                start.await();
                Optional<Claim> claim = who.claim(player, "x", null);
                if (claim.isPresent()) winners.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, winners.get());
    }

    @Test
    void aWatcherHearsAboutOtherPluginsButNotItsOwn() {
        java.util.List<String> heard = new java.util.ArrayList<>();
        Sessions.watch(FakePlugin.named("ExyliaPracticeCore"),
                claim -> heard.add("taken:" + claim.plugin()),
                claim -> heard.add("given:" + claim.plugin()));

        practice.claim(player, "IN_QUEUE", null).orElseThrow().release();
        Claim theirs = ffa.claim(player, "arena", null).orElseThrow();
        theirs.release();

        assertEquals(java.util.List.of("taken:ExyliaFFA", "given:ExyliaFFA"), heard);
    }

    @Test
    void aDisabledPluginStopsHoldingAnyone() {
        ffa.claim(player, "arena", null).orElseThrow();
        Sessions.forgetPlugin("ExyliaFFA");
        assertTrue(Sessions.isFree(player));
    }
}
