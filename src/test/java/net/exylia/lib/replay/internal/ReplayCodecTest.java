package net.exylia.lib.replay.internal;

import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayFrame;
import net.exylia.lib.replay.ReplayMark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a recording survives being written down.
 *
 * <p>This is the module's whole risk. Everything else it does is visible the
 * moment it goes wrong &mdash; a body in the wrong place is a body in the wrong
 * place &mdash; but a recording is written once and read back weeks later, by
 * which time the fight it was of is gone. A frame that comes back a thousandth
 * of a block out is a hit that lands in the replay and did not land in the
 * duel, and there is nothing left to check it against.
 */
class ReplayCodecTest {

    private static final UUID RED = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BLUE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    /** A degree and a half, which is the protocol's own resolution for a yaw. */
    private static final float ROTATION_SLOP = 360f / 256f;

    @Test
    @DisplayName("every frame comes back exactly as it went in")
    void framesRoundTrip() {
        Replay written = duel();
        Replay read = Replay.from(written.toBytes());

        assertEquals(written.id(), read.id());
        assertEquals(written.createdAt(), read.createdAt());
        assertEquals(written.frames(), read.frames());
        assertEquals(written.actors(), read.actors());

        for (int tick = 0; tick < written.frames(); tick++) {
            for (UUID actor : List.of(RED, BLUE)) {
                ReplayFrame before = written.at(tick, actor);
                ReplayFrame after = read.at(tick, actor);
                assertEquals(before.present(), after.present(), "present on tick " + tick);
                if (!before.present()) {
                    continue;
                }
                // Exact, not close: position is held and written in the same
                // thousandths of a block, so a difference here is a bug rather
                // than rounding.
                assertEquals(before.x(), after.x(), 0.0, "x on tick " + tick);
                assertEquals(before.y(), after.y(), 0.0, "y on tick " + tick);
                assertEquals(before.z(), after.z(), 0.0, "z on tick " + tick);
                assertEquals(before.yaw(), after.yaw(), 0.0f, "yaw on tick " + tick);
                assertEquals(before.pitch(), after.pitch(), 0.0f, "pitch on tick " + tick);
                assertEquals(before.health(), after.health(), 0.0, "health on tick " + tick);
                assertEquals(before.pose(), after.pose(), "pose on tick " + tick);
                assertEquals(before.sprinting(), after.sprinting());
                assertEquals(before.onGround(), after.onGround());
            }
        }
    }

    @Test
    @DisplayName("a position is kept to a thousandth of a block and a yaw to a degree")
    void resolutionIsWorthTheName() {
        MotionTrack.Builder track = new MotionTrack.Builder();
        track.put(0, 12.3456, 64.9999, -7.0005, 123.4f, -45.6f, 19.5,
                MotionTrack.flagsOf(NpcPose.STANDING, true, true, true));
        Replay replay = one(track.build(1));

        ReplayFrame frame = Replay.from(replay.toBytes()).at(0, RED);
        assertEquals(12.3456, frame.x(), 0.0005);
        assertEquals(64.9999, frame.y(), 0.0005);
        assertEquals(-7.0005, frame.z(), 0.0005);
        assertEquals(123.4f, frame.yaw(), ROTATION_SLOP);
        assertEquals(-45.6f, frame.pitch(), ROTATION_SLOP);
        assertEquals(19.5, frame.health(), 0.25);
    }

    @Test
    @DisplayName("a tick the server was too busy to sample repeats the one before it")
    void gapsCarryForward() {
        MotionTrack.Builder track = new MotionTrack.Builder();
        track.put(0, 1.0, 0.0, 0.0, 0f, 0f, 20.0,
                MotionTrack.flagsOf(NpcPose.STANDING, false, true, true));
        // Ticks one to three never arrived.
        track.put(4, 2.0, 0.0, 0.0, 0f, 0f, 20.0,
                MotionTrack.flagsOf(NpcPose.STANDING, false, true, true));
        Replay replay = Replay.from(one(track.build(5)).toBytes());

        for (int tick = 1; tick <= 3; tick++) {
            assertTrue(replay.at(tick, RED).present(), "tick " + tick + " is a hole");
            assertEquals(1.0, replay.at(tick, RED).x(), 0.0,
                    "tick " + tick + " did not keep still");
        }
        assertEquals(2.0, replay.at(4, RED).x(), 0.0);
    }

    @Test
    @DisplayName("somebody who was not there yet is not there")
    void leadingTicksAreAbsent() {
        MotionTrack.Builder track = new MotionTrack.Builder();
        track.put(3, 0.0, 0.0, 0.0, 0f, 0f, 20.0,
                MotionTrack.flagsOf(NpcPose.STANDING, false, true, true));
        Replay replay = Replay.from(one(track.build(4)).toBytes());

        assertFalse(replay.at(0, RED).present());
        assertFalse(replay.at(2, RED).present());
        assertTrue(replay.at(3, RED).present());
        assertFalse(replay.at(99, RED).present(), "past the end is nobody");
        assertFalse(replay.at(0, BLUE).present(), "a stranger is nobody");
    }

    @Test
    @DisplayName("somebody who left is gone, not standing where they left")
    void trailingTicksAreAbsent() {
        MotionTrack.Builder track = new MotionTrack.Builder();
        track.put(0, 5.0, 64.0, 5.0, 0f, 0f, 20.0,
                MotionTrack.flagsOf(NpcPose.STANDING, false, true, true));
        track.put(1, 5.0, 64.0, 5.0, 0f, 0f, 20.0,
                MotionTrack.flagsOf(NpcPose.STANDING, false, true, true));
        // They logged out here; the recording ran on for another eight ticks.
        Replay replay = Replay.from(one(track.build(10)).toBytes());

        assertTrue(replay.at(1, RED).present());
        for (int tick = 2; tick < 10; tick++) {
            assertFalse(replay.at(tick, RED).present(),
                    "tick " + tick + " left a body standing in the arena");
        }
    }

