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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * modules.commandHandlers().forEach(lamp::register);
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
 * <h2>Switching a module while the server runs</h2>
 * {@link #setEnabled(String, boolean)} turns one module on or off live, with
 * the modules that need it, and answers a {@link ModuleToggle}. Its listeners
 * and placeholders come off, its timers stop, and its actions and commands
 * stay registered but refuse while it is off. Persisting the switch is the
 * plugin's: write it to the config the switches function reads.
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
    /** Switches set live by {@link #setEnabled}, ahead of the config's. */
    private final Map<String, Boolean> overrides = new ConcurrentHashMap<>();
    /** What each running module registered while it enabled, so switching it off can take it back. */
    private final Map<String, Footprint> footprints = new ConcurrentHashMap<>();
    /** Modules whose actions, menus are already registered or handed out. */
    private final Set<String> actionsDone = ConcurrentHashMap.newKeySet();
    private final Set<String> menusDone = ConcurrentHashMap.newKeySet();
    /** Which module each handed-out command handler class (and its superclasses) belongs to. */
    private final Map<Class<?>, String> commandOwners = new ConcurrentHashMap<>();
    /** Modules that threw while enabling; not brought back along with a requirement. */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private volatile @org.jetbrains.annotations.Nullable PluginActions actions;

    private record Footprint(Set<Listener> listeners, Set<String> placeholders) {
    }

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

        start(module);
    }

    /** Enables one module, recording what it registered; releases it and answers false if it throws. */
    private boolean start(PluginModule module) {
        Set<Listener> before = listeners();
        Set<String> placeholdersBefore = Set.copyOf(Placeholders.names());
        try {
            module.enable();
        } catch (RuntimeException | LinkageError failure) {
            // LinkageError too: a soft dependency that is missing surfaces as a
            // NoClassDefFoundError the first time a class naming it is loaded.
            debug().error("Module " + module.getId() + " failed to enable and stays off", failure);
            abandon(module, before, placeholdersBefore);
            failed.add(module.getId());
            return false;
        }
        failed.remove(module.getId());
        Set<Listener> added = listeners();
        added.removeAll(before);
        Set<String> names = new java.util.HashSet<>(Placeholders.names());
        names.removeAll(placeholdersBefore);
        footprints.put(module.getId(), new Footprint(added, names));
        return true;
    }

    /**
     * Turns one module on or off while the server runs.
     *
     * <p><b>Off:</b> every running module that needs it (directly or through
     * another) goes off first, the last registered first; for each, its
     * listeners are unregistered, its timers stop, its {@code onDisable} runs
     * and the placeholders it registered answer nothing. Its actions and
     * commands stay registered and refuse (see {@link #offModuleOf(Method)}).
     *
     * <p><b>On:</b> when what it requires is running, its files are read, it
     * enables, its actions are registered if they never were, the players
     * online are handed to it and its timers start. Then the modules that need
     * it and whose switch is on come back too, in registration order.
     *
     * <p>The switch asked for is remembered ahead of the config's for the rest
     * of this run, so a dependant switched off by hand does not come back with
     * its requirement. Writing it to the config is the plugin's job.
     *
     * <p>From the thread the registry is driven from: the main thread, or the
     * global region on Folia.
     *
     * @param id the module's id
     * @param on whether it should run
     * @return what happened
     * @since 1.557.0
     */
    public @NotNull ModuleToggle setEnabled(@NotNull String id, boolean on) {
        PluginModule module = modules.get(id);
        if (module == null) return ModuleToggle.of(ModuleToggle.Status.UNKNOWN);
        overrides.put(id, on);
        if (module.isEnabled() == on) return ModuleToggle.of(ModuleToggle.Status.UNCHANGED);
        return on ? switchOn(module) : switchOff(module);
    }

    private ModuleToggle switchOff(PluginModule module) {
        Set<String> off = new java.util.HashSet<>(Set.of(module.getId()));
        List<PluginModule> going = new ArrayList<>(List.of(module));
        boolean after = false;
        for (PluginModule other : all()) {
            if (other == module) {
                after = true;
            } else if (after && other.isEnabled() && other.requiredModules().stream().anyMatch(off::contains)) {
                off.add(other.getId());
                going.add(other);
            }
        }
        Collections.reverse(going);
        List<String> changed = new ArrayList<>();
        for (PluginModule leaving : going) {
            stop(leaving);
            changed.add(leaving.getId());
        }
        return new ModuleToggle(ModuleToggle.Status.DISABLED, changed, List.of(), List.of());
    }

    private void stop(PluginModule module) {
        Footprint footprint = footprints.remove(module.getId());
        if (footprint != null) footprint.listeners().forEach(HandlerList::unregisterAll);
        try {
            module.disable();
        } catch (RuntimeException | LinkageError failure) {
            debug().error("Module " + module.getId() + " failed to disable cleanly", failure);
        }
        if (footprint != null) {
            for (String name : footprint.placeholders()) Placeholders.register(plugin, name, request -> "");
        }
    }

    private ModuleToggle switchOn(PluginModule module) {
        List<String> missing = missing(module);
        if (!missing.isEmpty()) {
            return new ModuleToggle(ModuleToggle.Status.BLOCKED, List.of(), missing, List.of());
        }
        List<String> menus = new ArrayList<>();
        if (!bringUp(module, menus)) return ModuleToggle.of(ModuleToggle.Status.FAILED);
        Set<String> up = new java.util.HashSet<>(Set.of(module.getId()));
        List<String> changed = new ArrayList<>(List.of(module.getId()));
        for (PluginModule other : all()) {
            if (other.isEnabled() || failed.contains(other.getId()) || !switchedOn(other)) continue;
            if (other.requiredModules().stream().noneMatch(up::contains) || !missing(other).isEmpty()) continue;
            if (bringUp(other, menus)) {
                up.add(other.getId());
                changed.add(other.getId());
            }
        }
        return new ModuleToggle(ModuleToggle.Status.ENABLED, changed, List.of(), menus);
    }

    /** Everything start-up does for one module, in the same order. */
    private boolean bringUp(PluginModule module, List<String> menus) {
        if (!start(module)) return false;
        step(module, "actions", () -> registerActionsOf(module));
        step(module, "menus", () -> {
            if (menusDone.add(module.getId())) menus.addAll(module.menus());
        });
        step(module, "online players", () -> module.loadOnlinePlayers(Bukkit.getOnlinePlayers()));
        step(module, "timers", module::startTimers);
        return true;
    }

    private void step(PluginModule module, String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError failure) {
            debug().error("Module " + module.getId() + " could not register its " + what, failure);
        }
    }

    private void registerActionsOf(PluginModule module) {
        PluginActions namespace = actions;
        if (namespace != null && actionsDone.add(module.getId())) {
            module.actions(namespace.onlyWhile(module::isEnabled));
        }
    }

    /** The required plugins and modules that are not running. */
    private List<String> missing(PluginModule module) {
        List<String> out = new ArrayList<>();
        for (String required : module.requiredPlugins()) {
            if (!Bukkit.getPluginManager().isPluginEnabled(required)) out.add(required);
        }
        for (String required : module.requiredModules()) {
            if (!isEnabled(required)) out.add(required);
        }
        return out;
    }

    /** The switch in force: the one set live, else the config's. */
    private boolean switchedOn(PluginModule module) {
        Boolean live = overrides.get(module.getId());
        return live != null ? live : switches.apply(module.getId()).orElse(false);
    }

    /**
     * The module that owns a command and is not running, for a plugin's command
     * condition to refuse it.
     *
     * <p>Lamp cannot unregister a command cleanly, so the commands of a module
     * switched off live stay registered. The plugin adds one condition to its
     * own Lamp builder (no Lamp type crosses into this library):
     *
     * <pre>{@code
     * .commandCondition(context -> modules.offModuleOf(context.command().function().method())
     *         .ifPresent(id -> {
     *             messages.send(context.actor().sender(), "module-off");
     *             throw new SendableException() { public void sendTo(CommandActor actor) { } };
     *         }))
     * }</pre>
     *
     * <p>Only handlers handed out by {@link #commandHandlers()} or
     * {@link #allCommandHandlers()} are known; a method of a nested class
     * counts for the handler that encloses it.
     *
     * @param command the method behind the command being run
     * @return the module's id, or empty when it runs or the command is no module's
     * @since 1.557.0
     */
    public @NotNull Optional<String> offModuleOf(@NotNull Method command) {
        for (Class<?> type = command.getDeclaringClass(); type != null; type = type.getEnclosingClass()) {
            String owner = commandOwners.get(type);
            if (owner != null) return isEnabled(owner) ? Optional.empty() : Optional.of(owner);
        }
        return Optional.empty();
    }

    /**
     * Every registered module's command handlers, running or not, for a plugin
     * whose modules can be switched on live: a module off at start still has
     * its commands, refused by {@link #offModuleOf(Method)} until it runs.
     * {@code commandHandlers()} of a module that never enabled must therefore
     * not touch what {@code onEnable} builds.
     *
     * @return the handlers, in module order
     * @since 1.557.0
     */
    public @NotNull List<Object> allCommandHandlers() {
        List<Object> handlers = new ArrayList<>();
        for (PluginModule module : all()) {
            step(module, "commands", () -> handlers.addAll(handlersOf(module)));
        }
        return handlers;
    }

    private List<Object> handlersOf(PluginModule module) {
        List<Object> handlers = module.commandHandlers();
        for (Object handler : handlers) {
            for (Class<?> type = handler.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                commandOwners.putIfAbsent(type, module.getId());
            }
        }
        return handlers;
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

    /**
     * Reloads one running module's files and runs its {@code onReload}, as
     * {@link #reloadConfigs()} does for every module. For an in-game editor that
     * changed one module's settings and wants them live without touching the rest.
     *
     * @return {@code false} when no module with this id is running
     * @throws RuntimeException whatever the module's reload threw, after it is logged
     * @since 1.284.0
     */
    public boolean reload(@NotNull String id) {
        PluginModule module = getModule(id).orElse(null);
        if (module == null) return false;
        try {
            module.reloadConfig();
        } catch (RuntimeException | LinkageError failure) {
            debug().error("Module " + id + " failed to reload", failure);
            throw failure;
        }
        return true;
    }

    /** Every running module's actions; before the menus are compiled. */
    public void registerActions(@NotNull PluginActions actions) {
        this.actions = actions;
        eachRunning("actions", this::registerActionsOf);
    }

    /** The menus of every running module, in registration order. */
    public @NotNull List<String> menuIds() {
        List<String> ids = new ArrayList<>();
        eachRunning("menus", module -> {
            menusDone.add(module.getId());
            ids.addAll(module.menus());
        });
        return ids;
    }

    /**
     * Every running module's command handlers, for the plugin to register with
     * its own Lamp: {@code lamp.register(modules.commandHandlers().toArray())}.
     *
     * @return the handlers, in module order
     * @since 1.280.0
     */
    public @NotNull List<Object> commandHandlers() {
        List<Object> handlers = new ArrayList<>();
        eachRunning("commands", module -> handlers.addAll(handlersOf(module)));
        return handlers;
    }

    /**
     * Every running module's commands.
     *
     * @deprecated names Lamp across the library boundary; see
     * {@link PluginModule#commandHandlers()}. Use {@link #commandHandlers()}.
     */
    @Deprecated
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
     * The modules a restart would start or stop: what the config's switches,
     * the installed plugins and the requirements would run, compared with what
     * runs. A module kept off by a requirement that is off is not listed, nor
     * one switched live with {@link #setEnabled} whose switch was written to the
     * config. Each one listed can be applied now with {@code setEnabled}.
     */
    public @NotNull List<String> pendingRestart() {
        Map<String, Boolean> wouldRun = new HashMap<>();
        List<String> pending = new ArrayList<>();
        for (PluginModule module : all()) {
            boolean run = switches.apply(module.getId()).orElse(false)
                    && module.requiredPlugins().stream().allMatch(Bukkit.getPluginManager()::isPluginEnabled)
                    && module.requiredModules().stream().allMatch(r -> wouldRun.getOrDefault(r, false));
            wouldRun.put(module.getId(), run);
            if (run != module.isEnabled()) pending.add(module.getId());
        }
        return pending;
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
