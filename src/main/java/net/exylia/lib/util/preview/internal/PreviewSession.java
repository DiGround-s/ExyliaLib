package net.exylia.lib.util.preview.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.preview.Preview;
import net.exylia.lib.util.preview.PreviewSettings;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceTarget;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One player's preview, from the lift to the way back.
 *
 * <h2>The only thing that really matters</h2>
 * That the player is put back. Everything else &mdash; the effect, the timing,
 * the isolation &mdash; is cosmetic next to leaving somebody flying,
 * invulnerable, a thousand blocks up. So the restore is idempotent, reachable
 * from every direction, and has a timer behind it that fires whatever else
 * fails.
 */
final class PreviewSession implements Preview {

    private final Plugin plugin;
    private final Player viewer;
    private final UUID viewerId;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final PreviewSettings settings;
    private final Runnable onComplete;
    private final Runnable onRelease;

    private final AtomicBoolean finished = new AtomicBoolean();
    private final List<Entity> hidden = new ArrayList<>();
    private final List<TaskHandle> scheduled = new ArrayList<>(3);

    private StagedPlayer captured;
    private Stages.Slot slot;
    private SequenceRun run;

    PreviewSession(Plugin plugin, Player viewer, TaskScheduler tasks, Debug debug,
                   PreviewSettings settings, Runnable onComplete, Runnable onRelease) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.viewerId = viewer.getUniqueId();
        this.tasks = tasks;
        this.debug = debug;
        this.settings = settings;
        this.onComplete = onComplete;
        this.onRelease = onRelease;
    }

    /** Who this is for. */
    UUID viewerId() {
        return viewerId;
    }

    @Override
    public @NotNull Player viewer() {
        return viewer;
    }

    @Override
    public boolean isFinished() {
        return finished.get();
    }

    /**
     * Lifts the player and plays the effect.
     *
     * <p>Runs on the thread that owns the player, which the caller arranges.
     */
    void start(@NotNull Sequence sequence) {
        captured = StagedPlayer.capture(viewer);
        slot = Stages.claim(viewer.getWorld(), settings);

        // The safety net is armed before anything is changed, not after: a
        // failure between here and the end would otherwise strand the player
        // with no timer to rescue them.
        arm();

        viewer.closeInventory();
        captured.freeze(viewer);
        isolate();

        Location stage = slot.where().clone();
        // Keep the direction they were facing, so the effect appears in front
        // of them rather than wherever the stage happens to point.
        stage.setYaw(captured.origin().getYaw());
        stage.setPitch(0f);
        viewer.teleport(stage);

        // A tick or two before the first particle: the client has to have the
        // new position, or the effect is drawn around where the player was.
        scheduleAtViewer(settings.settleTicks(), () -> {
            if (finished.get() || !viewer.isOnline()) {
                return;
            }
            play(sequence, stage);
        });
    }

    private void play(Sequence sequence, Location stage) {
        Vector forward = stage.getDirection().setY(0);
        Location where = forward.lengthSquared() < 1.0e-6
                ? stage.clone().add(0, 1, 0)
                : stage.clone().add(forward.normalize().multiply(settings.distance()));

        run = net.exylia.lib.util.sequence.Sequences.of(plugin)
                .play(sequence, SequenceTarget.at(where).by(viewer).onlyTo(viewer));

        long after = Math.max(1L, sequence.durationMillis() / 50L) + settings.lingerTicks();
        scheduleAtViewer(after, this::end);
    }

    /**
     * Hides everything else from the player, and the player from everyone else.
     *
     * <p>Both directions matter. The first is what makes the stage empty; the
     * second is what stops a bystander seeing a body hanging in the sky, and
     * stops two players previewing at once from seeing each other even if the
     * slots were somehow close.
     */
    private void isolate() {
        for (Player other : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (other.equals(viewer)) {
                continue;
            }
            hide(other);
            // The other way round, so nobody watches this player float.
            other.hidePlayer(plugin, viewer);
        }
    }

    private void hide(Entity entity) {
        try {
            viewer.hideEntity(plugin, entity);
            hidden.add(entity);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // An entity that went away between listing and hiding. Nothing to
            // hide, and nothing to put back.
        }
    }

    /**
     * The safety net.
     *
     * <p>Whatever else happens &mdash; a step that throws, a task that never
     * fires, a sequence that outlives its estimate &mdash; the player comes
     * back after this.
     */
    private void arm() {
        scheduleAtViewer(settings.maxTicks(), () -> {
            if (!finished.get()) {
                debug.warn("A preview for " + viewer.getName()
                        + " ran past its limit and was ended by the safety timer.");
                end();
            }
        });
    }

    private void scheduleAtViewer(long delayTicks, Runnable work) {
        TaskHandle handle = tasks.runAtEntityLater(viewer, delayTicks, work);
        synchronized (scheduled) {
            scheduled.add(handle);
        }
    }

    @Override
    public void end() {
        // Whoever gets here first wins. Every other path — quit, death, plugin
        // disable, the timer, the caller — then does nothing.
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        try {
            stopWork();
            restore(true);
        } finally {
            onRelease.run();
            runCallback();
        }
    }

    /**
     * Ends without putting the player anywhere.
     *
     * <p>For a player who is already gone: quitting, being kicked, or the
     * server stopping. Their position is whatever the server saved, and
     * teleporting a player who is leaving throws.
     *
     * <p>The slot is still released and the hiding still undone, because the
     * same player may come back in a second.
     */
    void endWithoutMoving() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        try {
            stopWork();
            restore(false);
        } finally {
            onRelease.run();
            runCallback();
        }
    }

    /**
     * Ends because the player moved themselves somewhere else.
     *
     * <p>A teleport by another plugin, or a world change. Their new position is
     * intentional and must not be overwritten by the origin this preview
     * remembered.
     */
    void endWhereTheyAre() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        try {
            stopWork();
            restore(false);
        } finally {
            onRelease.run();
            runCallback();
        }
    }

    private void stopWork() {
        if (run != null) {
            run.cancel();
        }
        List<TaskHandle> copy;
        synchronized (scheduled) {
            copy = List.copyOf(scheduled);
            scheduled.clear();
        }
        for (TaskHandle handle : copy) {
            handle.cancel();
        }
    }

    /**
     * Puts the player back together.
     *
     * <p>Each part is guarded on its own: an entity that despawned must not
     * stop the teleport, and a failed teleport must not stop the flight flags
     * from being restored. Half a restore is what leaves somebody stuck.
     */
    private void restore(boolean move) {
        if (slot != null) {
            Stages.release(slot);
            slot = null;
        }
        if (viewer.isOnline()) {
            reveal();
            if (move && captured != null) {
                try {
                    viewer.teleport(captured.origin());
                } catch (RuntimeException failed) {
                    debug.error("A preview could not return " + viewer.getName()
                            + " to where they were.", failed);
                }
            }
            if (captured != null) {
                try {
                    captured.restore(viewer);
                } catch (RuntimeException failed) {
                    debug.error("A preview could not restore " + viewer.getName()
                            + "'s state.", failed);
                }
            }
        }
        captured = null;
    }

    private void reveal() {
        for (Entity entity : hidden) {
            try {
                viewer.showEntity(plugin, entity);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Gone while hidden. Nothing to show.
            }
        }
        hidden.clear();
        for (Player other : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!other.equals(viewer)) {
                try {
                    other.showPlayer(plugin, viewer);
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // Left while this one was hidden from them.
                }
            }
        }
    }

    private void runCallback() {
        if (onComplete == null || !viewer.isOnline()) {
            return;
        }
        // A tick later, on the player's own thread: a menu reopened inside the
        // same tick as a teleport opens against the position the client has not
        // acknowledged yet.
        try {
            tasks.runAtEntityLater(viewer, 1L, () -> {
                try {
                    onComplete.run();
                } catch (RuntimeException broken) {
                    debug.error("What was meant to happen after a preview failed.", broken);
                }
            });
        } catch (RuntimeException rejected) {
            // The plugin is being disabled and its scheduler is closed. The
            // player is already restored, which is what mattered.
            debug.warn("A preview finished while its plugin was shutting down;"
                    + " what was to follow was skipped.");
        }
    }

    /** The origin, for a caller that needs to know where they were. */
    @Nullable Location origin() {
        return captured == null ? null : captured.origin();
    }
}
