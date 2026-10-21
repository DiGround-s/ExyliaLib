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

// Protection listeners guard on this: held, or still on the way home.
stays.isHeld(player.getUniqueId());
```

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
- Tests: `src/test/java/net/exylia/lib/session/PluginStaysTest.java`.
