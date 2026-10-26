# Escrow

Money and items held while a match is played — a duel stake, a wager, a pot — so
no restart, crash or quit ever loses or doubles a stake. Since 1.345.0.

Entry point: `net.exylia.lib.escrow.Escrow`.

```java
private Escrow escrow;

@Override
public void onEnable() {
    escrow = Escrow.of(this);   // refunds whatever this server's last run left held
}

// Both players accept a 500-coin duel.
escrow.hold(duelId, player.getUniqueId(), "coins", new BigDecimal("500")).thenAccept(hold -> {
    if (hold != Escrow.Hold.HELD) cancel(duelId, hold);    // CANNOT_PAY or FAILED
});

// Items staked: take them from the player first, then record them.
escrow.holdItems(duelId, player.getUniqueId(), taken);

// Somebody won.
escrow.settle(duelId, winner, 0.05).thenAccept(payouts -> announce(payouts));  // pot less 5%
escrow.settleItems(duelId, winner);

// Nobody did: a draw, a cancel, a server shutting down.
escrow.refund(duelId);
escrow.refundItems(duelId);
```

## The API

| Call | Contract |
| --- | --- |
| `Escrow.of(plugin)` | the plugin's escrow, the same instance per load; the first call starts recovery |
| `recovery()` | completes when recovery is done; fails when the last run's rows could not be read |
| `isReady()` | the same, answered now: `true` once recovery is done and did not fail. Since 1.356.0 |
| `hold(matchId, player, currency, amount)` | `CompletableFuture<Hold>`: `HELD`, `CANNOT_PAY` or `FAILED`; never completes exceptionally |
| `holdMore(matchId, player, currency, amount)` | a further stake (a double, a split, a raise) for a player who already holds one on the match; `FAILED`, charging nothing, when they hold none. Since 1.356.0 |
| `settle(matchId, winner)` / `settle(matchId, winner, tax)` | `CompletableFuture<List<Payout>>`, one per currency in the pot; empty when nothing was held |
| `settleAmount(matchId, player, amount)` / `settleAmount(…, counted)` | `CompletableFuture<Boolean>`: pays one player an amount the game worked out instead of the pot; see [Paying an amount](#paying-an-amount). Since 1.356.0 |
| `refund(matchId)` | every held stake back to whoever paid it |
| `refund(matchId, player)` | one player's stakes and items back, the rest stay held (since 1.356.0) |
| `holdItems(matchId, player, items)` | `CompletableFuture<Boolean>`; `false` means the items were given back |
| `settleItems(matchId, winner)` | every held item of the match to the winner |
| `settleItems(matchId, winner, multiplier)` | the same, `multiplier` times over; `0` settles them and gives nothing. Since 1.356.0 |
| `refundItems(matchId)` | every player's own items back |
| `heldItemMatches(player)` | `CompletableFuture<Set<String>>`: the matches the player still has `HELD` items on, any server (1.348.0); a join handler asks before restoring an inventory the escrow owns |
| `giveItems(player, items)` | items that belong to nobody's match (a cancelled or swapped [trade](trades.md)): into the inventory, the rest owed. Since 1.349.0 |
| `deliver(player)` | hands over everything owed to the player; the library calls it on join |

`Payout(currency, pot, tax, prize)`: `tax` is `pot × tax` rounded **down** to
the currency's decimals, so rounding favours the winner; the tax is destroyed,
which is what a money sink is for.

- A match id is 1 to 64 characters, unique per plugin. A player may hold
  several stakes on one match; they all go into the pot.
- `currency` is any id `Economy.of(id)` serves — Vault, PlayerPoints, an item or
  experience currency, a plugin's own. Empty means the server's default.
- `hold` is for a player online on this server: an economy may accept a charge
  to somebody who is not before checking they can pay it.
- A winner exempt from the tax (a permission, a rank) is settled with a `tax`
  of `0`; the payout then reports nothing kept back.

## Paying an amount

`settle` pays the pot to a winner. A round played against the server's rules
has no pot: the game decides what the stake is now worth.

```java
escrow.hold(roundId, uuid, "coins", stake);
escrow.holdMore(roundId, uuid, "coins", stake);            // the player doubled
escrow.settleAmount(roundId, uuid, stake.multiply(new BigDecimal("4")), 2);   // won both
escrow.settleAmount(roundId, uuid, BigDecimal.ZERO);       // or lost: settled, nothing paid
```

- The first `counted` stakes held, oldest first, are settled. Any held after
  them (an extra charged while the player was leaving, which the game never
  used) goes back to whoever paid it.
- The amount is paid in the oldest stake's currency, rounded down to its
  decimals, and **paid once**: the call that moves the oldest stake out of
  `HELD` owns the match, and a call that loses it touches nothing else. A second
  call, a racing `refund` or another server answers `false` and pays nothing.
- Like every payment here, an offline player or a refused deposit is owed and
  paid on the next join.

## The order is the whole point

There is no escrow account. Each stake is a row, written before anything moves.

```
hold:   write PENDING → withdraw → write HELD
                          ↳ refused: write VOID, answer CANNOT_PAY / FAILED
                                         ↳ HELD write failed: deposit back, answer FAILED
settle: HELD → SETTLED (compare-and-set, per row) → pay the winner what this call moved
refund: HELD → REFUNDED (compare-and-set, per row) → pay each owner what this call moved
```

- **A `PENDING` row is never refunded.** A server stopping between the write and
  the charge leaves one, and the charge may not have happened: paying it back
  would be free money. Recovery marks it `VOID` and reports it, so staff can
  check the economy history if the player asks.
- **The state is written before the money moves**, on the way out as on the way
  in. A settle that fails to write leaves the stake `HELD`, and the next start
  refunds it instead of paying it twice.
- **Idempotent by compare-and-set.** Each row moves out of `HELD` with
  `updateIf(row, "state", HELD)`, and only the rows a call won are paid. A
  second settle, a refund after a settle, a settle racing a refund, or two
  servers on one database: each stake moves once.
- **A hold that lands after the match ended goes straight back.** The match is
  remembered as closed for an hour on the server that closed it; a charge still
  in flight finds it and refunds itself, answering `FAILED`.

## Items

`holdItems` records items the caller has **already** taken from the player. It
copies them, encodes them with Bukkit's own versioned bytes (read back through
the library's newer-version-aware reader, so a shared database survives a game
update), and writes one `HELD` row. Until the future completes the items exist
only in memory; when it answers `false` the database refused, recovery failed,
or the match already ended, and the items have been given back.

