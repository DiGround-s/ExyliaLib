package net.exylia.lib.module;

import net.exylia.lib.action.PluginActions;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.placeholder.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Every {@link PluginModule} a plugin has, in the order they were registered.
 *
 * <pre>{@code
 * modules = PluginModules.of(plugin, id -> Settings.get().modules().switchOf(id));
 * modules.register(new MarketModule());
 * modules.register(new AuctionsModule());   // requiredModules() = ["market"]
 * modules.registerActions(actions);
 * menus.load(modules.menuIds());
 * modules.registerCommands(lamp);
 * modules.loadOnlinePlayers(Bukkit.getOnlinePlayers());
 * modules.startTimers();
 * // on reload:  modules.reloadConfigs();
 * // on disable: modules.disableAll();
 * }</pre>
 *
 * <h2>One module never takes the others down</h2>
 * A module that throws while enabling is logged, has the listeners it had
 * already registered removed, gets its own {@code onDisable}, and stays off;
 * the modules after it still start. The same holds on the way down and for the
 * actions, commands and timers each module registers.
 *
 * <h2>Lookups answer only for running modules</h2>
 * {@link #getModule(String, Class)} is empty for a module that is switched off,
 * missing a requirement or failed to enable. A present module is a usable one.
 *
 * <h2>Threads and lifecycle</h2>
 * Driven from the main thread at plugin start, reload and disable.
 * {@link #getModule(String)} and {@link #isEnabled(String)} are safe from any
 * thread. Nothing here outlives the plugin: {@link #disableAll()} on disable
 * releases every module.
 *
 * @since 1.276.0
 */
public final class PluginModules {

    private final Plugin plugin;
    private final Function<String, Optional<Boolean>> switches;
    private final Map<String, PluginModule> modules = Collections.synchronizedMap(new LinkedHashMap<>());

    private PluginModules(Plugin plugin, Function<String, Optional<Boolean>> switches) {
        this.plugin = plugin;
        this.switches = switches;
    }

    /**
     * A registry for one plugin.
     *
     * @param plugin   the plugin the modules belong to
     * @param switches each module's switch by id, read from the plugin's config;
     *                 empty for an id with no switch, which stays off and is
     *                 reported, since a misspelt key would otherwise never load
     *                 and never say why
     */
    public static @NotNull PluginModules of(@NotNull Plugin plugin,
                                            @NotNull Function<String, Optional<Boolean>> switches) {
        return new PluginModules(plugin, switches);
    }

    /**
     * Adds a module and, when its switch is on and what it requires is
     * running, enables it.
     *
     * <p>Order matters: a module may read the modules registered before it
     * while it enables, and they are disabled in the reverse order.
     */
    public void register(@NotNull PluginModule module) {
        module.attach(plugin);
        modules.put(module.getId(), module);
        Optional<Boolean> switched = switches.apply(module.getId());
        if (switched.isEmpty()) {
            debug().error("Module " + module.getId() + " has no switch in the config, so it stays off.", null);
            return;
        }
        if (!switched.get()) return;
        for (String required : module.requiredPlugins()) {
            if (!Bukkit.getPluginManager().isPluginEnabled(required)) {
                debug().warn("Module " + module.getId() + " stays off: it needs " + required
                        + ", which is not installed or not enabled.");
                return;
            }
        }
        for (String required : module.requiredModules()) {
            if (!isEnabled(required)) {
                debug().warn("Module " + module.getId() + " stays off: it needs the " + required
                        + " module, which is off.");
                return;
            }
        }

        Set<Listener> before = listeners();
        Set<String> placeholdersBefore = Placeholders.names();
        try {
            module.enable();
        } catch (RuntimeException | LinkageError failure) {
            // LinkageError too: a soft dependency that is missing surfaces as a
            // NoClassDefFoundError the first time a class naming it is loaded.
            debug().error("Module " + module.getId() + " failed to enable and stays off", failure);
            abandon(module, before, placeholdersBefore);
        }
    }

    /** Disables every running module, the last registered first. */
    public void disableAll() {
        List<PluginModule> running = new ArrayList<>(all());
        Collections.reverse(running);
        for (PluginModule module : running) {
            if (!module.isEnabled()) continue;
            try {
                module.disable();
            } catch (RuntimeException | LinkageError failure) {
                debug().error("Module " + module.getId() + " failed to disable cleanly", failure);
            }
        }
    }

    /**
     * Reloads every running module's files, each on its own.
     *
     * @throws IllegalStateException naming the modules that failed, after the
     *                               rest reloaded
     */
    public void reloadConfigs() {
        List<String> failed = new ArrayList<>();
        for (PluginModule module : all()) {
            try {
                module.reloadConfig();
            } catch (RuntimeException | LinkageError failure) {
                debug().error("Module " + module.getId() + " failed to reload", failure);
                failed.add(module.getId());
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("Modules that failed to reload: " + String.join(", ", failed));
        }
    }

    /** Every running module's actions; before the menus are compiled. */
    public void registerActions(@NotNull PluginActions actions) {
        eachRunning("actions", module -> module.actions(actions));
    }

    /** The menus of every running module, in registration order. */
    public @NotNull List<String> menuIds() {
        List<String> ids = new ArrayList<>();
        eachRunning("menus", module -> ids.addAll(module.menus()));
        return ids;
    }

    /** Every running module's commands. */
    public void registerCommands(@NotNull Lamp<BukkitCommandActor> lamp) {
        eachRunning("commands", module -> module.commands(lamp));
    }

    /** Every running module's timers; last at start, once players are loaded. */
    public void startTimers() {
        eachRunning("timers", PluginModule::startTimers);
    }

    /** Hands the players already online to every running module; once, at start. */
    public void loadOnlinePlayers(@NotNull Collection<? extends Player> players) {
        eachRunning("online players", module -> module.loadOnlinePlayers(players));
    }

    /**
     * The modules whose switch no longer matches what is running. A switch is
     * only applied at startup, so a reload can only report it.
     */
    public @NotNull List<String> pendingRestart() {
        return all().stream()
                .filter(m -> switches.apply(m.getId()).orElse(false) != m.isEnabled())
                .map(PluginModule::getId)
                .toList();
    }

    /** The module with this id, when it is running. */
    public @NotNull Optional<PluginModule> getModule(@NotNull String id) {
        return Optional.ofNullable(modules.get(id)).filter(PluginModule::isEnabled);
    }

    /** The module with this id and type, when it is running. */
    public <T extends PluginModule> @NotNull Optional<T> getModule(@NotNull String id, @NotNull Class<T> type) {
        return getModule(id).filter(type::isInstance).map(type::cast);
    }

    /** Whether the module with this id is running. */
    public boolean isEnabled(@NotNull String id) {
        return getModule(id).isPresent();
    }

    /** Every registered module, running or not, in registration order. */
    public @NotNull List<PluginModule> all() {
        synchronized (modules) {
            return List.copyOf(modules.values());
        }
    }

    private void eachRunning(String what, Consumer<PluginModule> step) {
        for (PluginModule module : all()) {
            if (!module.isEnabled()) continue;
            try {
                step.accept(module);
            } catch (RuntimeException | LinkageError failure) {
                debug().error("Module " + module.getId() + " could not register its " + what, failure);
            }
        }
    }

    /**
     * Undoes what a module that failed to enable had done so far: its new
     * listeners are unregistered, its new placeholders answer nothing (they
     * cannot be removed), and its {@code onDisable} releases the rest.
     */
    private void abandon(PluginModule module, Set<Listener> before, Set<String> placeholdersBefore) {
        for (Listener listener : listeners()) {
            if (!before.contains(listener)) HandlerList.unregisterAll(listener);
        }
        for (String name : Placeholders.names()) {
            if (!placeholdersBefore.contains(name)) Placeholders.register(plugin, name, request -> "");
        }
        try {
            module.onDisable();
        } catch (RuntimeException | LinkageError failure) {
            debug().error("Module " + module.getId() + " could not release what it had started", failure);
        }
    }

    private Set<Listener> listeners() {
        Set<Listener> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RegisteredListener registered : HandlerList.getRegisteredListeners(plugin)) {
            out.add(registered.getListener());
        }
        return out;
    }

    private Debug debug() {
        return Debug.of(plugin);
    }
}
