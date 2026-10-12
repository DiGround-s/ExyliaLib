package net.exylia.lib.module;

import net.exylia.lib.action.PluginActions;
import net.exylia.lib.config.Configs;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * One switchable part of a plugin: a feature a server owner turns on or off in
 * the plugin's config, with its own files, listeners, actions, menus, commands
 * and timers.
 *
 * <pre>{@code
 * public final class MarketModule extends PluginModule {
 *     public MarketModule() { super("market"); }
 *
 *     protected void registerConfigs() {
 *         MarketDefaults.install(config("modules/market/config", MarketDefaults.class).load());
 *     }
 *     protected void onEnable() { manager = new MarketManager(); }
 *     protected void onDisable() { if (manager != null) manager.shutdown(); }
 *     protected void commands(Lamp<BukkitCommandActor> lamp) { lamp.register(new MarketCommand()); }
 *     protected void timers() { timer(1200L, 1200L, () -> manager.sweep()); }
 * }
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #registerConfigs()} declares and reads the module's files. It
 *       runs before {@link #onEnable()} and <b>again on every reload</b>, so it
 *       must be safe to repeat.</li>
 *   <li>{@link #onEnable()} builds the managers and registers listeners. The
 *       module counts as enabled only once it has returned: until then
 *       {@link PluginModules#getModule(String, Class)} does not return it, not
 *       even to its own code. If it throws, the registry unregisters the
 *       listeners it added, empties the placeholders it registered, calls
 *       {@link #onDisable()} and leaves it off, so {@code onDisable} must
 *       tolerate managers that were never built.</li>
 *   <li>{@link #actions(PluginActions)}, then {@link #menus()}, then
 *       {@link #commands(Lamp)} run once, at plugin start, after every module
 *       is enabled and only for the ones that are. Actions come before menus
 *       because compiling a menu resolves the actions its buttons name.</li>
 *   <li>{@link #loadOnlinePlayers(Collection)} runs once, at plugin start, for
 *       players already online during a hot load. Not on reload.</li>
 *   <li>{@link #timers()} runs last at start, and again after every
 *       {@link #onReload()}: the timers it scheduled before are cancelled first,
 *       so an interval changed in a file applies without a restart.</li>
 *   <li>{@link #onReload()} runs after {@code registerConfigs} on reload, only
 *       while the module is enabled. The switch itself is only read at startup.</li>
 *   <li>{@link #onDisable()} flushes and releases, after the module's timers
 *       were cancelled. Modules are disabled in the reverse order they were
 *       registered.</li>
 * </ol>
 *
 * <h2>Threads</h2>
 * Every lifecycle method is called on the thread the registry is driven from,
 * the server's main thread in practice. {@link #isEnabled()} is safe from any
 * thread, database callbacks included.
 *
 * @since 1.276.0
 */
public abstract class PluginModule {

    private final String id;
    // Read from database callbacks as well as the main thread.
    private volatile boolean enabled;
    private volatile Plugin plugin;
    /** What {@link #timer} scheduled; touched only at start, reload and disable. */
    private final List<TaskHandle> timers = new ArrayList<>();

    /**
     * @param id the module's id: its switch in the config and the name other
     *           modules ask for it by, lower-case kebab case such as {@code sell-wands}
     */
    protected PluginModule(@NotNull String id) {
        this.id = id;
    }

    /** The module's id. */
    public final @NotNull String getId() {
        return id;
    }

    /** Whether the module is running: enabled and not disabled since. */
    public final boolean isEnabled() {
        return enabled;
    }

    /**
     * The plugin this module belongs to.
     *
     * @throws IllegalStateException before the module was registered
     */
    protected final @NotNull Plugin plugin() {
        Plugin owner = plugin;
        if (owner == null) throw new IllegalStateException("Module " + id + " is not registered yet.");
        return owner;
    }

    final void attach(Plugin owner) {
        this.plugin = owner;
    }

    final void enable() {
        registerConfigs();
        onEnable();
        this.enabled = true;
        Debug.of(plugin()).log("Module enabled: " + id);
    }

    final void disable() {
        stopTimers();
        try {
            onDisable();
        } finally {
            // Off even when onDisable threw: a half-released module is not usable.
            this.enabled = false;
        }
        Debug.of(plugin()).log("Module disabled: " + id);
    }

    final void reloadConfig() {
        if (!enabled) return;
        try {
            registerConfigs();
            onReload();
        } finally {
            // A file that fails to read still leaves the module its timers.
            startTimers();
        }
    }

    final void startTimers() {
        stopTimers();
        timers();
    }

    private void stopTimers() {
        timers.forEach(TaskHandle::cancel);
        timers.clear();
    }

    /**
     * Starts declaring one of this module's config files, relative to the
     * plugin's data folder and without extension.
     *
     * <p>{@code load()} is idempotent, so declaring the same file again on
     * reload does not touch the disk; re-reading is
     * {@code Configs.reloadAll(plugin)}, which the plugin does once for every
     * module.
     */
    protected final <T extends Record> @NotNull Configs.Builder<T> config(@NotNull String name,
                                                                         @NotNull Class<T> schema) {
        return Configs.define(plugin(), name, schema);
    }

    /**
     * Schedules a global timer that lives as long as this configuration does.
     *
     * <p>Only from {@link #timers()}: it is cancelled on reload and on disable,
     * so the task can use the module's managers without asking whether the
     * module is still running.
     *
     * @param delayTicks  ticks before the first run
     * @param periodTicks ticks between runs; a minute is 1200, a second 20
     */
    protected final void timer(long delayTicks, long periodTicks, @NotNull Runnable task) {
        timers.add(Tasks.of(plugin()).runTimer(delayTicks, periodTicks, task));
    }

    /** Declares and reads the module's files; runs at enable and on every reload. */
    protected abstract void registerConfigs();

    /** After the files were read again on reload, while enabled. */
    protected void onReload() {
    }

    /** Builds the module; it is enabled only after this returns. */
    protected abstract void onEnable();

    /** Releases the module; also called after a failed {@link #onEnable()}. */
    protected abstract void onDisable();

    /** The players already online at plugin start; called once, never on reload. */
    protected void loadOnlinePlayers(@NotNull Collection<? extends Player> players) {
    }

    /**
     * The plugins this module cannot run without, by their plugin.yml name.
     * A module missing one stays off with a single line in the log.
     */
    protected @NotNull List<String> requiredPlugins() {
        return List.of();
    }

    /**
     * The modules of the same plugin this module cannot run without, by id.
     *
     * <p>They must be registered before this one. A module whose requirement
     * is switched off or failed to enable stays off with a single line in the
     * log naming it.
     */
    protected @NotNull List<String> requiredModules() {
        return List.of();
    }

    /** Registers the actions this module's menus and items call. */
    protected void actions(@NotNull PluginActions actions) {
    }

    /**
     * The menus this module opens, by id: the path under the data folder
     * without the extension, such as {@code modules/homes/menus/homes}. Only an
     * enabled module's menus are compiled.
     */
    protected @NotNull List<String> menus() {
        return List.of();
    }

    /** Registers this module's commands. */
    protected void commands(@NotNull Lamp<BukkitCommandActor> lamp) {
    }

    /** Schedules this module's timers through {@link #timer}; runs at start and after every reload. */
    protected void timers() {
    }
}
