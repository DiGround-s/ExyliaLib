package net.exylia.lib.camera.internal;

import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Other players taken out of one player's view while they watch something, and given back after. NPCs that are
 * players underneath (tagged {@code NPC}, as Citizens does) stay. Someone joining meanwhile is hidden as well.
 * Every change runs on the viewer's own thread.
 */
public final class PlayerShade implements Listener {

    private static final Map<UUID, Plugin> HIDING = new ConcurrentHashMap<>();
    private static final Set<Plugin> LISTENING = ConcurrentHashMap.newKeySet();

    private PlayerShade() {
    }

    public static void hide(Plugin plugin, Player viewer) {
        if (LISTENING.add(plugin)) Bukkit.getPluginManager().registerEvents(new PlayerShade(), plugin);
        HIDING.put(viewer.getUniqueId(), plugin);
        Tasks.of(plugin).runAtEntity(viewer, () -> {
            if (HIDING.get(viewer.getUniqueId()) != plugin) return;
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other != viewer && !other.hasMetadata("NPC")) viewer.hidePlayer(plugin, other);
            }
        }, null);
    }

    public static void show(Player viewer) {
        Plugin plugin = HIDING.remove(viewer.getUniqueId());
        if (plugin == null || !plugin.isEnabled()) return;
        Tasks.of(plugin).runAtEntity(viewer, () -> {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other != viewer) viewer.showPlayer(plugin, other);
            }
        }, null);
    }

    public static boolean hidden(Player viewer) {
        return HIDING.containsKey(viewer.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        if (joined.hasMetadata("NPC")) return;
        HIDING.forEach((id, plugin) -> {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null || !plugin.isEnabled()) return;
            Tasks.of(plugin).runAtEntity(viewer, () -> {
                if (HIDING.get(id) == plugin && joined.isOnline()) viewer.hidePlayer(plugin, joined);
            }, null);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // A viewer who leaves comes back to a fresh view: nothing to give back.
        HIDING.remove(event.getPlayer().getUniqueId());
    }
}
