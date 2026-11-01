package net.exylia.lib.price;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * A block that is no container but holds goods a sell wand can sell: a
 * spawner's storage, a virtual chest. Registered with {@link SellTargets}.
 *
 * @since 1.435.0
 */
public interface SellTarget {

    /**
     * Whether the block is this target's to sell. Memory only: asked for
     * every block a wand is used on, before any permission or cooldown.
     */
    boolean claims(@NotNull Block block);

    /**
     * Sells what a claimed block holds. Called on the block's region thread
     * (the player's, after an interact); the sale may complete later, on any
     * thread.
     *
     * @param factor what the wand multiplies prices by, on top of the player's own bonuses
     * @return what was sold, {@link Sold#NOTHING} when nothing was
     */
    @NotNull CompletableFuture<Sold> sell(@NotNull Player player, @NotNull Block block, double factor);

    /**
     * What a sale sold.
     *
     * @param items how many items left the block
     * @param money what the player was paid, in the default currency
     */
    record Sold(long items, @NotNull BigDecimal money) {

        public static final Sold NOTHING = new Sold(0, BigDecimal.ZERO);

        public Sold {
            money = money == null ? BigDecimal.ZERO : money;
        }

        public boolean isEmpty() {
            return items <= 0;
        }
    }
}
