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
        public void pose(List<Player> viewers, int entityId, DisplayKeyframe pose, int overTicks) {
            sent.add("pose@" + pose.atMillis() + " over " + overTicks);
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
                List.of(), 0L);
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

    @Test
    @DisplayName("it is destroyed when its life is up, exactly once")
    void destroyedOnce() {
        LiveDisplay live = display(1000, 0, 1000);
        live.spawn(sink, new Location(null, 0, 0, 0));
        sent.clear();

        assertTrue(live.advance(sink, 1000L));
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
        assertTrue(live.advance(sink, 400L));
        assertEquals(List.of("destroy"), sent);
    }
}
