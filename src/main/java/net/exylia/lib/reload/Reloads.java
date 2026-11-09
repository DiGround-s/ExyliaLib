package net.exylia.lib.reload;

import net.exylia.lib.config.Languages;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Prefixes;
import net.exylia.lib.text.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    /** The latest reload each plugin declared, so an applied default can be made live. */
    private static final Map<String, Reloads> DECLARED = new ConcurrentHashMap<>();
    private static final long TICKS_BETWEEN_PLUGINS = 5;

    private final Plugin plugin;
    private final Debug debug;
    private final List<Step> steps = new ArrayList<>();
    /** The language this plugin's files were last read in. */
    private volatile String language;

    private Reloads(Plugin plugin) {
        this.plugin = plugin;
        this.debug = Debug.of(plugin);
        this.language = languageOf(plugin);
    }

    /**
     * Starts declaring what reloading this plugin means.
     *
     * @param plugin the plugin being reloaded
     * @return a new, empty reload
     */
    public static @NotNull Reloads of(@NotNull Plugin plugin) {
        Reloads reloads = new Reloads(plugin);
        DECLARED.put(plugin.getName(), reloads);
        return reloads;
    }

    /**
     * The reload a plugin declared most recently.
     *
     * <p>What {@code /exylialib updates} runs after writing a new default into
     * one of that plugin's files, so the change is live without asking the
     * owner to reload it by hand.
     *
     * @param pluginName the plugin's name
     * @return its reload, or {@code null} when it never declared one
     * @since 1.158.0
     */
    public static @Nullable Reloads declared(@NotNull String pluginName) {
        return DECLARED.get(pluginName);
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
        language = languageOf(plugin);
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
            Text.from(plugin, chatLine(report)).send(sender);
        }
        return report;
    }

    /**
     * Writes the line the player sees.
     *
     * <p>The console gets {@link Report#describe()}, which is one plain
     * sentence a log can be grepped for. A player gets the same facts as a
     * message from this plugin: its prefix, a sound, and the numbers picked
     * out, because on a server running several of these plugins an unbranded
     * {@code Reloaded 4 steps} does not say who answered.
     *
     * <p>{@code %prefix%} is only written when the plugin has one. Text with a
     * plugin but no registered prefix leaves the token in place, and a literal
     * {@code %prefix%} in chat is worse than no prefix at all.
     */
    private String chatLine(Report report) {
        String prefix = Prefixes.get(plugin) == null ? "" : "%prefix% ";
        if (report.ok()) {
            return "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|0.7|1.0]" + prefix
                    + (report.steps() == 1
                            ? Phrases.tr("{success}Reloaded {highlight}{0} {success}step in {info}{1}ms",
                                    report.steps(), report.millis())
                            : Phrases.tr("{success}Reloaded {highlight}{0} {success}steps in {info}{1}ms",
                                    report.steps(), report.millis()));
        }
        return "[sound:ENTITY_VILLAGER_NO|0.7|1.0]" + prefix
                + Phrases.tr("{warning}Reloaded {highlight}{0}/{1} {warning}steps in {info}{2}ms {letters_black}» {error}{3}",
                        report.steps() - report.failed().size(), report.steps(), report.millis(),
                        String.join(", ", report.failed()));
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
        Deque<Reloads> stale = whereTheLanguageChanged();
        int ran = stale.size();
        for (Map.Entry<String, List<Listener>> listeners : LIBRARY_LISTENERS.entrySet()) {
            // A plugin about to read everything again does this in its turn.
            if (stale.stream().anyMatch(declared -> declared.plugin.getName().equals(listeners.getKey()))) continue;
            for (Listener listener : listeners.getValue()) {
                listener.fire();
                ran++;
            }
        }
        reloadNext(stale);
        return ran;
    }

    /**
     * A plugin that follows the library's language reads every file in the new one when it changes: its menus,
     * messages and catalogues are not only colours a menu keeps, and without this they stay in the old language
     * until the plugin's own reload. A plugin that names its own language is not touched.
     */
    private static Deque<Reloads> whereTheLanguageChanged() {
        Deque<Reloads> stale = new ArrayDeque<>();
        for (Reloads declared : DECLARED.values()) {
            String now = languageOf(declared.plugin);
            if (!now.equals(declared.language)) {
                declared.language = now;
                stale.add(declared);
            }
        }
        return stale;
    }

    /**
     * One plugin at a time with ticks between them: reloading a whole server in one tick stops it for many seconds,
     * and players are on it.
     */
    private static void reloadNext(Deque<Reloads> stale) {
        Reloads next = stale.poll();
        if (next == null) return;
        if (!next.plugin.isEnabled()) {
            reloadNext(stale);
            return;
        }
        Tasks.of(next.plugin).runLater(TICKS_BETWEEN_PLUGINS, () -> {
            try {
                next.run(null);
                for (Listener listener : LIBRARY_LISTENERS.getOrDefault(next.plugin.getName(), List.of())) {
                    if (next.steps.stream().noneMatch(step -> step.action() == listener.action())) listener.fire();
                }
            } finally {
                reloadNext(stale);
            }
        });
    }

    /** Empty for a plugin with no folder, which only a test has. */
    private static String languageOf(Plugin plugin) {
        return plugin.getDataFolder() == null ? "" : Languages.code(plugin);
    }

    /** Drops one plugin's listeners. Called when it disables. */
    public static void release(@NotNull String pluginName) {
        LIBRARY_LISTENERS.remove(pluginName);
        DECLARED.remove(pluginName);
    }

    /** Drops every listener. Called by the library on shutdown. */
    public static void releaseAll() {
        LIBRARY_LISTENERS.clear();
        DECLARED.clear();
    }

    /** How many listeners are registered, for tests and diagnostics. */
    static int listenerCount() {
        return LIBRARY_LISTENERS.values().stream().mapToInt(List::size).sum();
    }

    private record Step(String name, Runnable action, boolean onLibraryReload) {
    }

    private record Listener(Plugin plugin, Runnable action) {

        /**
         * A listener that throws is reported against the plugin that registered it: one plugin's bug must not stop
         * another's rebuild. The plugin is held directly, because looking it up would fail exactly when things are
         * already going wrong.
         */
        void fire() {
            try {
                action.run();
            } catch (Throwable failure) {
                Debug.of(plugin).error("Library reload listener failed", failure);
            }
        }
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
