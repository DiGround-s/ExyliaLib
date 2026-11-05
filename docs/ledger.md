# Ledger

How much money the server's plugins created and took out of the economy, per
hour, plugin and source. Since 1.478.0.

Entry point: `net.exylia.lib.ledger.Ledger`.

```java
// onEnable of a plugin that reads it (an economy dashboard)
Ledger ledger = Ledger.of(this);

// money paid through Modifiers.pay is recorded on its own; the rest is told
Ledger.created(this, "daily-reward", player.getUniqueId(), amount);
Ledger.sunk(this, "order-tax", player.getUniqueId(), tax);
Ledger.sunk(this, "perk-swap", player.getUniqueId(), "points", fee);   // a named currency

ledger.day(Ledger.currentDay()).thenAccept(entries -> ...);          // every source today
ledger.hour(Ledger.currentHour()).thenAccept(entries -> ...);        // this hour
long now = Ledger.currentHour();
ledger.top(Ledger.Flow.CREATED, null, now - 24, now + 1, 10)         // top earners, last day
        .thenAccept(top -> ...);
```

## Created and sunk

- **Created** is money that enters the economy from nothing: a job payout, a
  mission reward, a sell to the server.
- **Sunk** is money that leaves it: a tax, a fee, a purchase from the server.
- A transfer between players is neither and is never recorded.

Every `Modifiers.pay` that deposits is recorded as created, under the plugin
that called it (found from the call stack, Lukittu-loaded plugins included)
and the `source` it was given. Sinks have no single door, so a plugin records
them with `Ledger.sunk`. A plugin paying with `Economy.pay` directly records
with `Ledger.created`.

## API

| Method | What it does |
| --- | --- |
| `Ledger.of(Plugin)` | Opens the server's one ledger; the first opener hosts the tables in its database |
| `Ledger.isOpen()` | Whether anything is being recorded |
| `Ledger.created(plugin, source, player, [currency,] amount)` | Records money created; `player` and `currency` may be `null` |
| `Ledger.sunk(plugin, source, player, [currency,] amount)` | Records money sunk |
| `ledger.day(long day)` | Every `Entry` of one UTC day, all servers, plugins and sources, hour by hour |
| `ledger.hour(long hour)` | Every `Entry` of one UTC hour |
| `ledger.top(flow, currency, fromHour, toHour, limit)` | `PlayerTotal`s, highest first, `toHour` exclusive, at most 31 days |
| `ledger.player(uuid, flow, currency, fromHour, toHour)` | One player's `BigDecimal` total over a span, every server and source, `toHour` exclusive (1.480.0) |
| `ledger.player(uuid, plugin, flow, currency, fromHour, toHour)` | The same through one plugin only, every server and source: what a plugin caps its own payouts on (1.482.0) |
| `ledger.flush()` | Writes what is counted in memory (runs every minute and before every read) |
| `Ledger.hourOf(millis)`, `dayOf(millis)`, `currentHour()`, `currentDay()` | UTC hours and days since the epoch |

`Entry(hour, server, plugin, source, currency, flow, amount, times)`: `times`
is how many records made the amount. Group and sum entries as the screen needs
(per source, per plugin, per server, per hour).

## Behaviour

- **Nothing is recorded until a plugin opens the ledger.** Before that a
  record costs one volatile read, `Modifiers.pay` included.
- **Counted in memory, written every minute.** A record adds to a counter per
  hour, server, plugin, source, currency and flow, and one per player, hour,
  plugin, currency and flow. The flush writes them with atomic increments
  (`Repository.increment`), so servers sharing a database add up instead of
  overwriting each other. A write that fails is reported and dropped: this is
  a statistic, not a balance.
- **Amounts are kept to two decimals** (stored as hundredths); less than half
  a hundredth records nothing. Sources are stored lower case.
- **Network-wide by sharing the database.** Each row carries the `server-id`,
  so a dashboard sees every server writing to the same database and can still
  split per server. Player totals add every server together. No Redis is
  involved.
- **One ledger per server.** The first plugin that opens it hosts it. When the
  host disables, its counters are written and the next enabled plugin that
  opened it takes over; with nobody left, recording stops.
- **Rows older than 90 days are purged**, every six hours.
- Every method is safe from any thread; futures complete off the server thread.

## Tables

| Table | Kind | Holds |
| --- | --- | --- |
| `exylia_ledger` | `HISTORY` | one row per hour, server, plugin, source, currency, flow: `cents`, `times` |
| `exylia_ledger_players` | `HISTORY`, `@Owner player` | one row per hour, player, plugin, currency, flow: `cents` |

The `plugin` column arrived in 1.482.0 (added on start like any new record
component). Older rows have none: they still count in `player(uuid, flow, ...)`
and `top`, never in the per-plugin total.

Both are covered by `/exylialib wipe`; a player reset deletes that player's
rows.

## Source and tests

- Public: `ledger/Ledger.java`.
- Internal: `ledger/internal/LedgerRow.java`, `LedgerPlayerRow.java`; the hook in
  `modifier/Modifiers.pay`; `release` in `ExyliaLib.releaseAfterDisable`,
  `releaseAll` on shutdown before the database closes.
- Tests: `src/test/java/net/exylia/lib/ledger/LedgerTest.java`.
