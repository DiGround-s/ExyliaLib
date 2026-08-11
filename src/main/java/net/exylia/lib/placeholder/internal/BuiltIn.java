package net.exylia.lib.placeholder.internal;

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
                .register();
    }

    /** TPS is reported to two decimals, which is how server owners read it. */
    private static double round(double value) {
        return Math.round(Math.min(value, 20.0) * 100.0) / 100.0;
    }

    /** Returns a player by name, used by placeholders that name somebody else. */
    static Player online(String name) {
        return Bukkit.getPlayerExact(name);
    }
}
