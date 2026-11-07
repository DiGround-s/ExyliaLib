package net.exylia.lib.screen;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A layer of sprites a plugin moves from Java, for animated games on a screen: a coin tossed, cards dealt, a ball
 * rolling. The screen's markup holds a {@code <canvas id="table">} where it should be drawn (its size from CSS);
 * the plugin takes the scene by that id from the session and places sprites on it in canvas pixels, top left at
 * 0, 0. The rest of the screen (score, buttons) stays markup.
 *
 * <pre>{@code
 * ScreenScene table = session.scene("table");
 * table.sprite("coin").item(new ItemStack(Material.SUNFLOWER)).size(32, 32).at(84, 120);
 * table.sprite("coin").move(84, 20, 8, Motion.Ease.OUT);      // up, the client gliding it
 * }</pre>
 *
 * <p>Sprites stay where they were put until moved or removed: every change redraws the screen once (changes made
 * in the same tick are drawn together), and only the sprites that changed go out, as one transform each, which the
 * client glides over the given ticks. Each sprite costs what its content costs ({@link Node#item}: 1 display,
 * a picture: 4); they count towards the screen's display budget. Safe from any thread.
 *
 * @since 1.500.0
 */
public final class ScreenScene {

    private final String id;
    private final Runnable redraw;
    private final Map<String, Sprite> sprites = new LinkedHashMap<>();

    /** A scene that redraws with {@code redraw}; a session makes its own with {@link ScreenSession#scene}. */
    public ScreenScene(@NotNull String id, @NotNull Runnable redraw) {
        this.id = id;
        this.redraw = redraw;
    }

    /** The sprite named {@code name}, made (empty, at 0, 0) the first time it is asked for. */
    public synchronized @NotNull Sprite sprite(@NotNull String name) {
        return sprites.computeIfAbsent(name, Sprite::new);
    }

    /** Whether a sprite of that name is on the scene. */
    public synchronized boolean has(@NotNull String name) {
        return sprites.containsKey(name);
    }

    /** Takes a sprite off the scene. */
    public void remove(@NotNull String name) {
        synchronized (this) {
            if (sprites.remove(name) == null) return;
        }
        redraw.run();
    }

    /** Takes every sprite off. */
    public void clear() {
        synchronized (this) {
            if (sprites.isEmpty()) return;
            sprites.clear();
        }
        redraw.run();
    }

    /**
     * The scene as a box of {@code width} by {@code height} pixels, its sprites placed in it: what {@code <canvas>}
     * draws, and what a screen built in Java puts where the scene goes.
     */
    public @NotNull Node node(double width, double height) {
        List<Sprite> now;
        synchronized (this) {
            now = new ArrayList<>(sprites.values());
        }
        Node box = Node.box().size(width, height);
        for (Sprite sprite : now) {
            Node drawn = sprite.draw();
            if (drawn != null) box.add(drawn);
        }
        return box;
    }

    /**
     * One thing on a {@link ScreenScene}: what it shows, where its top left stands, its scale and its layer. The
     * setters return the sprite so a change reads as one line; each redraws the screen.
     */
    public final class Sprite {

        private final String name;
        private Supplier<Node> content;
        private double x, y;
        private double width = -1, height = -1;
        private double scale = 1;
        private int layer;
        private Motion.Transition transition = new Motion.Transition(0, Motion.Ease.LINEAR, false);

        private Sprite(String name) {
            this.name = name;
        }

        /** Shows an item as its flat sprite (16 pixels unless {@link #size sized}). */
        public @NotNull Sprite item(@NotNull ItemStack item) {
            ItemStack copy = item.clone();
            return content(() -> Node.item(copy));
        }

        /** Shows an item as its real 3D model, turning {@code spin} degrees a second ({@link Node#item3d}). */
        public @NotNull Sprite item3d(@NotNull ItemStack item, double spin) {
            ItemStack copy = item.clone();
            return content(() -> Node.item3d(copy, spin));
        }

        /** Shows words, written with {@code Text} ({@code "{highlight}x2"}). */
        public @NotNull Sprite text(@NotNull String text) {
            return content(() -> Node.text(text));
        }

        /** Shows a picture, ARGB pixels row by row ({@link Node#image(int, int, int[])}). */
        public @NotNull Sprite image(int width, int height, int @NotNull [] argb) {
            int[] copy = argb.clone();
            return content(() -> Node.image(width, height, copy));
        }

        /** Shows a plain box in a colour ({@code {primary}}, {@code #rrggbb}): a board's cell, a bar. */
        public @NotNull Sprite rect(double width, double height, @NotNull String colour) {
            size(width, height);
            return content(() -> Node.box().background(colour));
        }

        /** Shows whatever node this makes, built again on each redraw. */
        public @NotNull Sprite content(@NotNull Supplier<Node> content) {
            synchronized (ScreenScene.this) {
                this.content = content;
            }
            redraw.run();
            return this;
        }

        /** Its size in pixels; {@code -1} for its content's own. */
        public @NotNull Sprite size(double width, double height) {
            synchronized (ScreenScene.this) {
                this.width = width;
                this.height = height;
            }
            redraw.run();
            return this;
        }

        /** Puts it at once at {@code x}, {@code y} (its top left, in canvas pixels). */
        public @NotNull Sprite at(double x, double y) {
            return move(x, y, 0, Motion.Ease.LINEAR);
        }

        /** Glides it to {@code x}, {@code y} over {@code ticks}, the client drawing every frame between. */
        public @NotNull Sprite move(double x, double y, int ticks, @NotNull Motion.Ease ease) {
            synchronized (ScreenScene.this) {
                this.x = x;
                this.y = y;
                this.transition = new Motion.Transition(Math.max(0, ticks), ease, false);
            }
            redraw.run();
            return this;
        }

        /** Grows or shrinks it about its centre to {@code scale} over {@code ticks}; 0 hides it. */
        public @NotNull Sprite scale(double scale, int ticks, @NotNull Motion.Ease ease) {
            synchronized (ScreenScene.this) {
                this.scale = scale;
                this.transition = new Motion.Transition(Math.max(0, ticks), ease, false);
            }
            redraw.run();
            return this;
        }

        /** Draws it above ({@code > 0}) or below its siblings, -4 to 8. */
        public @NotNull Sprite layer(int layer) {
            synchronized (ScreenScene.this) {
                this.layer = layer;
            }
            redraw.run();
            return this;
        }

        /** Takes it off the scene. */
        public void remove() {
            ScreenScene.this.remove(name);
        }

        public @NotNull String name() {
            return name;
        }

        public double x() {
            synchronized (ScreenScene.this) {
                return x;
            }
        }

        public double y() {
            synchronized (ScreenScene.this) {
                return y;
            }
        }

        private @Nullable Node draw() {
            Supplier<Node> what;
            double x, y, w, h, s;
            int z;
            Motion.Transition glide;
            synchronized (ScreenScene.this) {
                what = content;
                x = this.x;
                y = this.y;
                w = width;
                h = height;
                s = scale;
                z = layer;
                glide = transition;
            }
            if (what == null) return null;
            Node node = what.get();
            if (w >= 0) node.width(w);
            if (h >= 0) node.height(h);
            // The id keeps its displays from one redraw to the next, so a move is a glide, not a new sprite.
            return node.id("scene:" + id + ":" + name).absolute(y, Double.NaN, Double.NaN, x)
                    .transform(new Motion.Transform(0, 0, s)).transition(glide).layer(z);
        }
    }
}
