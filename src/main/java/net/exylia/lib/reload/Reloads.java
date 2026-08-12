package net.exylia.lib.reload;

import net.exylia.lib.debug.Debug;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reloading, for a plugin and from the library.
 *
 * <pre>{@code
 * private final Reloads reloads = Reloads.of(this)
 *         .step("configs", () -> Configs.reloadAll(this))
 *         .step("debug",   () -> debug.enabled(config.get().debug()))
 *         .step("menus",   menus::rebuild);
 *
 * @Subcommand("reload")
 * public void reload(CommandSender sender) {
 *     reloads.run(sender);   // [MyPlugin] Reloaded 3 steps in 12ms
 * }
 * }</pre>
 *
 * <h2>Each side reloads what it owns</h2>
 * A plugin reloads itself and never reloads the library: the palette is
 * everyone's, so recolouring it from one plugin's command would re-send every
 * scoreboard and hologram of every plugin on the server. The library reloads
 * itself through {@code /exylialib reload}.
 *
 * <p>What crosses that line is a notification, not a call: when the library
 * reloads, {@link #onLibraryReload} listeners run, so a plugin can rebuild
 * what it parsed once and kept — a menu built at startup holds the old
 * colours until something rebuilds it. Nobody invokes anybody.
 *
 * <h2>A failed step does not stop the rest</h2>
 * Steps run in the order they were declared, and one that throws is caught,
 * reported and followed by the next. A half-reloaded plugin is worse than one
 * that says plainly which part failed.
 *
 * @since 1.15.0
 */
public final class Reloads {

    /** Listeners by plugin name, so they can be dropped when it disables. */
    private static final Map<String, List<Listener>> LIBRARY_LISTENERS =
            new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final Debug debug;
    private final List<Step> steps = new ArrayList<>();

    private Reloads(Plugin plugin) {
        this.plugin = plugin;
        this.debug = Debug.of(plugin);
    }

    /**
     * Starts declaring what reloading this plugin means.
     *
     * @param plugin the plugin being reloaded
     * @return a new, empty reload
     */
    public static @NotNull Reloads of(@NotNull Plugin plugin) {
        return new Reloads(plugin);
    }

    /**
     * Adds a step, run in declaration order.
     *
     * @param name   what it is called in the report; keep it short
     * @param action what to do
     * @return this, for chaining
     */
    public @NotNull Reloads step(@NotNull String name, @NotNull Runnable action) {
        steps.add(new Step(name, action, false));
        return this;
    }

    /**
     * Adds a step that also runs when the library reloads.
     *
     * <p>For anything derived from the shared palette and kept as state — a
     * menu, a cached component, a pre-rendered scoreboard title. Ordinary
     * steps only run on the plugin's own reload.
     *
     * @param name   what it is called in the report
     * @param action what to do
     * @return this, for chaining
     */
    public @NotNull Reloads stepAlsoOnLibraryReload(@NotNull String name,
                                                    @NotNull Runnable action) {
        steps.add(new Step(name, action, true));
        onLibraryReload(plugin, action);
        return this;
    }

    /**
     * Runs every step and returns what happened.
     *
     * <p>Nothing is printed. Use {@link #run(CommandSender)} to also tell
     * whoever asked.
     *
     * @return the report
     */
    public @NotNull Report run() {
        long started = System.currentTimeMillis();
        List<String> failed = new ArrayList<>();

        for (Step step : steps) {
            try {
                step.action().run();
            } catch (Throwable failure) {
                // Reported, not rethrown: the remaining steps still matter.
                failed.add(step.name());
                debug.error("Reload step '" + step.name() + "' failed", failure);
            }
        }
        return new Report(steps.size(), List.copyOf(failed),
                System.currentTimeMillis() - started);
    }

    /**
     * Runs every step and tells the sender how it went.
     *
     * @param sender who asked, or {@code null} to only log
     * @return the report
     */
    public @NotNull Report run(@Nullable CommandSender sender) {
        Report report = run();
        if (report.ok()) {
            debug.success(report.describe());
        } else {
            debug.warn(report.describe());
        }
        if (sender != null && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            // The console already saw it through the debug line above.
            net.exylia.lib.text.Text.of((report.ok() ? "{success}" : "{warning}")
                    + report.describe()).send(sender);
        }
        return report;
    }

    /** Returns how many steps are declared. */
    public int stepCount() {
        return steps.size();
    }

    /**
     * Runs something after the library reloads.
     *
     * <p>The library never calls into a plugin; it announces that the shared
     * configuration changed and each plugin decides what that means for it.
     *
     * @param plugin   whose listener this is — dropped when it disables
     * @param action   what to rebuild
     */
    public static void onLibraryReload(@NotNull Plugin plugin, @NotNull Runnable action) {
        LIBRARY_LISTENERS
                .computeIfAbsent(plugin.getName(), key -> new CopyOnWriteArrayList<>())
                .add(new Listener(plugin, action));
    }

    /**
     * Tells every listener the library reloaded.
     *
     * <p>Called by the library itself after its own configuration is applied.
     * A listener that throws is reported against its own plugin and does not
     * stop the others.
     *
     * @return how many listeners ran
     */
    public static int fireLibraryReload() {
        int ran = 0;
        for (List<Listener> listeners : LIBRARY_LISTENERS.values()) {
            for (Listener listener : listeners) {
                try {
                    listener.action().run();
                } catch (Throwable failure) {
                    // Reported against the plugin that registered it: one
                    // plugin's bug must not stop another's rebuild. The
                    // plugin is held directly, because looking it up would
                    // fail exactly when things are already going wrong.
                    Debug.of(listener.plugin()).error(
                            "Library reload listener failed", failure);
                }
                ran++;
            }
        }
        return ran;
    }

    /** Drops one plugin's listeners. Called when it disables. */
    public static void release(@NotNull String pluginName) {
        LIBRARY_LISTENERS.remove(pluginName);
    }

    /** Drops every listener. Called by the library on shutdown. */
    public static void releaseAll() {
        LIBRARY_LISTENERS.clear();
    }

    /** How many listeners are registered, for tests and diagnostics. */
    static int listenerCount() {
        return LIBRARY_LISTENERS.values().stream().mapToInt(List::size).sum();
    }

    private record Step(String name, Runnable action, boolean onLibraryReload) {
    }

    private record Listener(Plugin plugin, Runnable action) {
    }

    /**
     * What a reload did.
     *
     * @param steps    how many ran
     * @param failed   the names of those that threw, in order
     * @param millis   how long it all took
     */
    public record Report(int steps, @NotNull List<String> failed, long millis) {

        /** Returns whether every step succeeded. */
        public boolean ok() {
            return failed.isEmpty();
        }

        /** Returns the one-line summary shown to whoever asked. */
        public @NotNull String describe() {
            if (ok()) {
                return "Reloaded " + steps + (steps == 1 ? " step in " : " steps in ")
                        + millis + "ms";
            }
            return "Reloaded " + (steps - failed.size()) + "/" + steps + " steps in "
                    + millis + "ms — failed: " + String.join(", ", failed);
        }
    }
}
