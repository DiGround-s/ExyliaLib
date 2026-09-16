package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.CameraShot;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * That a looping shot comes round instead of ending.
 *
 * <p>A body that dances until somebody stops it is filmed by a camera that does
 * the same, and the two have to be independent: the shot ends where its path
 * ends, and the only thing that makes it carry on is this flag. A shot that
 * quietly ended would leave the player looking at their own hidden body for as
 * long as they kept dancing.
 */
class LiveCameraLoopTest {

    private final List<String> sent = new ArrayList<>();

    private final CameraSink sink = new CameraSink() {
        @Override
        public int newEntityId() {
            return 1;
        }

        @Override
        public void spawn(List<Player> viewers, int entityId, CameraPath.Frame at, int holdTicks) {
            sent.add("spawn");
        }

        @Override
        public void move(List<Player> viewers, int entityId, CameraPath.Frame to) {
            sent.add("move " + to.yaw());
        }

        @Override
        public void look(List<Player> viewers, int entityId) {
            sent.add("look " + entityId);
        }

        @Override
        public void destroy(List<Player> viewers, int entityId) {
            sent.add("destroy");
        }
    };

    private static List<CameraPath.Frame> path() {
        List<CameraPath.Frame> frames = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            frames.add(new CameraPath.Frame(index, 0, 0, index * 10f, 0f));
        }
        return frames;
    }

    private LiveCamera camera(boolean loop) {
        return new LiveCamera(null, List.of(), 1, path(), loop, 0L);
    }

    @Test
    @DisplayName("a looping shot goes round again from its second frame")
    void loopsFromTheTop() {
        LiveCamera shot = camera(true);
        sent.clear();

        assertFalse(shot.advance(sink, CameraShot.SAMPLE_MILLIS));
        assertEquals(List.of("move 10.0"), sent);

        // The last position is the first one, so reaching it is the cycle
        // coming round: it is sent, and nothing is sent twice for it.
        sent.clear();
        assertFalse(shot.advance(sink, 2 * CameraShot.SAMPLE_MILLIS));
        assertEquals(List.of("move 20.0"), sent);

        // Straight back into the path on the same beat: one sample later the
        // second position is due again, not two.
        sent.clear();
        assertFalse(shot.advance(sink, 3 * CameraShot.SAMPLE_MILLIS));
        assertEquals(List.of("move 10.0"), sent);
    }

    @Test
    @DisplayName("a loop that fell a whole cycle behind starts again from now")
    void catchesUpRatherThanReplayingEverything() {
        LiveCamera shot = camera(true);
        sent.clear();

        // The server hitched for a second: the path is several cycles stale.
        assertFalse(shot.advance(sink, 20 * CameraShot.SAMPLE_MILLIS));

        sent.clear();
        assertFalse(shot.advance(sink, 21 * CameraShot.SAMPLE_MILLIS));
        assertEquals(List.of("move 10.0"), sent, "back on the beat, one frame at a time");
    }
}
