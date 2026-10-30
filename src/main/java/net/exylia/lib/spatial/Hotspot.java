package net.exylia.lib.spatial;

import net.exylia.lib.stage.StageButton;
import net.exylia.lib.stage.StagePlane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Something a player can point at on an in-world screen: a rectangle on a {@link StagePlane}, every
 * cell of a plane's grid, or a {@link StageButton}. Registered on a {@link SpatialPointer}, which
 * decides every couple of ticks which one is under the crosshair, plays the hover sound once per
 * change, and hands it the clicks and the wheel.
 *
 * <pre>{@code
 * Hotspot stand = pointer.add(Hotspot.rect(plane, 0.9, -0.3, 0.6, 0.2)
 *         .onHover(on -> standButton.hover(on))
 *         .onPress(click -> stand()));
 * Hotspot board = pointer.add(Hotspot.cells(plane)
 *         .onCell(cell -> view.highlight(cell))          // -1 when the crosshair leaves the grid
 *         .onPress(click -> play(click.cell())));
 * pointer.add(Hotspot.of(again).onPress(click -> playAgain()));   // lights and squashes by itself
 * }</pre>
 *
 * <p>When two overlap, the higher {@link #priority} wins, then the one nearer the eyes: a side panel
 * turned in front of the board takes the crosshair before the board behind it. A disabled hotspot is
 * passed over (and loses the hover if it had it). Used from the pointer's thread, like the pointer.
 *
 * @since 1.406.0
 */
public final class Hotspot {

    private final StagePlane plane;
    private final double across;
    private final double up;
    private final double width;
    private final double height;
    private final boolean cells;
    private final @Nullable StageButton button;
    private int priority;
    private boolean enabled = true;
    private boolean silent;
    private @Nullable Consumer<Boolean> hover;
    private @Nullable IntConsumer cell;
    private @Nullable Consumer<SpatialClick> press;
    private @Nullable IntConsumer scroll;
    /** The cell under the crosshair: 0 on a rectangle, the index on a grid, -1 when not hovered. */
    int current = -1;

    private Hotspot(StagePlane plane, double across, double up, double width, double height, boolean cells,
                    @Nullable StageButton button) {
        this.plane = Objects.requireNonNull(plane, "plane");
        this.across = across;
        this.up = up;
        this.width = width;
        this.height = height;
        this.cells = cells;
        this.button = button;
    }

    /** A rectangle centred {@code across} and {@code up} from the plane's middle. */
    public static @NotNull Hotspot rect(@NotNull StagePlane plane, double across, double up, double width, double height) {
        return new Hotspot(plane, across, up, width, height, false, null);
    }

    /** Every cell of the plane's grid ({@link StagePlane#pick}); the gap between cells misses. */
    public static @NotNull Hotspot cells(@NotNull StagePlane plane) {
        return new Hotspot(plane, 0, 0, 0, 0, true, null);
    }

    /**
     * A {@link StageButton}: it lights while hovered and squashes when pressed by itself, and plays its
     * own theme's sounds, so the pointer plays none for it.
     */
    public static @NotNull Hotspot of(@NotNull StageButton button) {
        Hotspot spot = new Hotspot(button.plane(), 0, 0, 0, 0, false, button);
        spot.silent = true;
        return spot;
    }

    /** Runs with {@code true} when the crosshair lands on it and {@code false} when it leaves. */
    public @NotNull Hotspot onHover(@Nullable Consumer<Boolean> hover) {
        this.hover = hover;
        return this;
    }

    /** Runs with the cell under the crosshair whenever it changes, {@code -1} when it leaves. */
    public @NotNull Hotspot onCell(@Nullable IntConsumer cell) {
        this.cell = cell;
        return this;
    }

    /** Runs when it is clicked while under the crosshair. */
    public @NotNull Hotspot onPress(@Nullable Consumer<SpatialClick> press) {
        this.press = press;
        return this;
    }

    /** Runs with -1 or +1 for each notch of the wheel while it is under the crosshair. */
    public @NotNull Hotspot onScroll(@Nullable IntConsumer scroll) {
        this.scroll = scroll;
        return this;
    }

    /** Which wins when two overlap: the higher; equal ones go to the nearer. Default 0. */
    public @NotNull Hotspot priority(int priority) {
        this.priority = priority;
        return this;
    }

    /** Whether it answers at all; a disabled one loses the hover on the next poll. */
    public @NotNull Hotspot enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /** Whether the pointer plays no hover or press sound for it (the caller plays its own). */
    public @NotNull Hotspot silent(boolean silent) {
        this.silent = silent;
        return this;
    }

    public boolean enabled() {
        return enabled;
    }

    public int priority() {
        return priority;
    }

    public boolean silent() {
        return silent;
    }

    /** Whether it is under the crosshair now. */
    public boolean hovered() {
        return current >= 0;
    }

    /** The cell under the crosshair, or {@code -1}. */
    public int cell() {
        return current;
    }

    public @NotNull StagePlane plane() {
        return plane;
    }

    boolean scrolls() {
        return scroll != null;
    }

    /**
     * Where a ray from the eyes lands on it.
     *
     * @return {cell, distance from the eyes}, or {@code null} on a miss
     */
    double @Nullable [] pick(double ex, double ey, double ez, double dx, double dy, double dz) {
        StagePlane.Hit hit = plane.hit(ex, ey, ez, dx, dy, dz);
        if (hit == null) return null;
        int index;
        if (button != null) index = button.contains(hit) ? 0 : -1;
        else if (cells) index = plane.pick(hit);
        else index = hit.inside(across, up, width, height) ? 0 : -1;
        if (index < 0) return null;
        var point = plane.point(hit.across(), hit.up(), 0);
        double x = point.getX() - ex;
        double y = point.getY() - ey;
        double z = point.getZ() - ez;
        return new double[]{index, Math.sqrt(x * x + y * y + z * z)};
    }

    /** The crosshair moved onto {@code index}, or off it with -1. */
    void hover(int index) {
        if (index == current) return;
        boolean was = current >= 0;
        current = index;
        boolean now = index >= 0;
        if (button != null && was != now) button.hover(now);
        if (hover != null && was != now) hover.accept(now);
        if (cell != null) cell.accept(index);
    }

    void press(SpatialClick click) {
        if (button != null) button.press();
        if (press != null) press.accept(click);
    }

    void scroll(int notch) {
        if (scroll != null) scroll.accept(notch);
    }
}
