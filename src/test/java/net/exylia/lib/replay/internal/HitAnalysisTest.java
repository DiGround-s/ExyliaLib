package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayHit;
import net.exylia.lib.replay.ReplayMark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the numbers staff judge a hit by mean what they say: reach to the
 * hitbox, the angle off it, and where the attacker's screen had the target.
 */
class HitAnalysisTest {

    private static final UUID RED = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BLUE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final int STANDING = MotionTrack.flagsOf(0, false, true, false, false, false, false, false, true);

    @Test
    @DisplayName("looking straight at somebody 2.5 blocks away is no angle and the reach to their skin")
    void straightAtThem() {
        // Red at the origin facing +Z (yaw 0), Blue 2.5 blocks ahead, standing still.
        ReplayHit hit = only(analyse(0f, 0f, 2.5, 2, false));
        assertEquals(0.0, hit.angleOff(), 0.0);
        assertEquals(2.2, hit.reachServer(), 1e-3, "2.5 to the centre, 0.3 to the near face");
        assertEquals(2, hit.seenLag());
        assertTrue(hit.confirmed());
    }

    @Test
    @DisplayName("a look thirty degrees to the side is off the box by about what is left after its half width")
    void offToTheSide() {
        ReplayHit hit = only(analyse(30f, 0f, 2.5, 0, false));
        // The near edge of the box is atan(0.3 / 2.2) away from the centre line, seen from the eyes.
        double edge = Math.toDegrees(Math.atan2(0.3, 2.2));
        assertEquals(30 - edge, hit.angleOff(), 1.0);
    }

    @Test
    @DisplayName("the seen reach measures to where the attacker's screen had a target running away")
    void seenReachIsWhereTheScreenHadThem() {
        // Blue runs away along +Z at 0.25 a tick; Red's screen is 4 ticks behind.
        ReplayHit hit = only(analyse(0f, 0f, 2.5, 4, true));
        assertEquals(2.2 + 0.25 * 1, hit.reachServer(), 1e-3, "the sample after the attack, a tick on");
        assertEquals(2.2 - 0.25 * 4, hit.reachSeen(), 1e-3, "four ticks back");
        assertFalse(hit.estimated());
    }

    @Test
    @DisplayName("the ray test and the reach are what the server measures")
    void geometry() {
        double[] box = {-0.3, 0, 2.2, 0.3, 1.8, 2.8};
        assertTrue(HitAnalysis.hits(0, 1.62, 0, 0, 0, 1, box));
        assertFalse(HitAnalysis.hits(0, 1.62, 0, 0, 0, -1, box), "behind is not a hit");
        assertEquals(0.0, HitAnalysis.distance(0, 1, 2.5, box), 0.0, "inside is no distance");
        assertEquals(1.27, HitAnalysis.eyeHeight("CROUCHING"), 0.0);
    }

    /** Red attacks Blue on tick 10 with this look, Blue starting this far ahead. */
    private static Replay analyse(float yaw, float pitch, double ahead, int lag, boolean running) {
        MotionTrack.Builder red = new MotionTrack.Builder();
        MotionTrack.Builder blue = new MotionTrack.Builder();
        for (int tick = 0; tick <= 20; tick++) {
            red.put(tick, 0, 0, 0, yaw, pitch, yaw, 20, STANDING, lag);
            double z = ahead + (running ? 0.25 * (tick - 10) : 0);
            blue.put(tick, 0, 0, z, 180f, 0f, 180f, 20, STANDING);
        }
        byte[] data = new MarkData.Hit(BLUE, MarkData.STRONG, 3f, true, yaw, pitch, lag, false,
                Float.NaN, Float.NaN, Float.NaN, 0f).write();
        Replay draft = new Replay(UUID.randomUUID(), 1L, 21,
                List.of(new ReplayActor(RED, "Red", null, null, null), new ReplayActor(BLUE, "Blue", null, null, null)),
                List.of(red.build(), blue.build()), List.of(new ReplayMark(10, ReplayMark.ATTACK, RED, data)),
                List.of(), null);
        return new Replay(draft.id(), 1L, 21, draft.actors(), draft.tracks(), HitAnalysis.analysed(draft),
                List.of(), null);
    }

    private static ReplayHit only(Replay replay) {
        List<ReplayHit> hits = replay.hits();
        assertEquals(1, hits.size());
        assertTrue(MarkData.Hit.read(replay.marks().getFirst().data()).analysed(), "written into the mark");
        return hits.getFirst();
    }
}
