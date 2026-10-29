package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.CameraShot;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The viewpoint every range check reads: the camera while it films, the body otherwise. */
class ViewpointTest {

    private final CameraSink sink = new CameraSink() {
        @Override
        public int newEntityId() {
            return 1;
        }

        @Override
        public void spawn(List<Player> viewers, int entityId, CameraPath.Frame at, int holdTicks) {
        }

        @Override
        public void move(List<Player> viewers, int entityId, CameraPath.Frame to) {
        }

        @Override
        public void look(List<Player> viewers, int entityId) {
        }

        @Override
        public void destroy(List<Player> viewers, int entityId) {
        }
    };

    @Test
    @DisplayName("a filmed player sees from the camera's last position, and from their body once it is gone")
    void switches() {
        UUID viewer = UUID.randomUUID();
        List<CameraPath.Frame> frames = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            frames.add(new CameraPath.Frame(index * 20, 80, 0, 0f, 10f));
        }
        LiveCamera camera = new LiveCamera(null, List.of(), 1, frames, false, 0L);
        assertNull(CameraRuntime.viewpoint(viewer));
        CameraRuntime.testFilm(viewer, camera);
        try {
            assertEquals(0, CameraRuntime.viewpoint(viewer)[0], 1e-9);
            camera.advance(sink, 4 * CameraShot.SAMPLE_MILLIS);
            assertEquals(80, CameraRuntime.viewpoint(viewer)[0], 1e-9);
            assertEquals(80, CameraRuntime.viewpoint(viewer)[1], 1e-9);
        } finally {
            CameraRuntime.testFilm(viewer, null);
        }
        assertNull(CameraRuntime.viewpoint(viewer));
    }
}
