# Stays

A player kept somewhere a plugin owns (an arena, a board, a kit room) and given
back exactly as they came. Since 1.292.0.

Entry point: `net.exylia.lib.session.Stays`.

## Using

```java
private PluginStays stays;

@Override
public void onEnable() {
    stays = Stays.of(this);
}

// In: claimed and saved now, emptied once they stand in the arena.
stays.enter(player, "duel", () -> { endMatch(player); return true; }).ifPresent(stay ->
        stay.ready()
                .thenCompose(ignored -> teleports.to(player, arena).start().future())
                .thenCompose(ignored -> stays.dress(player, GameMode.ADVENTURE, stay::isCurrent)));

// Out: home, restored there, released last.
stays.leave(player);                 // where they came from
stays.leave(player, lobby);          // somewhere of your choosing
stays.leaveHere(player);             // they walked out on their own

// A hold that came first (a queue, a lent player) becomes a stay with its token and handler.
sessions.claim(player, "match", onRelease).flatMap(claim -> stays.enter(player, claim));

// Join: whatever a crash left behind goes back, and so does the player.
stays.recover(player);

// A stay restored in part (1.348.0): its own snapshot context, and only the parts named on the
// way out and on join. A risk duel keeps everything but the inventory the escrow took.
Set<SnapshotPart> kept = SnapshotPart.allExcept(SnapshotPart.INVENTORY, SnapshotPart.ARMOR, SnapshotPart.OFF_HAND);
stays.enter(player, "duel", onRelease, "duel_risk");
stays.leave(player, kept);           // also leave(player, home, kept) and leaveHere(player, kept)
stays.recover(player, "duel_risk", kept);   // that context in part, then the rest in full

// A network whose servers share the database and each keep players (1.350.0):
stays.recoverLocal(player);                          // only stays saved on this server
stays.recoverLocal(player, "duel_risk", kept);

// Commands a player may run while their join recovery is pending (1.350.0); none by default.
stays.allowWhileRecovering("msg", "login");

// Protection listeners guard on this: held, still on the way home, or still being recovered.
stays.isHeld(player.getUniqueId());
Stays.isRecovering(player.getUniqueId());           // any plugin, 1.350.0
```

## Recovering on join

`recover` reads the database, sends the player home and restores them there;
that takes a few ticks after the join. Until it ends, the player holds whatever
their player file had: an inventory rolled back by the crash, or the kit of the
arena they crashed out of. *Since 1.350.0* every recover holds the player for
that window, automatically:

- `isHeld` answers `true` for the recovering plugin, and `Stays.isRecovering`
  for any plugin; `enter` is refused, and so is a [trade](trades.md).
- The library refuses their commands (except those every recovering plugin
  allowed with `allowWhileRecovering`; labels match without the slash,
  ignoring case and any `plugin:` prefix), inventory clicks and drags,
  opening containers, dropping, picking up and interacting with blocks and
  entities. A refused command says why, in the player's chat.
- The hold ends when the recovery ends, restored or failed. A plugin that is
  disabled mid-recovery drops its holds.

Nothing to change at the call site. Do allow an authentication plugin's
`login`/`register` if the server runs one, or a slow database keeps the player
from logging in until the recovery lands.

**Which recover.** Snapshots stored in a shared database are visible to every
server. `recover` restores all of them, which is right on a single server and
on a network where only one server keeps players for the plugin. When several
servers each keep players (every arena server runs the plugin), use
`recoverLocal`: a stay saved on the arena server describes what the player had
there, and restoring it on the lobby overwrites the lobby inventory, or doubles
it when the arena later restores it too. See
[Snapshots](snapshots.md#only-this-servers-snapshots).

Each restore also writes the player file before its row is deleted, so a crash
right after a recovery cannot bring the pre-restore inventory back
([Snapshots](snapshots.md#contracts)).

## What it keeps

It is [Sessions](../src/main/java/net/exylia/lib/session/Sessions.java),
[Snapshots](snapshots.md) and [Teleports](teleport.md) in the one order that
works, which every arena plugin used to learn on its own:

- **Saved before the teleport, emptied after it.** A per-world inventory plugin
  writes whatever the player holds into the world they leave, so `enter` only
  saves and `dress` empties them once they have arrived.
- **No restore before its save has landed.** Database writes run concurrently;
  a restore read ahead of the save finds nothing, and the late save is restored
  again on the next join.
- **Released last.** Releasing tells every plugin the player is free; nothing may
  dress them while their things are still on the way back.

## Contracts

- Every method is safe from any thread; player work runs on the player's thread.
- `enter` is refused when any plugin holds the player, this one included.
- `enter(player, claim)` (1.294.0) takes a claim this plugin already holds instead; refused when the claim is not current or the player is still leaving.
- A second `leave` while the first runs does nothing; the first finishes.
- On the way out the cursor and the crafting grid are emptied. With the inventory restored their items are discarded (the snapshot holds the real inventory); with a leave that does not restore `INVENTORY`, they go into the inventory in the same step, and what does not fit is owed through `Escrow.giveItems` (1.350.1).
- `enter(..., context)` (1.348.0) saves under that snapshot context instead of `stay`; `leave`/`leaveHere` with a `Set<SnapshotPart>` restore only those parts and still use the snapshot up.
- A plugin that enters under a context restored in part recovers with `recover(player, context, parts)`: the plain `recover` restores every snapshot in full, that one included.
- `recover`/`recoverLocal` hold the player until they end (1.350.0); `enter` is refused meanwhile.
- `recoverLocal` restores only snapshots saved on this server; `recover` restores every server's (1.350.0).
- `leave` on a player this plugin is not keeping does nothing.
- A player with no snapshot left (a previous join used it) is still sent home.
- Nothing to close: claims go with the plugin, snapshots survive for `recover`.
- Reload: not applicable, nothing here comes from the palette.

## Whose death drops are (since 1.322.0)

`DeathDrops` says which deaths' drops a plugin owns, for a fight that never takes
the player anywhere — a duel in a region of the open world, where what the loser
drops is the winner's loot. Graves and death chests ask it, as well as
`Sessions.holder`, and leave a claimed death alone.

```java
DeathDrops.claim(this, player -> duels.isFighting(player.getUniqueId()));

if (DeathDrops.claimedBy(player).isPresent()) return;   // in a graves plugin
```

- One rule per plugin; a second `claim` replaces it. Released with the plugin.
- Asked during the death event, on the player's thread: answer from memory.
- A rule that throws claims nothing.

## Source and tests

- Public: `session/Stays.java`, `session/PluginStays.java`, `session/DeathDrops.java`.
- Internal: `session/internal/Recovering.java` — the recovery holds and the listener that enforces them, registered by the library.
- Tests: `src/test/java/net/exylia/lib/session/PluginStaysTest.java`.
