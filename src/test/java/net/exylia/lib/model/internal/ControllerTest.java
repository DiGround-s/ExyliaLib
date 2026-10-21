package net.exylia.lib.model.internal;

import net.exylia.lib.util.internal.Ease;
import net.exylia.lib.util.internal.KeyTrack;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a model plays and how the layers blend, with a clock in hand. */
class ControllerTest {

    private static final double EPSILON = 1e-6;

    /** Two bones: legs (0) and arms (1). */
    private static Clip holding(String name, int bone, int channel, double value, Clip.Loop loop, long length,
                                List<Clip.Event> events) {
        KeyTrack[][] bones = new KeyTrack[2][];
        bones[bone] = new KeyTrack[Clip.CHANNELS];
        bones[bone][channel] = new KeyTrack(new long[]{0}, new double[]{value}, new Ease[]{Ease.LINEAR},
                new double[]{Double.NaN});
        return new Clip(name, length, loop, bones, events);
    }

    private static Controller controller(Map<String, Clip> clips) {
        return new Controller(2, clips::get);
    }

    private static double[][] sample(Controller controller, long now) {
        double[][] out = new double[2][Clip.CHANNELS];
        controller.sample(now, out);
        return out;
    }

    @Test
    void itWalksWhenItMovesAndRunsWhenItHurries() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("idle", holding("idle", 0, Clip.ROT_X, 0, Clip.Loop.LOOP, 1000, List.of()));
        clips.put("walk", holding("walk", 0, Clip.ROT_X, 30, Clip.Loop.LOOP, 1000, List.of()));
        Controller controller = controller(clips);
        controller.advance(0, 0);
        assertEquals("idle", controller.baseState());
        controller.advance(50, 2);
        assertEquals("walk", controller.baseState());
        // Fading in: a quarter of a second to hand over.
        assertEquals(0, sample(controller, 50)[0][Clip.ROT_X], EPSILON);
        assertEquals(30 * 0.5, sample(controller, 50 + Controller.BASE_FADE_MS / 2)[0][Clip.ROT_X], EPSILON);
        assertEquals(30, sample(controller, 50 + Controller.BASE_FADE_MS)[0][Clip.ROT_X], EPSILON);
        // No run of its own: running is walking.
        controller.advance(400, 6);
        assertEquals("run", controller.baseState());
        assertEquals(30, sample(controller, 2000)[0][Clip.ROT_X], EPSILON);
        // Hysteresis: a walk does not stop the moment it slows below the start speed.
        controller.advance(450, 0.5);
        assertEquals("walk", controller.baseState());
        controller.advance(500, 0.1);
        assertEquals("idle", controller.baseState());
    }

    @Test
    void anActionTakesOnlyTheBonesItMoves() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("walk", holding("walk", 0, Clip.ROT_X, 30, Clip.Loop.LOOP, 1000, List.of()));
        clips.put("attack", holding("attack", 1, Clip.ROT_X, -90, Clip.Loop.ONCE, 1000, List.of()));
        Controller controller = controller(clips);
        controller.advance(0, 2);
        assertTrue(controller.play("attack", 0));
        controller.advance(500, 2);
        double[][] out = sample(controller, 500);
        assertEquals(30, out[0][Clip.ROT_X], EPSILON, "the legs keep walking");
        assertEquals(-90, out[1][Clip.ROT_X], EPSILON, "the arms swing");
        // A one-shot hands the arms back as it ends.
        controller.advance(1000 - Controller.ACTION_OUT_MS / 2, 2);
        assertEquals(-45, sample(controller, 1000 - Controller.ACTION_OUT_MS / 2)[1][Clip.ROT_X], 1);
        controller.advance(1100, 2);
        assertNull(controller.action());
        assertEquals(0, sample(controller, 1100)[1][Clip.ROT_X], EPSILON);
    }

    @Test
    void eventsFireOnceAsTheyArePassed() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("attack", holding("attack", 1, Clip.ROT_X, 10, Clip.Loop.ONCE, 1000,
                List.of(new Clip.Event(300, "impact"))));
        Controller controller = controller(clips);
        controller.advance(0, 0);
        controller.play("attack", 0);
        controller.advance(200, 0);
        assertEquals(List.of(), controller.drainEvents());
        controller.advance(400, 0);
        assertEquals(List.of("impact"), controller.drainEvents());
        controller.advance(600, 0);
        assertEquals(List.of(), controller.drainEvents());
    }

    @Test
    void aLoopFiresItsEventEveryPass() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("idle", holding("idle", 0, Clip.ROT_X, 0, Clip.Loop.LOOP, 1000, List.of(new Clip.Event(900, "step"))));
        Controller controller = controller(clips);
        controller.advance(0, 0);
        controller.advance(950, 0);
        assertEquals(List.of("step"), controller.drainEvents());
        controller.advance(1500, 0);
        assertEquals(List.of(), controller.drainEvents());
        controller.advance(1950, 0);
        assertEquals(List.of("step"), controller.drainEvents());
    }

    @Test
    void deathHoldsItsLastFrameAndNothingReplacesIt() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("death", holding("death", 0, Clip.ROT_Z, 90, Clip.Loop.ONCE, 800, List.of()));
        clips.put("hurt", holding("hurt", 0, Clip.ROT_Z, -10, Clip.Loop.ONCE, 300, List.of()));
        Controller controller = controller(clips);
        controller.advance(0, 0);
        assertEquals(800, controller.die(0));
        assertTrue(controller.dying());
        assertFalse(controller.play("hurt", 100));
        controller.advance(5000, 3);
        assertEquals("death", controller.action());
        assertEquals(90, sample(controller, 5000)[0][Clip.ROT_Z], EPSILON);
    }

    @Test
    void nothingToPlayIsSaid() {
        Controller controller = controller(new HashMap<>());
        assertFalse(controller.play("attack", 0));
        assertEquals(0, controller.die(0));
        double[][] out = sample(controller, 0);
        assertEquals(1, out[0][Clip.SCALE_X], EPSILON);
        assertEquals(0, out[1][Clip.ROT_Y], EPSILON);
    }

    /** An action on the arms that reaches 90 degrees at 1 s and strikes at 0.6 s. */
    private static Clip swing(List<Clip.Event> events) {
        KeyTrack[][] bones = new KeyTrack[2][];
        bones[1] = new KeyTrack[Clip.CHANNELS];
        bones[1][Clip.ROT_X] = new KeyTrack(new long[]{0, 1000}, new double[]{0, 90},
                new Ease[]{Ease.LINEAR, Ease.LINEAR}, new double[]{Double.NaN, Double.NaN});
        return new Clip("swing", 1000, Clip.Loop.ONCE, bones, events);
    }

    @Test
    void aFittedActionStrikesWhenTheWindUpEnds() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("cast:slam", swing(List.of(new Clip.Event(600, "release"))));
        Controller controller = controller(clips);
        controller.advance(0, 0);
        assertTrue(controller.play("cast:slam", 0, 1200));
        // A wind-up twice as long as the strike: half speed until it strikes.
        controller.advance(600, 0);
        assertTrue(controller.drainEvents().isEmpty(), "not yet: halfway through the wind-up");
        controller.advance(1199, 0);
        assertTrue(controller.drainEvents().isEmpty());
        controller.advance(1200, 0);
        assertEquals(List.of("release"), controller.drainEvents(), "on the wind-up's last moment");
        // After the strike it plays at its own pace: the remaining 400 ms take 400 ms.
        controller.advance(1500, 0);
        assertEquals("cast:slam", controller.action());
        controller.advance(1601, 0);
        assertNull(controller.action(), "done once its own length is played");
    }

    @Test
    void aFittedActionWithoutAStrikeEndsWithTheWindUp() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("attack", swing(List.of()));
        Controller controller = controller(clips);
        controller.advance(0, 0);
        controller.play("attack", 0, 500);
        controller.advance(250, 0);
        // Twice as fast: a quarter of a second in is half the swing.
        assertEquals(45, sample(controller, 250 + Controller.ACTION_IN_MS)[1][Clip.ROT_X], 1.0);
        controller.advance(501, 0);
        assertNull(controller.action());
    }

    @Test
    void fittingIsKeptWithinReason() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("attack", swing(List.of(new Clip.Event(1000, "impact"))));
        assertEquals(1000, Controller.strike(clips.get("attack")));
        Controller controller = controller(clips);
        controller.advance(0, 0);
        // Asked to strike in 10 ms: four times as fast is the most it goes.
        controller.play("attack", 0, 10);
        controller.advance(200, 0);
        assertTrue(controller.drainEvents().isEmpty());
        controller.advance(250, 0);
        assertEquals(List.of("impact"), controller.drainEvents());
    }

    @Test
    void eachStatePlaysAtItsOwnSpeed() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("attack", swing(List.of(new Clip.Event(500, "hit"))));
        Controller controller = new Controller(2, clips::get, state -> state.equals("attack") ? 2 : 1);
        controller.advance(0, 0);
        controller.play("attack", 0);
        controller.advance(249, 0);
        assertTrue(controller.drainEvents().isEmpty());
        controller.advance(250, 0);
        assertEquals(List.of("hit"), controller.drainEvents(), "twice as fast");
    }

    @Test
    void aNewPhaseFadesToItsOwnWalk() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("idle", holding("idle", 0, Clip.ROT_X, 0, Clip.Loop.LOOP, 1000, List.of()));
        clips.put("idle_p2", holding("idle_p2", 0, Clip.ROT_X, 20, Clip.Loop.LOOP, 1000, List.of()));
        int[] phase = {1};
        Controller controller = new Controller(2, state -> phase[0] > 1 && clips.containsKey(state + "_p2")
                ? clips.get(state + "_p2") : clips.get(state));
        controller.advance(0, 0);
        assertEquals(0, sample(controller, 0)[0][Clip.ROT_X], EPSILON);
        phase[0] = 2;
        controller.refresh(100);
        assertEquals(10, sample(controller, 100 + Controller.BASE_FADE_MS / 2)[0][Clip.ROT_X], EPSILON);
        assertEquals(20, sample(controller, 100 + Controller.BASE_FADE_MS)[0][Clip.ROT_X], EPSILON);
    }

    @Test
    void itSwimsFliesAndClimbsWhereTheModelCanAndWalksWhereItCannot() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("idle", holding("idle", 0, Clip.ROT_X, 0, Clip.Loop.LOOP, 1000, List.of()));
        clips.put("walk", holding("walk", 0, Clip.ROT_X, 30, Clip.Loop.LOOP, 1000, List.of()));
        clips.put("swim", holding("swim", 0, Clip.ROT_X, 60, Clip.Loop.LOOP, 1000, List.of()));
        clips.put("climb", holding("climb", 0, Clip.ROT_X, 90, Clip.Loop.LOOP, 1000, List.of()));
        Controller controller = controller(clips);
        controller.advance(0, 0);

        controller.advance(50, 2, Controller.Motion.SWIM, 0);
        assertEquals("swim", controller.baseState());
        controller.advance(100, 2, Controller.Motion.CLIMB, 0);
        assertEquals("climb", controller.baseState());
        controller.advance(150, 2, Controller.Motion.FLY, 0);
        assertEquals("walk", controller.baseState(), "no fly of its own: it walks through the air");
        controller.advance(200, 0, Controller.Motion.GROUND, 0);
        assertEquals("idle", controller.baseState());
        controller.advance(250, 0, Controller.Motion.GROUND, 400);
        assertEquals("idle", controller.baseState(), "no turn of its own: it stands");
    }

    @Test
    void aFallLongEnoughLandsAndAStepDoesNot() {
        Map<String, Clip> clips = new HashMap<>();
        clips.put("idle", holding("idle", 0, Clip.ROT_X, 0, Clip.Loop.LOOP, 1000, List.of()));
        clips.put("fall", holding("fall", 0, Clip.ROT_X, 45, Clip.Loop.LOOP, 1000, List.of()));
        clips.put("turn", holding("turn", 0, Clip.ROT_X, 10, Clip.Loop.LOOP, 1000, List.of()));
        clips.put("land", holding("land", 1, Clip.ROT_X, 20, Clip.Loop.ONCE, 400, List.of()));
        Controller controller = controller(clips);
        controller.advance(0, 0);

        controller.advance(50, 0, Controller.Motion.FALL, 0);
        assertEquals("fall", controller.baseState());
        controller.advance(150, 0, Controller.Motion.GROUND, 0);
        assertNull(controller.action(), "a step down is no landing");
        controller.advance(200, 0, Controller.Motion.JUMP, 0);
        assertEquals("idle", controller.baseState(), "no jump of its own: it keeps what it was doing");
        controller.advance(400, 0, Controller.Motion.FALL, 0);
        controller.advance(600, 0, Controller.Motion.GROUND, 0);
        assertEquals("land", controller.action());
        assertEquals("idle", controller.baseState());
        controller.advance(700, 0, Controller.Motion.GROUND, 300);
        assertEquals("turn", controller.baseState(), "turning on the spot");
    }
}
