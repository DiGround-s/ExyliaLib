package net.exylia.lib.effect.internal;

import org.bukkit.entity.Firework;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Keeps the library's fireworks cosmetic.
 *
 * <p>A firework with effects hurts everything near its explosion, and a
 * celebration that damages the winner is not a celebration. Every firework
 * the library spawns is tagged before it goes off, and the damage it deals is
 * cancelled here. Fireworks spawned by players or other plugins are untouched.
 */
public final class HarmlessFireworks implements Listener {

    private static final String TAG = "exylialib_harmless";

    /**
     * Marks a firework as one whose explosion hurts nobody.
     *
     * @param firework the firework, before it detonates
     */
    public static void tag(Firework firework) {
        firework.addScoreboardTag(TAG);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework
                && firework.getScoreboardTags().contains(TAG)) {
            event.setCancelled(true);
        }
    }
}