### The player file has to agree with the row

Paper writes a player's data to disk only on autosave and on quit, while an
escrow row is durable the moment it is written. A crash between the two used to
load the player as they were at the last autosave, and that disagreed with the
database: still holding items the escrow also refunds (a dupe), or without
items the escrow already handed over (a loss). *Since 1.350.0* the library
writes the player file (`player.saveData()`, on the player's own thread, the
one Folia allows) at both ends:

- **`holdItems`**, once its `HELD` row has landed, for the player whose items
  they are, when they are online here.
- **Every hand-over into an inventory** (`settleItems`, `refundItems`,
  `giveItems`, `deliver`), right after the items are in it.

What stays the caller's: **take the items before calling `holdItems`, from
the player's own inventory, on their thread**, and keep them out of reach of
anything that saves the player in between (the row has not landed yet). Items
taken from somewhere other than the player's inventory (an ender chest, a
backpack plugin's storage) are that storage's to persist. [Trades](trades.md)
already saves both players when a trade confirms.

Items reach a player on that player's thread, into their inventory. Whatever
does not fit — and everything, when the player is not on this server or is
dead — becomes an owed row. **Nothing is ever dropped on the ground.** A dead
player's inventory is cleared on respawn, so items owed while dead are handed
over a few ticks after they respawn (since 1.349.0), and on their next join
otherwise.

## Owed rows and delivery

Money and items the escrow could not hand over now are written to an owed row:
the winner was offline, the economy refused a deposit (a balance at its
ceiling), the inventory was full, the player left before their thread ran.

A few seconds after every join the library calls `deliver(player)` for each
plugin. Each owed row is **deleted before it is given**, and only the call whose
delete removed it gives it — two joins, or two servers, hand it over once
(the same pattern as [Notices](notices.md)). What still cannot be delivered is
written back as a new owed row. Call `deliver` yourself after a player frees
inventory space, from a `/claim` command for instance.

## Recovery

The first `Escrow.of(plugin)` reads what **this server** left behind:

| Left as | Becomes |
| --- | --- |
| `HELD` money | `REFUNDED`, paid back (or owed, when the owner is offline) |
| `PENDING` money | `VOID`, reported, never refunded |
| `HELD` items | `REFUNDED`, given back (or owed) |

Matches are not resumed: their state was never stored. Holds wait for recovery;
if it failed — the database was unreachable — every hold is refused until the
plugin restarts, because taking new stakes while old ones may still be owed is
how money doubles.

Rows carry the server id — the `server-id` of the Redis block the proxy bridge
rides (see [Proxy](proxy.md)), `server-1` when no plugin turns Redis on — so on
a network sharing one database a restarting server refunds only its own
matches, never a match still being played on another server. **Servers that
share a database must have distinct server ids**; without Redis they all read
`server-1`, and a restart on one would refund the others' live matches.

