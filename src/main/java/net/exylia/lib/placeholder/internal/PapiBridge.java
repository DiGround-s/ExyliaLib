package net.exylia.lib.placeholder.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /**
     * The expansions of one plugin, by identifier, so unloading one plugin does
     * not affect others and a plugin that asked for a second identifier gets a
     * second expansion rather than losing its first.
     */
    private static final Map<String, Map<String, Object>> EXPANSIONS = new ConcurrentHashMap<>();

    /** The identifiers a plugin asked to answer under, on top of its own name. */
    private static final Map<String, Set<String>> ALIASES = new ConcurrentHashMap<>();

    /** The last value PlaceholderAPI gave for a piece of text, per player. */
    private static final Map<UUID, Map<String, String>> VALUES = new ConcurrentHashMap<>();

    /** Text an off-thread render asked about, waiting for the next main-thread pass. */
    private static final Map<UUID, Set<String>> WANTED = new ConcurrentHashMap<>();

    private static volatile boolean available;
    private static volatile BiFunction<Player, String, String> testApplier;
    private static volatile TaskHandle refresher;
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

    /** Whether PlaceholderAPI, or the test stand-in, can answer at all. */
    private static boolean installed() {
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
        return !onMainThread() && installed();
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
        Map<String, Object> expansions =
                EXPANSIONS.computeIfAbsent(plugin.getName(), name -> new ConcurrentHashMap<>());
        for (String identifier : identifiers(plugin)) {
            expansions.computeIfAbsent(identifier, id -> PapiExpansion.create(plugin, id));
        }
    }

    /**
     * Publishes a plugin's placeholders under a second identifier as well.
     *
     * <p>Recorded whether or not PlaceholderAPI is installed, so a plugin that
     * asks for its identifier while loading still gets it once the bridge comes
     * up. The plugin's own name keeps answering: an identifier is added, never
     * replaced, because the configs already written against the long name are
     * not this call's to break.
     *
     * @param plugin     the plugin whose placeholders should also be visible
     * @param identifier the extra identifier, such as {@code practice}
     */
    public static void alias(Plugin plugin, String identifier) {
        ALIASES.computeIfAbsent(plugin.getName(), name -> ConcurrentHashMap.newKeySet())
                .add(identifier.toLowerCase(Locale.ROOT));
        refresh(plugin);
    }

    /** Every identifier a plugin answers under: its own name, then its aliases. */
    private static Set<String> identifiers(Plugin plugin) {
        Set<String> identifiers = new LinkedHashSet<>();
        identifiers.add(plugin.getName().toLowerCase(Locale.ROOT));
        identifiers.addAll(ALIASES.getOrDefault(plugin.getName(), Set.of()));
        return identifiers;
    }

    /**
     * Resolves PlaceholderAPI placeholders in text.
     *
     * <p>On the main thread this asks PlaceholderAPI. Off it — which is where
     * scoreboards and holograms render — the answer is the one the last
     * main-thread pass got, and the text is remembered so {@link #refreshWanted}
     * has it next tick.
     *
     * <p>Asking from the render thread is what this avoids, not what it fixes:
     * PlaceholderAPI runs third-party expansions, and those read the world,
     * scoreboards and entities. Off the main thread Paper answers that with
     * {@code IllegalStateException: Asynchronous ... access}, thrown
     * <em>through</em> whoever was rendering, which blanked the sidebar before a
     * single line was sent. Refusing to ask kept the sidebar alive but left
     * every PlaceholderAPI placeholder on it written out as {@code %name%},
     * which is what remembering the values is for.
     *
     * @param player the viewer
     * @param text   the text to fill in
     * @return the filled text, or the original when PlaceholderAPI is absent
     */
    public static String apply(Player player, String text) {
        if (player == null || !installed()) {
            return text;
        }
        if (!onMainThread()) {
            return remembered(player, text);
        }
        return resolve(player, text);
    }

    /**
     * The value from the last main-thread pass, asking for the next one.
     *
     * <p>So the first render of a placeholder shows it as written and the render
     * after it shows the value. A board refreshing every half second is that far
     * behind, which nobody sees on a rank, a balance or a clock.
     */
    private static String remembered(Player player, String text) {
        UUID id = player.getUniqueId();
        WANTED.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet()).add(text);
        Map<String, String> values = VALUES.get(id);
        String value = values == null ? null : values.get(text);
        return value == null ? text : value;
    }

    /** Asks PlaceholderAPI, from a thread that is allowed to. */
    private static String resolve(Player player, String text) {
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
     * Starts the main-thread pass that keeps {@link #apply} answerable off it.
     *
     * <p>Every tick, but its work is only what an async render asked for since
     * the last one: a server whose boards use no PlaceholderAPI placeholder
     * resolves nothing. A board refreshing every half second costs two
     * resolutions a second per placeholder, which is what rendering it on the
     * main thread would have cost anyway.
     */
    public static void startRefreshing(Plugin plugin) {
        if (refresher != null) {
            return;
        }
        refresher = Tasks.of(plugin).runTimer(1L, 1L, PapiBridge::refreshWanted);
    }

    /** Stops the pass and forgets every remembered value. */
    public static void stopRefreshing() {
        TaskHandle handle = refresher;
        refresher = null;
        if (handle != null) {
            handle.cancel();
        }
        WANTED.clear();
        VALUES.clear();
    }

    /** Resolves what the last renders asked about. Runs on the main thread. */
    static void refreshWanted() {
        for (UUID id : List.copyOf(WANTED.keySet())) {
            Set<String> texts = WANTED.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                VALUES.remove(id);
                continue;
            }
            if (texts == null || texts.isEmpty()) {
                continue;
            }
            Map<String, String> values =
                    VALUES.computeIfAbsent(id, key -> new ConcurrentHashMap<>());
            for (String text : texts) {
                values.put(text, resolve(player, text));
            }
        }
        // Somebody who left stops asking, so their values are only dropped here.
        // One entry per player who ever saw a board, so the sweep is cheap.
        if (!VALUES.isEmpty()) {
            VALUES.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
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

    /** Removes the test stand-in, the caches and the reported failure. */
    public static void resetForTests() {
        testApplier = null;
        available = false;
        refresher = null;
        EXPANSIONS.clear();
        ALIASES.clear();
        WANTED.clear();
        VALUES.clear();
        FAILURE_REPORTED.set(false);
    }

    /** Removes a plugin's expansions, under every identifier it answered as. */
    public static void release(String pluginName) {
        Map<String, Object> expansions = EXPANSIONS.remove(pluginName);
        if (expansions != null) {
            expansions.values().forEach(PapiExpansion::unregister);
        }
        ALIASES.remove(pluginName);
    }

    /** Removes every expansion and stops the refreshing pass. */
    public static void releaseAll() {
        EXPANSIONS.values().forEach(expansions ->
                expansions.values().forEach(PapiExpansion::unregister));
        EXPANSIONS.clear();
        ALIASES.clear();
        stopRefreshing();
    }
}
