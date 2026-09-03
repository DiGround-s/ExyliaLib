package net.exylia.lib.util.teleport.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.util.teleport.TeleportHandle;
import net.exylia.lib.util.teleport.TeleportSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every countdown on the server, and everything that can interrupt one.
 *
 * <h2>One per player</h2>
 * A second teleport for the same player cancels the first rather than stacking.
 * Two countdowns for one player would both fire, and the player would arrive at
 * the first destination and then be dragged to the second — while the effects
 * of both played over each other.
 *
 * <h2>Why the listeners live here rather than in each plugin</h2>
 * A move event fires for every player on every tick they move. Twenty plugins
 * each registering their own handler is twenty lookups per player per tick for
 * a feature that is idle almost always. One handler, one map lookup, and it
 * returns immediately for the ninety-nine players who are not teleporting.
 *
 * <p>Registered against the library rather than against a consumer, so a plugin
 * disabling does not take the listeners of the others with it.
 */
@ApiStatus.Internal
public final class TeleportRuntime implements Listener {

    /** The countdown each player is currently in, if any. */
    private static final Map<UUID, RunningTeleport> ACTIVE = new ConcurrentHashMap<>();

    /** Which plugin started each of those, so a disable can end its own. */
    private static final Map<UUID, String> OWNERS = new ConcurrentHashMap<>();

    /**
     * The channel a proxy listens on, registered once for the whole server.
     *
     * <p>Against the library rather than against a consumer, because it is one
     * registration for one channel: twenty plugins each registering it is
     * twenty entries for the same thing, and the day one of them is disabled
     * the others find their {@code Connect} messages refused.
     */
    private static final String PROXY_CHANNEL = "BungeeCord";

    private static volatile boolean listening;

    /** The library plugin, which owns the listeners and the proxy channel. */
    private static volatile Plugin library;

    private TeleportRuntime() {
    }

    /** Registers the listeners and the proxy channel, once, against the library. */
    public static synchronized void init(@NotNull Plugin plugin) {
        if (listening) {
            return;
        }
        library = plugin;
        Bukkit.getPluginManager().registerEvents(new TeleportRuntime(), plugin);
        try {
            plugin.getServer().getMessenger()
                    .registerOutgoingPluginChannel(plugin, PROXY_CHANNEL);
        } catch (RuntimeException refused) {
            // A server with no messenger, or one that already has the channel.
            // Never fatal: cross-server is the only thing that stops working,
            // and everything local is unaffected.
            Debug.of(plugin).warn("Could not register the proxy channel, so cross-server"
                    + " teleports will not work: " + refused.getMessage());
        }
        listening = true;
    }

    /**
     * Starts a teleport, cancelling whatever countdown that player was in.
     *
     * @param plan what the request decided
     * @return the running teleport
     */
    public static @NotNull TeleportHandle start(@NotNull TeleportPlan plan) {
        UUID id = plan.player().getUniqueId();
        RunningTeleport previous = ACTIVE.get(id);
        if (previous != null) {
            // Ended before the new one is registered, so its own deregistration
            // cannot remove the replacement from under it.
            previous.cancel();
        }

        RunningTeleport teleport = new RunningTeleport(plan, () -> {
            ACTIVE.remove(id);
            OWNERS.remove(id);
        });
        // Registered before it begins: a teleport with no countdown finishes
        // inside begin(), and registering afterwards would leave a finished
        // entry behind that nothing ever removes.
        ACTIVE.put(id, teleport);
        OWNERS.put(id, plan.plugin().getName());
        teleport.begin();
        return teleport;
    }

    /** Whether this player is waiting out a countdown. */
    public static boolean isWarmingUp(@NotNull UUID player) {
        return ACTIVE.containsKey(player);
    }

    /** Ends this player's countdown, if they are in one. */
    public static void cancel(@NotNull UUID player) {
        RunningTeleport teleport = ACTIVE.get(player);
        if (teleport != null) {
            teleport.cancel();
        }
    }

    /** How many countdowns are running across every plugin. */
    public static int active() {
        return ACTIVE.size();
    }

