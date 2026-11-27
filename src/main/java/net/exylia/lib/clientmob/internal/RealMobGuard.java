package net.exylia.lib.clientmob.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityInteractEvent;

/** Real mobs change nothing in the world: they do not trample farmland or press plates. */
final class RealMobGuard implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent event) {
        if (MobRuntime.isReal(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(EntityInteractEvent event) {
        if (MobRuntime.isReal(event.getEntity().getUniqueId())) event.setCancelled(true);
    }
}
