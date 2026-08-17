# Economy

Money, for every Exylia plugin. One facade over however many economies a server
runs.

```java
Economy.balance(player);                    // 1250.00
Economy.has(player, price);                 // true
Economy.charge(player, price);              // withdraw, or fail without touching it
Economy.pay(player, reward);                // deposit
Economy.transfer(sender, target, amount);   // move between players

Economy.of("points").balance(player);       // a named currency
```

Every amount is a `BigDecimal` and every player is a `UUID`. Vault and
PlayerPoints are adapted by reflection, so neither is needed at compile time and
either can be absent without consequence.

Since 1.26.0.

---

## Why this is in the library

A plugin that names an economy plugin has chosen one for the whole server. The
ecosystem is full of that choice: a shop hard-wired to Vault, a crate paying
PlayerPoints, and no way to change either without a build.

This module inverts it. A plugin asks for *money*; `plugins/ExyliaLib/economy.yml`
decides which economy serves it. A plugin that genuinely cares about a second
currency names it by id — `Economy.of("points")` — and everything else follows
the owner's file.

The failure being prevented is not "the wrong symbol appears". Without a written
choice, the economy that serves is whichever provider happened to register
first, which changes silently the day a plugin is added or removed. A shop that
moved from Vault to another currency overnight takes every balance with it: the
money is still there, under a currency nothing asks for any more, and the
players report it as money that disappeared.

