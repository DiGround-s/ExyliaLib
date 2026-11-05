package net.exylia.lib.util.internal;

import net.exylia.lib.util.SpawnOrigin;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Carries a mob's origin over to what it turns into: a split, a conversion,
 * a shearing. Natural parents write nothing, so only farm mobs pay the tag.
 */
public final class SpawnOriginListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        SpawnOrigin origin = SpawnOrigin.of(event.getEntity());
        if (origin == SpawnOrigin.NATURAL) return;
        for (Entity made : event.getTransformedEntities()) {
            made.getPersistentDataContainer().set(SpawnOrigin.KEY, PersistentDataType.STRING, origin.name());
        }
    }
}