## Loser loses their items: a survival duel

A duel in the open world where the loser's inventory goes to the winner uses
three modules, and the order is what keeps a crash from both giving the loser
their things back and paying them to the winner:

```java
// On start: claim the deaths, keep the rest of the player's state.
DeathDrops.claim(this, victim -> duels.isFighting(victim.getUniqueId()));
snapshots.save(player, "duel");

// In PlayerDeathEvent, on the loser's thread:
List<ItemStack> drops = new ArrayList<>(event.getDrops());
event.getDrops().clear();                                   // never reaches the ground
escrow.holdItems(duelId, loser, drops)                      // 1. durable first
        .thenCompose(held -> snapshots.restore(loser, "duel", null,
                SnapshotPart.allExcept(SnapshotPart.INVENTORY, SnapshotPart.ARMOR,
                        SnapshotPart.OFF_HAND)))           // 2. consumes the snapshot row
        .thenCompose(ignored -> escrow.settleItems(duelId, winner))   // 3. pay out
        .thenCompose(ignored -> escrow.settle(duelId, winner, tax));
```

1. **Hold first.** The drops become a `HELD` row before anything else touches
   them. A crash now: recovery gives them back to the loser, and nobody is paid.
2. **Consume the snapshot.** A partial restore still deletes the row
   ([Snapshots](snapshots.md#partial-restore)), so the loser can never later be
   restored with the inventory they just lost. A crash now: the items are still
   `HELD` and go back to the loser on restart — the duel is undone, not doubled.
3. **Settle.** The items move to `SETTLED` before the winner gets them; a second
   settle does nothing.

`DeathDrops` ([Stays](stays.md)) is what tells graves and death-chest plugins to
leave those drops alone. If the snapshot was taken with the inventory in it,
mind the one window this cannot close: a crash between step 1 and step 2 leaves
both the snapshot (with the pre-duel inventory) and the held items, and a join
that restores the snapshot in full while recovery refunds the items gives the
loser both. For an open-world duel, where players fight in their own gear,
restore the snapshot on join with the same `allExcept(...)` parts — the escrow
is what owns the inventory.

## Contracts

- **Everything is a future**, completing off the server thread. Every method may
  be called from any thread; nothing here touches the database from the main
  thread.
- **Money and items reach a player on that player's thread** (`runAtEntity`), and
  a player who left before it ran is owed instead.
- **The player file is saved when items move** (1.350.0): after a `holdItems`
  row lands and after items land in an inventory. See
  [The player file has to agree with the row](#the-player-file-has-to-agree-with-the-row).
- **Withdrawals run on the database callback thread**, through `Economy`, which
  is thread-safe.
- **Nothing is cached** but the set of matches recently closed on this server.
  Settle and refund read the database, so they are correct from any server.
- **Released with the plugin**: nothing is written on the way out, because
  every stake was a row the moment it was taken. A plugin that wants live
  matches refunded on shutdown calls `refund` from `onDisable`; otherwise the
  next start does it.
- **The palette reload does not apply**: nothing here is a component.

## Tables

In the plugin's own database (`database.yml`), with a `plugin` column so a
shared database stays legible:

| Table | Holds |
| --- | --- |
| `exylia_escrow_stakes` | one row per money stake: match, player, currency, amount, state, server |
| `exylia_escrow_items` | one row per `holdItems` call: match, player, encoded items, state, server |
| `exylia_escrow_owed` | money or items waiting for a player's next join |

Rows are kept after they settle, as a record. Nothing expires them.

## Source and tests

- Public: `escrow/Escrow.java` (`Hold`, `Payout`).
- Internal: `escrow/internal/` — `StakeRow`, `ItemRow`, `OwedRow`, `StakeState`.
- Library hooks: `Escrow.joined` on join, `Escrow.respawned` on respawn, `Escrow.release` in
  `releaseAfterDisable`, `Escrow.releaseAll` on shutdown.
- Tests: `src/test/java/net/exylia/lib/escrow/EscrowTest.java` against H2 with
  an in-memory currency and stub items (`Escrow.setHands`): settle once with
  tax, refund once, a settle racing a refund moving each stake once, refused
  holds, a hold after the match ended, recovery (held refunded, pending voided,
  another server's rows untouched, items returned), owed money delivered once,
  item overflow owed rather than dropped, a further stake needing a first one,
  an amount paid once with an unused extra refunded (also under racing calls),
  and items paid at a multiplier.
