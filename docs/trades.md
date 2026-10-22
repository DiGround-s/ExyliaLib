# Trades

A two-player trade window: both players put items in, both see both offers
live, both confirm, and a short countdown that any change resets stops a
last-second swap. Closing, leaving, dying, a timeout or a stop gives every item
back. Since 1.349.0.

Entry point: `net.exylia.lib.trade.Trades`.

```java
private Trades trades;
private Escrow escrow;

@Override
public void onEnable() {
    escrow = Escrow.of(this);
    trades = Trades.of(this);   // opens the same escrow: owed returns are delivered on join
}

// A duel with items bet: both must put something in.
TradeOptions options = TradeOptions.defaults()
        .title(messages.get("bet.title"))                                  // %player% = the other player
        .info(() -> messages.get("bet.money").replace("%amount%", money)) // one extra line, live
        .allowEmpty(false)
        .timeout(Duration.ofMinutes(2));

trades.open(challenger, target, options).thenAccept(result -> {
    if (!result.confirmed()) {
        tell(result.outcome(), result.cancelledBy());   // the items already went back
        return;
    }
    escrow.holdItems(duelId, result.first(), result.firstItems());
    escrow.holdItems(duelId, result.second(), result.secondItems());
});

// A plain trade: swap the two sides.
trades.open(a, b).thenAccept(result -> {
    if (result.confirmed()) {
        escrow.giveItems(result.second(), result.firstItems());
        escrow.giveItems(result.first(), result.secondItems());
    }
});
```

## The API

| Call | Contract |
| --- | --- |
| `Trades.of(plugin)` | the plugin's trades, the same instance per load; opens `Escrow.of(plugin)`, so call it in `onEnable` |
| `open(first, second)` / `open(first, second, options)` | `CompletableFuture<TradeResult>`; `UNAVAILABLE` at once for the same player twice, an offline or dead player, one already trading, one held by **another** plugin through `Sessions`, or one whose [stay recovery](stays.md#recovering-on-join) is still pending (the last two since 1.350.0) |
| `Trades.isTrading(uuid)` | whether the player has a trade open with any plugin |

`TradeOptions` is immutable; each method answers a copy.

| Option | Default | Meaning |
| --- | --- | --- |
| `title(String)` | the library's translated `{primary}&lTRADE {letters_black}» {highlight}%player%` | Exylia text; `%player%` is the other player's name. Pass the line from your own `messages.yml` |
| `info(Supplier<String>)` | none | one extra line on the centre item, after `{letters_black}▎`, read about once a second on the viewer's thread — memory, never a database |
| `allowEmpty(boolean)` | `true` | whether a side may confirm while offering nothing |
| `timeout(Duration)` | 3 minutes | how long the window may stay open; zero for no limit |
| `countdown(Duration)` | 3 seconds | how long both confirmations must stand; zero completes at the second confirm |

`TradeResult(outcome, first, firstItems, second, secondItems, cancelledBy)`:
`confirmed()`, `itemsOf(uuid)`. The lists are filled only for `CONFIRMED`;
`cancelledBy` is the player whose close, quit or death ended it.

| `TradeOutcome` | |
| --- | --- |
| `CONFIRMED` | both confirmed and the countdown ran out; the items are the caller's |
| `CLOSED` | a player closed their window (or another plugin opened one over it) |
| `LEFT` | a player left the server |
| `DIED` | a player died |
| `TIMED_OUT` | the timeout ran out |
| `UNAVAILABLE` | it could not start, or another plugin refused the window |
| `SHUT_DOWN` | the owning plugin was disabled or the server is stopping |

**On `CONFIRMED` the items exist only in the result.** They have been removed
from both windows; hand them on in the callback — `Escrow.holdItems` for a bet,
`Escrow.giveItems` for a swap. A callback that throws before doing so loses them.

*Since 1.350.0* each player's file is saved (`player.saveData()`, on their
thread) as their window is emptied on `CONFIRMED`. Without it, a crash after the
escrow held the bet but before the next autosave loaded both players still
holding what they bet, while recovery refunded it too. `Escrow.holdItems` and
`giveItems` save again once the items are in the escrow or in the other
player's inventory.

