# Restock shops

A limited stock of offers that rotates every N minutes, with a per-player
stock limit: seed shops, wandering traders, rotating deals. The library decides
what is in stock and how many each player may still take; the plugin prices and
delivers. *Since 1.632.0.*

```java
Restock seeds = Restocks.of(this, "seeds")
        .every(Duration.ofMinutes(10))
        .slots(4)
        .pool(List.of(
                new Offer("carrot", 50, 8),                    // id, weight, per-player stock
                new Offer("golden_seed", 3, 1, "rare", true))) // rarity, announce
        .scope(config.scope())   // default, global, server or a group (NetworkGroups)
        .build();

seeds.current();            // the offers on sale now
seeds.untilRestock();       // for a countdown
seeds.take(uuid, "carrot", 2).thenAccept(ok -> {
    if (ok) charge(player); // the plugin charges and delivers
});
seeds.onRestock(stock -> stock.stream().filter(Offer::announce).forEach(this::announce));
```

## How it works

- **Same stock everywhere, no storage.** The stock is drawn from
  `(shop id, window)` with `window = epoch millis / period`, by weight and
  without replacement (`Weighted`), so every server shows the same offers and
  no leader or table is needed. Pool order does not matter. A weight of 0 never
  appears; a pool smaller than `slots` shows all it has.
- **Changing the pool, period or slots changes the draw.** Keep them equal on
  every server of a shop.
- **Atomic limits.** `take` is all-or-nothing and uses the database's
  compare-and-set (`updateIf`, `insertIfAbsent`), retried when another server
  wins the row, so two servers cannot sell past `stockPerPlayer`. It answers
  `false` when the offer is not on sale, the amount is not positive or it would
  pass the limit. Charge the player after a `true`; if the payment fails,
  `release(uuid, id, amount)` gives the stock back.
- **`remaining`** is asynchronous (`CompletableFuture<Integer>`) because it reads
  the database; it is 0 for an offer not on sale.
- **Counts belong to their window.** One row per player and offer holds
  `window:taken`; a row from an earlier window counts as zero, and there is
  nothing to clean up.
- **`onRestock`** runs once per window on each server (a one-second check), on
  the scheduler thread. It does not run for the stock on sale when registered.
  The plugin sends the announcement for offers with `announce()`.
- **Sharing.** `.scope(...)` takes a plugin setting as NetworkGroups does:
  whether a player's purchases are shared with every server, one server or one
  group. Servers must draw the same stock regardless of it.

## Storage

Table `exylia_restock_takes` (`DataKind.PROGRESS`, `@Owner` on `player`): a
season wipe and a player reset cover it. Never read or write it directly.
