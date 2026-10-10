package net.exylia.lib.region.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The player really behind a hit, for rules that only care about players.
 *
 * <p>A hit rarely comes from the player's own hand: an arrow, TNT they lit, a
 * crystal they set off, a lingering potion they threw or the wolf they own all
 * count as them, or a no-PvP region is one anybody walks around.
 *
 * <p>Bukkit API only ({@code Tameable.getOwner} rather than Paper's owner UUID), so
 * it loads on plain Spigot.
 */
final class Culprits {

    /** Enough for TNT lit by a flaming arrow; a chain never loops, this only bounds it. */
    private static final int MAX_HOPS = 4;

    private Culprits() {
        throw new AssertionError("No instances.");
    }

    /**
     * Whoever caused this damage: the damage source first, the only thing that
     * remembers who set off an end crystal, then the entity that dealt it.
     */
    static @Nullable Player playerBehind(EntityDamageEvent event) {
        Player causing = playerBehind(event.getDamageSource().getCausingEntity());
        if (causing != null) return causing;
        return event instanceof EntityDamageByEntityEvent byEntity ? playerBehind(byEntity.getDamager()) : null;
    }

    /**
     * Whoever is behind this entity: itself when it is a player, a projectile's
     * shooter, whoever lit the TNT, whoever threw the potion that left the cloud,
     * a tamed animal's owner while they are online.
     */
    static @Nullable Player playerBehind(@Nullable Entity entity) {
        for (int hop = 0; entity != null && hop < MAX_HOPS; hop++) {
            if (entity instanceof Player player) return player;
            if (entity instanceof Projectile projectile) {
                entity = projectile.getShooter() instanceof Entity shooter ? shooter : null;
            } else if (entity instanceof TNTPrimed tnt) {
                entity = tnt.getSource();
            } else if (entity instanceof AreaEffectCloud cloud) {
                entity = cloud.getSource() instanceof Entity thrower ? thrower : null;
            } else if (entity instanceof Tameable pet) {
                AnimalTamer owner = pet.getOwner();
                return owner == null ? null : Bukkit.getPlayer(owner.getUniqueId());
            } else {
                return null;
            }
        }
        return null;
    }
}
