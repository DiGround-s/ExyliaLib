package net.exylia.lib.screen.kit;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.ScreenScene;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * A reel on a {@link ScreenScene}: a column of symbols running down its window and landing on chosen ones. Symbols shrink
 * away at both ends of the window, since a scene has no clip. What the reel lands on is decided before it spins and is
 * shown only as it comes in; everything before that comes from {@code decoy}.
 *
 * <pre>{@code
 * ScreenReel<Symbol> reel = new ScreenReel<>(board, "reel0", 10, 8, 40, 3, (sprite, symbol) -> sprite.size(32, 32).item(symbol.item()));
 * reel.rest(List.of(a, b, c));
 * reel.spin(30, this::anySymbol, List.of(x, y, z), this::landed);
 * }</pre>
 *
 * Costs {@code rows + 2} sprites, each one transform per step, a step every 2 to 5 ticks.
 *
 * @param <T> what a symbol is
 * @since 1.532.0
 */
public final class ScreenReel<T> {

    private final ScreenScene scene;
    private final List<ScreenScene.Sprite> sprites = new ArrayList<>();
    private final double x, y, pitch;
    private final int rows;
    private final BiConsumer<ScreenScene.Sprite, T> paint;
    /** Each sprite's place: 0 parked above the window, 1 to rows on view, rows + 1 gone below. */
    private final int[] places;
    private ScreenScene.Timeline spinning;

    /**
     * @param name  what its sprites are named after, unique on the scene
     * @param x     the left of its column, in canvas pixels
     * @param y     the top of its first row
     * @param pitch from one row to the next
     * @param rows  how many symbols show at rest
     * @param paint makes a sprite show a symbol, its size set
     */
    public ScreenReel(@NotNull ScreenScene scene, @NotNull String name, double x, double y, double pitch, int rows,
                      @NotNull BiConsumer<ScreenScene.Sprite, T> paint) {
        this.scene = scene;
        this.x = x;
        this.y = y;
        this.pitch = pitch;
        this.rows = rows;
        this.paint = paint;
        this.places = new int[rows + 2];
        for (int i = 0; i < places.length; i++) {
            sprites.add(scene.sprite(name + "_" + i));
            places[i] = i;
        }
    }

    private double top(int place) {
        return y + (place - 1) * pitch;
    }

    private static double size(int place, int rows) {
        return place >= 1 && place <= rows ? 1 : 0;
    }

    /** Draws it stopped on {@code symbols}, top to bottom, as many as it has rows. */
    public void rest(@NotNull List<T> symbols) {
        if (spinning != null) spinning.cancel();
        for (int i = 0; i < places.length; i++) {
            places[i] = i;
            ScreenScene.Sprite sprite = sprites.get(i);
            paint.accept(sprite, symbols.get(i >= 1 && i <= rows ? i - 1 : 0));
            sprite.to(x, top(i), size(i, rows), 0, Motion.Ease.LINEAR);
        }
    }

    /**
     * Runs the reel for about {@code ticks} and lands it on {@code landing}, top to bottom. It starts and runs at a step
     * every 2 ticks, slowing over the last steps.
     *
     * @param landed runs as it stops; {@code null} for nothing
     * @return the timeline, to cancel it with the round
     */
    public @NotNull ScreenScene.Timeline spin(int ticks, @NotNull Supplier<T> decoy, @NotNull List<T> landing, Runnable landed) {
        if (spinning != null) spinning.cancel();
        ScreenScene.Timeline run = scene.timeline();
        if (scene.reducedMotion()) {
            rest(landing);
            if (landed != null) run.at(Math.max(1, ticks / 3), landed);
            return spinning = run.play();
        }
        List<Integer> lengths = lengths(ticks, rows);
        int at = 0;
        for (int step = 0; step < lengths.size(); step++) {
            int left = lengths.size() - 1 - step;
            int length = lengths.get(step);
            T symbol = left >= 1 && left <= rows ? landing.get(left - 1) : null;
            run.at(at, () -> step(length, symbol != null ? symbol : decoy.get()));
            at += length;
        }
        if (landed != null) run.at(at, landed);
        return spinning = run.play();
    }

    /** Every sprite one place on; the one past the bottom jumps back above the top, showing its next symbol. */
    private void step(int length, T next) {
        for (int i = 0; i < places.length; i++) {
            ScreenScene.Sprite sprite = sprites.get(i);
            int place = (places[i] + 1) % places.length;
            places[i] = place;
            if (place == 0) {
                paint.accept(sprite, next);
                sprite.to(x, top(0), 0, 0, Motion.Ease.LINEAR);
            } else {
                sprite.to(x, top(place), size(place, rows), length, Motion.Ease.LINEAR);
            }
        }
    }

    /** How long each step takes: 2 ticks a step, slowing over the last ones, that land in about {@code ticks}. */
    static @NotNull List<Integer> lengths(int ticks, int rows) {
        List<Integer> tail = new ArrayList<>();
        // The last steps, every one of them a symbol coming in, and two more before.
        int[] slowing = {2, 3, 3, 4, 5};
        int spent = 0;
        for (int i = 0; i < rows + 3; i++) {
            int length = slowing[Math.min(slowing.length - 1, i * slowing.length / (rows + 3))];
            tail.add(length);
            spent += length;
        }
        List<Integer> all = new ArrayList<>();
        for (int left = ticks - spent; left > 0; left -= 2) all.add(2);
        all.addAll(tail);
        return all;
    }
}
