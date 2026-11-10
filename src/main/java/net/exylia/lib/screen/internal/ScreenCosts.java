package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.ScreenCost;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The heaviest frame each named screen has drawn, by plugin and name, for {@link net.exylia.lib.screen.Screens#cost}.
 * One entry per screen file: bounded by what plugins ship.
 *
 * <p>Internal: free to change without notice.
 */
public final class ScreenCosts {

    private static final Map<String, ScreenCost> PEAKS = new ConcurrentHashMap<>();

    private ScreenCosts() {
    }

    static void record(Plugin plugin, String name, ScreenCost cost) {
        if (name == null) return;
        PEAKS.merge(plugin.getName() + ":" + name, cost, ScreenCost::max);
    }

    public static ScreenCost of(Plugin plugin, String name) {
        return PEAKS.getOrDefault(plugin.getName() + ":" + name, ScreenCost.NONE);
    }
}
