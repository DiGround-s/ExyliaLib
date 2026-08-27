package net.exylia.lib.schedule;

import net.exylia.lib.internal.LibrarySettings;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timetables: what starts by itself, when, and on what conditions.
 *
 * <pre>{@code
 * PluginSchedules schedules = Schedules.of(this);
 * schedules.condition("event-inactive", s -> !events.isActive(s.target()));
 * schedules.onFire(s -> events.start(s.target()));
 * schedules.set(ScheduleCodec.decode(config.scheduleJson()));
 * }</pre>
 *
 * <h2>What this replaces</h2>
 * Every plugin in the ecosystem that started something on a clock wrote the same
 * scheduler: a repeating task, a list of entries parsed out of YAML, a
 * comparison of the current hour and minute against each one, and a map of the
 * last minute each entry fired in so that the second tick of the same minute
 * would not fire it twice. There were at least three copies, they had drifted
 * into disagreeing about the key for the minimum player count, and none of them
 * could express a condition beyond that count.
 *
 * <p>Here it is one module: a {@link Schedule} that a form can edit, a
 * {@link ScheduleCodec} that stores it beside the rewards of the same row, one
 * asynchronous timer for the whole server, and named gates a plugin registers so
 * "not while it is already running" is a checkbox rather than a hard-coded
 * {@code if} in a scheduler.
 *
 * <h2>Cost</h2>
 * One task on the server, not one per plugin. It runs off the main thread and
 * compares one {@code long} per plugin against the clock, so nothing is walked,
 * parsed or compared until a schedule is actually due. The calendar arithmetic
 * happens once per fire.
 *
 * @since 1.70.0
 */
public final class Schedules {

    private static final Map<String, PluginSchedules> BY_PLUGIN = new ConcurrentHashMap<>();

    private Schedules() {
        throw new AssertionError("No instances.");
    }

    /**
     * This plugin's timetable.
     *
     * <p>The same instance every time, so a plugin may call this wherever it
     * needs one rather than passing it around.
     *
     * @param plugin the plugin
     * @return its timetable
     */
    public static @NotNull PluginSchedules of(@NotNull Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
        return BY_PLUGIN.computeIfAbsent(plugin.getName(),
                ignored -> new PluginSchedules(plugin, defaultZone()));
    }

    /**
     * The calendar every timetable reads times in unless it says otherwise.
     *
     * <p>The library's own {@code timezone} setting, so a network that runs on
     * Madrid time says so once instead of in every plugin's config. An
     * unreadable zone falls back to the host's own and is reported, which is
     * better than a server whose events silently fire an hour out.
     *
     * @return the zone
     */
    public static @NotNull ZoneId defaultZone() {
        String configured = LibrarySettings.get().timezone();
        if (configured == null || configured.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (RuntimeException unknown) {
            return ZoneId.systemDefault();
        }
    }

    /**
     * One pass of the shared timer, over every plugin's timetable.
     *
     * <p>Called once a second by the library's own runtime. Not for consumers:
     * a timetable is driven by the clock, not by whoever asks.
     *
     * @param now the clock, as epoch milliseconds
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void tickAll(long now) {
        for (PluginSchedules schedules : BY_PLUGIN.values()) {
            schedules.tick(now);
        }
    }

    /**
     * Forgets one plugin's timetable.
     *
     * <p>Called by the library when a plugin is disabled. Consumers do not need
     * to call this: the fires themselves are the plugin's own tasks and go with
     * it either way, and this is what stops the shared timer from walking a
     * timetable whose classloader is gone.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        PluginSchedules schedules = BY_PLUGIN.remove(pluginName);
        if (schedules != null) {
            schedules.clear();
        }
    }

    /** Forgets every plugin's timetable, on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.values().forEach(PluginSchedules::clear);
        BY_PLUGIN.clear();
    }

    /** How many schedules there are across every plugin, for diagnostics. */
    public static int total() {
        int total = 0;
        for (PluginSchedules schedules : BY_PLUGIN.values()) {
            total += schedules.size();
        }
        return total;
    }
}