    /**
     * Ends every countdown one plugin started.
     *
     * <p>Called when that plugin is disabled, before its scheduler goes away: a
     * countdown owns an entity timer belonging to it.
     *
     * @param pluginName the plugin's name
     * @return how many were ended
     */
    public static int endAllOf(@NotNull String pluginName) {
        int ended = 0;
        for (Map.Entry<UUID, String> entry : Map.copyOf(OWNERS).entrySet()) {
            if (!entry.getValue().equals(pluginName)) {
                continue;
            }
            RunningTeleport teleport = ACTIVE.get(entry.getKey());
            if (teleport != null) {
                teleport.cancel();
                ended++;
            }
        }
        return ended;
    }

    /** Ends every countdown on the server, on shutdown. */
    public static void endEverything() {
        for (RunningTeleport teleport : List.copyOf(ACTIVE.values())) {
            teleport.cancel();
        }
        ACTIVE.clear();
        OWNERS.clear();
        CrossServer.stop();
        // Both stores go too. Neither is written to disk, so there is nothing
        // to flush; what they hold is only meaningful while the players it
        // belongs to are on this server.
        BackHistory.forgetEverything();
        TpaBook.forgetEverything();
    }

    /**
     * Test seam: forgets everything so a fresh server can be stood up.
     *
     * <p>Public only because the tests live in the package next door.
     */
    @ApiStatus.Internal
    public static synchronized void resetForTests() {
        ACTIVE.clear();
        OWNERS.clear();
        BackHistory.forgetEverything();
        TpaBook.forgetEverything();
        // Server-wide and set by whichever plugin configured itself last, so a
        // test that leaves its own here would decide the next one's arrivals.
        arrivals = new TeleportSettings();
        CrossServer.stop();
        library = null;
        listening = false;
    }

    /**
     * What an arriving player's handover uses.
     *
     * <p>An arrival belongs to no plugin: the proxy moved the player, the join
     * listener is the library's, and there is no request to read a setting
     * from. So the value is published here by
     * {@link net.exylia.lib.util.teleport.PluginTeleports#using} and the last
     * plugin to configure one wins.
     *
     * <p>Server-wide rather than per plugin because that is what the value
     * describes — how long this server's clients take to load a world — and
     * because the alternative is an owner raising a number that does nothing.
     *
     * @param settings the settings an arrival should use
     */
    @ApiStatus.Internal
    public static void arrivalSettings(@NotNull TeleportSettings settings) {
        arrivals = settings;
    }

    /** The settings an arriving player's handover is completed with. */
    private static volatile TeleportSettings arrivals = new TeleportSettings();

    // ------------------------------------------------------------------ events

    /**
     * Cancels a countdown when the player walks out of the block they started
     * on.
     *
     * <p>The cheap check first: almost every move event on a busy server
     * belongs to somebody who is not teleporting, and that case must cost one
     * map lookup and nothing else.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (ACTIVE.isEmpty() || event.isCancelled()) {
            return;
        }
        RunningTeleport teleport = ACTIVE.get(event.getPlayer().getUniqueId());
        if (teleport != null) {
            teleport.movedTo(event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (ACTIVE.isEmpty() || event.isCancelled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        RunningTeleport teleport = ACTIVE.get(player.getUniqueId());
        if (teleport != null) {
            teleport.damaged();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        RunningTeleport teleport = ACTIVE.get(id);
        if (teleport != null) {
            // Ends the timer as well as the countdown. An entity timer stops
            // itself once the entity is gone, but not until its next period,
            // and the registration would outlive the player either way.
            teleport.playerLeft();
        }
        // Nothing about a player who left is worth keeping. The places they
        // walked through are the least useful, and their requests are worse
        // than useless: leaving the ones they sent would let somebody accept a
        // visit from a person who is not on the server.
        BackHistory.forget(id);
        TpaBook.forget(id);
        Plugin owner = library;
        if (owner != null) {
            CrossServer.withdraw(owner, event.getPlayer());
        }
    }

    /**
     * Completes a handover for a player the proxy just moved here.
     *
     * <p>Does nothing at all for every ordinary join, which is almost all of
     * them: the check is one asynchronous read of one key that is absent.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Plugin owner = library;
        if (owner == null) {
            return;
        }
        CrossServer.announce(owner, event.getPlayer());
        CrossServer.claim(owner, event.getPlayer(), arrivals);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        RunningTeleport teleport = ACTIVE.get(event.getPlayer().getUniqueId());
        if (teleport != null) {
            teleport.playerLeft();
        }
    }
}
