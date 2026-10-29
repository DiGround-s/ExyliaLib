package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.CameraShot;
import net.exylia.lib.camera.WorldShot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * What rides a world shot's camera, asserted on what would be sent.
 *
 * <p>The order is the contract: panels are seated before the view moves into
 * the camera, a subtitle is sent once per line rather than once per sample,
 * and everything that rode the camera is destroyed with it.
 */
class CameraOverlayTest {

    private final List<String> sent = new ArrayList<>();
    private int nextId = 10;

    private final CameraSink sink = new CameraSink() {
        @Override
        public int newEntityId() {
            return nextId++;
        }

        @Override
        public void spawn(List<Player> viewers, int entityId, CameraPath.Frame at, int holdTicks) {
            sent.add("spawn " + entityId);
        }

        @Override
        public void move(List<Player> viewers, int entityId, CameraPath.Frame to) {
        }

        @Override
        public void look(List<Player> viewers, int entityId) {
            sent.add("look " + entityId);
        }

        @Override
        public void destroy(List<Player> viewers, int entityId) {
            sent.add("destroy " + entityId);
        }

        @Override
        public void spawnPanel(List<Player> viewers, int entityId, CameraPath.Frame at, CameraOverlay.Panel panel) {
            sent.add("panel " + entityId);
        }

        @Override
        public void mount(List<Player> viewers, int vehicleId, int[] passengers) {
            sent.add("mount " + vehicleId + " " + passengers.length);
        }

        @Override
        public void text(List<Player> viewers, int entityId, Component text) {
            sent.add("text " + PlainTextComponentSerializer.plainText().serialize(text));
        }

        @Override
        public void background(List<Player> viewers, int entityId, int argb) {
            sent.add("background " + entityId + " " + (argb >>> 24));
        }
    };

    @Test
    @DisplayName("panels ride the camera from the first frame and change only when due")
    void lifecycle() {
        WorldShot shot = WorldShot.builder()
                .key(0, new Location(null, 0, 70, 0))
                .key(1, new Location(null, 10, 70, 0))
                .subtitle(0.3, 0.3, Component.text("hello"))
                .letterbox(true)
                .build();
        int camera = sink.newEntityId();
        CameraOverlay overlay = new CameraOverlay(shot, sink);
        LiveCamera live = new LiveCamera(null, List.of(), camera, CameraPath.of(shot), false, 0L, overlay);

        overlay.start(sink, List.of(), camera, CameraPath.of(shot).get(0));
        assertEquals(List.of("panel 11", "panel 12", "panel 13", "mount 10 3"), sent);

        sent.clear();
        live.advance(sink, 3 * CameraShot.SAMPLE_MILLIS);
        live.advance(sink, 4 * CameraShot.SAMPLE_MILLIS);
        assertEquals(List.of("text hello", "background 11 112"), sent);

        sent.clear();
        live.advance(sink, 7 * CameraShot.SAMPLE_MILLIS);
        assertEquals(List.of("text ", "background 11 0"), sent);

        // What end() destroys with the camera (end itself needs a live packet module).
        assertArrayEquals(new int[]{11, 12, 13}, overlay.ids());
    }

    @Test
    @DisplayName("two people at once: lines on one side stack, lines on two sides sit apart, bars are symmetric")
    void stacked() {
        WorldShot.Subtitle a = new WorldShot.Subtitle(0, 4000, Component.text("Ann"), Component.text("hi"),
                WorldShot.Position.BOTTOM, WorldShot.Align.CENTER, 0, -1, null);
        WorldShot.Subtitle b = new WorldShot.Subtitle(500, 4000, Component.text("Bob"), Component.text("hey"),
                WorldShot.Position.BOTTOM, WorldShot.Align.CENTER, 0, -1, null);
        WorldShot.Subtitle left = new WorldShot.Subtitle(0, 4000, null, Component.text("l"),
                WorldShot.Position.TOP, WorldShot.Align.LEFT, 0, -1, null);
        WorldShot.Subtitle right = new WorldShot.Subtitle(0, 4000, null, Component.text("r"),
                WorldShot.Position.TOP, WorldShot.Align.RIGHT, 0, -1, null);
        WorldShot shot = WorldShot.builder().key(0, new Location(null, 0, 70, 0)).key(1, new Location(null, 1, 70, 0))
                .subtitle(a).subtitle(b).subtitle(left).subtitle(right).letterbox(0.1).build();
        CameraOverlay overlay = new CameraOverlay(shot, sink);
        var layout = overlay.layout(shot.subtitlesAt(600));
        assertEquals(4, layout.size());
        CameraOverlay.Panel first = layout.get(a);
        CameraOverlay.Panel second = layout.get(b);
        // Ann started first, so she sits nearest the bar and Bob above her, clear of her two rows.
        org.junit.jupiter.api.Assertions.assertTrue(second.y() >= first.y() + 2 * 0.25f * 0.2f);
        org.junit.jupiter.api.Assertions.assertTrue(layout.get(left).x() < 0 && layout.get(right).x() > 0);
        assertEquals(layout.get(left).y(), layout.get(right).y(), 1e-6);

        sent.clear();
        overlay.start(sink, List.of(), 99, CameraPath.of(shot).get(0));
        // Four line panels, two bars, no fade, no title.
        assertEquals(4 + 2, overlay.ids().length);
    }
}
