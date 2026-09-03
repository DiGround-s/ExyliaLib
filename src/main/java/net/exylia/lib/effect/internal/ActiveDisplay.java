package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.effect.Timer;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.text.Text;
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

    /** The owner stamped by the builder, or {@code null} when created through a static. */
    private final String stampedOwner;

    /** The resolved owner, set when the display starts. */
    private String owner;
    private TaskScheduler scheduler;

    private final Player viewer;
    private final Timer timer;
    private final long periodTicks;

    /** Guards cleanup, so ending from several directions still ends once. */
    private final AtomicBoolean ended = new AtomicBoolean();

    private volatile Rendered rendered;
    private volatile Runnable onEnd;
    private volatile TaskHandle task;

    ActiveDisplay(Player viewer, Rendered rendered, Timer timer, long periodTicks) {
        this(viewer, rendered, timer, periodTicks, null);
    }

    ActiveDisplay(Player viewer, Rendered rendered, Timer timer, long periodTicks,
                  @Nullable String stampedOwner) {
        this.stampedOwner = stampedOwner;
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
        // Resolved once, at the start: the display then knows exactly which
        // plugin's scheduler drives it and which plugin's disable ends it.
        EffectRuntime.Registration registration = EffectRuntime.resolve(stampedOwner);
        this.owner = registration.plugin().getName();
        this.scheduler = registration.scheduler();

        if (exclusive()) {
            EffectRuntime.supersede(this);
        }
        EffectRuntime.register(this);
        run(() -> draw(viewer, rendered, timer));

        if (!needsTicking()) {
            // Nothing will ever change, so there is no task to run. A permanent
            // bar with static text costs exactly one packet.
            return this;
        }

        task = scheduler.runAtEntityTimer(viewer, periodTicks, periodTicks, handle -> {
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
                EffectRuntime.logger(owner).log(Level.WARNING,
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

    /**
     * Returns whether a newer display of this kind replaces this one.
     *
     * <p>The client has one action bar and one title, so two displays writing
     * either of them are not two effects: they are one screen being fought
     * over, redrawn by two tasks at whatever rate each of them ticks. That is
     * what a player sees flickering when a toggle is pressed twice — the old
     * effect was never told it had been replaced.
     *
     * <p>A boss bar is the other kind. The client stacks them, an owner
     * showing two at once means two, and replacing one with the other would be
     * a bug rather than a fix.
     */
    boolean exclusive() {
        return false;
    }

    @Override
    public void stop() {
        finish(true);
    }

    /**
     * Ends this effect because a newer one of the same kind took its screen.
     *
     * <p>Neither cleared nor ended: the display that replaces it draws over the
     * same pixels in the same tick, so clearing would only make it blink, and
     * an {@code onEnd} is the consequence of a countdown reaching zero — this
     * one never did.
     */
    void superseded() {
        finish(false, false);
    }

    private void finish(boolean clearScreen) {
        finish(clearScreen, true);
    }

    /**
     * Ends the effect once.
     *
     * @param clearScreen whether the effect still needs removing from the screen
     * @param runEnd      whether the effect reached its own end, and so runs
     *                    whatever was bound to it
     */
    private void finish(boolean clearScreen, boolean runEnd) {
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

        Runnable action = runEnd ? onEnd : null;
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
        scheduler.runAtEntity(viewer, action, null);
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
        // The same string again is the common case for a bar driven from a
        // timer: the value it shows changes once a second and the caller
        // pushes it twenty times. Nothing to parse and nothing to redraw.
        Rendered current = rendered;
        if (current.isBare() && text.equals(current.raw())) {
            return this;
        }
        return replace(new Rendered(text, current.timeStyle()));
    }

    @Override
    public @NotNull Display text(@NotNull Text text) {
        Rendered current = rendered;
        if (text.equals(current.base())) {
            return this;
        }
        return replace(new Rendered(text, current.timeStyle()));
    }

    private Display replace(Rendered next) {
        this.rendered = next;
        if (!ended.get()) {
            run(() -> redraw(viewer, rendered, timer));
        }
        return this;
    }

    /**
     * Re-parses and re-draws with the same text.
     *
     * <p>For a palette reload: the text is unchanged, but the colours it
     * parses into are not.
     */
    void invalidate() {
        this.rendered = new Rendered(rendered.raw(), rendered.timeStyle());
        if (!ended.get()) {
            run(() -> redraw(viewer, rendered, timer));
        }
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

    /** The plugin this display runs and is cleaned up under. */
    String owner() {
        return owner;
    }

    /** Returns whether this display is showing to a player. */
    boolean isFor(Player player) {
        return viewer.getUniqueId().equals(player.getUniqueId());
    }

    Player viewer() {
        return viewer;
    }
}
