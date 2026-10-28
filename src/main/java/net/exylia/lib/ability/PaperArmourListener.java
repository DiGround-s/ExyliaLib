package net.exylia.lib.ability;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Paper's armour event, which also sees right-click equips and dispensers.
 *
 * <p>Loaded by name only on Paper: Spigot never resolves this class.
 */
final class PaperArmourListener implements Listener {

    private final PluginAbilities abilities;

    PaperArmourListener(PluginAbilities abilities) {
        this.abilities = abilities;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmour(PlayerArmorChangeEvent event) {
        abilities.refreshSoon(event.getPlayer());
    }
}
