package net.exylia.lib.price;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Something that buys items back, or sells them: a shop, a worth list.
 *
 * <pre>{@code
 * Prices.register(this, stack -> worth.get(stack.getType()) == null
 *         ? Optional.empty()
 *         : Optional.of(new PriceQuote(worth.get(stack.getType()), "", "worth")));
 * }</pre>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Memory only, synchronous, any thread.</b> It is asked for every
 *       block an autosell breaks and for item lines drawn on a packet thread,
 *       so it reads a map or a field and never a database, a file or the
 *       network.</li>
 *   <li><b>Empty means "not mine".</b> The next provider by priority is asked.
 *       A quote with a price of zero or below counts as empty.</li>
 *   <li><b>The base price.</b> Nobody in particular: no sell bonus, no
 *       booster, no wand. Boosting is the sell site's, through
 *       {@link net.exylia.lib.modifier.Modifiers}.</li>
 *   <li><b>A failure is empty.</b> An exception is logged and the next
 *       provider is asked.</li>
 * </ul>
 *
 * @since 1.264.0
 */
@FunctionalInterface
public interface PriceProvider {

    /**
     * What this provider pays for the stack's item, whatever its count.
     *
     * @param stack never air, never changed
     * @return the quote, empty when this provider does not buy it
     */
    @NotNull Optional<PriceQuote> sell(@NotNull ItemStack stack);

    /**
     * What this provider charges for the stack's item, whatever its count.
     *
     * @param stack never air, never changed
     * @return the quote, empty when this provider does not sell it (the default)
     */
    default @NotNull Optional<PriceQuote> buy(@NotNull ItemStack stack) {
        return Optional.empty();
    }
}
