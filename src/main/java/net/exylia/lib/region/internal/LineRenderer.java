package net.exylia.lib.region.internal;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Displays;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.internal.BlockPalette;
import net.exylia.lib.text.Colors;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Draws an outline's bars for one viewer, as block displays only that viewer has.
 *
 * <p>Each bar is a block display stretched along it, as thin as its tone says, lit at full
 * brightness and glowing in its colour, so it reads at night and through the wall it runs
 * behind. They are sent through the display module, which owns their lives: a plugin that is
 * disabled, or a server that stops, takes them off the client with everything else it drew.
 *
 * <h2>Only what changed is sent</h2>
 * {@link #draw} is handed the whole outline every frame and compares it with what is on screen.
 * A bar that is still there is left alone; one that moved is glided to its new place by the
 * client in a couple of frames; only the difference in count is spawned or removed. A still
 * outline costs nothing after its first frame, and one that follows a cursor costs a pose per
 * moving bar.
 *
 * <h2>Only what is near is drawn</h2>
 * Bars are clipped to a box around the viewer before anything else. A client draws a display
 * only while the chunk it stands in is loaded and it is within the display's range, and a bar
 * standing at the middle of a two-thousand-block edge is neither. Clipped, every bar stands
 * somewhere the viewer can see it, and an outline as big as a claim costs what the part of it
 * near the viewer costs. The box moves in steps of {@link #WINDOW_STEP} so walking does not
 * reshape the outline every frame.
 */
final class LineRenderer {

    /** The most bars one outline may put on one viewer's screen. */
    static final int MAX_LINES = 768;

    /**
     * How long a bar may live, which is the display module's own ceiling.
     *
     * <p>Not a duration anybody waits for: a bar is removed when its outline is, and this only
     * bounds one that something forgot.
     */
    private static final long LIFE_MILLIS = 24L * 60L * 60L * 1000L;

    /** How long a moved bar takes to glide to its new place. */
    private static final long GLIDE_MILLIS = 100L;

    /**
     * How far a bar may move from where it was spawned before it is spawned again instead.
     *
     * <p>A display is culled by where it was spawned, not by where it is drawn, so a bar glided
     * across half the box would vanish for a viewer standing next to it.
     */
    private static final double RESPAWN_DISTANCE = 24.0;

    /** How far a bar is drawn, as a multiple of the client's entity distance. */
    private static final float VIEW_RANGE = 2.5f;

    /** How far the clip box jumps when the viewer leaves the cell it was centred on. */
    private static final double WINDOW_STEP = 16.0;

    private final Plugin owner;
    private final int edgeColor;
    private final Map<Integer, DisplayModel> models = new HashMap<>();
    private List<Shown> shown = List.of();
    private boolean closed;

    /**
     * @param owner     the plugin whose displays these are
     * @param edgeColor the colour of {@link Line.Tone#EDGE} bars as {@code 0xRRGGBB}, or a
     *                  negative number for the palette's
     */
    LineRenderer(@NotNull Plugin owner, int edgeColor) {
        this.owner = owner;
        this.edgeColor = edgeColor;
    }

    /** Whether this server can draw lines at all; without it the outline is drawn with particles. */
    static boolean available() {
        return Displays.isSupported();
    }

    /**
     * Puts an outline on screen, sending only what differs from the last one.
     *
     * <p>On the viewer's thread, so where they stand is read where it may be.
     *
     * @param viewer who sees it
     * @param lines  the whole outline, in a stable order
     * @param reach  how far from the viewer bars are drawn, in blocks
     */
    synchronized void draw(@NotNull Player viewer, @NotNull List<Line> lines, double reach) {
        if (closed) {
            return;
        }
        List<Line> visible = clipped(viewer.getLocation(), lines, reach);

        // Bars that did not change keep their display.
        Map<Line, ArrayDeque<Shown>> unchanged = new HashMap<>();
        for (Shown bar : shown) {
            unchanged.computeIfAbsent(bar.line(), ignored -> new ArrayDeque<>()).add(bar);
        }
        List<Shown> next = new ArrayList<>(visible.size());
        List<Line> moved = new ArrayList<>();
        for (Line line : visible) {
            ArrayDeque<Shown> same = unchanged.get(line);
            Shown kept = same == null ? null : same.poll();
            if (kept != null) {
                next.add(kept);
            } else {
                moved.add(line);
            }
        }

        // The rest are moved onto the bars that did, then spawned or removed for the difference.
        List<Shown> spare = new ArrayList<>();
        for (ArrayDeque<Shown> left : unchanged.values()) {
            spare.addAll(left);
        }
        Iterator<Shown> reuse = spare.iterator();
        World world = viewer.getWorld();
        for (Line line : moved) {
            Shown previous = reuse.hasNext() ? reuse.next() : null;
            if (previous != null) {
                reuse.remove();
            }
            next.add(place(viewer, world, line, previous));
        }
        for (Shown left : spare) {
            left.remove();
        }
        shown = next;
    }

    /** Takes every bar off the viewer's screen. Safe from any thread, and safe twice. */
    synchronized void clear() {
        for (Shown bar : shown) {
            bar.remove();
        }
        shown = List.of();
    }

    /**
     * Takes every bar off the screen for good.
     *
     * <p>A frame already running when its outline ended must not put bars back after the clear
     * that was meant to remove them: nothing would remember to take those away.
     */
    synchronized void close() {
        closed = true;
        clear();
    }

    /** The bars near the viewer, at most {@link #MAX_LINES} of them. */
    private static List<Line> clipped(Location viewer, List<Line> lines, double reach) {
        double centerX = Math.floor(viewer.getX() / WINDOW_STEP) * WINDOW_STEP + WINDOW_STEP / 2.0;
        double centerY = Math.floor(viewer.getY() / WINDOW_STEP) * WINDOW_STEP + WINDOW_STEP / 2.0;
        double centerZ = Math.floor(viewer.getZ() / WINDOW_STEP) * WINDOW_STEP + WINDOW_STEP / 2.0;
        List<Line> visible = new ArrayList<>(Math.min(lines.size(), MAX_LINES));
        for (Line line : lines) {
            Line inside = line.clip(centerX - reach, centerY - reach, centerZ - reach,
                    centerX + reach, centerY + reach, centerZ + reach);
            if (inside != null) {
                visible.add(inside);
                // ponytail: past the cap the rest of the outline is not drawn; shrink the reach
                // per outline if a real shape ever needs more bars than this near one viewer.
                if (visible.size() == MAX_LINES) {
                    break;
                }
            }
        }
        return visible;
    }

    /** Draws one bar, gliding an old one onto it when that is close enough, spawning otherwise. */
    private Shown place(Player viewer, World world, Line line, Shown previous) {
        DisplayModel model = model(line.tone());
        if (previous != null && previous.handle() != null && previous.handle().isShowing()
                && previous.at().getWorld() == world
                && distance(previous.at(), line) <= RESPAWN_DISTANCE) {
            DisplayHandle handle = previous.handle();
            if (previous.line().tone() != line.tone()) {
                handle.restyle(model);
            }
            Location at = previous.at();
            handle.moveTo(pose(line, line.midX() - at.getX(), line.midY() - at.getY(), line.midZ() - at.getZ()),
                    GLIDE_MILLIS, DisplayMotion.Easing.LINEAR);
            return new Shown(line, handle, at);
        }
        if (previous != null) {
            previous.remove();
        }
        Location at = new Location(world, line.midX(), line.midY(), line.midZ());
        DisplayHandle handle = Displays.of(owner).show(model,
                DisplayMotion.of(List.of(pose(line, 0.0, 0.0, 0.0)), LIFE_MILLIS), at, List.of(viewer));
        return new Shown(line, handle, at);
    }

    /**
     * Where a bar is and which way it points, as a display pose.
     *
     * <p>A block display grows along its own z axis, so the bar is a block as thin as its tone
     * on x and y and as long as the line on z, turned so that z points along the line: tipped up
     * or down first, then swung round to face the right way. The display module centres a block
     * on its pose, so the pose is the middle of the line.
     */
    static DisplayKeyframe pose(Line line, double x, double y, double z) {
        float thickness = line.tone().thickness();
        if (line.isMarker()) {
            return new DisplayKeyframe(0L, (float) x, (float) y, (float) z, Rotation.NONE,
                    thickness, thickness, thickness);
        }
        double length = line.length();
        return new DisplayKeyframe(0L, (float) x, (float) y, (float) z, facing(line, length),
                thickness, thickness, (float) length);
    }

    /** The turn that points a display's z axis along a line. */
    static Rotation facing(Line line, double length) {
        double dx = (line.x2() - line.x1()) / length;
        double dy = (line.y2() - line.y1()) / length;
        double dz = (line.z2() - line.z1()) / length;
        double pitch = Math.asin(Math.clamp(dy, -1.0, 1.0));
        double yaw = Math.atan2(dx, dz);
        return Rotation.around(Rotation.Axis.X, -pitch).then(Rotation.around(Rotation.Axis.Y, yaw));
    }

    /** The model a tone is drawn with, in the colour the palette gives it now. */
    private DisplayModel model(Line.Tone tone) {
        int rgb = tone == Line.Tone.EDGE && edgeColor >= 0
                ? edgeColor
                : Colors.get(tone.token(), TextColor.color(tone.fallback())).value();
        return models.computeIfAbsent(rgb, colour -> DisplayModel.block(BlockPalette.nearest(colour))
                .glow(colour)
                .light(15)
                .viewRange(VIEW_RANGE));
    }

    private static double distance(Location at, Line line) {
        double dx = line.midX() - at.getX();
        double dy = line.midY() - at.getY();
        double dz = line.midZ() - at.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * One bar on screen.
     *
     * @param line   what it draws
     * @param handle its display, or {@code null} when the display budget was full
     * @param at     where it was spawned, which its poses are relative to
     */
    private record Shown(Line line, DisplayHandle handle, Location at) {

        void remove() {
            if (handle != null) {
                handle.remove();
            }
        }
    }
}
