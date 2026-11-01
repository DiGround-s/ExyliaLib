# Respawns

Where a player respawns, and code that runs once they are standing again, on
Paper and on Folia alike.

*Since 1.440.0.*

Entry point: `net.exylia.lib.respawn.Respawns`.

## Why this exists

On Folia, **neither `PlayerRespawnEvent` nor `PlayerPostRespawnEvent` fires
when a player respawns after dying**. Folia's death respawn
(`ServerPlayer.respawn(Consumer, RespawnReason)`) never constructs the event;
only leaving the End does. A plain `@EventHandler PlayerRespawnEvent` still
works on Paper but is dead code on Folia: the respawn-at-spawn, the reminder,
the kit, the board refresh simply never happen.

This module drives the same rules and callbacks from the events on Paper and
detects the respawn itself on Folia.

## Choosing where they respawn

```java
Respawns.of(this).location(EventPriority.HIGH, ctx -> {
    if (ctx.bedSpawn()) return null;          // keep their bed or anchor
    return spawn.location();                  // everybody else at spawn
});
```

`location(EventPriority priority, RespawnRule rule, RespawnReason... reasons)`
returns a `RespawnHook`.

- Rules run **in priority order, like listeners**. Each one sees, as
  `ctx.target()`, vanilla's spot or what the rule before it returned. The last
  non-null answer wins; `null` means "no change".
- With no `reasons` a rule only sees **deaths**. Pass
  `RespawnReason.END_PORTAL` (or `PLUGIN`) to opt in.
- `RespawnContext`: `player()`, `target()` (a copy), `bedSpawn()` (bed, anchor
  or forced spawn point), `deathLocation()` / `deathWorld()` (`null` when the
  respawn followed no death), `reason()`.
- On Paper a rule **is** a `PlayerRespawnEvent` listener of your plugin at that
  priority, so it orders against plain listeners too. The respawn location is
  set on the event: nobody is teleported.

## Running code once they stand

```java
Respawns.of(this).after(player -> kits.giveStarter(player));
```

`after(Consumer<Player>)` runs after **every** respawn, on the player's thread,
with the final location settled: the rules applied and, on Folia, the move
made. It replaces `PlayerPostRespawnEvent` and the usual "one tick after
respawn" hop.

Everything a plugin registered goes away when it is disabled. `RespawnHook#cancel`
is for something shorter-lived than the plugin.

## How Folia is handled

1. `PlayerDeathEvent` (not cancelled) records the death location and the
   stored respawn point (`getPotentialRespawnLocation`, read unvalidated:
   validating reads the bed's block, which may be another region's).
2. A global timer every 2 ticks, alive only while somebody is dead, hops onto
   each dead player's thread with **one hop in flight per player**, and only
   there reads `!isDead() && isValid()`. Nothing about the player is read off
   their own thread. Folia's respawn removes the player with
   `CHANGED_DIMENSION`, which does not retire their scheduler, so a queued hop
   survives the respawn either way.
3. Once they stand, the rules run with vanilla's actual landing as the target.
   If the answer differs, the player is moved through
   `Teleports.of(library).to(player, loc).warmup(0)`: no countdown, not
   cancelled by moving or damage, no message, serialised with every other move
   of that player, cause `PLUGIN` from the library (region holds such as
   SurvivalCore's let another plugin's `PLUGIN` moves through).
4. Then the `after` callbacks run.

`bedSpawn()` on Folia is inferred: the landing is within 3 blocks of the stored
respawn point. A broken bed or empty anchor makes vanilla use the world spawn
and the flag false, as on Paper.

Quitting on the respawn screen forgets the death; rejoining dead starts
watching again. A respawn screen left open for an hour costs one map entry and
one queued hop.

**Never twice.** If a real `PlayerRespawnEvent` fires for the death (a platform
that someday fires it), the library claims the death at `LOWEST` and the watch
skips it; the rules run as listeners and the callbacks once, after the event.

**Limits.** On Folia the player is visible at vanilla's spot for a tick or two
before the move, and `ExyliaTeleportEvent`/`PlayerTeleportEvent` fire for it.
On Folia only `DEATH` and `END_PORTAL` respawns exist.

## Migrating a listener

| Before | After |
| --- | --- |
| `@EventHandler(priority = P) void on(PlayerRespawnEvent e) { ... e.setRespawnLocation(loc); }` | `Respawns.of(plugin).location(P, ctx -> ... loc /* or null */)` |
| a `PlayerRespawnEvent` handler that only reacts (messages, kits, effects, boards) | `Respawns.of(plugin).after(player -> ...)` |
| `PlayerPostRespawnEvent` handler, or `runAtEntityLater(player, 1, ...)` from the respawn event | `Respawns.of(plugin).after(player -> ...)` (drop the hop) |
| `e.isBedSpawn() \|\| e.isAnchorSpawn()` | `ctx.bedSpawn()` |
| `e.getPlayer().getLastDeathLocation()` / a death location stored on `PlayerDeathEvent` | `ctx.deathLocation()` |
| `if (e.getRespawnReason() != DEATH) return;` | the default; pass reasons to widen it |

Notes:

- Don't teleport from `after` to choose the respawn: return the location from a
  rule, so on Paper nobody is teleported and on Folia the move is the library's.
- A rule runs on the player's thread but must stay cheap: no database, read
  what is cached.
- A rule that consumes state (a one-time return point) should only clear it
  when it returns a location.
