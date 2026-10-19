package net.exylia.lib.region.internal;

import net.exylia.lib.effect.Effects;
import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.OutlineStyle;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.SelectionOptions;
import net.exylia.lib.region.WorldIdentity;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The selection, drawn while the player picks it.
 *
 * <p>Runs on the player's own thread every {@link #FRAME_TICKS} ticks for as long as the
 * session is open. Each frame finds the block the player is looking at when the next click
 * depends on it, feeds it to a freehand pen that is down, and draws what the session says the
 * selection looks like — with {@link LineRenderer lines}, or with particles when those were
 * chosen or the server cannot send lines.
 *
 * <h2>Why this is not a {@code RegionVisualization}</h2>
 * That one draws a region the server has, looked up by id on every frame so a shrinking zone
 * updates itself. A selection has no id and is not a region yet: it is a few clicks that change
 * under the viewer's feet and a cursor that changes every frame, so it is asked for afresh each
 * time. The geometry underneath — {@link OutlineLines} and {@link OutlineSampler} — is shared.
 *
 * <h2>It ends when the selection does</h2>
 * There is no duration. A preview belongs to a session and is stopped by the same release path
 * that ends it, so a selection that is still open is still drawn, and one that ended is taken
 * off the screen at once.
 */
final class SelectionPreview {

    /**
     * Ticks between frames.
     *
     * <p>Lines cost nothing on a frame where nothing moved, so this is set by how closely the
     * bar to the cursor should follow the crosshair: ten times a second, glided in between.
     */
    static final long FRAME_TICKS = 2L;

    /** How far the player can look and still aim a click, in blocks. */
    static final int CURSOR_RANGE = 100;

    /** How far from the player lines are drawn, in blocks. */
    private static final double REACH = 80.0;

    /**
     * How far the player's height must change before an outline drawn at it follows.
     *
     * <p>A jump is a block and a bit; following it would make the whole outline bob.
     */
    private static final int LEVEL_HYSTERESIS = 2;

    /** What the preview draws: the session, seen from the frame's side. */
    interface Canvas {

        /** The world the selection is in, or {@code null} while there is nothing to draw. */
        @Nullable UUID world();

        /** Whether the block the player is looking at matters to this frame. */
        boolean wantsCursor();

        /** Hands a freehand pen the block the player is looking at. */
        void look(@NotNull BlockPosition cursor);

        /** The selection's shape, for particles. */
        @Nullable RegionShape shape();

        /** The selection's bars, for lines. */
        @NotNull List<Line> lines(@Nullable BlockPosition cursor, double level);
    }

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final UUID playerId;
    private final Canvas canvas;
    private final SelectionOptions options;
    private final LineRenderer lines;
    private volatile TaskHandle task;
    private double level = Double.NaN;
    private long ticks;

    private SelectionPreview(Plugin library, UUID playerId, Canvas canvas, SelectionOptions options) {
        this.playerId = playerId;
        this.canvas = canvas;
        this.options = options;
        this.lines = new LineRenderer(library, -1);
    }

    /**
     * Starts drawing, on the thread that owns the viewer.
     *
     * @param library ExyliaLib, whose scheduler runs the frames and whose displays the lines are
     * @param player  who sees it
     * @param canvas  what to draw, asked for again on every frame
     * @param options how it is drawn
     * @param freehand whether a pen may be put down, which needs the frames even with nothing to draw
     * @return the running preview, or {@code null} when there is nothing for it to do
     */
    static @Nullable SelectionPreview start(@NotNull Plugin library, @NotNull Player player,
                                            @NotNull Canvas canvas, @NotNull SelectionOptions options,
                                            boolean freehand) {
        if (!options.hasPreview() && !freehand) {
            return null;
        }
        SelectionPreview preview = new SelectionPreview(library, player.getUniqueId(), canvas, options);
        preview.attach(Tasks.of(library).runAtEntityTimer(player, 1L, FRAME_TICKS, preview::render));
        return preview;
    }

    private void attach(TaskHandle handle) {
        this.task = handle;
        if (!active.get()) {
            handle.cancel();
        }
    }

    private void render(TaskHandle timer) {
        if (!active.get()) {
            timer.cancel();
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            stop();
            return;
        }
        UUID worldId = canvas.world();
        // Somebody who walked into another world is not looking at this selection. Their session
        // is still theirs; there is simply nothing to draw until they come back.
        if (worldId == null || !player.getWorld().getUID().equals(worldId)) {
            lines.clear();
            return;
        }
        BlockPosition cursor = canvas.wantsCursor() ? cursor(player) : null;
        if (cursor != null) {
            canvas.look(cursor);
        }
        if (!options.hasPreview()) {
            return;
        }
        double height = level(player);
        if (options.previewStyle() == OutlineStyle.LINES && LineRenderer.available()) {
            lines.draw(player, canvas.lines(cursor, height), REACH);
        } else if (ticks++ % Math.max(1L, options.previewPeriodTicks() / FRAME_TICKS) == 0L) {
            particles(player, height);
        }
    }

    /** The block the player is looking at, or {@code null} for sky. */
    private static @Nullable BlockPosition cursor(Player player) {
        Block target = player.getTargetBlockExact(CURSOR_RANGE, FluidCollisionMode.NEVER);
        if (target == null) {
            return null;
        }
        return new BlockPosition(WorldIdentity.from(target.getWorld()), target.getX(), target.getY(), target.getZ());
    }

    /** The height an outline with no top or bottom is drawn at, which ignores jumps. */
    private double level(Player player) {
        double standing = player.getLocation().getBlockY() + 1.0;
        if (Double.isNaN(level) || Math.abs(standing - level) >= LEVEL_HYSTERESIS) {
            level = standing;
        }
        return level;
    }

    private void particles(Player player, double height) {
        RegionShape shape = canvas.shape();
        String particle = options.previewParticle();
        World world = player.getWorld();
        if (shape == null || particle == null) {
            return;
        }
        OutlineSampler.Outline outline = OutlineSampler.sample(shape, options.previewSpacing());
        double[] coordinates = outline.coordinates();
        for (int offset = 0; offset < coordinates.length; offset += 3) {
            double y = outline.dynamicY() ? height : coordinates[offset + 1];
            Location location = new Location(world, coordinates[offset], y, coordinates[offset + 2]);
            Effects.particle(particle).at(location).show(player);
        }
    }

    /** Stops drawing and takes the lines off the screen. Calling it twice is harmless. */
    void stop() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        TaskHandle current = task;
        if (current != null) {
            current.cancel();
        }
        lines.close();
    }
}
