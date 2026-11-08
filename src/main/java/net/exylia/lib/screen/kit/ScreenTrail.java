package net.exylia.lib.screen.kit;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.ScreenScene;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A line on a {@link ScreenScene} as a run of dots: a crash curve, a price. A sprite does not rotate, so it is dots
 * rather than strokes. Hand it the points it should show; the dots glide there, so an axis that rescales just flows.
 * Costs one display per dot, one transform per dot per call.
 *
 * @since 1.532.0
 */
public final class ScreenTrail {

    private final ScreenScene scene;
    private final String name;
    private final int dots;
    private final double size;
    private int shown;

    /**
     * @param name what its sprites are named after, unique on the scene
     * @param dots how many at most: more points than this are thinned evenly, keeping the last
     * @param size a dot across, in pixels
     */
    public ScreenTrail(@NotNull ScreenScene scene, @NotNull String name, int dots, double size) {
        this.scene = scene;
        this.name = name;
        this.dots = dots;
        this.size = size;
    }

    /**
     * Shows {@code points}, each {x, y} in canvas pixels, oldest first, in {@code colour}, over {@code ticks}.
     * The colour changes only if the caller asks: a redraw of each dot's content is sent only when it differs.
     */
    public void draw(@NotNull List<double[]> points, @NotNull String colour, int ticks) {
        int count = Math.min(dots, points.size());
        for (int dot = 0; dot < count; dot++) {
            // Spread over the whole line, the last dot on its last point.
            double[] point = points.get(count == 1 ? points.size() - 1 : (int) Math.round(dot * (points.size() - 1.0) / (count - 1)));
            boolean fresh = !scene.has(name + dot);
            ScreenScene.Sprite sprite = scene.sprite(name + dot);
            if (fresh) sprite.rect(size, size, colour);
            sprite.move(point[0] - size / 2, point[1] - size / 2, fresh ? 0 : ticks, Motion.Ease.LINEAR);
        }
        for (int dot = count; dot < shown; dot++) scene.remove(name + dot);
        shown = count;
    }

    /** All its dots take {@code colour}: the curve goes red when it crashes. */
    public void colour(@NotNull String colour) {
        for (int dot = 0; dot < shown; dot++) scene.sprite(name + dot).rect(size, size, colour);
    }

    /** Takes it off the scene. */
    public void clear() {
        for (int dot = 0; dot < shown; dot++) scene.remove(name + dot);
        shown = 0;
    }
}
