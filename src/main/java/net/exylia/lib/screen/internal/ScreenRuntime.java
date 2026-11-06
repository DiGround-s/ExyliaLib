package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Screen;
import net.exylia.lib.screen.ScreenSession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Who has which screen open, and what closes it. One screen per player across every plugin.
 *
 * <p>Internal: free to change without notice.
 */
public final class ScreenRuntime {

    private static final Map<UUID, LiveScreen> OPEN = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> SENSITIVITY = new ConcurrentHashMap<>();
    private static volatile boolean warned;

    private ScreenRuntime() {
    }

    /** Installs the close rules under the library's name. Called by the library on enable. */
    public static void init(Plugin library) {
        library.getServer().getPluginManager().registerEvents(new Closer(), library);
    }

    public static ScreenSession open(Plugin plugin, Player player, Screen screen) {
        if (!net.exylia.lib.packet.Packets.isAvailable()) {
            if (!warned) {
                warned = true;
                plugin.getLogger().log(Level.WARNING, "Screens need PacketEvents; " + player.getName()
                        + "'s screen was not opened.");
            }
            return null;
        }
        ScreenPackets.install();
        close(player.getUniqueId());
        LiveScreen live = new LiveScreen(plugin, player, screen);
        OPEN.put(player.getUniqueId(), live);
        live.show();
        return live;
    }

    public static ScreenSession session(Plugin plugin, Player player) {
        LiveScreen live = OPEN.get(player.getUniqueId());
        return live != null && live.plugin() == plugin ? live : null;
    }

    static LiveScreen live(UUID player) {
        return OPEN.get(player);
    }

    /** Called by a session as it closes, so a newer one is never removed. */
    static void closed(LiveScreen live) {
        OPEN.remove(live.viewer().getUniqueId(), live);
    }

    private static void close(UUID player) {
        LiveScreen live = OPEN.get(player);
        if (live != null) live.close();
    }

    public static double sensitivity(UUID player) {
        return SENSITIVITY.getOrDefault(player, 1.0);
    }

    public static void sensitivity(UUID player, double value) {
        SENSITIVITY.put(player, value);
    }

    /** Closes the screens a disabled plugin opened. Called by the library. */
    public static void release(Plugin plugin) {
        for (LiveScreen live : List.copyOf(OPEN.values())) {
            if (live.plugin() == plugin) live.close();
        }
    }

    /** Closes every screen, on library shutdown. */
    public static void releaseAll() {
        for (LiveScreen live : List.copyOf(OPEN.values())) live.close();
    }

    static final class Closer implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            close(event.getPlayer().getUniqueId());
            SENSITIVITY.remove(event.getPlayer().getUniqueId());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(PlayerDeathEvent event) {
            close(event.getEntity().getUniqueId());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorld(PlayerChangedWorldEvent event) {
            close(event.getPlayer().getUniqueId());
        }

        /** The screen stands where the eyes were: a real move (a match found, a warp) leaves it behind. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onTeleport(PlayerTeleportEvent event) {
            if (event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()
                    || event.getFrom().distanceSquared(event.getTo()) > 1) {
                close(event.getPlayer().getUniqueId());
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            if (event.getEntity() instanceof Player player) close(player.getUniqueId());
        }
    }
}
