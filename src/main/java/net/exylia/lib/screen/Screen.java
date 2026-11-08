package net.exylia.lib.screen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A full-screen menu drawn in front of the player, with a cursor they move with their mouse.
 *
 * <pre>{@code
 * Screen hub = Screen.of(session -> Node.column(
 *                 Node.text("{letters}Practice").scale(1.4),
 *                 Node.text("{letters}Queues open: " + queues.open()))   // re-read every second
 *         .padding(16).gap(8).background("#17131f"))
 *     .onClose(session -> queues.leaveDraft(session.viewer()));
 * Screens.of(plugin).open(player, hub);
 * }</pre>
 *
 * <p>The render function is called when the screen opens, after every click or scroll handler, after
 * {@link ScreenSession#set}, and once a second, so a value that moves (a countdown, a player count) is simply
 * read inside it. Only what changed is sent. It runs on the viewer's thread and should read memory, never a
 * database.
 *
 * <p>The layout is written for a {@link #WIDTH} by {@link #HEIGHT} pixel area, the part every common screen
 * shape shows at the default field of view; the backdrop reaches past it to the edges of any view.
 *
 * @since 1.483.0
 */
public final class Screen {

    /** The default width of the layout area, in screen pixels. */
    public static final double WIDTH = 400;
    /** The default height of the layout area, in screen pixels. */
    public static final double HEIGHT = 225;
    /** The default backdrop: a deep violet-black, opaque. */
    public static final String BACKDROP = "#0b0912";

    private final Function<ScreenSession, Node> render;
    private double width = WIDTH;
    private double height = HEIGHT;
    private String backdrop = BACKDROP;
    private boolean wheel;
    private Consumer<ScreenSession> onClose;
    private Map<String, Object> state = Map.of();
    private boolean navigate;
    private java.util.function.Predicate<ScreenSession> onBack;
    private Node scene;
    private double parallax;
    private String persistScope;
    private java.util.Set<String> persisted = java.util.Set.of();

    private Screen(Function<ScreenSession, Node> render) {
        this.render = render;
    }

    /** A screen drawn by {@code render}, which returns the root box; it fills the layout area. */
    public static @NotNull Screen of(@NotNull Function<ScreenSession, Node> render) {
        return new Screen(Objects.requireNonNull(render, "render"));
    }

    /** A layout area other than {@link #WIDTH} by {@link #HEIGHT}; drawn at the same pixel size. */
    public @NotNull Screen size(double width, double height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * What is drawn behind the layout area, to the edges of the view: a palette token or a hex colour, or since
     * 1.497.0 a {@code linear-gradient(to bottom, {primary}20, {info}10)}, drawn as up to twelve bands.
     */
    public @NotNull Screen backdrop(@NotNull String colour) {
        this.backdrop = colour;
        return this;
    }

    /**
     * Whether the mouse wheel scrolls. Off by default: the client is then drawn as a spectator, which hides
     * the hand, the crosshair and the hotbar, but a spectator's wheel sends the server nothing. On, the client
     * keeps its real mode, so the wheel works and the hand and crosshair show.
     */
    public @NotNull Screen wheel(boolean wheel) {
        this.wheel = wheel;
        return this;
    }

    /** Runs once when the screen closes, for any reason, on the viewer's thread when they are still online. */
    public @NotNull Screen onClose(@Nullable Consumer<ScreenSession> handler) {
        this.onClose = handler;
        return this;
    }

    /**
     * What every session of this screen starts remembering, as if {@link ScreenSession#set} had been called
     * for each entry before the first render: the open tab, a preselected kit.
     *
     * @since 1.485.0
     */
    public @NotNull Screen state(@NotNull Map<String, ?> initial) {
        this.state = Map.copyOf(initial);
        return this;
    }

    public @NotNull Map<String, Object> state() {
        return state;
    }

    /**
     * State keys each player keeps across sessions and restarts (markup {@code persist="player"}), stored with
     * the player's data under {@code scope}: the last tab or filter they picked comes back the next time. A
     * value given to {@link #state(Map)} still wins over the stored one.
     *
     * @since 1.503.0
     */
    public @NotNull Screen persist(@NotNull String scope, @NotNull java.util.Collection<String> keys) {
        this.persistScope = Objects.requireNonNull(scope, "scope");
        this.persisted = java.util.Set.copyOf(keys);
        return this;
    }

    /** The scope persisted keys are stored under, or {@code null} when none are. @since 1.503.0 */
    public @Nullable String persistScope() {
        return persistScope;
    }

    /** The state keys kept per player. @since 1.503.0 */
    public @NotNull java.util.Set<String> persisted() {
        return persisted;
    }

    public @NotNull Function<ScreenSession, Node> render() {
        return render;
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public @NotNull String backdrop() {
        return backdrop;
    }

    public boolean wheel() {
        return wheel;
    }

    /**
     * Keyboard navigation: W, A, S and D move the cursor to the nearest clickable box that way, Space clicks it
     * ({@code <screen keys="navigate">}). Needs a 1.21.2+ client, which sends its keys; older ones keep the mouse.
     * While it is on, {@link Node#key} bindings are not used.
     *
     * @since 1.496.0
     */
    public @NotNull Screen navigate(boolean navigate) {
        this.navigate = navigate;
        return this;
    }

    /** @since 1.496.0 */
    public boolean navigate() {
        return navigate;
    }

    /**
     * What going back does once no dialog is open (the {@code back} action, {@link ScreenSession#back()}): go back
     * to where the player came from, answering whether it did. Shift itself always closes the screen (1.514.0).
     *
     * @since 1.496.0
     */
    public @NotNull Screen onBack(@Nullable java.util.function.Predicate<ScreenSession> handler) {
        this.onBack = handler;
        return this;
    }

    /** @since 1.496.0 */
    public @Nullable java.util.function.Predicate<ScreenSession> onBack() {
        return onBack;
    }

    /**
     * A picture behind everything, covering the whole view (a 16:9 window and some): a starfield, an arena, a
     * soft glow. Made with {@link Node#image}; 64 by 36 is plenty, every pixel is a glyph. Its clear pixels show
     * the backdrop, a gradient one included. Four displays, sent once. {@code null} for none.
     *
     * @since 1.497.0
     */
    public @NotNull Screen scene(@Nullable Node picture) {
        if (picture != null && picture.pixels() == null) throw new IllegalArgumentException("A scene is a Node.image");
        this.scene = picture;
        return this;
    }

    /** @since 1.497.0 */
    public @Nullable Node scene() {
        return scene;
    }

    /**
     * How many pixels the {@link #scene} drifts against the cursor, so it reads as far behind the screen; 0 (the
     * default) keeps it still. 2 to 4 is subtle. Each cursor move then also moves the scene: four transforms.
     *
     * @since 1.497.0
     */
    public @NotNull Screen parallax(double pixels) {
        this.parallax = Math.max(0, Math.min(16, pixels));
        return this;
    }

    /** @since 1.497.0 */
    public double parallax() {
        return parallax;
    }

    public @Nullable Consumer<ScreenSession> onClose() {
        return onClose;
    }
}
