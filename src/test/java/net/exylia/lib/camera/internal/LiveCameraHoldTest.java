package net.exylia.lib.camera.internal;

import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCameraHoldTest {

    private long now;
    private final List<CameraPath.Frame> sent = new ArrayList<>();

    private final CameraSink sink = new CameraSink() {
        @Override
        public int newEntityId() {
            return 1;
        }

        @Override
        public void spawn(List<org.bukkit.entity.Player> viewers, int entityId, CameraPath.Frame at, int holdTicks) {
        }

        @Override
        public void move(List<org.bukkit.entity.Player> viewers, int entityId, CameraPath.Frame to) {
            sent.add(to);
        }

        @Override
        public void look(List<org.bukkit.entity.Player> viewers, int entityId) {
        }

        @Override
        public void destroy(List<org.bukkit.entity.Player> viewers, int entityId) {
        }
    };

    @AfterEach
    void reset() {
        CameraRuntime.testHooks(null, null);
    }

    private LiveCamera camera(int frames) {
        CameraRuntime.testHooks(() -> now, sink);
        List<CameraPath.Frame> path = new ArrayList<>();
        for (int index = 0; index < frames; index++) {
            path.add(new CameraPath.Frame(index, 0, 0, 0, 0, false));
        }
        return new LiveCamera(null, List.of(), 1, path, false, now);
    }

    @Test
    void standsAtTheHoldThenGoesOnFromIt() {
        LiveCamera camera = camera(21);
        camera.pauseAt(500);
        now = 5000;
        assertFalse(camera.advance(sink, now));
        assertTrue(camera.isPaused());
        assertEquals(500, camera.elapsedMillis());
        assertEquals(5, sent.size(), "nothing past the hold, however late the tick");

        now = 90_000;
        assertFalse(camera.advance(sink, now), "a held shot never runs out");
        assertEquals(5, sent.size());

        camera.resume();
        assertFalse(camera.isPaused());
        assertEquals(500, camera.elapsedMillis(), "goes on from where it stopped");
        now += 100;
        camera.advance(sink, now);
        assertEquals(6, sent.size());
    }
}
