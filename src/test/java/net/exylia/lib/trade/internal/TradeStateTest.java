package net.exylia.lib.trade.internal;

import net.exylia.lib.trade.internal.TradeState.Phase;
import net.exylia.lib.trade.internal.TradeState.Toggle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeStateTest {

    /** Side {@code side} offers something, and both screens catch up. */
    private static void offer(TradeState state, int side) {
        assertTrue(state.change(side));
        state.snapped(side, state.revision(side), false);
        state.shown(1 - side, state.revision(side));
    }

    private static TradeState stocked(long countdown) {
        TradeState state = new TradeState(countdown, false);
        offer(state, 0);
        offer(state, 1);
        return state;
    }

    @Test
    void bothConfirmingStartsTheCountdownWhichCompletesOnce() {
        TradeState state = stocked(3000);
        assertEquals(Toggle.CONFIRMED, state.toggle(0, 0));
        assertEquals(Phase.OPEN, state.phase());
        assertEquals(Toggle.CONFIRMED, state.toggle(1, 1000));
        assertEquals(Phase.COUNTDOWN, state.phase());
        assertEquals(3000, state.remaining(1000));

        assertFalse(state.tick(3999));
        assertTrue(state.tick(4000));
        assertFalse(state.tick(5000));
        assertEquals(Phase.COMPLETING, state.phase());
    }

    @Test
    void anyChangeResetsBothConfirmationsAndTheCountdown() {
        TradeState state = stocked(3000);
        state.toggle(0, 0);
        state.toggle(1, 0);
        assertTrue(state.change(1));
        assertEquals(Phase.OPEN, state.phase());
        assertFalse(state.confirmed(0));
        assertFalse(state.confirmed(1));
        assertFalse(state.tick(10_000));
    }

    @Test
    void aConfirmAgainstAStaleScreenIsRefused() {
        TradeState state = stocked(3000);
        assertTrue(state.change(1));                     // side 1 swapped; side 0 has not seen it
        state.snapped(1, state.revision(1), false);
        assertEquals(Toggle.STALE, state.toggle(0, 0));
        state.shown(0, state.revision(1));
        assertEquals(Toggle.CONFIRMED, state.toggle(0, 0));
    }

    @Test
    void aConfirmBeforeTheOwnSnapshotIsRefused() {
        TradeState state = stocked(3000);
        assertTrue(state.change(0));                     // landed, not yet snapshotted
        state.shown(1, state.revision(0));
        assertEquals(Toggle.STALE, state.toggle(0, 0));
    }

    @Test
    void anEmptySideCannotConfirmUnlessAllowed() {
        TradeState strict = new TradeState(0, false);
        assertEquals(Toggle.EMPTY, strict.toggle(0, 0));

        TradeState lenient = new TradeState(0, true);
        assertEquals(Toggle.CONFIRMED, lenient.toggle(0, 0));
        assertEquals(Toggle.CONFIRMED, lenient.toggle(1, 0));
        assertEquals(Phase.COMPLETING, lenient.phase());  // no countdown: straight through
    }

    @Test
    void pressingAgainTakesTheConfirmBackAndStopsTheCountdown() {
        TradeState state = stocked(3000);
        state.toggle(0, 0);
        state.toggle(1, 0);
        assertEquals(Toggle.UNCONFIRMED, state.toggle(0, 0));
        assertEquals(Phase.OPEN, state.phase());
        assertTrue(state.confirmed(1));
        assertFalse(state.tick(10_000));
    }

    @Test
    void cancelWinsOnceAndLocksEverything() {
        TradeState state = stocked(3000);
        assertTrue(state.cancel());
        assertFalse(state.cancel());
        assertEquals(Phase.CANCELLED, state.phase());
        assertFalse(state.change(0));
        assertEquals(Toggle.LOCKED, state.toggle(0, 0));
    }

    @Test
    void aCompletingTradeCannotBeCancelled() {
        TradeState state = stocked(0);
        state.toggle(0, 0);
        state.toggle(1, 0);
        assertEquals(Phase.COMPLETING, state.phase());
        assertFalse(state.cancel());
        assertFalse(state.change(0));
    }

    @Test
    void eachWindowIsEmptiedOnceAndOnlyAfterTheEnd() {
        TradeState state = stocked(3000);
        assertFalse(state.take(0));                      // still open
        state.cancel();
        assertTrue(state.take(0));
        assertFalse(state.take(0));
        assertTrue(state.take(1));
        assertFalse(state.handed(0));
        assertTrue(state.handed(1));
    }
}
