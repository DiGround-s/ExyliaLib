package net.exylia.lib.placeholder.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Connects the module to PlaceholderAPI, when it happens to be installed.
 *
 * <p>PlaceholderAPI is optional. Every reference to its classes is confined to
 * {@link PapiExpansion}, which is only loaded once the plugin has been seen on
 * the server, so ExyliaLib works fine without it.
 *
 * <p>The bridge goes both ways:
 * <ul>
 *   <li>everything registered in ExyliaLib becomes visible to PlaceholderAPI, so
 *       other plugins can read Exylia values without anyone writing an
 *       expansion by hand;</li>
 *   <li>PlaceholderAPI placeholders inside Exylia text resolve normally.</li>
 * </ul>
 */
public final class PapiBridge {

    /** One expansion per owning plugin, so unloading one does not affect others. */
    private static final Map<String, Object> EXPANSIONS = new ConcurrentHashMap<>();

    private static volatile Boolean available;
    private static volatile BiFunction<Player, String, String> testApplier;

    private PapiBridge() {
    }

    /**
     * Returns whether PlaceholderAPI is installed.
     *
     * <p>Checked once. A plugin cannot appear halfway through a server's life
     * without a restart, so caching this is safe and keeps it off the hot path.
     */
    public static boolean available() {
        Boolean known = available;
        if (known != null) {
            return known;
        }
        boolean found;
        try {
            found = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        } catch (Throwable ignored) {
            // No server running, which happens in tests.
            found = false;
        }
        available = found;
        return found;
    }

    /**
     * Publishes a plugin's placeholders to PlaceholderAPI.
     *
     * <p>Safe to call repeatedly: the expansion is created once per plugin and
     * reads the live registry, so later registrations are picked up without
     * re-registering anything.
     *
     * @param plugin the plugin whose placeholders should be visible
     */
    public static void refresh(Plugin plugin) {
        if (!available()) {
            return;
        }
        EXPANSIONS.computeIfAbsent(plugin.getName(), name -> PapiExpansion.create(plugin));
    }

    /**
     * Resolves PlaceholderAPI placeholders in text.
     *
     * @param player the viewer
     * @param text   the text to fill in
     * @return the filled text, or the original when PlaceholderAPI is absent
     */
    public static String apply(Player player, String text) {
        BiFunction<Player, String, String> applier = testApplier;
        if (applier != null) {
            return applier.apply(player, text);
        }
        if (!available() || player == null) {
            return text;
        }
        return PapiExpansion.apply(player, text);
    }

    /** Installs a stand-in for tests without loading PlaceholderAPI's runtime. */
    static void setApplierForTests(BiFunction<Player, String, String> applier) {
        testApplier = applier;
    }

    /** Removes the test stand-in and availability cache. */
    static void resetForTests() {
        testApplier = null;
        available = null;
    }

    /** Removes a plugin's expansion. */
    public static void release(String pluginName) {
        Object expansion = EXPANSIONS.remove(pluginName);
        if (expansion != null) {
            PapiExpansion.unregister(expansion);
        }
    }

    /** Removes every expansion. */
    public static void releaseAll() {
        EXPANSIONS.values().forEach(PapiExpansion::unregister);
        EXPANSIONS.clear();
    }
}
