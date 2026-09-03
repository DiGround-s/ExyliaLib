package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.EffectConfig;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Keeps track of what is showing, and makes sure none of it outlives its owner.
 *
 * <p>A display that is never stopped is a leak a player can see: a boss bar left
 * on screen after the plugin that made it is gone, which no command can remove
 * because nothing knows about it any more. Every display is registered here, so
 * it can always be found and stopped.
 */
public final class EffectRuntime {

    /** Every showing display, keyed by identity so equal-looking ones stay distinct. */
    private static final Map<ActiveDisplay, Boolean> ACTIVE = new ConcurrentHashMap<>();

    /**
     * A plugin that called {@link #owner}: its scheduler, for ticking its
     * effects, and its name, for stopping them when it disables.
     */
    public record Registration(Plugin plugin, TaskScheduler scheduler) {
    }

    /**
     * One registration per plugin, like every other module in this library.
     *
     * <p>The first version kept a single global owner, which two plugins on one
     * server would overwrite in each other: displays scheduled under the wrong
     * plugin and cleaned up under the wrong name.
     */
    private static final Map<String, Registration> REGISTRATIONS = new ConcurrentHashMap<>();

    private EffectRuntime() {
    }

    /** Records which plugin owns the effects created from now on. */
    public static void owner(Plugin plugin) {
        REGISTRATIONS.put(plugin.getName(), new Registration(plugin, Tasks.of(plugin)));
    }

    /** Forgets a plugin's registration, called when it is disabled. */
    public static void release(String pluginName) {
        REGISTRATIONS.remove(pluginName);
    }

    /** Forgets every registration. Used on shutdown and by tests. */
    public static void releaseAll() {
        REGISTRATIONS.clear();
    }

    /**
     * Resolves which registration an effect belongs to.
     *
     * <p>An effect created through {@code Effects.of(plugin)} carries its
     * owner's name, so the answer is exact. One created through the static
     * builders carries nothing, which only has an answer when exactly one
     * plugin owns effects on this server — anything else would be a guess, and
     * a wrong guess is a display another plugin's disable kills.
     *
     * @param stampedOwner the owner stamped on the builder, or {@code null}
     * @return the registration to run and clean up under
     */
    static Registration resolve(@Nullable String stampedOwner) {
        if (stampedOwner != null) {
            return REGISTRATIONS.computeIfAbsent(stampedOwner, name -> {
                Plugin plugin = lookUp(name);
                if (plugin == null) {
                    throw new IllegalStateException(
                            "No plugin named " + name + " is loaded, so its effects have"
                                    + " nowhere to run");
                }
                return new Registration(plugin, Tasks.of(plugin));
            });
        }
        // Nobody stamped an owner, so work it out from who is calling. This
        // used to be a remembered value that a plugin had to set in onEnable,
        // and forgetting it crashed the first boss bar shown — a contract that
        // only fails at runtime is not a contract worth keeping.
        if (REGISTRATIONS.size() == 1) {
            // One plugin owns effects here: no ambiguity to resolve, and this
            // is the common case on a small server.
            return REGISTRATIONS.values().iterator().next();
        }
        Plugin caller = callingPlugin();
        if (caller != null) {
            return resolve(caller.getName());
        }
        throw new IllegalStateException(
                "Could not work out which plugin these effects belong to;"
                        + " create them through Effects.of(plugin)");
    }

