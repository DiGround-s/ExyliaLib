package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.EffectConfig;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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

    private static volatile Plugin owner;
    private static volatile TaskScheduler scheduler;

    private EffectRuntime() {
    }

    /** Records which plugin owns the effects created from now on. */
    public static void owner(Plugin plugin) {
        owner = plugin;
        scheduler = Tasks.of(plugin);
    }

    /**
     * Returns the scheduler effects run on.
     *
     * <p>Effects are driven through the task module rather than a scheduler of
     * their own, which is what makes them work unchanged on Folia: a boss bar
     * tracking a player is ticked on the thread that owns that player.
     */
    static TaskScheduler scheduler() {
        TaskScheduler known = scheduler;
        if (known == null) {
            throw new IllegalStateException(
                    "Effects.owner(plugin) must be called in onEnable before creating effects");
        }
        return known;
    }

    /** Returns the plugin that owns effects, for cleanup. */
    static String ownerName() {
        Plugin plugin = owner;
        return plugin == null ? "" : plugin.getName();
    }

    static Logger logger() {
        Plugin plugin = owner;
        return plugin == null ? Logger.getLogger("ExyliaLib") : plugin.getLogger();
    }

    /** Returns whether an owner has been set, so effects can degrade rather than throw. */
    static boolean hasOwner() {
        return scheduler != null;
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
        return ConfigPlayer.play(effect, viewer);
    }

    /** Plays a configured effect for everybody online. */
    public static void playAll(EffectConfig effect) {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        for (Player player : players) {
            ConfigPlayer.play(effect, player);
        }
    }
}