    @Test
    @DisplayName("a gap a player came back from is empty, not carried across")
    void aGapTheyCameBackFromIsEmpty() {
        MotionTrack.Builder track = new MotionTrack.Builder();
        track.put(0, 1.0, 64.0, 0.0, 0f, 0f, 20.0,
                MotionTrack.flagsOf(NpcPose.STANDING, false, true, true));
        // Out from tick one to four, back on tick five somewhere else.
        track.absentUntil(5);
        track.put(5, 9.0, 64.0, 0.0, 0f, 0f, 20.0,
                MotionTrack.flagsOf(NpcPose.STANDING, false, true, true));
        Replay replay = Replay.from(one(track.build(6)).toBytes());

        assertTrue(replay.at(0, RED).present());
        for (int tick = 1; tick <= 4; tick++) {
            assertFalse(replay.at(tick, RED).present(), "tick " + tick);
        }
        assertTrue(replay.at(5, RED).present());
        assertEquals(9.0, replay.at(5, RED).x(), 0.0);
    }

    @Test
    @DisplayName("marks keep their tick, their owner and their bytes")
    void marksRoundTrip() {
        Replay read = Replay.from(duel().toBytes());
        List<ReplayMark> marks = read.marks();

        assertEquals(3, marks.size());
        assertEquals(ReplayMark.SWING, marks.get(0).kind());
        assertEquals(RED, marks.get(0).actor());
        assertNull(marks.get(0).data(), "a swing carries nothing");

        assertEquals(2, marks.get(1).tick());
        assertEquals(BLUE, marks.get(1).actor());
        assertArrayEquals("critical".getBytes(StandardCharsets.UTF_8), marks.get(1).data());
        assertEquals("critical", marks.get(1).text());

        assertNull(marks.get(2).actor(), "a mark can belong to nobody");
        assertEquals("round-over", marks.get(2).text());
    }

    @Test
    @DisplayName("a blob that is not a recording says so rather than half-reading one")
    void rubbishIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Replay.from(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        assertThrows(IllegalArgumentException.class, () -> Replay.from(new byte[0]));
    }

    @Test
    @DisplayName("three minutes of two players fits in a database column")
    void aDuelIsSmall() {
        int frames = 3 * 60 * 20;
        MotionTrack.Builder red = new MotionTrack.Builder();
        MotionTrack.Builder blue = new MotionTrack.Builder();
        for (int tick = 0; tick < frames; tick++) {
            // Two players circling each other, which is the expensive case: a
            // position that changes every single tick on every axis.
            double angle = tick * 0.05;
            byte flags = MotionTrack.flagsOf(NpcPose.STANDING, true, tick % 7 != 0, true);
            red.put(tick, Math.cos(angle) * 4, Math.sin(angle * 0.3) * 0.6,
                    Math.sin(angle) * 4, (float) Math.toDegrees(angle), -10f, 20.0, flags);
            blue.put(tick, -Math.cos(angle) * 4, Math.sin(angle * 0.4) * 0.6,
                    -Math.sin(angle) * 4, (float) Math.toDegrees(-angle), 5f, 17.5, flags);
        }
        Replay replay = new Replay(UUID.randomUUID(), System.currentTimeMillis(), frames,
                List.of(new ReplayActor(RED, "Red", null, null),
                        new ReplayActor(BLUE, "Blue", null, null)),
                List.of(red.build(frames), blue.build(frames)), List.of());

        int size = replay.toBytes().length;
        assertTrue(size < 100_000,
                "a three minute duel came out at " + size + " bytes");
        assertEquals(frames, Replay.from(replay.toBytes()).frames());
    }

    /** Two players, a few ticks, and one of each kind of mark. */
    private static Replay duel() {
        MotionTrack.Builder red = new MotionTrack.Builder();
        MotionTrack.Builder blue = new MotionTrack.Builder();
        for (int tick = 0; tick < 6; tick++) {
            red.put(tick, tick * 0.25, 64.0, -tick * 0.5, tick * 30f, -12.5f, 20.0 - tick,
                    MotionTrack.flagsOf(NpcPose.STANDING, tick % 2 == 0, true, true));
            blue.put(tick, -tick * 0.25, 64.0, tick * 0.5, -tick * 30f, 7.5f, 14.0,
                    MotionTrack.flagsOf(NpcPose.SNEAKING, false, tick != 3, true));
        }
        return new Replay(UUID.randomUUID(), 1_700_000_000_000L, 6,
                List.of(new ReplayActor(RED, "Red", "dGV4dHVyZQ==", "c2ln"),
                        new ReplayActor(BLUE, "Blue", null, null)),
                List.of(red.build(6), blue.build(6)),
                List.of(new ReplayMark(1, ReplayMark.SWING, RED, null),
                        ReplayMark.of(2, "crit", BLUE, "critical"),
                        ReplayMark.of(5, "round", null, "round-over")));
    }

    /** One player, one track. */
    private static Replay one(MotionTrack track) {
        return new Replay(UUID.randomUUID(), 1L, track.frames(),
                List.of(new ReplayActor(RED, "Red", null, null)), List.of(track), List.of());
    }
}
