package net.exylia.lib.cosmetic;

import net.exylia.lib.economy.Economy;
import net.exylia.lib.format.Amounts;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a cosmetic costs: an amount of one currency.
 *
 * <pre>{@code
 * price: 500                              # the default currency
 * price: {amount: 500, currency: gems}    # a named one
 *
 * shop:
 *   tier-prices:
 *     common: 500
 *     legendary: {amount: 50, currency: gems}
 * }</pre>
 *
 * <pre>{@code
 * Map<String, Price> tiers = Price.parseAll(config.get("shop.tier-prices"));
 * Optional<Price> price = Price.resolve(Price.parse(entry.price()).orElse(null), entry.tier(), tiers);
 * }</pre>
 *
 * @param amount   how much, always above zero
 * @param currency the currency's id, or {@code null} for the default currency
 * @since 1.239.0
 */
public record Price(@NotNull BigDecimal amount, @Nullable String currency) {

    public Price {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("a price must be above zero, got " + amount);
        }
        currency = currency == null || currency.isBlank() ? null : currency.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a price as a config writes it: a number, a number as text
     * ({@code 2.5k} too), or a block with {@code amount} and {@code currency}.
     *
     * <p>Anything else is empty: zero, a negative, an exponent, text that is
     * not a number. Empty means "no price", so a typo makes a cosmetic
     * unpurchasable rather than free.
     *
     * @param raw the value under the price's key
     * @return the price, or empty when there is none or it cannot be read
     */
    public static @NotNull Optional<Price> parse(@Nullable Object raw) {
        if (raw instanceof ConfigurationSection section) raw = section.getValues(false);
        String currency = null;
        if (raw instanceof Map<?, ?> block) {
            Object named = block.get("currency");
            currency = named == null ? null : named.toString();
            raw = block.get("amount");
        }
        if (raw == null || raw instanceof Map<?, ?> || raw instanceof ConfigurationSection) return Optional.empty();
        // Amounts is the one reader for money: suffixes, no exponents, no ambiguous commas.
        String finalCurrency = currency;
        return Amounts.parse(raw.toString())
                .filter(amount -> amount.signum() > 0)
                .map(amount -> new Price(amount, finalCurrency));
    }

    /**
     * Reads a whole block of prices, such as {@code shop.tier-prices}, keyed by
     * lower-case id. Entries that cannot be read are left out.
     *
     * @param raw the block, a map or a configuration section
     * @return the prices; empty when the block is missing
     */
    public static @NotNull Map<String, Price> parseAll(@Nullable Object raw) {
        if (raw instanceof ConfigurationSection section) raw = section.getValues(false);
        Map<String, Price> prices = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> block) {
            block.forEach((key, value) -> parse(value)
                    .ifPresent(price -> prices.put(String.valueOf(key).trim().toLowerCase(Locale.ROOT), price)));
        }
        return Map.copyOf(prices);
    }

    /**
     * The price of one entry: its own, else its tier's, else none.
     *
     * @param explicit   the entry's own price, or {@code null}
     * @param tier       the entry's tier, or {@code null}
     * @param tierPrices the default price of each tier, from {@link #parseAll}
     * @return the price; empty means the entry is not for sale
     */
    public static @NotNull Optional<Price> resolve(@Nullable Price explicit, @Nullable String tier,
                                                   @NotNull Map<String, Price> tierPrices) {
        if (explicit != null) return Optional.of(explicit);
        if (tier == null) return Optional.empty();
        return Optional.ofNullable(tierPrices.get(tier.trim().toLowerCase(Locale.ROOT)));
    }

    /** The price written the way its currency writes it, such as {@code 500 Gems}: what {@code %price%} shows. */
    public @NotNull String format() {
        return Economy.format(currency, amount);
    }

    /** Whether an economy can take this price right now. */
    public boolean payable() {
        return net.exylia.lib.economy.internal.CurrencyRegistry.resolve(currency).isPresent();
    }

    /** The economy operations of this price's currency. */
    public @NotNull Economy.CurrencyView view() {
        return Economy.of(currency);
    }
}
