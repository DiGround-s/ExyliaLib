package net.exylia.lib.util.mob;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A counted hit on a mob in hits mode ({@link MobBehaviour#usesHits()}).
 *
 * <pre>{@code
 * mobs.onHit(hit -> rewards.give(hit.player(), perHit));
 * }</pre>
 *
 * <p>Told for every counted hit, the one that breaks it included
 * ({@code hitsLeft == 0}); a hit a player's cooldown swallowed is not counted
 * and not told.
 *
 * @param template what the mob was spawned from
 * @param entity   the mob; do not keep it past the handler
 * @param player   who hit it
 * @param hitsLeft hits left after this one
 * @param maxHits  the hits it spawned with
 * @since 1.195.0
 */
public record MobHit(@NotNull MobTemplate template, @NotNull LivingEntity entity, @NotNull Player player,
                     int hitsLeft, int maxHits) {
}
