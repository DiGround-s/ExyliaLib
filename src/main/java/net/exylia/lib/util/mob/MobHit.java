package net.exylia.lib.util.mob;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A player's hit on a custom mob: a counted hit in hits mode
 * ({@link MobBehaviour#usesHits()}), or a melee hit or projectile that did
 * damage in health mode.
 *
 * <pre>{@code
 * mobs.onHit(hit -> rewards.give(hit.player(), perHit));
 * }</pre>
 *
 * <p>Told for every counted hit, the one that breaks it included
 * ({@code hitsLeft == 0}); a hit a player's cooldown swallowed is not counted
 * and not told. In health mode a hit that did no damage — cancelled, or taken
 * whole by a shield — is not told either; the killing one is.
 *
 * @param template what the mob was spawned from
 * @param entity   the mob; do not keep it past the handler
 * @param player   who hit it
 * @param hitsLeft hits left after this one; in health mode, the health left,
 *                 rounded up
 * @param maxHits  the hits it spawned with; in health mode, its max health,
 *                 rounded up
 * @param damage   the damage it took, capped at the health it had; 1 in hits
 *                 mode
 * @since 1.195.0
 */
public record MobHit(@NotNull MobTemplate template, @NotNull LivingEntity entity, @NotNull Player player,
                     int hitsLeft, int maxHits, double damage) {

    /**
     * Whether it was a counted hit in hits mode, not damage in health mode.
     *
     * @since 1.206.0
     */
    public boolean counted() {
        return template.behaviour().usesHits();
    }
}
