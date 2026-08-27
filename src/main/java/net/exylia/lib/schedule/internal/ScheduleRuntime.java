package net.exylia.lib.schedule.internal;

import net.exylia.lib.schedule.Schedules;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The one timer every schedule on the server shares.
 *
 * <p>Owned by the library rather than by any consumer, for the reason every
 * shared runtime here is: a plugin's own task dies with that plugin, and the
 * timetable of the plugin next to it should not.
 *
 * <h2>One second, asynchronous, and nearly free</h2>
 * A tick reads one {@code long} per plugin and returns. There is no scan, no
 * parse and no calendar arithmetic until something is due, so the cost of this
 * on an idle server is a handful of comparisons per second — which is what a
 * clock that has to be accurate to the minute costs, and no more.
 *
 * <p>Off the main thread because nothing here touches the server. What happens
 * when a schedule <em>is</em> due moves onto the owning plugin's own scheduler
 * first; see {@code PluginSchedules}.
 *
 * @since 1.70.0
 */
public final class ScheduleRuntime {

    /** One second, in ticks. A timetable is written to the minute. */
    private static final long PERIOD_TICKS = 20L;

    private static volatile TaskHandle task;

    private ScheduleRuntime() {
        throw new AssertionError("No instances.");
    }

    /**
     * Starts the shared timer.
     *
     * @param library the library plugin
     */
    public static void init(@NotNull Plugin library) {
        stop();
        task = Tasks.of(library).runAsyncTimer(PERIOD_TICKS, PERIOD_TICKS, ScheduleRuntime::tick);
    }

    /** Stops the shared timer. */
    public static void stop() {
        TaskHandle running = task;
        if (running != null) {
            running.cancel();
            task = null;
        }
    }

    private static void tick() {
        Schedules.tickAll(System.currentTimeMillis());
    }
}
