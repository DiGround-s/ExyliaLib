package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.WorldShot;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * What rides a world shot's camera: the subtitle, the letterbox bars and the
 * fade panel.
 *
 * <h2>Why they ride rather than follow</h2>
 * Each one is a text display seated on the camera entity with a
 * camera-facing billboard and an offset in the camera's own axes. The client
 * then positions them from the camera every frame it draws, so they move with
 * the view exactly, however fast it pans, and moving the camera costs no
 * packet for any of them. Sending each its own position would be four times
 * the packets and a panel visibly trailing the view on every turn.
 *
 * <h2>The geometry</h2>
 * A text display with a {@code CENTER} billboard is turned to the camera's own
 * orientation, and its transform is applied after that turn. A translation of
 * {@code (0, y, -d)} is therefore always {@code d} blocks straight ahead of the
 * view and {@code y} above its centre, whatever the camera is doing. Panels are
 * described here at one block away and scaled with their distance, so the same
 * numbers read the same at any depth. A bottom bar is turned half a turn about
 * the view axis so it grows downwards from its edge: the edge is then exactly
 * where it was put, without knowing how tall the client draws one line.
 */
final class CameraOverlay {

    /**
     * One panel, in view units: one block in front of the camera, where the
     * default 70&deg; field of view spans about 0.7 above and below centre.
     *
     * @param text       what it says; a space for a plain panel
     * @param background ARGB behind the text
     * @param y          where its origin sits, above the centre of the view
     * @param distance   how far ahead of the camera, in blocks
     * @param scaleX     horizontal stretch
     * @param scaleY     vertical stretch
     * @param lineWidth  where text wraps, in pixels
     * @param flipped    whether it grows downwards from {@code y} instead of upwards
     * @param shadow     whether the text has a drop shadow
     */
    record Panel(Component text, int background, float y, float distance, float scaleX, float scaleY,
                 int lineWidth, boolean flipped, boolean shadow) {
    }

    /** Clear enough to read the world through, dark enough to read white text on. */
    private static final int SUBTITLE_BACKGROUND = 0x70000000;
    private static final int BLACK = 0xFF000000;

    /**
     * Where a subtitle's bottom edge sits, and how large it is drawn.
     *
     * <p>Just above the bottom bar's inner edge, in the band films put them.
     * A scale of 0.2 makes a line about a twentieth of the screen tall at the
     * default field of view: readable at a glance, small enough to leave the
     * shot alone.
     */
    private static final float SUBTITLE_Y = -0.50f;
    private static final float SUBTITLE_SCALE = 0.2f;
    private static final int SUBTITLE_WIDTH = 360;

    /** The bars' inner edges: about a tenth of the view each at the default field of view. */
    private static final float BAR_EDGE = 0.57f;

    private final WorldShot shot;
    private final int subtitleId;
    private final int topBarId;
    private final int bottomBarId;
    private final int fadeId;

    private WorldShot.Subtitle showing;
    private int alpha = -1;

    CameraOverlay(WorldShot shot, CameraSink sink) {
        this.shot = shot;
        this.subtitleId = shot.subtitles().isEmpty() ? -1 : sink.newEntityId();
        this.topBarId = shot.letterbox() ? sink.newEntityId() : -1;
        this.bottomBarId = shot.letterbox() ? sink.newEntityId() : -1;
        this.fadeId = shot.fades() ? sink.newEntityId() : -1;
    }

    /** Every panel this overlay owns, for the mount and the destroy. */
    int[] ids() {
        List<Integer> ids = new ArrayList<>(4);
        for (int id : new int[]{subtitleId, topBarId, bottomBarId, fadeId}) {
            if (id >= 0) {
                ids.add(id);
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Spawns every panel at the camera and seats them on it. */
    void start(CameraSink sink, List<Player> viewers, int cameraId, CameraPath.Frame at) {
        if (subtitleId >= 0) {
            showing = shot.subtitleAt(0);
            sink.spawnPanel(viewers, subtitleId, at, new Panel(
                    showing == null ? Component.empty() : showing.rendered(),
                    showing == null ? 0 : SUBTITLE_BACKGROUND,
                    SUBTITLE_Y, 0.95f, SUBTITLE_SCALE, SUBTITLE_SCALE, SUBTITLE_WIDTH, false, true));
        }
        if (topBarId >= 0) {
            sink.spawnPanel(viewers, topBarId, at,
                    new Panel(Component.text(" "), BLACK, BAR_EDGE, 1f, 80f, 10f, 200, false, false));
            sink.spawnPanel(viewers, bottomBarId, at,
                    new Panel(Component.text(" "), BLACK, -BAR_EDGE, 1f, 80f, 10f, 200, true, false));
        }
        if (fadeId >= 0) {
            alpha = shot.fadeAlpha(0);
            // Centred by being far larger than any view rather than by
            // knowing how tall the client draws a line.
            sink.spawnPanel(viewers, fadeId, at,
                    new Panel(Component.text(" "), alpha << 24, -4f, 0.8f, 80f, 30f, 200, false, false));
        }
        int[] passengers = ids();
        if (passengers.length > 0) {
            sink.mount(viewers, cameraId, passengers);
        }
    }

    /**
     * Brings the subtitle and the fade to a moment of the shot.
     *
     * <p>Only what changed is sent: a subtitle once per line, a fade in steps
     * of a few percent. Called on every camera sample.
     */
    void update(CameraSink sink, List<Player> viewers, long millis) {
        if (subtitleId >= 0) {
            WorldShot.Subtitle now = shot.subtitleAt(millis);
            if (now != showing) {
                showing = now;
                sink.text(viewers, subtitleId, now == null ? Component.empty() : now.rendered());
                sink.background(viewers, subtitleId, now == null ? 0 : SUBTITLE_BACKGROUND);
            }
        }
        if (fadeId >= 0) {
            int next = shot.fadeAlpha(millis);
            if (Math.abs(next - alpha) >= 8 || (next != alpha && (next == 0 || next == 255))) {
                alpha = next;
                sink.background(viewers, fadeId, next << 24);
            }
        }
    }
}
