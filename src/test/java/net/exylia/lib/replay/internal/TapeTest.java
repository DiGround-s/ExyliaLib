package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.ReplayActor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the black box's rolling buffer keeps exactly the window, in order,
 * however many times it wraps and grows.
 */
class TapeTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static Tape tape() {
        UUID id = UUID.randomUUID();
        return new Tape(id, new ReplayActor(id, "Someone", null, null, null), true, true);
    }

    @Test
    @DisplayName("a moving body keeps every tick of the window, in order, through wraps and growth")
    void keepsTheWindowInOrder() {
        Tape tape = tape();
        for (int tick = 0; tick < 5_000; tick++) {
            tape.put(tick, WORLD, tick * 0.1, 64, 0, 0f, 0f, 0, 20f);
            if (tick % 20 == 0) tape.prune(tick - 1200);
        }
        tape.prune(4999 - 1200);
        List<Tape.Frame> frames = tape.from(4999 - 1200);
        assertEquals(4999 - 1200, frames.getFirst().tick(), "the frame the window starts in is kept");
        for (int index = 1; index < frames.size(); index++) {
            assertEquals(frames.get(index - 1).tick() + 1, frames.get(index).tick());
            assertEquals(frames.get(index).tick() * 0.1, frames.get(index).x(), 1e-9);
        }
    }

    @Test
    @DisplayName("something standing still costs a heartbeat, not a frame a tick")
    void stillnessIsCheap() {
        Tape tape = tape();
        for (int tick = 0; tick < 200; tick++) {
            tape.put(tick, WORLD, 5, 64, 5, 0f, 0f, 0, 20f);
        }
        List<Tape.Frame> frames = tape.from(0);
        assertEquals(200 / Tape.HEARTBEAT, frames.size());
    }

    @Test
    @DisplayName("two samplers on one tick write it once")
    void oneTickOnce() {
        Tape tape = tape();
        assertTrue(!tape.put(7, WORLD, 1, 2, 3, 0f, 0f, 0, 20f));
        assertTrue(tape.put(7, WORLD, 9, 9, 9, 0f, 0f, 0, 20f));
        assertEquals(1.0, tape.from(0).getFirst().x(), 0.0);
    }

    @Test
    @DisplayName("what somebody wore when the window starts survives the trim")
    void equipmentAtTheStartSurvives() {
        Tape tape = tape();
        tape.wear(10, 0, null);
        tape.prune(500);
        assertEquals(0, tape.worn().size(), "an empty hand is nothing to remember");
    }

    @Test
    @DisplayName("something removed is gone from that tick, not held for a heartbeat")
    void goneIsAbsent() {
        Tape tape = tape();
        tape.put(0, WORLD, 0, 64, 0, 0f, 0f, MotionTrack.PRESENT, 20f);
        tape.put(1, WORLD, 1, 64, 0, 0f, 0f, MotionTrack.PRESENT, 20f);
        tape.gone(2);
        List<Tape.Frame> frames = tape.from(0);
        assertEquals(3, frames.size());
        assertEquals(0, frames.get(2).flags() & MotionTrack.PRESENT);
        assertEquals(2, frames.get(2).tick());
    }

    @Test
    @DisplayName("the same stamp a whole tick later is the next tick")
    void aRepeatedStampLaterIsTheNextTick() throws InterruptedException {
        Tape tape = tape();
        tape.put(5, WORLD, 0, 64, 0, 0f, 0f, MotionTrack.PRESENT, 20f);
        Thread.sleep(40);
        tape.put(5, WORLD, 1, 64, 0, 0f, 0f, MotionTrack.PRESENT, 20f);
        List<Tape.Frame> frames = tape.from(0);
        assertEquals(2, frames.size());
        assertEquals(6, frames.get(1).tick());
    }
}
