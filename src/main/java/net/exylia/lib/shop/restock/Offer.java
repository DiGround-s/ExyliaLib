package net.exylia.lib.shop.restock;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One thing a restock shop may put on sale. The plugin prices and delivers it
 * by {@code id}; the library only decides whether it is in stock and how many
 * each player may still take.
 *
 * @param id             the plugin's key for it, unique in the pool
 * @param weight         how likely it is to be drawn; zero or less never is
 * @param stockPerPlayer how many each player may take per window
 * @param rarity         a label the plugin reads (for example to announce); may be {@code null}
 * @param announce       whether the plugin should announce it when it appears
 * @since 1.632.0
 */
public record Offer(@NotNull String id, int weight, int stockPerPlayer, @Nullable String rarity, boolean announce) {

    public Offer(@NotNull String id, int weight, int stockPerPlayer) {
        this(id, weight, stockPerPlayer, null, false);
    }
}
