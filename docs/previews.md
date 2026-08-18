# Preview module

Showing one player an effect, against nothing. Since 1.30.0.

Entry point: `net.exylia.lib.util.preview.Previews`.

## Using

```java
private PluginPreviews previews;

@Override
public void onEnable() {
    previews = Previews.of(this).using(config.get().preview());
}

// From a menu button:
previews.show(player, effect.sequence(), () -> openMenu(player));
```

The player is lifted to an empty patch of sky, held there, shown the effect in
front of them, and put back exactly where they were.

## Why the sky, and not an emptied room

ExyliaCommons cleared the chunks around the player by sending a whole chunk
through NMS. Without NMS that means sending every block as air, and a
four-chunk radius is about a million of them — megabytes to the client, twice,
for a three-second effect.

Somewhere with no blocks needs no block packets at all. The background is
genuinely empty rather than pretending to be, and coming back is one teleport.

No NMS, no reflection, no PacketEvents dependency: the isolation is
`hideEntity` and `hidePlayer`, which are public Bukkit API.

## Contracts

- **Only the viewer sees it.** The sequence is played with `onlyTo(player)`, and
  for the duration they are hidden from everyone and everyone from them.
- **The player always comes back.** Ending is idempotent and reachable from
  every direction. A safety timer ends a preview that outlasts its estimate.
- **They do not fall.** Held by flight, gravity off, invulnerable, fall damage
  disarmed. Flight rather than resetting velocity every tick: the client
  predicts its own movement, and fighting it every tick is what produces
  rubber-banding.
- **The game mode is never changed.** Creative would hand out a creative
  inventory for the length of the preview.
- **One per player.** A second preview ends the first. Two overlapping previews
  would each remember an origin, and the second to finish would return the
  player to a patch of empty sky.
- **Two at once never meet.** Each preview claims its own stage from a global
  grid, across every plugin.
- **The stage stays in the player's own world.** Crossing worlds would change
  their sky and fire a world-change event at every plugin for something the
  player did not do.
- **The callback always runs**, however the preview ended, so a menu that opened
  one is reopened either way. It never runs for a player who has gone.

## What ends a preview

| | What happens |
| --- | --- |
| The effect finishing | Returned to the origin |
| `Preview#end()` | Returned to the origin |
| Quit or kick | Not moved — teleporting a leaving player throws; the slot is freed and the hiding undone |
| Death | Left where they are; respawn decides |
| World change | Left where they are; they were sent there on purpose |
| Teleport by another plugin | Left where they are |
| Plugin disabled | Returned, before its scheduler goes away |
| Server stopping | Ended in place |
| Safety timer | Returned, and a warning is logged |
| Logging in still altered | Cleared on join — only reachable if the server died mid-preview |

## Configuration

`PreviewSettings` nests in a plugin's own config record.

```yaml
preview:
  height: 1000        # how far above the world the stage sits
  separation: 64      # how far apart two simultaneous stages are
  distance: 5.0       # how far in front of the player the effect plays
  settle-ticks: 4     # wait after the teleport, so the client has the position
  linger-ticks: 20    # how long to hold the stage after the effect ends
  max-ticks: 600      # the safety net
```

Every value is floored: a stage below y=320 would put the player inside terrain,
and a `max-ticks` shorter than the settle plus the linger would fire before the
effect it is meant to outlast.

## Source and tests

- Public: `util/preview/` — `Previews`, `PluginPreviews`, `Preview`,
  `PreviewSettings`.
- Internal: `util/preview/internal/` — `PreviewRuntime` (the registry and every
  interrupting listener), `PreviewSession`, `StagedPlayer` (capture and
  restore), `Stages` (the slot grid).
- Tests: `PreviewTest` covers the lift, the restore, quitting, a second preview
  replacing the first, plugin disable, and two players at once.
