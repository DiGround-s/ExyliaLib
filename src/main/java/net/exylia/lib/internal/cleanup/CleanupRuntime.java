package net.exylia.lib.internal.cleanup;

import net.exylia.lib.ExyliaLib;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.task.Tasks;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;

/**
 * The server's housekeeping: one asynchronous pass that deletes what the
 * server keeps writing and never removes.
 *
 * <p>Today that is the log folder. Anything added later is another sweep in the
 * same pass and another section in {@code cleanup.yml}, rather than another
 * timer.
 *
 * <p>The timer runs whether or not the sweeps are enabled — it reads one
 * boolean and returns — so turning a sweep on with {@code /exylialib reload}
 * takes effect without a restart and nothing has to be rescheduled.
 *
 * @since 1.90.0
 */
public final class CleanupRuntime {

    /**
     * Long enough after startup that the pass never competes with the disk work
     * a server does while it is coming up.
     */
    private static final long FIRST_RUN_TICKS = 20L * 60L;

    /**
     * Every six hours. A log becomes expendable by ageing a day, so checking
     * more often finds nothing; checking once a day would mean a server
     * restarted every morning never reaches the check at all.
     */
    private static final long INTERVAL_TICKS = 20L * 60L * 60L * 6L;

    private static volatile ConfigFile<CleanupSettings> file;

    private CleanupRuntime() {
        throw new AssertionError("No instances.");
    }

    /**
     * Loads {@code cleanup.yml} and starts the pass.
     *
     * @param plugin the running library
     */
    public static void init(ExyliaLib plugin) {
        file = Configs.define(plugin, "cleanup", CleanupSettings.class).load();
        Path logs = serverFolder(plugin).resolve("logs");
        Tasks.of(plugin).runAsyncTimer(FIRST_RUN_TICKS, INTERVAL_TICKS, () -> sweep(plugin, logs));
    }

    /**
     * Re-reads the file, so a sweep can be turned on or its retention changed
     * without a restart. Called from {@link ExyliaLib#reloadPalette()} with the
     * library's other shared configuration.
     */
    public static void reload() {
        ConfigFile<CleanupSettings> current = file;
        if (current != null) current.reload();
    }

    /** One pass over everything this module tidies. */
    private static void sweep(ExyliaLib plugin, Path logs) {
        ConfigFile<CleanupSettings> current = file;
        if (current == null) return;
        CleanupSettings.Logs settings = current.get().logs();
        if (!settings.enabled()) return;

        int days = Math.max(1, settings.keepDays());
        int deleted = LogCleaner.sweep(logs, days, Instant.now(),
                path -> plugin.getLogger().warning("Could not delete " + path + " while cleaning logs."));
        if (deleted > 0) {
            plugin.getLogger().info("Deleted " + deleted + " log file"
                    + (deleted == 1 ? "" : "s") + " older than " + days + " days.");
        }
    }

    /**
     * The server's root folder, reached from the data folder rather than from
     * the working directory: a start script may run the jar from anywhere, but
     * {@code plugins/ExyliaLib} is always two levels under the root.
     */
    private static Path serverFolder(ExyliaLib plugin) {
        File data = plugin.getDataFolder();
        File plugins = data.getParentFile();
        File root = plugins == null ? null : plugins.getParentFile();
        // Only if the data folder had no ancestors to walk, which means it was
        // given as a bare relative name. Then the working directory is the root.
        return (root != null ? root : new File(".")).getAbsoluteFile().toPath();
    }
}
