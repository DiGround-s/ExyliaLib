package net.exylia.lib.spatial.crate;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * One reward as the crate opening draws it.
 *
 * @param id     the reward's id
 * @param icon   the item it is drawn as
 * @param name   its name, formatting included
 * @param rarity its rarity's name, coloured: {@code {highlight}Legendary}
 * @param colour its rarity's colour, a palette token or {@code #rrggbb}: the glow, the marker, the burst
 * @param rank   its rarity's place, 1 for the commonest up to {@code ranks}: how big the reveal is
 * @param ranks  how many rarities there are
 * @since 1.416.0
 */
public record CrateFace(@NotNull String id, @NotNull ItemStack icon, @NotNull String name, @NotNull String rarity,
                        @NotNull String colour, int rank, int ranks) {

    public CrateFace {
        icon = icon.clone();
        ranks = Math.max(1, ranks);
        rank = Math.clamp(rank, 1, ranks);
    }

    /** Where its rarity stands, 0 for the commonest and 1 for the rarest. */
    public double weight() {
        return ranks == 1 ? 1 : (rank - 1) / (double) (ranks - 1);
    }
}
