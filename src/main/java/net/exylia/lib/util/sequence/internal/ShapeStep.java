package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceStep;
import net.exylia.lib.util.sequence.SequenceTarget;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A geometric shape, drawn out of whatever the line asked for.
 *
 * <p>The points were computed when the sequence was compiled, so playing this
 * is a loop over an array. A twenty-effect kill sequence that ExyliaCommons
 * re-derived from trigonometry on every death now costs the trigonometry once,
 * before the server finished starting.
 *
 * <h2>Animation</h2>
 * With {@code ticks:1} every point is drawn at once. Above that the points are
 * spread over time, so the shape draws itself. The spread is the original's:
 * point <em>i</em> of <em>n</em> waits
 * {@code i/(n-1) * (ticks-1) * interval}.
 *
 * <p>What is new is that the frames are grouped. Commons scheduled one task per
 * point &mdash; a 600-point animated torus scheduled 600 tasks, most of them
 * landing in the same tick anyway. Points that share a tick are now drawn by
 * one task, which is the difference between 600 scheduler entries and about 20.
 */
final class ShapeStep implements SequenceStep {

    /** A tick in milliseconds, for turning the file's seconds into frames. */
    private static final long TICK_MS = 50L;

    private final Paint paint;
    private final Frame[] frames;
    private final long intervalMs;
    private final long trailMillis;
    private final boolean rotates;
    private final double yaw;

    private ShapeStep(Paint paint, Frame[] frames, long intervalMs,
                      long trailMillis, boolean rotates, double yaw) {
        this.paint = paint;
        this.frames = frames;
        this.intervalMs = intervalMs;
        this.trailMillis = trailMillis;
        this.rotates = rotates;
        this.yaw = yaw;
    }

    /**
     * Groups the points of a shape into the frames that draw them.
     *
     * @param paint      how each point looks
     * @param points     the offsets, in draw order
     * @param ticks      how many frames the shape draws over
     * @param intervalMs how long a frame lasts
     * @param yawDegrees a fixed rotation, or {@code NaN} to face the source
     */
    static ShapeStep of(Paint paint, List<Vector> points, int ticks, long intervalMs,
                        double yawDegrees, boolean faceSource) {
        int total = points.size();
        if (total == 0) {
            return new ShapeStep(paint, new Frame[0], intervalMs, 0L, false, 0.0);
        }
        if (ticks <= 1 || total == 1) {
            Frame single = new Frame(0L, points.toArray(new Vector[0]));
            return new ShapeStep(paint, new Frame[]{single}, intervalMs, 0L, faceSource,
                    Math.toRadians(yawDegrees));
        }

        // The original's stagger, then collapsed: every point whose delay falls
        // in the same tick is drawn by the same task.
        java.util.TreeMap<Long, java.util.List<Vector>> byTick = new java.util.TreeMap<>();
        for (int i = 0; i < total; i++) {
            long delay = (long) ((double) i / (total - 1) * (ticks - 1) * intervalMs);
            byTick.computeIfAbsent(delay / TICK_MS * TICK_MS, key -> new java.util.ArrayList<>())
                    .add(points.get(i));
        }

        Frame[] built = new Frame[byTick.size()];
        int index = 0;
        long last = 0L;
        for (java.util.Map.Entry<Long, java.util.List<Vector>> entry : byTick.entrySet()) {
            built[index++] = new Frame(entry.getKey(), entry.getValue().toArray(new Vector[0]));
            last = Math.max(last, entry.getKey());
        }
        return new ShapeStep(paint, built, intervalMs, last, faceSource, Math.toRadians(yawDegrees));
    }

    /**
     * The same shape, turned further about the vertical.
     *
     * <p>For a step that is played several times over: each beat is the shape
     * it started as, rotated, sharing the points that were worked out once.
     */
    ShapeStep withExtraYaw(double extraRadians) {
        return new ShapeStep(paint, frames, intervalMs, trailMillis, rotates, yaw + extraRadians);
    }

    @Override
    public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
        if (frames.length == 0) {
            return;
        }
        Location anchor = target.location();
        // Resolved once per play, not once per point and not once per frame: a
        // paint that depends on who died has to look them up, and a ring of
        // twelve would otherwise look them up twelve times.
        Paint resolved = paint.forPlay(target);
        // Worked out once per play, not once per point: the whole shape shares
        // one rotation.
        double angle = rotates ? Math.toRadians(-anchor.getYaw()) : yaw;
        double cos = angle == 0.0 ? 1.0 : Math.cos(angle);
        double sin = angle == 0.0 ? 0.0 : Math.sin(angle);

        for (Frame frame : frames) {
            if (frame.delayMs() == 0L) {
                drawFrame(resolved, frame, target, anchor, cos, sin);
                continue;
            }
            TaskScheduler tasks = run.scheduler();
            TaskHandle handle = tasks.runAtLocationLater(anchor, frame.delayMs() / TICK_MS,
                    () -> {
                        if (!run.isCancelled()) {
                            drawFrame(resolved, frame, target, anchor, cos, sin);
                        }
                    });
            run.owns(handle);
        }
    }

    /**
     * Draws one frame.
     *
     * <p>Observers are resolved once per frame rather than once per point: they
     * cannot meaningfully change within a frame, and asking per point made the
     * cost of a shape quadratic in its detail.
     */
    private void drawFrame(Paint paint, Frame frame, SequenceTarget target, Location anchor,
                           double cos, double sin) {
        List<Player> observers = target.observers();
        if (observers.isEmpty()) {
            return;
        }
        for (Vector point : frame.points()) {
            double x = point.getX();
            double z = point.getZ();
            if (cos != 1.0 || sin != 0.0) {
                double rotatedX = x * cos - z * sin;
                z = x * sin + z * cos;
                x = rotatedX;
            }
            paint.drawAt(observers, anchor, x, point.getY(), z);
        }
    }

    /**
     * How long this keeps drawing, animation and paint together.
     *
     * <p>The frames finish at {@code trailMillis}; what the last frame drew can
     * outlive them. A shape of displays is not over when the last one is sent,
     * it is over when the last one has gone, and a preview that hands the
     * player back in between hands it back into a sky full of swords.
     */
    @Override
    public long trailMillis() {
        return trailMillis + paint.trailMillis();
    }

    /** The points that share one moment. */
    private record Frame(long delayMs, Vector[] points) {
    }
}
