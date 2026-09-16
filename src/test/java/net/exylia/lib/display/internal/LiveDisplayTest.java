package net.exylia.lib.display.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a display's poses are sent, and that it is always taken away.
 *
 * <p>The timing is the module's whole contract with the client: a pose has to
 * arrive as the previous one is reached, carrying the gap between them, or the
 * animation stutters. The removal is the other half — a display nobody destroys
 * stays on that player's screen until they relog, and no part of the server
 * knows it is there.
 */
class LiveDisplayTest {

    private final List<String> sent = new ArrayList<>();

    private final DisplaySink sink = new DisplaySink() {
        @Override
        public void spawn(List<Player> viewers, int entityId, DisplayModel model,
                          Location at, DisplayKeyframe pose) {
            sent.add("spawn@" + pose.atMillis());
        }

        @Override
        public void pose(List<Player> viewers, int entityId, DisplayModel model,
                         DisplayKeyframe pose, int overTicks) {
            sent.add("pose@" + pose.atMillis() + " over " + overTicks);
        }

        @Override
        public void mount(List<Player> viewers, int vehicleId, int[] passengers) {
            sent.add("mount " + vehicleId + "x" + passengers.length);
        }

        @Override
        public void destroy(List<Player> viewers, int entityId) {
            sent.add("destroy");
        }
    };

    private LiveDisplay display(long life, long... poseTimes) {
        List<DisplayKeyframe> poses = new ArrayList<>();
        for (long at : poseTimes) {
            poses.add(new DisplayKeyframe(at, 0f, 0f, 0f, Rotation.NONE, 1f, 1f, 1f));
        }
        return new LiveDisplay("Test", 7,
                DisplayModel.text(Component.empty()),
                DisplayMotion.of(poses, life),
                List.of(), 0L, 0);
    }

    @Test
    @DisplayName("a display knows what it rides, so the seat list can be taken down with it")
    void ridingDisplayNamesItsVehicle() {
        LiveDisplay standing = display(1000, 0);
        LiveDisplay seated = new LiveDisplay("Test", 8,
                DisplayModel.text(Component.empty()),
                DisplayMotion.still(1000),
                List.of(), 0L, 42);

        assertEquals(0, standing.vehicleId());
        assertEquals(42, seated.vehicleId());
        assertEquals(8, seated.entityId());
    }

    @Test
    @DisplayName("the first pose goes out with the spawn, so nothing flickers")
    void firstPoseRidesTheSpawn() {
        LiveDisplay live = display(1000, 0, 500, 1000);

        live.spawn(sink, new Location(null, 0, 0, 0));

        assertEquals(List.of("spawn@0"), sent);
    }

    @Test
    @DisplayName("a pose is sent as the one before it is reached, carrying the gap")
    void posesCarryTheirOwnDuration() {
        LiveDisplay live = display(1000, 0, 500, 1000);
        live.spawn(sink, new Location(null, 0, 0, 0));
        sent.clear();

        assertFalse(live.advance(sink, 0L));
        assertEquals(List.of("pose@500 over 10"), sent);

        sent.clear();
        assertFalse(live.advance(sink, 100L));
        assertTrue(sent.isEmpty(), "nothing is due yet");

        sent.clear();
        assertFalse(live.advance(sink, 500L));
        assertEquals(List.of("pose@1000 over 10"), sent);
    }

    @Test
    @DisplayName("a driver that fell behind catches up rather than skipping poses")
    void catchesUp() {
        LiveDisplay live = display(1000, 0, 200, 400, 600);
        live.spawn(sink, new Location(null, 0, 0, 0));
        sent.clear();

        // One late pass: the server hitched, and three poses are now due.
        assertFalse(live.advance(sink, 650L));

        assertEquals(List.of("pose@200 over 4", "pose@400 over 4", "pose@600 over 4"), sent);
    }

    private LiveDisplay looping(long life, long cycle, double accel, double maxSpeed,
                                long... poseTimes) {
        List<DisplayKeyframe> poses = new ArrayList<>();
        for (long at : poseTimes) {
            poses.add(new DisplayKeyframe(at, 0f, 0f, 0f, Rotation.NONE, 1f, 1f, 1f));
        }
        return new LiveDisplay("Test", 7,
                DisplayModel.text(Component.empty()),
                DisplayMotion.of(poses, life).looping(cycle, accel, maxSpeed),
                List.of(), 0L, 0);
    }

    @Test
    @DisplayName("a looping display starts its poses again, without re-sending the first")
    void loopsFromTheTop() {
        LiveDisplay live = looping(10_000, 400, 1.0, 1.0, 0, 200, 400);
        live.spawn(sink, new Location(null, 0, 0, 0));
        sent.clear();

        assertFalse(live.advance(sink, 0L));
        assertEquals(List.of("pose@200 over 4"), sent);

        sent.clear();
        assertFalse(live.advance(sink, 200L));
        assertEquals(List.of("pose@400 over 4"), sent);

        // The cycle is up: round again, and the pose the body is already
        // standing in is not sent twice.
        sent.clear();
        assertFalse(live.advance(sink, 400L));
        assertEquals(List.of("pose@200 over 4"), sent);
    }

