package net.exylia.lib.util.showcase;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One cosmetic being shown: how long it holds the stage, and how to take it
 * away early.
 *
 * <pre>{@code
 * NpcHandle body = npcs.show(model, where, 8000, stage.watching());
 * TaskHandle hit = tasks.runAtLocationLater(where, 10, () -> body.swing());
 * return ShowcaseTurn.of(3000, body::remove, hit::cancel);
 * }</pre>
 *
 * <p>A turn is cancelled when the next one starts, when its showcase is
 * removed or rebuilt, and when the plugin is disabled. So whatever it put on
 * screen may be given a life longer than the turn itself: the body standing
 * through the rest is taken away the moment the next one arrives, which reads
 * as one continuous stage rather than a blink between turns.
 *
 * @since 1.188.0
 */
public interface ShowcaseTurn {

    /**
     * How long the turn holds the stage, in milliseconds, before the rest
     * begins. Read once, when the turn starts.
     */
    long durationMillis();

    /**
     * Takes away whatever the turn put on screen.
     *
     * <p>Called from the showcase's region, once or more: safe to call twice.
     */
    void cancel();

    /**
     * A turn that lasts a while and, cancelled, runs each of these once.
     *
     * @param durationMillis how long it holds the stage
     * @param onCancel       what takes it away: removing a body, cancelling a task
     * @return the turn
     */
    static @NotNull ShowcaseTurn of(long durationMillis, @NotNull Runnable... onCancel) {
        List<Runnable> undo = List.of(onCancel);
        AtomicBoolean cancelled = new AtomicBoolean();
        return new ShowcaseTurn() {
            @Override
            public long durationMillis() {
                return Math.max(0, durationMillis);
            }

            @Override
            public void cancel() {
                if (!cancelled.compareAndSet(false, true)) {
                    return;
                }
                for (Runnable step : undo) {
                    step.run();
                }
            }
        };
    }
}
