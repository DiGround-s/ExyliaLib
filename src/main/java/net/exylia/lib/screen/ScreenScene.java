package net.exylia.lib.screen;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleConsumer;
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
 * <p>The motion kit (since 1.531.0) is what games share: {@link #timeline()} to run steps in order, {@link Sprite#flip}
 * and {@link Sprite#pop}, {@link #shake} and {@link Sprite#shake}, {@link #burst} and {@link #countUp}. Each is a few
 * glides of what a sprite already does, so a reel, a card or a wheel is more of the same. A player on reduced motion
 * gets the end of each at once; past the screen's motion ceiling, bursts and shakes are left out first.
 *
 * <p>Cards, dice, reels, wheels and curves are built from these in {@code net.exylia.lib.screen.kit}. A sprite does not
 * rotate; a wheel's segments go round upright ({@code ScreenOrbit}).
 *
 * @since 1.500.0
 */
public final class ScreenScene {

    /** What a scene needs of the screen it is drawn on. @since 1.531.0 */
    public interface Host {

        /** Draws the scene again, once however many changes a tick makes. */
        void redraw();

        /** Runs {@code step} on the viewer's thread {@code ticks} from now, unless the screen closed. */
        void later(int ticks, @NotNull Runnable step);

        /** Whether the viewer asked for reduced motion. */
        boolean reduced();

        /** Whether the screen is under its motion ceiling this second: past it, decoration waits. */
        boolean spare();
    }

    /** What a burst's sprites are named after: the screen counts them against its motion ceiling. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static final String SHARD = "~shard";

    private final String id;
    private final Host host;
    private final Runnable redraw;
    private final Map<String, Sprite> sprites = new LinkedHashMap<>();
    /** Where a {@link #shake} has the whole scene now; 2-tick glides while it shakes. */
    private double joltX, joltY;
    private boolean shaking;
    private int shards;

    /**
     * A scene that redraws with {@code redraw} and runs the steps of its timelines at once, in order (a test double, or
     * a screen that cannot draw); a session makes its own with {@link ScreenSession#scene}.
     */
    public ScreenScene(@NotNull String id, @NotNull Runnable redraw) {
        this(id, new Host() {
            @Override
            public void redraw() {
                redraw.run();
            }

            @Override
            public void later(int ticks, @NotNull Runnable step) {
                step.run();
            }

            @Override
            public boolean reduced() {
                return false;
            }

            @Override
            public boolean spare() {
                return true;
            }
        });
    }

    /** A scene on {@code host}. @since 1.531.0 */
    public ScreenScene(@NotNull String id, @NotNull Host host) {
        this.id = id;
        this.host = host;
        this.redraw = host::redraw;
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
        synchronized (this) {
            if (joltX != 0 || joltY != 0) box.transform(Motion.Transform.translate(joltX, joltY));
        }
        for (Sprite sprite : now) {
            Node drawn = sprite.draw();
            if (drawn != null) box.add(drawn);
        }
        return box;
    }

    // ------------------------------------------------------------------
    // Motion kit (1.531.0)
    // ------------------------------------------------------------------

    /** Whether the viewer asked for reduced motion: a kit that draws many steps jumps to the end. @since 1.532.0 */
    public boolean reducedMotion() {
        return host.reduced();
    }

    /** A new timeline: add steps, then {@link Timeline#play}. */
    public @NotNull Timeline timeline() {
        return new Timeline();
    }

    /**
     * The whole scene jolts about its place for {@code ticks}, {@code pixels} at first and dying away: a blast. Every
     * display of the scene gets a transform per step, so it is the dearest thing here: past the motion ceiling it stops
     * early, back in its place.
     */
    public void shake(double pixels, int ticks) {
        if (host.reduced() || !host.spare()) return;
        Timeline jolts = timeline();
        int steps = Math.max(1, ticks / 2);
        for (int step = 0; step < steps; step++) {
            double left = pixels * (steps - step) / steps;
            double x = step % 2 == 0 ? left : -left;
            double y = step % 4 < 2 ? left * 0.6 : -left * 0.6;
            jolts.at(step * 2, () -> {
                if (host.spare()) jolt(x, y);
                else jolt(0, 0);
            });
        }
        jolts.at(steps * 2, () -> jolt(0, 0));
        jolts.play();
    }

    private void jolt(double x, double y) {
        synchronized (this) {
            joltX = x;
            joltY = y;
            shaking = x != 0 || y != 0;
        }
        redraw.run();
    }

    /**
     * {@code count} small squares fly out from {@code x}, {@code y} up to {@code radius} pixels, shrinking away over
     * {@code ticks}, then are gone: one display each, for as long as they fly, and left out first over the display
     * budget. Never for what the player must read: past the motion ceiling it does nothing.
     *
     * @param colours what the squares are drawn in, in turn ({@code {success}}, {@code #rrggbb})
     */
    public void burst(double x, double y, int count, double radius, int ticks, @NotNull String... colours) {
        if (count <= 0 || colours.length == 0 || host.reduced() || !host.spare()) return;
        List<Sprite> flying = new ArrayList<>(count);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double start = random.nextDouble(Math.PI * 2);
        double[][] to = new double[count][2];
        for (int i = 0; i < count; i++) {
            double size = random.nextDouble(2, 3.5);
            double angle = start + Math.PI * 2 * i / count + random.nextDouble(-0.25, 0.25);
            double reach = radius * random.nextDouble(0.65, 1);
            to[i][0] = x + Math.cos(angle) * reach - size / 2;
            to[i][1] = y + Math.sin(angle) * reach - size / 2;
            String name;
            synchronized (this) {
                name = SHARD + shards++;
            }
            Sprite shard = sprite(name).rect(size, size, colours[i % colours.length]).at(x - size / 2, y - size / 2).layer(8);
            shard.decoration();
            flying.add(shard);
        }
        // A display only glides from a transform the client already has: the flight starts a tick after it appears.
        timeline().at(1, () -> {
            for (int i = 0; i < flying.size(); i++) flying.get(i).to(to[i][0], to[i][1], 0.25, ticks, Motion.Ease.OUT);
        }).at(ticks + 1, () -> flying.forEach(Sprite::remove)).play();
    }

    /**
     * A number counting from {@code from} to {@code to} over {@code ticks}, fast at first: {@code show} gets each value,
     * every two ticks, on the viewer's thread, and {@code to} exactly at the end. It draws nothing itself: {@code show}
     * sets a value the markup reads (a {@code session.set}) or a sprite's text. Cancel the timeline to start another.
     */
    public @NotNull Timeline countUp(double from, double to, int ticks, @NotNull DoubleConsumer show) {
        Timeline steps = timeline();
        int count = host.reduced() ? 0 : Math.max(0, ticks / 2 - 1);
        for (int step = 0; step < count; step++) {
            int at = step * 2;
            double value = from + (to - from) * Motion.Ease.OUT.at((step + 1) / (count + 1.0));
            steps.at(at, () -> show.accept(value));
        }
        steps.at(count * 2, () -> show.accept(to));
        return steps.play();
    }

    /**
     * Steps run in order on the viewer's thread, each some ticks after the timeline started; what one step does in a
     * tick is drawn together. A closed screen runs no more of them.
     *
     * <pre>{@code
     * table.timeline()
     *         .at(0, () -> tile.flip(6, () -> tile.rect(36, 36, "{error}")))
     *         .after(6, () -> table.burst(18, 18, 12, 30, 6, "{error}"))
     *         .play();
     * }</pre>
     *
     * @since 1.531.0
     */
    public final class Timeline {

        private final TreeMap<Integer, List<Runnable>> steps = new TreeMap<>();
        private volatile boolean cancelled;
        private int last;

        private Timeline() {
        }

        /** {@code step} runs {@code tick} ticks after {@link #play}; 0 runs it within {@link #play} itself. */
        public @NotNull Timeline at(int tick, @NotNull Runnable step) {
            last = Math.max(0, tick);
            steps.computeIfAbsent(last, key -> new ArrayList<>()).add(step);
            return this;
        }

        /** {@code step} runs {@code ticks} after the step added last. */
        public @NotNull Timeline after(int ticks, @NotNull Runnable step) {
            return at(last + ticks, step);
        }

        /** The tick of the last step added: how long it runs. */
        public int length() {
            return last;
        }

        /** Starts it. */
        public @NotNull Timeline play() {
            steps.forEach((tick, group) -> {
                Runnable all = () -> {
                    if (cancelled) return;
                    group.forEach(Runnable::run);
                };
                if (tick == 0) all.run();
                else host.later(tick, all);
            });
            return this;
        }

        /** Runs no more of its steps. */
        public void cancel() {
            cancelled = true;
        }
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
        private double squeeze = 1;
        private double dx, dy;
        private boolean decoration;
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

        /**
         * Moves and scales it together, one glide: the only way to do both on different terms from the last call.
         *
         * @since 1.531.0
         */
        public @NotNull Sprite to(double x, double y, double scale, int ticks, @NotNull Motion.Ease ease) {
            synchronized (ScreenScene.this) {
                this.x = x;
                this.y = y;
                this.scale = scale;
                this.transition = new Motion.Transition(Math.max(0, ticks), ease, false);
            }
            redraw.run();
            return this;
        }

        /** Narrows it across to {@code squeeze} of its width about its centre over {@code ticks}; 0 is its edge. @since 1.531.0 */
        public @NotNull Sprite squeeze(double squeeze, int ticks, @NotNull Motion.Ease ease) {
            synchronized (ScreenScene.this) {
                this.squeeze = squeeze;
                this.transition = new Motion.Transition(Math.max(0, ticks), ease, false);
            }
            redraw.run();
            return this;
        }

        /**
         * Turns it over in {@code ticks}: it narrows to its edge, {@code face} changes what it shows while nothing of it
         * can be seen, and it widens again. The face is decided by the caller and sent only now, so hidden information
         * stays on the server until the flip.
         *
         * @since 1.531.0
         */
        public @NotNull Sprite flip(int ticks, @NotNull Runnable face) {
            if (host.reduced()) {
                face.run();
                return this;
            }
            int half = Math.max(1, ticks / 2);
            squeeze(0, half, Motion.Ease.LINEAR);
            host.later(half, () -> {
                face.run();
                squeeze(1, Math.max(1, ticks - half), Motion.Ease.LINEAR);
            });
            return this;
        }

        /** Swells to {@code peak} and settles back to its size in {@code ticks}: a piece landing, a number changing. @since 1.531.0 */
        public @NotNull Sprite pop(double peak, int ticks) {
            if (host.reduced()) return this;
            int half = Math.max(1, ticks / 2);
            scale(peak, half, Motion.Ease.OUT);
            host.later(half, () -> scale(1, Math.max(1, ticks - half), Motion.Ease.IN_OUT));
            return this;
        }

        /** It alone jolts about its place, {@code pixels} at first and dying away, for {@code ticks}. @since 1.531.0 */
        public @NotNull Sprite shake(double pixels, int ticks) {
            if (host.reduced() || !host.spare()) return this;
            Timeline jolts = new Timeline();
            int steps = Math.max(1, ticks / 2);
            for (int step = 0; step < steps; step++) {
                double left = pixels * (steps - step) / steps;
                double to = step % 2 == 0 ? left : -left;
                jolts.at(step * 2, () -> nudge(to, to * 0.5));
            }
            jolts.at(steps * 2, () -> nudge(0, 0));
            jolts.play();
            return this;
        }

        private void nudge(double x, double y) {
            synchronized (ScreenScene.this) {
                dx = x;
                dy = y;
                transition = new Motion.Transition(2, Motion.Ease.LINEAR, false);
            }
            redraw.run();
        }

        private void decoration() {
            synchronized (ScreenScene.this) {
                decoration = true;
            }
            redraw.run();
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
            double x, y, w, h, s, sq, ox, oy;
            int z;
            boolean low;
            Motion.Transition glide;
            synchronized (ScreenScene.this) {
                what = content;
                x = this.x;
                y = this.y;
                w = width;
                h = height;
                s = scale;
                sq = squeeze;
                ox = dx;
                oy = dy;
                z = layer;
                low = decoration;
                // While the scene shakes every sprite follows it in the same short glides, whatever it did last.
                glide = shaking ? new Motion.Transition(2, Motion.Ease.LINEAR, false) : transition;
            }
            if (what == null) return null;
            Node node = what.get();
            if (w >= 0) node.width(w);
            if (h >= 0) node.height(h);
            // The id keeps its displays from one redraw to the next, so a move is a glide, not a new sprite.
            node.id("scene:" + id + ":" + name).absolute(y, Double.NaN, Double.NaN, x)
                    .transform(new Motion.Transform(ox, oy, s, sq)).transition(glide).layer(z)
                    // A sprite appears where it is put: its own moves are what a game draws, not an arrival on top.
                    .enter(Motion.Tween.NONE);
            return low ? node.decoration(true) : node;
        }
    }
}