    /**
     * Works out which plugin is calling, from the class that called in.
     *
     * <p>Every plugin is loaded by its own classloader, which is what
     * {@link JavaPlugin#getProvidingPlugin} reads. Walking the stack costs
     * nothing here: this runs once per display, not once per tick.
     *
     * @return the calling plugin, or {@code null} when the call came from
     *         inside the library or from a test
     */
    private static Plugin callingPlugin() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(type -> !type.getName().startsWith("net.exylia.lib."))
                        .map(EffectRuntime::pluginOf)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null));
    }

    /** Finds a loaded plugin by name, tolerating a server that has none yet. */
    private static Plugin lookUp(String name) {
        try {
            return Bukkit.getPluginManager().getPlugin(name);
        } catch (Throwable noServer) {
            // No plugin manager: very early startup, or a unit test.
            return null;
        }
    }

    private static Plugin pluginOf(Class<?> type) {
        try {
            return JavaPlugin.getProvidingPlugin(type);
        } catch (Throwable notAPlugin) {
            // A JDK or server class, or no plugin classloader at all in tests.
            return null;
        }
    }

    /**
     * Returns the scheduler effects run on.
     *
     * <p>Effects are driven through the task module rather than a scheduler of
     * their own, which is what makes them work unchanged on Folia: a boss bar
     * tracking a player is ticked on the thread that owns that player.
     */
    static TaskScheduler scheduler() {
        return resolve(null).scheduler();
    }

    /** Returns the logger of the plugin that owns effects, or the library's. */
    static Logger logger(@Nullable String stampedOwner) {
        Registration registration = stampedOwner == null
                ? (REGISTRATIONS.size() == 1 ? REGISTRATIONS.values().iterator().next() : null)
                : REGISTRATIONS.get(stampedOwner);
        return registration == null ? Logger.getLogger("ExyliaLib") : registration.plugin().getLogger();
    }

    /** Returns whether any plugin owns effects, so callers can degrade rather than throw. */
    static boolean hasOwner() {
        return !REGISTRATIONS.isEmpty();
    }

    /**
     * Ends whatever this display is about to replace.
     *
     * <p>Same kind, same player, same plugin: one action bar or one title,
     * being handed over. Scoped to the owner because another plugin's action
     * bar is not this one's to take — two plugins writing the same line is a
     * server's decision to make, not a race this module gets to settle.
     *
     * @param display the display that is starting
     */
    static void supersede(ActiveDisplay display) {
        // Iterated live: a ConcurrentHashMap tolerates the removal that
        // superseded() does from inside the loop, and copying every active
        // display on the server for every bar shown was most of what showing
        // a bar cost.
        for (ActiveDisplay showing : ACTIVE.keySet()) {
            if (showing != display
                    && showing.getClass() == display.getClass()
                    && showing.ownedBy(display.owner())
                    && showing.isFor(display.viewer())) {
                showing.superseded();
            }
        }
    }

    static void register(ActiveDisplay display) {
        ACTIVE.put(display, Boolean.TRUE);
    }

    static void unregister(ActiveDisplay display) {
        ACTIVE.remove(display);
    }

    /**
     * Stops every effect a plugin started.
     *
     * @param pluginName the plugin's name
     * @return how many were stopped
     */
    public static int stopAll(String pluginName) {
        int stopped = 0;
        for (ActiveDisplay display : List.copyOf(ACTIVE.keySet())) {
            if (display.ownedBy(pluginName)) {
                display.stop();
                stopped++;
            }
        }
        return stopped;
    }

    /**
     * Stops every effect showing to one player.
     *
     * @param viewer the player
     * @return how many were stopped
     */
    public static int stopFor(Player viewer) {
        int stopped = 0;
        for (ActiveDisplay display : List.copyOf(ACTIVE.keySet())) {
            if (display.isFor(viewer)) {
                display.stop();
                stopped++;
            }
        }
        return stopped;
    }

    /** Stops everything. Used on shutdown and by tests. */
    public static void stopEverything() {
        for (ActiveDisplay display : List.copyOf(ACTIVE.keySet())) {
            display.stop();
        }
        ACTIVE.clear();
    }

    /**
     * Re-draws every showing effect after the palette changed.
     *
     * <p>A static effect is drawn once and left alone, which is what makes a
     * permanent boss bar cost one packet instead of a task. That same saving
     * means nothing re-parses it when the colours change, so it would keep
     * the old ones until something stopped it — this is that something.
     *
     * @return how many were re-drawn
     */
    public static int invalidateAll() {
        int redrawn = 0;
        for (ActiveDisplay display : List.copyOf(ACTIVE.keySet())) {
            display.invalidate();
            redrawn++;
        }
        return redrawn;
    }

    /** Returns how many displays are showing. */
    public static int active() {
        return ACTIVE.size();
    }

    /**
     * Plays an effect declared in config.
     *
     * @param effect the configured effect
     * @param viewer who sees it
     * @return the display when it stays on screen, otherwise {@code null}
     */
    public static Display play(EffectConfig effect, Player viewer) {
        return play(effect, viewer, null);
    }

    /**
     * Plays an effect declared in config, under a known owner.
     *
     * @param effect the configured effect
     * @param viewer who sees it
     * @param owner  the owning plugin's name, or {@code null} to work it out
     *               from the caller
     * @return the display when it stays on screen, otherwise {@code null}
     */
    public static Display play(EffectConfig effect, Player viewer, @Nullable String owner) {
        return ConfigPlayer.play(effect, viewer, owner);
    }

    /** Plays a configured effect that counts down for a length the caller knows. */
    public static Display play(EffectConfig effect, Player viewer, @Nullable String owner, double seconds) {
        return ConfigPlayer.play(effect, viewer, owner, seconds);
    }

    /** Plays a configured effect for everybody online. */
    public static void playAll(EffectConfig effect) {
        playAll(effect, null);
    }

    /** Plays a configured effect for everybody online, under a known owner. */
    public static void playAll(EffectConfig effect, @Nullable String owner) {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        for (Player player : players) {
            ConfigPlayer.play(effect, player, owner);
        }
    }
}
