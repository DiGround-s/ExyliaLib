package net.exylia.lib.util.worldguard.internal;

import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;
import net.exylia.lib.util.PvpZones;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Lifts WorldGuard's {@code pvp deny} for the hits a game {@linkplain PvpZones#allow allows}.
 * Registered only when WorldGuard is installed.
 */
public final class WorldGuardPvpListener implements Listener {

    @EventHandler
    public void onRefused(DisallowedPVPEvent event) {
        if (PvpZones.allowedByGame(event.getAttacker(), event.getDefender())) event.setCancelled(true);
    }
}
