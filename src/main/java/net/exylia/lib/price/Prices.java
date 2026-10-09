package net.exylia.lib.price;

import net.exylia.lib.price.internal.PriceRuntime;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * What an item sells for, whoever buys it back: one question any plugin can
 * ask without depending on the plugin that answers it.
 *
 * <pre>{@code
 * // asking: a trade worth, a listing hint, an autosell filter
 * Prices.sell(stack).ifPresent(quote -> lines.add(quote.format(quote.each())));
 * BigDecimal worth = Prices.sell(stack, stack.getAmount()).orElse(BigDecimal.ZERO);
 * if (!Prices.sellable(drop)) return;
 *
 * // answering: a shop first, a worth list after it
 * Prices.register(this, shopProvider, 100);
 * Prices.register(this, worthProvider);          // priority 0
 * }</pre>
 *
 * <h2>Priority</h2>
 * Providers are asked from the highest priority down, equal priorities in the
 * order they were registered, and the first non-empty answer wins. Nothing is
 * combined: an item the shop prices is the shop's, even where a worth list
 * names it too. No providers is always empty.
 *
 * <h2>What the price is</h2>
 * The base price, for nobody in particular: no sell bonus, no booster, no
 * wand. Boosting stays with whoever pays, through
 * {@link net.exylia.lib.modifier.Modifiers} under its own source
 * ({@code shop-sell}, {@code worth-sell}), so asking a price never pays and
 * paying never needs a price provider to know about boosters.
 *
 * <h2>Threads and lifecycle</h2>
 * Every method is synchronous, memory only and safe from any thread; providers
 * are held to the same (see {@link PriceProvider}). What a plugin registered
 * is released when it disables; a plugin that switches a provider off at
 * runtime calls {@link #unregister(PriceProvider)}. Nothing is derived from the
 * palette, so there is nothing to reload.
 *
 * @since 1.264.0
 */
public final class Prices {

    /** The priority {@link #register(Plugin, PriceProvider)} uses. */
    public static final int NORMAL = 0;

    private Prices() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------------ registry

    /** Adds a provider at {@link #NORMAL} priority. Released with the plugin. */
    public static void register(@NotNull Plugin plugin, @NotNull PriceProvider provider) {
        register(plugin, provider, NORMAL);
    }

    /**
     * Adds a provider. Released with the plugin.
     *
     * @param priority higher is asked first; equal priorities in registration order
     */
    public static void register(@NotNull Plugin plugin, @NotNull PriceProvider provider, int priority) {
        PriceRuntime.register(plugin.getName(), provider, priority);
    }

    /** Takes a provider away before its plugin disables, such as a module switched off. */
    public static void unregister(@NotNull PriceProvider provider) {
        PriceRuntime.unregister(provider);
    }

    // ------------------------------------------------------------------ asking

    /**
     * What the stack's item sells for, whatever its count.
     *
     * <pre>{@code
     * Prices.sell(held).map(quote -> quote.format(quote.each())).orElse("-");
     * }</pre>
     *
     * @param stack {@code null} and air are never sold
     * @return the first provider's quote by priority, empty when nobody buys it
     */
    public static @NotNull Optional<PriceQuote> sell(@Nullable ItemStack stack) {
        if (empty(stack)) return Optional.empty();
        return PriceRuntime.sell(stack);
    }

    /**
     * What a number of the stack's item sells for, rounded down to the
     * currency's decimals (see {@link PriceQuote#total(int)}).
     *
     * <pre>{@code
     * BigDecimal worth = Prices.sell(stack, stack.getAmount()).orElse(BigDecimal.ZERO);
     * }</pre>
     *
     * @return the total in the quote's currency, empty when nobody buys it; it
     *         may round to zero for a cheap item
     */
    public static @NotNull Optional<BigDecimal> sell(@Nullable ItemStack stack, int amount) {
        return sell(stack).map(quote -> quote.total(amount));
    }

    /** Whether anybody buys the stack's item back. */
    public static boolean sellable(@Nullable ItemStack stack) {
        return sell(stack).isPresent();
    }

    /**
     * What the stack's item costs from whoever sells it, for providers that
     * answer {@link PriceProvider#buy}.
     *
     * @return the first provider's quote by priority, empty when nobody sells it
     */
    public static @NotNull Optional<PriceQuote> buy(@Nullable ItemStack stack) {
        if (empty(stack)) return Optional.empty();
        return PriceRuntime.buy(stack);
    }

    /** A material compare, not {@code isAir()}: that reaches the registry on a path run per block broken. */
    private static boolean empty(@Nullable ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }
}
