# Preview module

Showing one player an effect, against nothing. Since 1.30.0.

Entry point: `net.exylia.lib.util.preview.Previews`.

## Using

```java
private PluginPreviews previews;

@Override
public void onEnable() {
    previews = Previews.of(this).using(() -> config.get().preview());
}

// From a menu button:
if (previews.available()) {
    previews.show(player, effect.sequence(), () -> openMenu(player));
} else {
    tellThemAnAdminHasToSetTheStage(player);
}
```

The player is moved to the stage the server owner configured, held there, shown
the effect in front of them, and put back exactly where they were.

Pass a `Supplier<PreviewSettings>` rather than a snapshot: it is read on each
preview, so a reload — or an admin moving the stage — is picked up without
anyone remembering to re-register.

## Why a configured stage, and not an emptied room

ExyliaCommons cleared the chunks around the player by sending a whole chunk
through NMS. Without NMS that means sending every block as air, and a
four-chunk radius is about a million of them — megabytes to the client, twice,
for a three-second effect.

A room the server owner built needs no block packets at all, and it is a place
they chose to show effects off in. Until one is set there is no stage:
`available()` is false and `show` returns `null` without moving anybody, because
a guessed location puts the player inside terrain. Each plugin gives its admins
a `setpreviewlocation` command to fill it in.

No NMS, no reflection, no PacketEvents dependency: the isolation is
`hideEntity` and `hidePlayer`, which are public Bukkit API.

## Contracts

- **Only the viewer sees it.** The sequence is played with `onlyTo(player)`, and
  for the duration they are hidden from everyone and everyone from them.
- **Nothing happens without a stage.** `show` returns `null`, the player is not
  moved, and the callback does not run: there is nothing to come back from.
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
  player to the stage.
- **Two at once never meet.** One stage serves everyone: each viewer is hidden
  from the others and every step is sent to its own viewer alone.
- **The stage keeps its own facing.** The server owner aimed it at whatever they
  built behind it, so that is where the effect plays.
- **The callback always runs**, however the preview ended, so a menu that opened
  one is reopened either way. It never runs for a player who has gone.

## What ends a preview

| | What happens |
| --- | --- |
| The effect finishing | Returned to the origin |
| `Preview#end()` | Returned to the origin |
| Quit or kick | Not moved — teleporting a leaving player throws; the hiding is undone |
| Death | Left where they are; respawn decides |
| World change | Left where they are; they were sent there on purpose |
| Teleport by another plugin | Left where they are |
| Plugin disabled | Returned, before its scheduler goes away |
| Server stopping | Ended in place |
| Safety timer | Returned, and a warning is logged |
| Logging in still altered | Cleared on join — only reachable if the server died mid-preview |

A teleport is the plugin's own when it lands on the stage or on the origin;
anything else is somebody else moving the player, and ends the preview where
they were put.

## Configuration

`PreviewSettings` nests in a plugin's own config record.

```yaml
preview:
  location: ''        # server,world,x,y,z,yaw,pitch — empty means no previews
  distance: 5.0       # how far in front of the player the effect plays
  settle-ticks: 4     # wait after the teleport, so the client has the position
  linger-ticks: 20    # how long to hold the stage after the effect ends
  max-ticks: 600      # the safety net
```

`location` is the stored `ExyliaLocation` form, so it is the same text every
warp, home and arena in the ecosystem already uses. It is normally written by an
admin command rather than by hand — `PreviewSettings#at(Location)` returns the
same settings with a new stage, for a `ConfigFile#update` to save.

A `max-ticks` shorter than the settle plus the linger is raised: it would fire
before the effect it is meant to outlast.

## Source and tests

- Public: `util/preview/` — `Previews`, `PluginPreviews`, `Preview`,
  `PreviewSettings`.
- Internal: `util/preview/internal/` — `PreviewRuntime` (the registry and every
  interrupting listener), `PreviewSession`, `StagedPlayer` (capture and
  restore).
- Tests: `PreviewTest` covers the stage, the missing stage, the restore,
  quitting, a second preview replacing the first, plugin disable, and two
  players at once.
