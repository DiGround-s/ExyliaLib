package net.exylia.lib.util.internal;

import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.Effects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Puts back the effects plugins keep through a totem.
 *
 * <p>The resurrect event fires before the totem clears anything, so this is
 * where what the player had is read; the effects go back a tick later, once the
 * totem is done. Dormant until a plugin registers a rule.
 */
public final class TotemEffectsListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        for (Effects.TotemRule rule : Effects.totemRules()) {
            List<String> lines;
            try {
                lines = rule.lines().apply(player);
            } catch (Throwable error) {
                rule.plugin().getLogger().warning("Totem effect rule failed: " + error);
                continue;
            }
            if (lines == null || lines.isEmpty()) continue;
            List<Effects.ParsedEffect> restored = Effects.restoredAfterTotem(lines, name -> {
                PotionEffectType type = PotionEffectType.getByName(name);
                PotionEffect active = type == null ? null : player.getPotionEffect(type);
                return active == null ? null : active.getDuration();
            });
            if (restored.isEmpty()) continue;
            Tasks.of(rule.plugin()).runAtEntity(player, () -> {
                if (player.isOnline() && !player.isDead()) {
                    Effects.apply(player, restored.toArray(Effects.ParsedEffect[]::new));
                }
            });
        }
    }
}
