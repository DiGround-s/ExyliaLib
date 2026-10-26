# Lobby

Tables, seats and invitations: open tables anybody may take, invitations sent
to one player and answered with clickable buttons, a minimum and a maximum of
seats, a start when full or on the host's word, expiry, and a player who leaves
mid-play. One table per player, server-wide. Since 1.356.0.

Entry point: `net.exylia.lib.lobby.Lobby`.

## Using

```java
private Lobby<Match> lobby;

@Override
public void onEnable() {
    lobby = Lobby.of(this, hooks, () -> messages.get().tables());   // LobbyHooks<Match>, LobbyMessages
}

TableRules rules = new TableRules("tabletop:checkers", "/checkers", 2, 2,
        Duration.ofMinutes(10), Duration.ofSeconds(60));

lobby.create(host, match, rules, null);       // /checkers create: an open table
lobby.create(host, match, rules, target);     // /checkers duel <player>: an invitation
lobby.open(m -> m.game() == checkers);        // the open tables, oldest first, for a menu
lobby.join(player, table);                    // a click in that menu
lobby.accept(target, hostName);               // /checkers accept [host]  (null: the newest)
lobby.decline(target, hostName);              // /checkers deny [host]
lobby.invitesTo(target.getUniqueId());        // host names, newest first, for suggestions
lobby.start(host);                            // /checkers start, with minSeats seated
lobby.cancel(host);                           // /checkers cancel, while it waits
lobby.leave(player);                          // /checkers leave, quit, death

// The table is handed over by LobbyHooks.started:
lobby.play(table);                            // the round begins
lobby.finish(table);                          // played out
lobby.cancel(table);                          // called off; cancelAll() on disable
```

The hooks are where the plugin does its part. Every one has a default:

```java
LobbyHooks<Match> hooks = new LobbyHooks<>() {
    @Override
    public CompletableFuture<String> admit(Table<Match> table, Player player) {
        return escrow.hold(table.id(), player.getUniqueId(), "", table.subject().stake())
                .thenApply(hold -> hold == Escrow.Hold.HELD ? null : messages.get().cannotPay());
    }

    @Override
    public void started(Table<Match> table) {
        seatAndCountDown(table);              // then lobby.play(table), or lobby.cancel(table)
    }

    @Override
    public void dropped(Table<Match> table, Seat seat) {
        if (table.present().size() < 2) win(table, table.present().getFirst());   // then lobby.finish(table)
        else rounds.get(table).drop(table.seatOf(seat.player()));
    }

    @Override
    public void ended(Table<Match> table, TableEnd end) {
        if (end != TableEnd.FINISHED) escrow.refund(table.id());
    }

    @Override
    public Values values(Table<Match> table) {
        return Values.of("game", table.subject().game().displayName());
    }
};
```

| Hook | When |
| --- | --- |
| `admit(table, player)` | a seat was just reserved (the host's on `create`): complete with `null` to seat them or a line to refuse; failing refuses with `failed` |
| `released(table, seat)` | an admitted seat will not play (left before the start, or the table ended while it was admitted): give back that seat's hold |
| `joined(table, seat)` | a seat was admitted, the host's included once the table opens |
| `started(table)` | full, or started early, with every seat admitted |
| `dropped(table, seat)` | a player left a table being played |
| `ended(table, end)` | once, whatever ended it: `FINISHED`, `CANCELLED`, `EXPIRED`, `DECLINED` |
| `values(table)` | the plugin's own placeholders for every message |

## States

`CREATING` (the host is being admitted) → `OPEN` (listed, or invited) →
`STARTING` (handed to the plugin) → `PLAYING` → `ENDED`; any state may end.
Every change is under the table's lock, so two players taking the last seat
cannot both get it, and a table ends exactly once. A full table starts only
once **every** seat's admission has answered, so a stake still being held can
never fail after the table has left the lobby.

Seats keep their numbers for the table's life: a player who leaves while it is
played stays in `seats()`, marked by `hasLeft(seat)`; `present()` lists who is
still there.

| Leaving while | Does |
| --- | --- |
| `OPEN`, a guest | the seat is given back (`released`), the others are told |
| `OPEN`, the host | the table is closed (`CANCELLED`), everyone is told |
| `STARTING` | the table is called off (`CANCELLED`), the others are told |
| `PLAYING` | the claim is released, `dropped` is called, play goes on |

An invitation to a table of more than two opens to everyone once the invitee
sits down, and from then on waits as long as an open table does.

## One table at a time

Taking a seat claims the player through [sessions](../src/main/java/net/exylia/lib/session/Sessions.java)
with `TableRules.kind` as the activity. A player another plugin holds is
refused (`busyElsewhere`), so is one another plugin's readiness rule advises
against (`busyAdvice`), and so is one this plugin holds for anything else. The
claim is released when the player leaves or the table ends. Another plugin
asking for the player back (`Sessions.release`) makes them `leave`.

## Messages

`LobbyMessages` is a record of lines with English defaults and `@Comment`s.
The library writes it nowhere: the plugin embeds it in its own messages record,
translates it, and hands a supplier to `Lobby.of`, read on every send. Lines go
through the plugin's `%prefix%`; a blank line sends nothing. Every line has the
table's values, the plugin's (`values` hook) and `%seated%`, `%seats%`, `%min%`,
`%missing%`, `%command%`, plus `%player%` where it is about one.

The invitation is a centred block (`invite`): blank, title, description, blank,
`%accept%` and `%decline%`, blank. The buttons are built from `acceptButton` /
`declineButton` and their hovers, and run `<command> accept <host>` and
`<command> deny <host>`; the host's early start button runs `<command> start`.
The plugin's command handles those subcommands (with `@Single` arguments and a
no-argument `accept` overload calling `accept(target, null)`).

## Contracts

- **Threads.** Every method is safe from any thread. Messages are sent on each
  player's own thread. Hooks run on the thread that caused the step: the
  caller's, the expiry timer's (global), or the one completing `admit`.
- **No stakes.** The lobby holds seats only; holds, payouts and refunds are the
  plugin's, in `admit`, `released` and `ended`.
- **Lifetime.** Expiry timers are the plugin's tasks; its claims are dropped
  when it is disabled. Call `cancelAll()` from `onDisable` so `ended` gives
  every stake back.
- **Reload.** Nothing is cached: messages are read on every send and rules are
  per table. Not hooked into `ExyliaLib.loadPalette`.
- **For a duels plugin** the same API fits: a 2-seat `TableRules`, `create`
  with the target, `accept`/`decline`, `invitesTo` for suggestions. A host has
  one table at a time, so a second challenge is refused rather than replacing
  the first.

## Source and tests

- Public: `lobby/` — `Lobby`, `LobbyHooks`, `LobbyMessages`, `Table`,
  `TableRules`, `TableState`, `TableEnd`, `Seat`.
- Tests: `lobby/TableTest` (seat reservation, invitations, admissions gating
  the start, early start, drops keeping seat numbers, ending once),
  `lobby/LobbyTest` (on the fake server: a table filled, played and finished;
  one table at a time and claims held by others; invitation expiry; accept and
  decline; refused and late admissions; early start and a drop mid-play;
  eviction by another plugin; leaving before the start).
