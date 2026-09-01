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
                .add("cpu_system", request -> percent(SYSTEM == null ? -1 : SYSTEM.getCpuLoad()))
                .add("cpu_process", request -> percent(SYSTEM == null ? -1 : SYSTEM.getProcessCpuLoad()))
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
     * A processor load as a whole percentage.
     *
     * <p>Both loads answer with a negative number when they cannot be read —
     * no extended bean, or the first call, taken before there are two samples
     * to compare. Zero is what a sidebar can show; the alternative is a line
     * reading {@code -1%} for the first second of the server's life.
     */
    private static int percent(double load) {
        return load < 0 ? 0 : (int) Math.round(load * 100.0);
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
