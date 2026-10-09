package net.exylia.lib.price;

import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One price for one kind of item: what {@code unit} items pay, in a currency,
 * and which provider said so.
 *
 * <p>A shop sells some products by the lot (four for 1.00). The lot is kept as
 * it is rather than divided into a price per item, so a total is the lot price
 * times the items over the lot size, rounded once: three items of a lot of
 * three are worth exactly the lot, never 0.99.
 *
 * <pre>{@code
 * new PriceQuote(new BigDecimal("2.50"), "", "worth");      // one item for 2.50
 * new PriceQuote(new BigDecimal("1"), 4, "gems", "shop");   // four items for 1 gem
 * }</pre>
 *
 * @param price    what {@code unit} items pay; above zero
 * @param unit     how many items that price is for; at least 1
 * @param currency the currency id; blank for the default one
 * @param provider who answered, such as {@code shop} or {@code worth}
 * @since 1.264.0
 */
public record PriceQuote(@NotNull BigDecimal price, int unit, @NotNull String currency, @NotNull String provider) {

    public PriceQuote {
        if (price == null) throw new IllegalArgumentException("A quote needs a price.");
        unit = Math.max(1, unit);
        currency = currency == null ? "" : currency;
        provider = provider == null ? "" : provider;
    }

    /** A price for one item. */
    public PriceQuote(@NotNull BigDecimal price, @NotNull String currency, @NotNull String provider) {
        this(price, 1, currency, provider);
    }

    /** What one item pays, rounded down to the currency's decimals. */
    public @NotNull BigDecimal each() {
        return total(1);
    }

    /**
     * What a number of items pays, rounded down to the currency's decimals:
     * nobody is paid a fraction the currency cannot hold.
     *
     * <p>Counted by the item rather than by the lot, so a lot of four shows a
     * value on a stack of two; a sale that takes whole lots is the seller's.
     */
    public @NotNull BigDecimal total(int items) {
        BigDecimal total = price.multiply(BigDecimal.valueOf(items))
                .divide(BigDecimal.valueOf(unit), 8, RoundingMode.HALF_UP);
        CurrencyInfo info = Economy.info(currency.isBlank() ? null : currency);
        int decimals = info.decimals() < 0 ? 2 : info.decimals();
        return total.setScale(decimals, RoundingMode.DOWN);
    }

    /** An amount written the way this quote's currency writes money. */
    public @NotNull String format(@NotNull BigDecimal amount) {
        return Economy.format(currency.isBlank() ? null : currency, amount);
    }
}