**Who may trade.** A player held through [Sessions](stays.md) by another
plugin (an arena, an event, another plugin's duel) is refused: their inventory
is that plugin's. A hold of the plugin opening the trade is fine, which is how a
duel claims both players and then opens the bet window.

## The window

```
 0  1  2  3 │ 5  6  7  8      your offer │ a copy of theirs
 9 10 11 12 │14 15 16 17
18 19 20 21 │23 24 25 26      the middle column and the bottom row are filler
27 28 29 30 │32 33 34 35
36 37 38 39 │41 42 43 44
 ·  · [✔] · [i] · [●] ·  ·      47 your confirm · 49 info and countdown · 51 their status
```

- Your confirm: `{warning}&lCONFIRM` (gray dye) until pressed, then
  `{success}&lCONFIRMED` (lime dye, glinting); pressing again takes it back.
- Their status: their name, with `{success}confirmed` or `{error}not confirmed`.
- The centre: what the halves are, the caller's info line, and either
  `Waiting for both confirmations` or `Completes in » 3s ⌚` (a clock while it counts).
- Sounds are `UiSounds.DEFAULTS`: `click` for a confirm and every countdown
  second, `denied` for a refused confirm, and the suite's success sound
  (`ENTITY_EXPERIENCE_ORB_PICKUP`) when it completes.

Every line is English in code and translated through `lang/<code>/phrases.yml`
(see [Text](text.md)); the title and info line are the caller's.

## Why it cannot duplicate

- **Each player has their own window.** Their half holds their real items; the
  other half is a cloned snapshot of the other player's, redrawn after every
  change. Clicks on a copy, the divider or the bottom row are refused.
- **Only plain moves on your own half are allowed**: pick up, place, swap with
  the cursor, number keys, the offhand key and shift-click out. Drops, bundles
  and creative cloning in the window are refused; a drag touching any slot but
  your own half is refused.
- **Shift-click in is done by the library**, into your half only: vanilla would
  fill the first free slot of the window, a copy's included.
- **Double-click collect is refused anywhere in the view**: it sweeps every slot,
  the copies included.
- **A confirm counts what was on screen.** Every change bumps that side's
  revision and resets both confirmations before it lands. A press is refused
  (the `denied` sound) until the player's own snapshot and their copy of the
  other side are both current, so nobody confirms a stake swapped a tick earlier.
- **Both confirmations must stand for the countdown**; any change resets it.
- **Each window is emptied once.** A per-side claim in the state means a close,
  a quit, a death and the library's own cleanup racing each other take the items
  out exactly once.

## Giving items back

A cancelled trade gives each player their own items, on that player's thread:

- Here and alive: into the inventory. What does not fit goes through
  `Escrow.giveItems`, and from there to an owed row.
- Dead, or gone: straight to `Escrow.giveItems`, which owes them. Items owed
  while dead are delivered a few ticks after respawn; anything else on the next
  join ([Escrow](escrow.md#owed-rows-and-delivery)).
- A quit returns the items into the inventory before it is saved.

The plugin's `database.yml` is where owed rows live, as for any escrow.

## Threads

- `open` may be called from any thread.
- Each window is built, clicked, redrawn and emptied **on its own player's
  thread** (`runAtEntity`), so two players on two Folia regions never touch each
  other's inventory. The other side's offer crosses over as a cloned snapshot.
- The countdown and the timeout are checked on the global thread every five
  ticks; that timer only reads and moves the state, then schedules redraws.
- The future completes on whichever thread emptied the last window.
- **Disable and shutdown** are the one exception: there is no next tick, so the
  windows are emptied and the items given back on the disabling thread, before
  the plugin's tasks are cancelled and while its escrow is still open.

## Source and tests

- Public: `trade/Trades`, `TradeOptions`, `TradeResult`, `TradeOutcome`.
- Internal: `trade/internal/TradeState` (the rules, no server), `TradeSession`
  (the two windows), `TradeListener` (click, drag and close, found through the
  window's holder).
- Library hooks: `Trades.left` on quit, `Trades.release` on plugin disable,
  `Trades.releaseAll` on shutdown.
- Tests: `src/test/java/net/exylia/lib/trade/internal/TradeStateTest.java` —
  confirm and countdown completing once, any change resetting both
  confirmations and the countdown, a confirm against a stale screen or before
  the own snapshot refused, empty sides, taking a confirm back, cancel winning
  once and locking, a completing trade that cannot be cancelled, and each window
  emptied once.
