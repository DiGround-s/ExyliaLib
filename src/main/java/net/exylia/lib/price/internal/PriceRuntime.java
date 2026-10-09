package net.exylia.lib.price.internal;

import net.exylia.lib.price.PriceProvider;
import net.exylia.lib.price.PriceQuote;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Who prices items, highest priority first, per plugin, released with it. */
public final class PriceRuntime {

    private record Provided(String plugin, PriceProvider provider, int priority) {
    }

    private static final Logger LOG = Logger.getLogger("ExyliaLib");
    /** Highest priority first; equal priorities in the order they were registered. */
    private static final List<Provided> PROVIDERS = new CopyOnWriteArrayList<>();

    private PriceRuntime() {
    }

    public static synchronized void register(@NotNull String plugin, @NotNull PriceProvider provider, int priority) {
        int at = 0;
        while (at < PROVIDERS.size() && PROVIDERS.get(at).priority() >= priority) at++;
        PROVIDERS.add(at, new Provided(plugin, provider, priority));
    }

    public static void unregister(@NotNull PriceProvider provider) {
        PROVIDERS.removeIf(provided -> provided.provider() == provider);
    }

    public static void release(@NotNull String plugin) {
        PROVIDERS.removeIf(provided -> provided.plugin().equals(plugin));
    }

    public static @NotNull Optional<PriceQuote> sell(@NotNull ItemStack stack) {
        return first(stack, PriceProvider::sell);
    }

    public static @NotNull Optional<PriceQuote> buy(@NotNull ItemStack stack) {
        return first(stack, PriceProvider::buy);
    }

    /** The first provider's quote above zero; a provider that throws is skipped. */
    private static Optional<PriceQuote> first(ItemStack stack,
                                              BiFunction<PriceProvider, ItemStack, Optional<PriceQuote>> ask) {
        for (Provided provided : PROVIDERS) {
            Optional<PriceQuote> quote;
            try {
                quote = ask.apply(provided.provider(), stack);
            } catch (RuntimeException broken) {
                LOG.log(Level.WARNING, "Price provider of " + provided.plugin() + " failed; skipped.", broken);
                continue;
            }
            if (quote != null && quote.isPresent() && quote.get().price().signum() > 0) return quote;
        }
        return Optional.empty();
    }
}