    @Test
    @DisplayName("a rolled tempo is quick from the first cycle, not from the second")
    void startsAtTheTempoItWasGiven() {
        List<DisplayKeyframe> poses = new ArrayList<>();
        for (long at : new long[] {0, 200, 400}) {
            poses.add(new DisplayKeyframe(at, 0f, 0f, 0f, Rotation.NONE, 1f, 1f, 1f));
        }
        LiveDisplay live = new LiveDisplay("Test", 7,
                DisplayModel.text(Component.empty()),
                DisplayMotion.of(poses, 10_000).looping(0L, 400L, 1.0, 1.0, 2.0),
                List.of(), 0L, 0);
        live.spawn(sink, new Location(null, 0, 0, 0));
        sent.clear();

        // Twice the written tempo: the same poses, half the spans, and the
        // cycle is up at 200ms rather than at 400.
        assertFalse(live.advance(sink, 0L));
        assertEquals(List.of("pose@200 over 2"), sent);

        sent.clear();
        assertFalse(live.advance(sink, 100L));
        assertEquals(List.of("pose@400 over 2"), sent);

        sent.clear();
        assertFalse(live.advance(sink, 200L));
        assertEquals(List.of("pose@200 over 2"), sent, "it wrapped at half the written cycle");
    }

    @Test
    @DisplayName("a loop that winds up plays each cycle quicker than the last")
    void loopsFaster() {
        LiveDisplay live = looping(10_000, 400, 2.0, 4.0, 0, 200, 400);
        live.spawn(sink, new Location(null, 0, 0, 0));
        sent.clear();

        live.advance(sink, 0L);
        live.advance(sink, 200L);
        sent.clear();

        // Second cycle at twice the speed: the same poses, half the spans, and
        // the whole cycle takes 200ms rather than 400.
        live.advance(sink, 400L);
        assertEquals(List.of("pose@200 over 2"), sent);

        sent.clear();
        live.advance(sink, 500L);
        assertEquals(List.of("pose@400 over 2"), sent);

        sent.clear();
        live.advance(sink, 600L);
        assertEquals(List.of("pose@200 over 1"), sent, "the third cycle is quicker again");
    }

    @Test
    @DisplayName("a loop with an entry plays the entry once and cycles the rest")
    void loopsPastItsEntry() {
        List<DisplayKeyframe> poses = new ArrayList<>();
        for (long at : new long[]{0, 200, 400, 600}) {
            poses.add(new DisplayKeyframe(at, 0f, 0f, 0f, Rotation.NONE, 1f, 1f, 1f));
        }
        LiveDisplay live = new LiveDisplay("Test", 7,
                DisplayModel.text(Component.empty()),
                DisplayMotion.of(poses, 10_000).looping(200, 600, 1.0, 1.0),
                List.of(), 0L, 0);
        live.spawn(sink, new Location(null, 0, 0, 0));
        sent.clear();

        // The entry: poses at 200 and 400 on their way past.
        live.advance(sink, 0L);
        live.advance(sink, 200L);
        sent.clear();

        // The end of the cycle, and back to where the entry left off rather
        // than to the standing pose it started from.
        live.advance(sink, 400L);
        assertEquals(List.of("pose@600 over 4"), sent);

        sent.clear();
        live.advance(sink, 600L);
        assertEquals(List.of("pose@400 over 4"), sent, "the cycle is 200 to 600, not 0 to 600");

        sent.clear();
        live.advance(sink, 800L);
        assertEquals(List.of("pose@600 over 4"), sent);
    }

    @Test
    @DisplayName("a loop still ends at its life, which is the net under a caller that forgets")
    void loopsUntilItsLifeIsUp() {
        LiveDisplay live = looping(1000, 400, 1.0, 1.0, 0, 200, 400);
        live.spawn(sink, new Location(null, 0, 0, 0));

        assertFalse(live.advance(sink, 800L), "still dancing well past one cycle");
        sent.clear();

        assertTrue(live.advance(sink, 1100L));
        assertEquals(List.of("destroy"), sent);
    }

    @Test
    @DisplayName("it is destroyed when its life is up, exactly once")
    void destroyedOnce() {
        LiveDisplay live = display(1000, 0, 1000);
        live.spawn(sink, new Location(null, 0, 0, 0));
        sent.clear();

        // One tick past its life, so the client finishes drawing the last pose.
        assertFalse(live.advance(sink, 1000L));
        sent.clear();
        assertTrue(live.advance(sink, 1050L));
        assertEquals(List.of("destroy"), sent);
        assertFalse(live.isShowing());

        sent.clear();
        assertTrue(live.advance(sink, 1200L));
        live.destroy(sink);
        assertTrue(sent.isEmpty(), "a display must not be destroyed twice");
    }

    @Test
    @DisplayName("a display that never moves still goes away")
    void stillDisplaysExpire() {
        LiveDisplay live = display(400, 0);
        live.spawn(sink, new Location(null, 0, 0, 0));
        sent.clear();

        assertFalse(live.advance(sink, 100L));
        assertTrue(sent.isEmpty());
        assertFalse(live.advance(sink, 400L));
        assertTrue(live.advance(sink, 450L));
        assertEquals(List.of("destroy"), sent);
    }
}
