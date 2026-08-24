package net.exylia.lib.placeholder.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.logging.Level;

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

    private static volatile boolean available;
    private static volatile BiFunction<Player, String, String> testApplier;
    /** So a broken PlaceholderAPI names itself once instead of once per render. */
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();

    private PapiBridge() {
    }

    /**
     * Returns whether PlaceholderAPI is installed.
     *
     * <p>Only a positive answer is cached. ExyliaLib is {@code load: STARTUP},
     * so the first call can happen before PlaceholderAPI has enabled; latching
     * that "no" left the bridge dead for the rest of the server's life, and the
     * {@code softdepend} that orders the load only helps once someone asks
     * again. A negative answer costs a map lookup on the plugin manager, and it
     * is only reached after the registry and the render's own values have both
     * missed.
     */
    public static boolean available() {
        if (available) {
            return true;
        }
        boolean found;
        try {
            found = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        } catch (Throwable ignored) {
            // No server running, which happens in tests.
            found = false;
        }
        if (found) {
            available = true;
        }
        return found;
    }

    /**
     * Returns whether PlaceholderAPI can be consulted from the calling thread.
     *
     * <p>PlaceholderAPI runs third-party expansions, and those read the world,
     * scoreboards and entities. Off the main thread Paper answers that with
     * {@code IllegalStateException: Asynchronous ... access}, which is thrown
     * <em>through</em> whoever was rendering. Scoreboards render on an async
     * timer, so one such expansion aborted the render before a single line was
     * sent and the sidebar went blank.
     */
    private static boolean usable() {
        if (!onMainThread()) {
            return false;
        }
        return testApplier != null || available();
    }

    /**
     * Returns whether PlaceholderAPI is installed but could not be consulted.
     *
     * <p>Read by the template so it does not call a placeholder unknown when it
     * never got to ask the one thing that might own it. Saying "register it
     * with Placeholders.register" about a PlaceholderAPI placeholder sends its
     * author looking for the wrong registration.
     */
    public static boolean deferred() {
        return !onMainThread() && (testApplier != null || available());
    }

    private static boolean onMainThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            // No server running, which happens in tests: nothing to protect.
            return true;
        }
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
        if (player == null || !usable()) {
            return text;
        }
        try {
            BiFunction<Player, String, String> applier = testApplier;
            return applier != null ? applier.apply(player, text) : PapiExpansion.apply(player, text);
        } catch (Throwable t) {
            // Throwable, not Exception: an absent or half-loaded PlaceholderAPI
            // fails with NoClassDefFoundError, and an expansion that touches the
            // server from the wrong place fails with IllegalStateException.
            // Neither is a reason for the text that mentioned it to disappear.
            if (FAILURE_REPORTED.compareAndSet(false, true)) {
                Loggers.get().log(Level.WARNING, "PlaceholderAPI could not fill in \"" + text
                        + "\", so it is left as written. Reported once per server.", t);
            }
            return text;
        }
    }

    /**
     * Installs a stand-in for tests without loading PlaceholderAPI's runtime.
     *
     * <p>Public inside {@code internal} because the modules that render through
     * this bridge — the scoreboard, off the main thread — are tested from their
     * own packages.
     */
    public static void setApplierForTests(BiFunction<Player, String, String> applier) {
        testApplier = applier;
    }

    /** Removes the test stand-in, the availability cache and the reported failure. */
    public static void resetForTests() {
        testApplier = null;
        available = false;
        FAILURE_REPORTED.set(false);
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
