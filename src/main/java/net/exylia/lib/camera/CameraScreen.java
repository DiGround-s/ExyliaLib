package net.exylia.lib.camera;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/**
 * What a plugin draws over a camera's view while it plays: a colour over the
 * whole screen, a title card, one line at the foot, a small caption at the top
 * and the letterbox bars.
 *
 * <p>Everything rides the camera the way a world shot's subtitles do, so it
 * stays put however fast the view moves, and nothing here needs a resource
 * pack. Changes are sent on the camera's next frame, and only what changed.
 *
 * <pre>{@code
 * CameraScreen screen = playback.screen(viewer);
 * if (screen != null) {
 *     screen.fill(0xFF000000, 0);            // black, at once
 *     screen.fill(0x00FFFFFF, 400);          // then clear again over 0.4s
 *     screen.title(title, 2.5, 150);         // lands from 2.5 times its size
 *     screen.line(Component.text("Steve · Iron Sword"), 40);
 * }
 * }</pre>
 *
 * <h2>Layers</h2>
 * From the back: the bars, then the fill, then the line and the caption, then
 * the title. A black fill therefore hides the scene but not the title over it,
 * and glowing bodies still draw their outline on top of it, which is what an
 * impact frame is made of.
 *
 * @since 1.649.0
 */
public interface CameraScreen {

    /**
     * Covers the whole view in one colour, easing from what it shows now.
     *
     * @param argb   the colour, alpha first; an alpha of 0 clears the screen
     * @param millis how long the change takes; 0 is at once
     */
    void fill(int argb, long millis);

    /**
     * A title card in the middle of the view.
     *
     * @param text   what it says, or {@code null} to take it away
     * @param from   how many times its size it starts at before landing; 1 for
     *               no movement
     * @param millis how long it takes to land
     */
    void title(@Nullable Component text, double from, long millis);

    /**
     * One line at the foot of the view, written out a few characters at a time.
     *
     * @param text      what it says, or {@code null} to take it away
     * @param perSecond characters written a second; 0 shows it whole
     */
    void line(@Nullable Component text, int perSecond);

    /**
     * A small line at the top of the view, for a running figure such as a
     * distance. Shown whole and replaced as often as it is called.
     *
     * @param text what it says, or {@code null} to take it away
     */
    void caption(@Nullable Component text);

    /**
     * The black bars above and below the view.
     *
     * @param shown whether they are drawn
     */
    void letterbox(boolean shown);

    /** Takes everything off: no colour, no title, no lines, no bars. */
    default void clear() {
        fill(0, 0);
        title(null, 1, 0);
        line(null, 0);
        caption(null);
        letterbox(false);
    }
}
