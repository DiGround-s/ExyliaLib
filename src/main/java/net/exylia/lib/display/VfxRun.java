package net.exylia.lib.display;

import net.exylia.lib.display.internal.DisplayRuntime;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.util.sequence.SequenceRun;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * A {@link Vfx} that is playing.
 *
 * <p>Held by whoever may need it gone early: a caster that died mid wind-up, a
 * preview the player walked away from. Cancelling stops every piece still to
 * come, takes every display already spawned off every screen, and cancels the
 * sequences it started &mdash; each of those exactly once, however many times
 * it is called and from whichever thread.
 *
 * @since 1.197.0
 */
public final class VfxRun {

    private final Plugin plugin;
    private final List<Player> viewers;
    private final long endsAt;
    private final List<Object> owned = new ArrayList<>();
    private volatile boolean cancelled;

    VfxRun(@NotNull Plugin plugin, @NotNull List<Player> viewers, long endsAt) {
        this.plugin = plugin;
        this.viewers = viewers;
        this.endsAt = endsAt;
    }

    /**
     * Stops it where it is and takes everything it drew away.
     *
     * <p>Safe from any thread and safe to call twice. Particles and sounds
     * already sent stay sent; they belong to the client.
     */
    public void cancel() {
        List<Object> copy;
        synchronized (owned) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            copy = List.copyOf(owned);
            owned.clear();
        }
        for (Object piece : copy) {
            release(piece);
        }
    }

    /** Whether it was cancelled. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** Whether it has ended, by being cancelled or by running its length. */
    public boolean isDone() {
        return cancelled || System.currentTimeMillis() >= endsAt;
    }

    /**
     * When its last piece is gone, as a wall-clock time in milliseconds.
     *
     * <p>Now, for an effect nobody was watching.
     */
    public long endsAtMillis() {
        return endsAt;
    }

    /** Who it was played to, decided when it was built. */
    public @NotNull List<Player> viewers() {
        return viewers;
    }

    // ---------------------------------------------------------------- inside

    @NotNull Plugin plugin() {
        return plugin;
    }

    /** Runs one tick's pieces, unless the run was cancelled first. */
    void fire(List<Vfx.Step> due) {
        for (Vfx.Step step : due) {
            if (cancelled) {
                return;
            }
            try {
                step.action().accept(this);
            } catch (RuntimeException broken) {
                // One piece that throws must not take the rest of the effect
                // with it, nor reach whatever event started it.
                plugin.getLogger().log(Level.WARNING, "A piece of a visual effect failed.", broken);
            }
        }
    }

    /** Spawns one display for this run's viewers. */
    void show(Vfx.Shown piece) {
        int vehicle = 0;
        if (piece.mount() != null) {
            if (!piece.mount().isValid()) {
                return;
            }
            vehicle = piece.mount().getEntityId();
        }
        DisplayHandle handle = DisplayRuntime.show(plugin.getName(), piece.model(), piece.motion(),
                piece.where(), viewers, vehicle);
        if (handle != null) {
            owns(handle);
        }
    }

    /** The viewers standing in the same world as a place, and still online. */
    List<Player> sameWorld(Location at) {
        World world = at.getWorld();
        List<Player> here = new ArrayList<>(viewers.size());
        for (Player viewer : viewers) {
            if (viewer.isOnline() && world != null && world.equals(viewer.getWorld())) {
                here.add(viewer);
            }
        }
        return here;
    }

    /**
     * Keeps something to take down on cancel.
     *
     * <p>Handed something after the run was cancelled, it takes it down at
     * once: that closes the race between a cancel and a tick already firing.
     */
    void owns(Object piece) {
        synchronized (owned) {
            if (!cancelled) {
                owned.add(piece);
                return;
            }
        }
        release(piece);
    }

    private static void release(Object piece) {
        if (piece instanceof TaskHandle task) {
            task.cancel();
        } else if (piece instanceof DisplayHandle display) {
            display.remove();
        } else if (piece instanceof SequenceRun sequence) {
            sequence.cancel();
        }
    }

    @Override
    public String toString() {
        return "VfxRun[" + (cancelled ? "cancelled" : isDone() ? "done" : "playing") + ']';
    }
}
