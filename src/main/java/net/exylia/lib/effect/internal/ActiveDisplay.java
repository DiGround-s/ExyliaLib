package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.effect.Timer;
import net.exylia.lib.task.TaskHandle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * An effect that is on screen, and the task keeping it there.
 *
 * <p>Subclasses decide what to draw and how to clear it; everything else is the
 * same for all of them: advance the timer, redraw, finish when the time runs
 * out, and make sure the end happens exactly once.
 *
 * <h2>Redrawing</h2>
 * An effect only ticks when it has something to change. Text with no timer and
 * no placeholders is drawn once and left alone, because redrawing it every tick
 * would burn a task to send the same bytes forever.
 *
 * <h2>Ending exactly once</h2>
 * A display can end from three directions at the same time: its timer running
 * out on a scheduler thread, {@code stop()} from a command, and the plugin being
 * disabled. The end is guarded so all three converge on one cleanup, and the
 * {@code onEnd} action never runs twice.
 */
abstract class ActiveDisplay implements Display {

    private final String owner;
    private final Player viewer;
    private final Timer timer;
    private final long periodTicks;

    /** Guards cleanup, so ending from several directions still ends once. */
    private final AtomicBoolean ended = new AtomicBoolean();

    private volatile Rendered rendered;
    private volatile Runnable onEnd;
    private volatile TaskHandle task;

    ActiveDisplay(Player viewer, Rendered rendered, Timer timer, long periodTicks) {
        this.owner = EffectRuntime.ownerName();
        this.viewer = viewer;
        this.rendered = rendered;
        this.timer = timer;
        this.periodTicks = Math.max(1, periodTicks);
    }

    /** Draws the effect for the first time. */
    abstract void draw(Player viewer, Rendered rendered, Timer timer);

    /** Redraws after something changed. */
    abstract void redraw(Player viewer, Rendered rendered, Timer timer);

    /** Clears the effect from the screen. */
    abstract void clear(Player viewer);

    /**
     * Shows the effect and starts its task if it needs one.
     *
     * @return this display
     */
    Display start() {
        EffectRuntime.register(this);
        run(() -> draw(viewer, rendered, timer));

        if (!needsTicking()) {
            // Nothing will ever change, so there is no task to run. A permanent
            // bar with static text costs exactly one packet.
            return this;
        }

        task = EffectRuntime.scheduler().runAtEntityTimer(viewer, periodTicks, periodTicks, handle -> {
            if (ended.get()) {
                handle.cancel();
                return;
            }
            if (!viewer.isOnline()) {
                // The screen is gone with the player; nothing to clear.
                finish(false);
                return;
            }
            if (timer != null) {
                timer.advance(periodTicks);
            }
            try {
                redraw(viewer, rendered, timer);
            } catch (Throwable throwable) {
                EffectRuntime.logger().log(Level.WARNING,
                        "An effect failed while redrawing and has been stopped.", throwable);
                finish(true);
                return;
            }
            if (timer != null && timer.finished()) {
                finish(true);
            }
        });
        return this;
    }

    /** Returns whether anything about this effect can change over time. */
    private boolean needsTicking() {
        return timer != null || rendered.isDynamic() || repeats();
    }

    /**
     * Returns whether the effect must be re-sent even when nothing changed.
     *
     * <p>An action bar fades after about three seconds, so a permanent one has
     * to keep being sent. A boss bar does not.
     */
    boolean repeats() {
        return false;
    }

    @Override
    public void stop() {
        finish(true);
    }

    /**
     * Ends the effect once.
     *
     * @param clearScreen whether the effect still needs removing from the screen
     */
    private void finish(boolean clearScreen) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }

        TaskHandle running = task;
        if (running != null) {
            running.cancel();
        }
        EffectRuntime.unregister(this);

        if (clearScreen && viewer.isOnline()) {
            run(() -> clear(viewer));
        }

        Runnable action = onEnd;
        if (action != null) {
            // On the viewer's own thread, so the action may touch the game. That
            // is the point of onEnd: it is where the countdown's consequence
            // goes.
            run(action);
        }
    }

    /**
     * Runs something on the thread that owns the viewer.
     *
     * <p>Effects are per-player, so the entity scheduler is the correct one on
     * Folia and behaves as the main thread everywhere else.
     */
    private void run(Runnable action) {
        EffectRuntime.scheduler().runAtEntity(viewer, action, null);
    }

    @Override
    public boolean isShowing() {
        return !ended.get();
    }

    @Override
    public @Nullable Timer timer() {
        return timer;
    }

    @Override
    public @NotNull Display text(@NotNull String text) {
        this.rendered = new Rendered(text, rendered.timeStyle());
        if (!ended.get()) {
            run(() -> redraw(viewer, rendered, timer));
        }
        return this;
    }

    @Override
    public @NotNull Display addTime(double seconds) {
        if (timer != null) {
            timer.extend(Math.round(seconds * Ticks.PER_SECOND));
        }
        return this;
    }

    @Override
    public     @NotNull Display onEnd(@NotNull Runnable action) {
        this.onEnd = action;
        return this;
    }

    @Override
    public @NotNull Display progress(float progress) {
        // Boss bars override; others ignore.
        return this;
    }

    /** Triggers a redraw on the viewer's thread. Subclasses use this. */
    protected void rerender() {
        if (!ended.get()) {
            run(() -> redraw(viewer, rendered, timer));
        }
    }

    /** Returns whether this display belongs to a plugin. */
    boolean ownedBy(String pluginName) {
        return owner.equals(pluginName);
    }

    /** Returns whether this display is showing to a player. */
    boolean isFor(Player player) {
        return viewer.getUniqueId().equals(player.getUniqueId());
    }

    Player viewer() {
        return viewer;
    }
}
