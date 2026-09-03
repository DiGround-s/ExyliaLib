package net.exylia.lib.placeholder.internal;

import com.sun.management.OperatingSystemMXBean;
import net.exylia.lib.placeholder.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The placeholders every plugin would otherwise write again.
 *
 * <p>Registered once by ExyliaLib, so a scoreboard, a join message and a menu
 * title all read the same {@code %player_name%} without three plugins declaring
 * it. They are deliberately limited to what the server itself can answer:
 * anything about economy, clans or ranks belongs to the plugin that owns that
 * data.
 *
 * <p>None are marked async safe. They read live Bukkit state, and claiming
 * otherwise would invite a plugin to call them off the main thread.
 */
public final class BuiltIn {

    /**
     * The extended operating system bean, or {@code null} on a JVM without it.
     *
     * <p>Held rather than looked up per call: the bean samples the processor
     * between two readings of it, so a fresh one every second would compare a
     * reading against nothing and answer that the load is unknown.
     */
    private static final OperatingSystemMXBean SYSTEM = system();

    /**
     * How long a processor reading is reused before another is taken.
     *
     * <p>Both loads are a delta: the bean subtracts two samples of counters
     * the kernel moves in hundredths of a second. A sidebar asks for them once
     * per player per refresh — dozens of times a second on a full server — and
     * each of those calls spans less time than the counters' own resolution,
     * so the delta comes back as nothing and the line reads {@code 0%} for the
     * life of the server. Sampled once a second and shared by every caller,
     * the delta is over a second, which is what the number is supposed to
     * mean.
     */
    private static final long SAMPLE_NANOS = 1_000_000_000L;

    private static volatile long sampledAt = Long.MIN_VALUE / 2;
    private static volatile int systemLoad;
    private static volatile int processLoad;

    private BuiltIn() {
    }

    /**
     * Registers the built-in placeholders.
     *
     * @param plugin ExyliaLib itself, which owns them
     */
    public static void register(Plugin plugin) {
        Placeholders.group(plugin, "player")
                .describe("Information about the player reading the text")
                .add("name", request -> request.hasViewer() ? request.viewer().getName() : null)
                .add("displayname", request -> request.hasViewer() ? request.viewer().getDisplayName() : null)
                .add("uuid", request -> request.hasViewer() ? request.viewer().getUniqueId() : null)
                .add("world", request -> request.hasViewer() ? request.viewer().getWorld().getName() : null)
                .add("health", request -> request.hasViewer() ? request.viewer().getHealth() : null)
                .add("level", request -> request.hasViewer() ? request.viewer().getLevel() : null)
                .add("food", request -> request.hasViewer() ? request.viewer().getFoodLevel() : null)
                .add("gamemode", request -> request.hasViewer() ? request.viewer().getGameMode() : null)
                .add("ping", request -> request.hasViewer() ? request.viewer().getPing() : null)
                .add("x", request -> request.hasViewer() ? request.viewer().getLocation().getBlockX() : null)
                .add("y", request -> request.hasViewer() ? request.viewer().getLocation().getBlockY() : null)
                .add("z", request -> request.hasViewer() ? request.viewer().getLocation().getBlockZ() : null)
                .register();

        Placeholders.group(plugin, "target")
                .describe("Information about the player the text is about")
                .add("name", request -> request.target() != null ? request.target().getName() : null)
                .add("uuid", request -> request.target() != null ? request.target().getUniqueId() : null)
                .register();

        Placeholders.group(plugin, "server")
                .describe("Information about the server")
                .add("online", request -> Bukkit.getOnlinePlayers().size())
                .add("max", request -> Bukkit.getMaxPlayers())
                .add("tps", request -> round(Bukkit.getTPS()[0]))
                .add("mspt", request -> millis(Bukkit.getAverageTickTime()))
                .add("cpu_system", request -> {
                    sample();
                    return systemLoad;
                })
                .add("cpu_process", request -> {
                    sample();
                    return processLoad;
                })
                .register();
    }

    /** TPS is reported to two decimals, which is how server owners read it. */
    private static double round(double value) {
        return Math.round(Math.min(value, 20.0) * 100.0) / 100.0;
    }

    /**
     * Milliseconds to one decimal.
     *
     * <p>A tick is fifty milliseconds long, so the first decimal is already a
     * fiftieth of the budget: a second one is noise on a sidebar line.
     */
    private static double millis(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * Takes a fresh processor reading, at most once a second.
     *
     * <p>Racing callers may both sample; they write the same two numbers, and
     * a reading taken twice is not worth a lock on the path a scoreboard uses.
     */
    private static void sample() {
        if (SYSTEM == null || System.nanoTime() - sampledAt < SAMPLE_NANOS) {
            return;
        }
        sampledAt = System.nanoTime();
        systemLoad = percent(systemLoad, SYSTEM.getCpuLoad());
        processLoad = percent(processLoad, SYSTEM.getProcessCpuLoad());
    }

    /**
     * A processor load as a whole percentage.
     *
     * <p>Both loads answer with a negative number, or with no number at all,
     * when they cannot be read — no extended bean, or a reading taken before
     * there are two samples to compare. The previous answer is kept rather
     * than shown as zero: a load that cannot be read for one second is not a
     * server that stopped working.
     *
     * @param previous what the last readable sample said
     * @param load     the load, between zero and one, or negative when unknown
     */
    private static int percent(int previous, double load) {
        return load < 0 || Double.isNaN(load) ? previous : (int) Math.round(load * 100.0);
    }

    /** The extended bean where the JVM has one, {@code null} where it does not. */
    private static OperatingSystemMXBean system() {
        return java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                instanceof OperatingSystemMXBean bean ? bean : null;
    }

    /** Returns a player by name, used by placeholders that name somebody else. */
    static Player online(String name) {
        return Bukkit.getPlayerExact(name);
    }
}
