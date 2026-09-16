# Cameras

Showing a player the world from somewhere they are not. Since 1.172.0.

```java
PluginCameras cameras = Cameras.of(this);

CameraShot orbit = CameraShot.parse(
        "0 close"
      + " | 3.2 yaw=~360 ease=in_out"
      + " | 0.6 distance=2.1 pitch=4 ease=out", problem -> getLogger().warning(problem));

cameras.play(player, orbit);
```

## What it is for

The half of an animation the player who triggered it cannot see. A body that
waves, dances or celebrates is drawn for everybody around them and for nobody
inside it: the one player it was made for is looking out of its eyes at a wall.

After that it is for everything a shot is for. A tutorial that shows the thing
it is talking about. An intro that pulls off a spawn platform as somebody lands
on it. A cutscene at the end of an event. All of them are the same few seconds
of somebody else's eyes.

## Writing a shot

Frames are separated by `|`. Each starts with the seconds it takes to reach it
from the one before, and lists only what changes — everything it does not
mention is carried over, so a push-in is a frame that says nothing but
`distance:`.

| Channel | What it moves |
| --- | --- |
| `distance=` | how far the camera sits from what it is watching, in blocks |
| `yaw=` | where it sits around them, in degrees: `0` behind their back, `90` off their left shoulder, `180` facing them |
| `pitch=` | how far above the horizon it sits; positive looks down at them |
| `height=` | what it aims at, in blocks above their feet |
| `ease=` | how the frame is reached |

Everything is written in the subject's own terms, so one shot reads the same
whichever way the player happens to be facing. A number written as `~90` is
added to where that channel already is, so `yaw=~360` is one more turn around
them however many came before it.

A bare word is a whole frame: `behind`, `front`, `side`, `over`, `low`, `close`,
`wide` and `top`. It sets all four channels, and whatever the frame writes after
it is written on top.

Easing is the same vocabulary the ragdoll module uses — `in_out` by default,
then `in`, `out`, `linear`, `back`, `anticipate`, `elastic`, `bounce`, `snap`
and `smooth`. `smooth` runs a curve through the neighbouring frames instead of
stopping at every one of them, which is what a camera visiting four waypoints
needs and what nothing else gives it.

```yaml
Emote:
  Keys: '0 behind | 0.5 close ease=out | 2.4 yaw=~360 ease=smooth | 0.5 behind ease=in_out'
```

### The camera always looks at the subject

Nothing in a shot says where it points. A shot that had to be told both where
the camera is and where it aims is a shot nobody gets right by hand: the aim is
the subject at `height`, every frame, and the whole vocabulary above is about
walking the camera around that point.

## What it does to the player

A player looking through a camera is frozen and their client is drawn as a
spectator — no hand, no hotbar — because somebody who cannot see their own body
should not be walking, and should not be shown equipment belonging to a body
that is not on screen. Both are put back at the end, and **only if this module
was what did them**: a camera that unfroze everybody it filmed would let a
player walk out of somebody else's cutscene.

Everything else about them is untouched, deliberately. They are still standing
where they were, still have a hitbox, and can still be hit.

> A camera is not protection. A plugin that films somebody in the open has left
> them standing still with their eyes elsewhere. Whether that is acceptable is
> the plugin's decision, not this module's — an emote plugin cancels the shot on
> damage and refuses it in combat; a cutscene in a lobby does not have to.

One player looks through one camera. Asking for a second while a first is
playing ends the first.

## What it costs

The camera is a display entity told where to be and how long it has to get
there, so the client draws every frame in between at its own frame rate — the
same field the display and ragdoll modules animate with, for the same reason. A
shot is about ten packets a second, for one entity, for as long as it plays.

The whole path is solved the moment the shot starts, from where the subject is
standing and which way they are facing then. Nothing is read from the world
again while it plays, which is what keeps the driver off the main thread and off
Folia's regions entirely.

What that costs is that the camera does not follow a subject who walks out from
under it. That is the right trade for what this is for: a shot is a few seconds
long and whoever is being filmed is standing still for it, either because they
are frozen or because the emote they asked for is cancelled the moment they
move.

## Limits worth knowing before you write a shot

- **A shot is at most 60 seconds.** Anything past that is dropped and reported
  when the file is read. A camera is a moment, not a state.
- **Distance is clamped to 0.6–24 blocks.** Closer is inside the subject; further
  is outside what the client tracks, and an entity it stopped tracking is an
  entity it cannot render from.
- **Pitch is clamped to ±85°.** Straight up and straight down have no defined aim
  direction and the view rolls as it passes through them.
- **Walls pull the camera in.** Each position is ray-traced from the subject and
  stopped short of whatever it hits, so an orbit indoors hugs the room instead of
  rendering the inside of a block. Passable blocks are ignored: a camera that
  jumped to the subject's nose because it clipped a flower would be worse than
  one that sees through the flower.
- **Rotation travels as a byte**, so about 1.4° of resolution. A slow pan is
  smoothed by the client's own interpolation; a frame that goes more than 150°
  around the subject between two positions is reported, because past a half turn
  the client's shortest way round is the wrong way and the orbit visibly snaps
  backwards once a revolution.

## Written in configuration

Most callers never touch the API. The sequence module has a `[CAMERA]` line:

```yaml
Effects:
  - '[CAMERA] keys:0 close | 2.4 yaw=~360 ease=in_out | 0.4 behind ease=out'
  - '[SOUND] ENTITY_PLAYER_LEVELUP;0.7;1.4'
```

Parameters are separated by semicolons like every other line, and `keys:` comes
last because its own value is full of spaces and bars.

`who:` decides whose eyes it takes — `source` (the default: whoever caused the
sequence), `target` (whoever it happened to, when that is a player) or `both`,
through one camera and therefore one framing.

A shot is cancelled with the sequence that started it. A preview the player
closed, or an emote somebody was hit out of, gives those eyes back with the
body it took them for.

It is never the audience. An effect is played for everybody within its radius,
and a line that took the eyes of thirty people standing near a kill is a line
nobody could write safely. The audience of a sequence and the subject of a
camera are different things, and this is where that matters most.

## Two players, two shots

`play(List<Player>, shot, subject)` shares one camera entity between everybody
watching, so a celebration filmed for four people costs what it costs for one —
and everybody sees the identical framing.

Two players who should each see themselves from over their own shoulder are
therefore two calls, not one:

```java
cameras.play(first, overShoulder);
cameras.play(second, overShoulder);
```

Same shot, each solved around its own subject.

## Nothing the server has to carry

The camera is a packet, so it is not ticked, not saved and not in any chunk.
What that costs is that nothing else will give a player their view back, and the
failure is worse than a display left on a client: a player whose view is not
restored is looking out of an entity that no longer exists, unable to see their
own body, and no part of the server will notice.

The life is therefore held by the module. A shot ends when it runs out, when
somebody stops it, when the player leaves, dies or changes world, when its
plugin is disabled, or when the server stops. There is no seventh case.

## Requirements

PacketEvents. A camera is packets and nothing else, so there is no fallback
worth pretending about; without it, `isSupported()` is false, `play` returns
`null` and the server is told once.

## Source and tests

- `net.exylia.lib.camera` — `Cameras`, `PluginCameras`, `CameraShot`,
  `CameraHandle`
- `net.exylia.lib.camera.internal` — the runtime, the path solver and the one
  class that names PacketEvents
- `CameraShotTest`, `CameraPathTest` — the parser and the geometry, both without
  a server
