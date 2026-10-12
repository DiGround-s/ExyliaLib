package net.exylia.lib.api.market;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * ExyliaMarket: the player market, auctions, buy orders, the server shop,
 * {@code /sell}, the worth list, sell wands and autosell, and face-to-face trades.
 *
 * <pre>{@code
 * // a plugin that hands out drops: offer each one to the player's autosell first
 * boolean sold = ExyliaAPI.get(MarketService.class).map(m -> m.autoSell(player, drop)).orElse(false);
 * if (!sold) player.getInventory().addItem(drop);
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaMarket enables. Reach it through {@link ExyliaAPI#get(Class)}, and treat
 * an empty result as "this server has no market". Prices are not here: ask
 * ExyliaLib's {@code Prices}, which ExyliaMarket answers.
 *
 * @since 1.11.0
 */
public interface MarketService {

    /**
     * Whether one of ExyliaMarket's modules is running.
     *
     * @param moduleId {@code market}, {@code auctions}, {@code orders}, {@code shop},
     *                 {@code sell}, {@code worth}, {@code sell-wands} or {@code trade}
     * @return {@code true} when it is switched on and enabled
     */
    boolean isModuleEnabled(@NotNull String moduleId);

    /**
     * Offers a drop to the player's autosell.
     *
     * <p>Sold at the price {@code /sell} pays when the player has autosell on and
     * may use it; the money is paid in batches, not at once. Call on the
     * player's own thread.
     *
     * @param player who earned it
     * @param drop   the item, never changed
     * @return {@code true} when it was sold and must not be given as well
     */
    boolean autoSell(@NotNull Player player, @NotNull ItemStack drop);
}
