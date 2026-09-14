package net.exylia.lib.metrics;

import net.exylia.lib.metrics.internal.MetricsRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Lets an Exylia plugin tell stats.exylia.net how it is set up on a server, so
 * an error can be read against what the plugin was asked to do: how many
 * arenas it runs, how large its regions are.
 *
 * <pre>{@code
 * Metrics.describe(this, Map.of(
 *         "events", events.size(),
 *         "largestRegionBlocks", largest));
 * }</pre>
 *
 * <p>Only production Exylia plugins are reported, and nothing is sent when the
 * server owner turned metrics off: calling this is then free. Details describe
 * the setup, never players: no names, ids, addresses or anything a player
 * typed.
 *
 * @since 1.163.0
 */
public final class Metrics {

    private Metrics() {
    }

    /**
     * Replaces what this plugin reports about its setup.
     *
     * <p>The values are copied before this returns, so the map can be reused or
     * changed afterwards, and it is safe to call from any thread. The
     * description travels with the next report — within about a minute once
     * the server's first report went out — and is only sent again after it
     * changes. It is forgotten when the plugin is disabled, so a plugin that
     * describes itself on enable stays current across reloads.
     *
     * <p>Values may be strings, numbers, booleans, enums, {@code null}, maps and
     * iterables of those, nested at most {@value MetricsRuntime#MAX_DEPTH}
     * levels. Anything else, or more than 32 KB once written as JSON, keeps the
     * previous description and prints a debug line.
     *
     * @param plugin  the plugin describing itself
     * @param details what to report, keyed by name
     */
    public static void describe(@NotNull Plugin plugin, @NotNull Map<String, ?> details) {
        MetricsRuntime.describe(plugin.getName(), details);
    }
}
