package net.exylia.lib.util.crate;

import net.exylia.lib.util.crate.internal.Prizes;
import net.exylia.lib.util.crate.internal.Reel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of the opening screen that need no server: where the reels sit,
 * and where a reel is at a given tick. Both are only wrong once somebody is
 * watching them, which is the worst time to find out.
 */
class CrateReelTest {

    private static final int CENTRE = 4;

    private static Reel<String> reel(int column, int frames) {
        AtomicInteger face = new AtomicInteger();
        return Reel.of(column, "prize", frames, () -> "face_" + face.getAndIncrement());
    }

    private static Prizes.Outcome<String> won(String prize) {
        return new Prizes.Outcome<>(Prizes.Status.WON, prize, "common", 0);
    }

    private static long landing(Reel<String> reel) {
        long tick = 0;
        while (!reel.landed(tick)) tick++;
        return tick;
    }

    // ------------------------------------------------------------------
    // Where the reels sit
    // ------------------------------------------------------------------

    @Test
    void oneToFourReelsAreSpreadWithAGapAndCentred() {
        assertEquals(List.of(4), Reel.columns(1));
        assertEquals(List.of(3, 5), Reel.columns(2));
        assertEquals(List.of(2, 4, 6), Reel.columns(3));
        assertEquals(List.of(1, 3, 5, 7), Reel.columns(4));
    }

    @Test
    void moreThanFourCloseUpBecauseTheGapsNoLongerFit() {
        assertEquals(List.of(2, 3, 4, 5, 6), Reel.columns(5));
        assertEquals(List.of(2, 3, 4, 5, 6, 7), Reel.columns(6));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), Reel.columns(7));
    }

    @Test
    void noReelEverLandsOffTheGridOrOnTopOfAnother() {
        for (int count = 1; count <= Reel.LAST_COLUMN; count++) {
            List<Integer> columns = Reel.columns(count);
            assertEquals(count, columns.size());
            assertEquals(count, Set.copyOf(columns).size(), "two reels share a column at " + count);
            for (int column : columns) {
                assertTrue(column >= Reel.FIRST_COLUMN && column <= Reel.LAST_COLUMN,
                        "column " + column + " is off the grid at " + count + " reels");
            }
        }
    }

    @Test
    void aSpreadLayoutIsSymmetricAboutTheMiddle() {
        for (int count = 1; count <= 4; count++) {
            List<Integer> columns = Reel.columns(count);
            for (int i = 0; i < columns.size(); i++) {
                assertEquals(2 * CENTRE, columns.get(i) + columns.get(columns.size() - 1 - i),
                        count + " reels are not centred");
            }
        }
    }

    // ------------------------------------------------------------------
    // Where a reel is
    // ------------------------------------------------------------------

    @Test
    void aReelIsWhereItsClockSaysAndNeverGoesBackwards() {
        Reel<String> reel = reel(4, 20);
        int previous = 0;
        for (long tick = 0; tick <= 200; tick++) {
            int frame = reel.frameAt(tick);
            assertTrue(frame >= previous, "the reel went backwards at tick " + tick);
            assertTrue(frame <= 20, "the reel ran past its last face at tick " + tick);
            previous = frame;
        }
        assertEquals(20, previous, "the reel must reach its last face");
    }

    @Test
    void everyFaceGetsItsOwnMoment() {
        Reel<String> reel = reel(4, 20);
        long tick = 0;
        for (int frame = 0; frame <= 20; frame++) {
            while (reel.frameAt(tick) < frame) tick++;
            assertEquals(frame, reel.frameAt(tick), "face " + frame + " is skipped over");
        }
    }

    @Test
    void thePrizeIsOnTheMarkedRowExactlyWhenTheReelStops() {
        Reel<String> reel = reel(4, 20);
        long landing = landing(reel);

        assertFalse(reel.landed(landing - 1), "the reel stopped a tick early");
        assertSame(reel.prize(), reel.faceAt(landing, Reel.WINNER_ROW));
        assertSame(reel.prize(), reel.faceAt(landing + 500, Reel.WINNER_ROW));
    }

    @Test
    void everyRowOfAFallingReelHasAFaceToDraw() {
        Reel<String> reel = reel(4, 20);
        for (long tick = 0; tick <= 400; tick++) {
            for (int row = 0; row < Reel.ROWS; row++) {
                assertNotNull(reel.faceAt(tick, row), "row " + row + " had nothing to draw at tick " + tick);
            }
        }
    }

    @Test
    void anEmptyCatalogueStillFillsTheReelWithThePrize() {
        Reel<String> reel = Reel.of(4, "prize", 10, () -> null);
        for (int row = 0; row < Reel.ROWS; row++) {
            assertEquals("prize", reel.faceAt(0, row));
        }
    }

    /** A reel gains its extra faces in the fast run, where one face is one tick. */
    @Test
    void reelsLandExactlyTheConfiguredGapApart() {
        int base = 34;
        int gap = 40;
        long previous = landing(reel(1, base));
        for (int i = 1; i < 4; i++) {
            long next = landing(reel(1 + i * 2, base + i * gap));
            assertEquals(gap, next - previous, "reel " + (i + 1) + " must land two seconds after the last");
            previous = next;
        }
    }

    @Test
    void noGapMeansTheyStopTogether() {
        assertEquals(landing(reel(3, 34)), landing(reel(5, 34)));
    }

    @Test
    void aReelSlowsDownRatherThanCuttingOff() {
        assertTrue(Reel.delay(1) > Reel.delay(2));
        assertTrue(Reel.delay(2) > Reel.delay(4));
        assertTrue(Reel.delay(4) > Reel.delay(8));
        assertTrue(Reel.delay(8) > Reel.delay(30));
        assertEquals(Reel.delay(30), Reel.delay(200), "the fast run is one speed");
    }

    // ------------------------------------------------------------------
    // When the prize is handed over
    // ------------------------------------------------------------------

    @Test
    void aFallingReelHasNoOutcomeUntilItIsSettled() {
        assertNull(reel(4, 20).outcome());
    }

    @Test
    void aReelIsPaidOutOnceAndOnlyOnce() {
        Reel<String> reel = reel(4, 20);
        AtomicInteger payouts = new AtomicInteger();
        Prizes.Outcome<String> first = reel.settle(prize -> {
            payouts.incrementAndGet();
            return won(prize);
        });

        assertEquals(1, payouts.get());
        assertNotNull(first);
        assertSame(reel.prize(), first.prize());
        assertSame(first, reel.outcome());
        assertNull(reel.settle(prize -> {
            payouts.incrementAndGet();
            return won(prize);
        }));
        assertEquals(1, payouts.get(), "the same reel paid out twice");
    }

    @Test
    void aReelGivenUpOnIsNeverPaidOutAfterwards() {
        Reel<String> reel = reel(4, 20);
        assertTrue(reel.abandon());
        assertFalse(reel.abandon(), "the same reel was given up on twice");
        assertNull(reel.settle(CrateReelTest::won), "an abandoned reel was paid out as well");
        assertNull(reel.outcome());
    }

    @Test
    void aReelAlreadyPaidOutCannotBeGivenUpOn() {
        Reel<String> reel = reel(4, 20);
        reel.settle(CrateReelTest::won);
        assertFalse(reel.abandon(), "a paid reel was paid out the quit way as well");
    }
}
