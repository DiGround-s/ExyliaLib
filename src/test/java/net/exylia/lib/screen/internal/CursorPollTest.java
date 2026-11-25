package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorPollTest {

    private static final long MS = 1_000_000L;

    /** The server's reckoning, as {@code LiveScreen#turned} keeps it: the turn the answers add up to. */
    private static final class Reckoning {
        final CursorPoll poll = new CursorPoll();
        float lastYaw, lastPitch;
        double yaw, pitch;

        Reckoning(float yaw, float pitch) {
            lastYaw = yaw;
            lastPitch = pitch;
        }

        void answer(float y, float p) {
            if (poll.landed(y, p)) {
                lastYaw = y;
                lastPitch = p;
                return;
            }
            if (y == lastYaw && p == lastPitch) return;
            yaw += ScreenMath.turn(lastYaw, y);
            pitch += p - lastPitch;
            lastYaw = y;
            lastPitch = p;
        }
    }

    @Test
    void answersStillOnTheirWayCountFromTheOldPlaceAndTheResetStartsANewOne() {
        Reckoning server = new Reckoning(10, 50);
        server.answer(11, 55);
        server.answer(12, 61);
        // Past the limit: the reset goes out, but the client answers twice more before it reads it.
        server.poll.reset(0);
        server.answer(13, 64);
        server.answer(13, 64);
        server.answer(CursorPoll.SENTINEL_YAW, CursorPoll.SENTINEL_PITCH);
        assertFalse(server.poll.pending());
        // The frames after it: the mouse moved on from the sentinel.
        server.answer(CursorPoll.SENTINEL_YAW + 2, 3);
        server.answer(CursorPoll.SENTINEL_YAW + 2, 3);
        server.answer(CursorPoll.SENTINEL_YAW + 3, 5);
        assertEquals(3 + 3, server.yaw, 1e-4, "every turn the hand made, none lost and none twice");
        assertEquals(14 + 5, server.pitch, 1e-4);
    }

    @Test
    void theSentinelIsOnlyTheResetsAnswerWhileOneIsOnItsWay() {
        CursorPoll poll = new CursorPoll();
        assertFalse(poll.landed(CursorPoll.SENTINEL_YAW, CursorPoll.SENTINEL_PITCH), "no reset sent");
        poll.reset(0);
        assertFalse(poll.landed(CursorPoll.SENTINEL_YAW + 0.5f, CursorPoll.SENTINEL_PITCH));
        assertTrue(poll.landed(CursorPoll.SENTINEL_YAW, CursorPoll.SENTINEL_PITCH));
        assertFalse(poll.landed(CursorPoll.SENTINEL_YAW, CursorPoll.SENTINEL_PITCH), "only once");
    }

    @Test
    void anUnansweredResetIsSentAgain() {
        CursorPoll poll = new CursorPoll();
        poll.reset(0);
        assertFalse(poll.stale(500 * MS));
        assertTrue(poll.stale(1500 * MS));
    }

    /** A client drawing {@code fps} frames, asked as often as the estimator says, its hand always moving. */
    private static CursorPoll run(double fps, long untilMs) {
        CursorPoll poll = new CursorPoll();
        long frame = (long) (1e9 / fps);
        long now = 0, nextFrame = frame, answeredFrame = -1;
        while (now < untilMs * MS) {
            now += poll.period(now);
            while (nextFrame <= now) nextFrame += frame;
            long thisFrame = nextFrame / frame;
            // Every answer in one frame repeats the first: only a new frame's answer has changed.
            poll.answered(now, thisFrame != answeredFrame);
            answeredFrame = thisFrame;
        }
        return poll;
    }

    @Test
    void itFindsTheClientsFrameRateAndAsksALittleFaster() {
        CursorPoll sixty = run(60, 5000);
        assertEquals(1000 / 60.0, sixty.frameMillis(), 2.5);
        assertTrue(sixty.period(5000 * MS) < 1e9 / 60, "faster than a frame, so every frame has an answer");
        CursorPoll fast = run(144, 5000);
        assertEquals(1000 / 144.0, fast.frameMillis(), 1.5);
        CursorPoll faster = run(500, 5000);
        assertEquals(CursorPoll.FASTEST, faster.period(5000 * MS), "never past the fastest ask");
    }

    @Test
    void aStillClientIsAskedSeldom() {
        CursorPoll poll = new CursorPoll();
        assertEquals(CursorPoll.IDLE, poll.period(0), "nothing seen yet");
        poll.answered(0, true);
        poll.answered(7 * MS, true);
        assertTrue(poll.period(8 * MS) < CursorPoll.IDLE);
        assertEquals(CursorPoll.IDLE, poll.period(7 * MS + CursorPoll.IDLE_AFTER + 1));
    }
}