The second reason is that transfers have to be written once, correctly. See
[Transfers](#transfers) — the version being replaced never worked at all.

---

## The API

`net.exylia.lib.economy.Economy`. Static, thread-safe, no instance to hold.

| Call | Contract |
| --- | --- |
| `Economy.of(id)` | a `CurrencyView` bound to one currency |
| `Economy.isAvailable()` | whether **any** registered provider can serve right now |
| `Economy.register(provider)` | add a currency; throws when the id is taken |
| `Economy.unregister(id)` | remove one, for a plugin shutting down |
| `Economy.currencies()` | every registered id, for diagnostics |
| `Economy.balance(uuid)` | the balance in the default currency; `ZERO` when nothing serves |
| `Economy.has(uuid, amount)` | balance ≥ amount |
| `Economy.withdraw(uuid, amount)` | `EconomyResponse` |
| `Economy.deposit(uuid, amount)` | `EconomyResponse` |
| `Economy.charge(uuid, amount)` | `withdraw(...).isSuccess()` |
| `Economy.pay(uuid, amount)` | `deposit(...).isSuccess()` |
| `Economy.transfer(from, to, amount)` | `TransferResult` |

`Economy.CurrencyView` — what `of(id)` returns — has the same operations bound to
one currency, plus one the top-level facade does not expose:

| Call | Contract |
| --- | --- |
| `view.balance / has / withdraw / deposit / charge / pay / transfer` | as above, on this currency |
| `view.set(uuid, amount)` | set the balance to an exact value; **zero is allowed here**, unlike everywhere else |

`set` is only on the view — there is deliberately no `Economy.set` shortcut,
because setting a balance is an admin operation and not the thing a shop should
reach for. For the default currency, ask for the empty id: `Economy.of("")` is
the default view, since an empty id means "whatever `economy.yml` names".

`charge` is the one to use for a purchase:

```java
if (Economy.charge(buyer, price)) {
    give(item);
}
```

A charge that cannot be made changes nothing.

---

## `EconomyResponse`

The outcome of one balance operation. Never thrown — the ordinary reasons an
economy call fails are what a command branches on.

| `Type` | Means |
| --- | --- |
| `SUCCESS` | it happened |
| `INSUFFICIENT_FUNDS` | the player did not have enough |
| `INVALID_AMOUNT` | the currency cannot represent the amount |
| `NOT_AVAILABLE` | no provider could serve this currency |
| `FAILURE` | the provider was called and refused, giving a reason |

| Accessor | |
| --- | --- |
| `type()` | one of the above |
| `isSuccess()` | |
| `message()` | the provider's reason, or `null` |
| `amount()` | the amount that moved, or was asked for |
| `balance()` | the balance afterwards, or the balance that was too low |
| `shortfall()` | how much *more* the player needed |

`shortfall()` is `amount - balance` on `INSUFFICIENT_FUNDS`, clamped at zero, and
plain zero on every other type. It exists so a message can be written without
arithmetic at the call site:

```java
EconomyResponse response = Economy.withdraw(player, price);
if (response.type() == EconomyResponse.Type.INSUFFICIENT_FUNDS) {
    Text.of("{error}You need " + Formats.money(response.shortfall()) + " more.").send(player);
}
```

Equality compares amounts with `compareTo`, so `10` and `10.00` are the same
response. A `BigDecimal` `equals` would say otherwise, and the scale a provider
happens to return is not information.

---

## `TransferResult`

A transfer is two balance operations, and its truth does not fit in a boolean.

| `Type` | Money moved? |
| --- | --- |
| `SUCCESS` | the amount reached the receiver |
| `INSUFFICIENT_FUNDS` | nothing moved |
| `INVALID_AMOUNT` | nothing moved — also what a transfer to oneself returns |
| `NOT_AVAILABLE` | nothing moved |
| `WITHDRAW_FAILED` | nothing moved, or it moved and came back (see below) |
| `PARTIAL` | **the sender was charged and the receiver got nothing** |

Accessors: `type()`, `isSuccess()`, `isPartial()`, `from()`, `to()`, `amount()`,
`message()`. `from()` and `to()` are `null` on `INVALID_AMOUNT` and
`NOT_AVAILABLE`, where there was no meaningful pair.

`PARTIAL` is the whole reason this type exists instead of a `boolean`. A boolean
has one word for "the money is still where it was" and "the money is gone from
one balance and in no other", and they are not the same event: the first is a
message to the player, the second is a ticket, a manual refund and an admin who
needs to know *tonight*. A caller that treats `PARTIAL` as an ordinary failure
tells a player their payment did not go through while their balance says it did.

`WITHDRAW_FAILED` covers two things. The plain case is a refused withdrawal —
nothing moved. It is also returned when the withdrawal succeeded, the deposit
failed, and the refund put the money back: the net effect on both balances is
nil, so "nothing moved" is the honest answer, and `message()` reads
`"Deposit failed; sender was refunded"`. That case is logged at `WARNING`.

---

## Transfers

The order is the whole point.

```
withdraw from the sender
  ↓ only if that succeeded
deposit to the receiver
  ↓ only if that failed
refund the sender
  ↓ only if that failed too
PARTIAL, logged SEVERE
```

**Why the sender first.** Paying the receiver first mints money. If the receiver
is credited and the sender's charge is then refused — a frozen account, a
provider that went away between the two calls, a backend that rejects the write —
the amount now exists twice on the server. Nobody reports that bug, because
nobody loses anything by it; the server's total balance simply grows, and it
grows fastest for whoever finds the way to make the second half fail on purpose.

Charging first cannot mint: the receiver gets at most what verifiably left the
sender.

**Why the refund.** Charging first has the inverse risk, and it is a real one: a
failed deposit leaves the sender down the amount with nobody up it. So a deposit
that fails is followed immediately by a deposit of the same amount back to the
sender.

**Why `PARTIAL` is loud.** If the refund is refused as well, the money is
genuinely gone, and there is nothing left the library can do about it in code.
What it can do is refuse to be quiet. It logs at `SEVERE`, with the amount, the
currency id, both UUIDs, the deposit's reason and the refund's reason — that is
everything an admin needs to refund by hand:

```
SEVERE  Economy: PARTIAL transfer. 40 vault was taken from <uuid> for <uuid>,
        but the deposit failed (receiver account frozen) and the refund failed
        (storage offline). Manual refund required.
```

and it returns `TransferResult.Type.PARTIAL`, which `isPartial()` reports and no
`isSuccess()` check can mistake for anything else.

**A provider with a native transfer skips all of this.** `CurrencyProvider.transfer`
returns `null` by default, meaning "the library should do it". A provider that
overrides it — PlayerPoints, whose backend has an atomic `pay(from, to, amount)` —
performs the move as one operation, and the withdraw-verify-deposit path never
runs for that currency. `PARTIAL` is therefore impossible on PlayerPoints.

A transfer to oneself is refused as `INVALID_AMOUNT` before any provider is
called.

Every one of these paths is a test in `EconomyTest`, including the two that only
happen under load on a live server: the refunded deposit, and the refused refund.

---

## Precision

`BigDecimal` end to end. A balance is the one number a `double` must not hold:
`0.1 + 0.2` in a double is `0.30000000000000004`, and a shop that sums prices
that way shows a total a player can prove wrong.

**Doubles are converted with `BigDecimal.valueOf(double)`, never
`new BigDecimal(double)`.** The constructor takes the exact binary value, so the
double nearest `0.1` becomes
`0.1000000000000000055511151231257827021181583404541015625`. `valueOf` goes
through the shortest decimal string that round-trips — the `0.1` the economy
plugin meant. This is pinned by `PrecisionTest`, so a future "simplification"
fails there rather than on a live server.

Vault's API is `double`, so that is where the conversion lives: balances come out
through `valueOf`, and an amount goes in as `doubleValue()`, because that is the
only thing Vault accepts. The library's own accounting stays exact.

**PlayerPoints refuses what it cannot hold rather than reshaping it.** A point is
an integer. An amount with a fraction, or one outside the `int` range, comes back
as `EconomyResponse.invalidAmount()`:

| Amount | PlayerPoints |
| --- | --- |
| `64` | accepted |
| `1.5` | `INVALID_AMOUNT` |
| `3000000000` | `INVALID_AMOUNT` |

Truncating `1.9` to `1` destroys 0.9 points somebody may have paid for; rounding
it to `2` mints 0.9 out of nothing; `intValue()` on three billion wraps to a
negative balance. Any of the three breaks the one invariant an economy has — that
the sum of all balances matches what was deposited. Refusing is the only answer
that does not. The conversion is `setScale(0, UNNECESSARY).intValueExact()`, so
both rejections ride the same `ArithmeticException` and there is no silent path.

Reading points is exact: an `int` through `BigDecimal.valueOf(long)` cannot lose
anything.

---

## The balance cache

A balance read is served from a short cache, keyed by (currency id, player).

**What it buys.** Balances are read on hot paths — a scoreboard refresh, a
placeholder, a shop preview — several times per tick per player, and the economy
behind them answers from its own cache at best and a database at worst. Without
this, a ten-line scoreboard on a twenty-player server asks the economy thousands
of times a second, and the thin wrapper becomes the bottleneck it exists to
avoid. `EconomyTest` pins it: three reads in one tick ask the provider once.

**The TTL** is `balance-cache-millis` in `economy.yml`, default **500 ms**,
`expireAfterWrite`, up to 4096 entries. Values below 1 ms are floored at 1 ms, so
a misconfigured `0` cannot turn the cache off and read as a library slowdown.

**The library's own writes invalidate instantly.** Every successful `deposit`,
`withdraw` and `set` drops that entry, and a transfer drops both sides. So the
only staleness anyone can see is a change another plugin made *directly* against
the economy, bypassing the library — and half a second of that is the price being
paid deliberately.

This matters more than the TTL does. A scoreboard still showing the old balance
right after a purchase is a "my money disappeared" ticket even when the database
is perfectly correct.

The whole cache is dropped when the set of providers changes: on
`Economy.unregister`, on reload, and on shutdown. A currency id that means one
economy before a reload and another after must not keep serving the first one's
numbers.

---

## `economy.yml`

Generated at `plugins/ExyliaLib/economy.yml` on first start, reloaded by
`/exylialib reload` alongside the palette and `formats.yml`. Keys are kebab-case
and values are lowercase, as everywhere in the config module.

```yaml
# Economy for every Exylia plugin.
#
# Plugins never name an economy plugin themselves: they ask for money
# and this file decides which economy serves it. Change the currency
# here and every shop, kit and reward follows.
#
# Run /exylialib reload after editing. No restart is needed.
# The currency that answers when an operation does not name one.
# The id of a registered provider: 'vault' or 'points' of the
# built-in ones, or the id of any currency a plugin has added.
default-currency: vault
# The order to try other currencies when the default is not
# available. The first available one in this list serves the
# operation, and the switch is announced rather than silent —
# a currency changing on its own is how a balance disappears.
fallback:
- points
# How long a balance, once read, may be reused, in milliseconds.
# Balances are shown on scoreboards that refresh every tick,
# and asking the economy on every tick makes our thin wrapper
# the bottleneck it was meant to avoid. A balance a plugin
# changed through the library is refreshed at once; this only
# governs changes made outside it.
balance-cache-millis: 500
# Layout version of this file. ExyliaLib uses it to upgrade the file automatically.
# Do not edit.
config-version: 1
```

| Key | Default | |
| --- | --- | --- |
| `default-currency` | `vault` | the id that answers when an operation names none |
| `fallback` | `[points]` | the order to try when the default is not available |
| `balance-cache-millis` | `500` | how long a read balance may be reused |

The built-in ids are `vault` and `points` — lowercase provider ids, not enum
names. `EconomyFileTest` generates this file and asserts every key, every
default, and that editing `default-currency` really changes which currency
serves; a documented key that does not exist is otherwise the hardest kind of
mistake to notice, because the owner edits it, nothing happens, and the shop goes
on charging the economy they were trying to leave.

### Fallback

When the **default** currency is not registered or reports itself unavailable,
the `fallback` list is walked in order and the first available provider serves.
A server whose economy plugin disappeared keeps working instead of reporting a
balance of zero to everybody.

**Fallback applies only to the default.** A caller that names a currency —
`Economy.of("points")` — gets that currency or nothing. Falling back from a named
id would mean a plugin asking to charge 500 points, finding points unavailable,
and charging 500 from the player's money instead: the right number in the wrong
currency, which is worse than the operation failing. A named currency that is
down returns `NOT_AVAILABLE`.

The switch is logged once per change, never per call, and so is the return to the
named currency when it comes back. A scoreboard resolves the currency every tick,
and a warning printed every tick is a warning nobody reads.

When nothing at all can serve, operations return `NOT_AVAILABLE` and `balance`
returns zero. A server with no economy plugin still starts and still runs every
plugin; features that cost money can check `Economy.isAvailable()` and say so.

---

## Writing your own currency

Implement `CurrencyProvider` and register it. Only standard types appear in the
interface — a `UUID`, a `BigDecimal`, a `String` — so a provider backed by a web
service or a database column looks exactly like the built-in ones.

```java
public final class CoinsCurrency implements CurrencyProvider {

    @Override public @NotNull String id() { return "coins"; }
    @Override public @NotNull String displayName() { return "Coins"; }
    @Override public boolean isAvailable() { return storage.isConnected(); }

    @Override public @NotNull BigDecimal balance(@NotNull UUID player) { ... }
    @Override public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) { ... }
    @Override public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) { ... }

    @Override public @NotNull String currencyName(boolean plural) { return plural ? "coins" : "coin"; }
    @Override public @NotNull String symbol() { return "⛃"; }
}
```

```java
@Override
public void onEnable() {
    Economy.register(new CoinsCurrency(storage));
}

@Override
public void onDisable() {
    Economy.unregister("coins");
}
```

Then `Economy.of("coins")` works everywhere, and a server owner can put
`default-currency: coins` in `economy.yml` and have every Exylia shop use it.

**Contract.**

- `id()` is lowercase, stable and unique. A taken id throws `EconomyException`
  rather than overwriting: two plugins both believing they own `coins` and taking
  turns serving it splits balances across two backends, and that must surface
  while the second plugin is being written.
- `isAvailable()` is called on every resolution. Make it a flag or a cheap
  lookup, never a network round-trip.
- Implementations must be **safe from any thread**. Balances are read on
  scoreboard ticks and written from command handlers.
- Amounts reaching `deposit` and `withdraw` are already validated as positive by
  the library. A provider does not need to defend against a negative — though
  both built-ins do, cheaply, because a direct caller can bypass the facade.
- `balance()` has no failure channel. Both built-ins return zero on error, which
  fails closed: zero blocks a purchase, an invented number lets one through that
  the backend may not honour.

**Override `set` when the backend can set directly.** The default does a
`balance()` then a deposit-or-withdraw of the difference, which is the only
honest implementation when there is no native set (Vault). It is two operations
with a read in between, so a concurrent change makes the difference wrong.
PlayerPoints overrides it.

**Override `transfer` only when the backend moves money atomically.** The default
returns `null`, which tells the library to run withdraw → verify → deposit →
refund. A "transfer" that is really a withdraw then a deposit belongs in the
library, where the failure handling is written once and tested. PlayerPoints
overrides it because `pay(from, to, amount)` is a single storage operation.

---

## What throws and what returns

The line is: a caller bug throws, an economy outcome is returned.

| | |
| --- | --- |
| **`EconomyException`** | a null player; a null amount; a zero or negative amount on `has`, `withdraw`, `deposit`, `charge`, `pay` and `transfer`; a negative amount on `set`; registering an id that is already taken |
| **`EconomyResponse` / `TransferResult`** | insufficient funds; no provider available; an amount the currency cannot represent; the provider refusing with a reason; a partial transfer |

`set` is the one exception to "positive": it accepts zero, because setting a
balance to nothing is a legitimate admin action, and rejects only negatives.

The reasoning is that "insufficient funds" is the expected outcome of a purchase
and belongs in a value a command branches on, while a negative amount is a bug
that should fail loudly during development rather than reach a player as
"insufficient funds" — a message that sends the developer looking at the wrong
thing entirely.

One thing that does **not** throw: asking for a currency id nobody registered.
That resolves through the fallback list, and if nothing can serve, returns
`NOT_AVAILABLE`.

---

## With the format module

`Economy` produces numbers; the [format module](formats.md) turns them into
something a player reads, through the owner's `formats.yml`.

```java
Formats.money(Economy.balance(player));   // "$1,250.00"
```

`Formats.money(BigDecimal)` keeps the value exact end to end, so a balance never
takes a detour through a `double` on its way to the screen.

### A complete `/pay <player> 10M`

`Amounts.parse` reads what the player typed — suffixes, thousands separators —
and refuses anything ambiguous rather than guessing:

```java
public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player payer) || args.length < 2) {
        return false;
    }

    Player target = Bukkit.getPlayerExact(args[0]);
    if (target == null) {
        Text.of("{error}That player is not online.").send(payer);
        return true;
    }

    Optional<BigDecimal> parsed = Amounts.parse(args[1]);     // "10M" -> 10000000
    if (parsed.isEmpty() || parsed.get().signum() <= 0) {
        Text.of("{error}Enter an amount, such as 500 or 10M.").send(payer);
        return true;
    }
    BigDecimal amount = parsed.get();

    TransferResult result = Economy.transfer(payer.getUniqueId(), target.getUniqueId(), amount);
    switch (result.type()) {
        case SUCCESS -> {
            Text.of("{success}Sent " + Formats.money(amount) + " to " + target.getName()).send(payer);
            Text.of("{success}" + payer.getName() + " sent you " + Formats.money(amount)).send(target);
        }
        case INSUFFICIENT_FUNDS ->
                Text.of("{error}You do not have " + Formats.money(amount) + ".").send(payer);
        case INVALID_AMOUNT ->
                Text.of("{error}You cannot pay yourself.").send(payer);
        case NOT_AVAILABLE ->
                Text.of("{error}The economy is not available right now.").send(payer);
        case WITHDRAW_FAILED ->
                Text.of("{error}The payment did not go through. Nothing was taken.").send(payer);
        case PARTIAL ->
                Text.of("{error}Payment interrupted. Staff have been notified — "
                        + "your money is not lost.").send(payer);
    }
    return true;
}
```

Three things worth noting.

**Check the sign yourself.** `Amounts.parse` refuses negatives but accepts `0`,
and `Economy.transfer` throws `EconomyException` on a zero amount, because zero
is a caller bug rather than an economy outcome. `signum() <= 0` at the boundary
is where a player's typo becomes a message instead of a stack trace.

**`PARTIAL` gets its own branch.** Collapsing it into the generic failure is
exactly the mistake `TransferResult` exists to make impossible: the player's
money really is gone, the console already has a `SEVERE` line with everything
needed to refund by hand, and the player should be told the truth rather than
"payment failed".

**The amount round-trips.** A player reads `10M` on a scoreboard, types `10M`
into `/pay`, and the number they meant is the number that moves.

---

## Migrating from ExyliaCommons

`EconomyAPI` has 59 references in ExyliaSurvivalCore and 8 in ExyliaClans.
Audited call sites, by method: `format` 17, `isAvailable` 12, `has` 8,
`withdraw` 4, `charge` 4, `getBalance` 2, `deposit` 2, `pay` 1.

| ExyliaCommons | ExyliaLib |
| --- | --- |
| `EconomyAPI.initialize(plugin)` | — the library does it at its own enable |
| `EconomyAPI.isAvailable()` | `Economy.isAvailable()` |
| `EconomyAPI.isAvailable(type)` | `Economy.currencies().contains(id)` for "is it registered"; otherwise branch on `NOT_AVAILABLE` from the operation itself |
| `EconomyAPI.getBalance(player)` | `Economy.balance(uuid)` |
| `EconomyAPI.has(player, amount)` | `Economy.has(uuid, amount)` |
| `EconomyAPI.withdraw(player, amount)` | `Economy.withdraw(uuid, amount)` |
| `EconomyAPI.deposit(player, amount)` | `Economy.deposit(uuid, amount)` |
| `EconomyAPI.charge(player, amount)` | `Economy.charge(uuid, amount)` |
| `EconomyAPI.pay(player, amount)` | `Economy.pay(uuid, amount)` |
| `EconomyAPI.set(player, amount)` | `Economy.of("").set(uuid, amount)` |
| `EconomyAPI.transfer(from, to, amount)` | `Economy.transfer(from, to, amount)` — **and it works now** |
| `EconomyAPI.format(amount)` | **`Formats.money(amount)`** — the [format module](formats.md), not this one |
| `CurrencyType.VAULT` | the id `"vault"` |
| `CurrencyType.PLAYER_POINTS` | the id `"points"` |
| `EconomyAPI.getProvider()` / `getRegisteredProviders()` | `Economy.currencies()` for diagnostics |
| `EconomyAPI.reload()` / `shutdown()` | `/exylialib reload`; the library owns the lifecycle |

Three differences to plan for.

**`OfflinePlayer` becomes `UUID`.** Commons took `OfflinePlayer` and the
providers called `Bukkit.getOfflinePlayer` internally anyway. A `UUID` is what
every call site already has, and it is the only thing that works off the main
thread. `player.getUniqueId()` at the call site is the whole migration.

**`format` moves modules.** All 17 sites go to `Formats.money(...)`, which reads
the server owner's `formats.yml` — the same symbol, position and compaction as
every other number the server shows. An economy plugin's own `format()` is a
per-plugin choice; the point of `formats.yml` is that there is only one.

**Commons' transfer never worked.** `EconomyManager.transfer` was a
`TODO(human)` returning `TransferResult.failure("Not implemented")`, for both the
default and the typed overload. Every call site got a failure, so nothing depends
on transfer behaviour today, and the withdraw-verify-deposit-refund path here is
new rather than a replacement. Anything that "paid" a player by calling
`withdraw` then `deposit` by hand should move to `Economy.transfer` — that
open-coded pair is precisely the sequence with no refund on a failed deposit.

Two other things Commons could not do that the file now decides: the choice of
economy was the first provider that happened to load, and there was no cache, so
every scoreboard line went to the economy plugin.

---

## Where the code is

| | |
| --- | --- |
| Public API | `economy/Economy` (with `Economy.CurrencyView`), `CurrencyProvider`, `EconomyResponse`, `TransferResult`, `EconomySettings`, `EconomyException` |
| Internal | `economy/internal/CurrencyRegistry`, `BalanceCache`, `VaultCurrency`, `PlayerPointsCurrency` |
| Lifecycle | `ExyliaLib` — `economy.yml` read, settings applied, providers detected at enable; re-applied on `/exylialib reload` |
| Tests | `src/test/java/net/exylia/lib/economy/internal/` — `EconomyTest`, `PrecisionTest`, `EconomyFileTest` |
