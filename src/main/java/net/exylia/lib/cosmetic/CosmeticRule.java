package net.exylia.lib.cosmetic;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Decides whether a player's cosmetics should be drawn at all right now.
 *
 * <p>Asked about the wearer, not about the viewer: the question is whether
 * this player is currently somebody whose armour skin, kill effect or arrow
 * trail means anything — not who is allowed to look at them.
 *
 * <p>Asked wherever a cosmetic plugin decides what to draw, which may be a
 * packet thread. Read shared state and nothing else.
 *
 * @since 1.94.0
 */
@FunctionalInterface
public interface CosmeticRule {

    /**
     * Returns whether this player's cosmetics should be shown.
     *
     * @param wearer the player the cosmetic would belong to
     * @return {@code false} to hide every cosmetic this player would have
     */
    boolean shows(@NotNull Player wearer);
}
