package net.exylia.lib.screen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /** What is drawn behind the layout area, to the edges of the view: a palette token or a hex colour. */
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

    public @Nullable Consumer<ScreenSession> onClose() {
        return onClose;
    }
}
