# Prices

What an item sells for, whoever buys it back. A shop, a worth list or any
other plugin answers; anybody asks, without depending on the plugin that
answers. *Since 1.264.0.*

```java
// asking: a trade worth, a listing hint, an autosell filter
Prices.sell(stack).ifPresent(quote -> lines.add(quote.format(quote.each())));
BigDecimal worth = Prices.sell(stack, stack.getAmount()).orElse(BigDecimal.ZERO);
if (!Prices.sellable(drop)) return;

// answering: a shop first, a worth list after it
Prices.register(this, shopProvider, 100);
Prices.register(this, worthProvider);          // Prices.NORMAL, 0
```

## Asking

| Method | Answers |
| --- | --- |
| `Prices.sell(stack)` | `Optional<PriceQuote>` for the stack's item, whatever its count |
| `Prices.sell(stack, amount)` | `Optional<BigDecimal>`: what `amount` items pay, rounded down to the currency's decimals (may be zero for a cheap item) |
| `Prices.sellable(stack)` | whether anybody buys it back |
| `Prices.buy(stack)` | `Optional<PriceQuote>` from providers that also sell items |

`null` and air are never priced. No providers is always empty.

A `PriceQuote(price, unit, currency, provider)` is what `unit` items pay
(`unit` is 1 unless a shop sells by the lot), in `currency` (blank is the
default one), and who answered. `each()` and `total(items)` multiply first
and round once, so three items of a lot of three for 1.00 are 1.00, never
0.99. `format(amount)` writes an amount the way the currency does.

## Priority

Providers are asked from the highest priority down, equal priorities in the
order they were registered. **The first non-empty answer wins**; nothing is
combined. An empty answer, a price of zero or below, or an exception (logged)
passes the question to the next provider.

## Not boosted

A quote is the base price for nobody in particular: no sell bonus, no
booster, no wand. Boosting belongs to whoever pays, through
[Modifiers](modifiers.md) under its own source (`shop-sell`, `worth-sell`):
asking a price never pays, and a price provider never needs to know about
boosters.

## Providers

```java
@FunctionalInterface
public interface PriceProvider {
    Optional<PriceQuote> sell(ItemStack stack);
    default Optional<PriceQuote> buy(ItemStack stack) { return Optional.empty(); }
}
```

- **Memory only, synchronous, any thread.** It is asked for every block an
  autosell breaks and for item lines drawn on a packet thread.
- `Prices.register(plugin, provider[, priority])`; released when the plugin
  disables. `Prices.unregister(provider)` for a provider switched off at
  runtime (a module disabled).
- Nothing is derived from the palette: no reload hook.
