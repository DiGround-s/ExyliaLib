# Guide

Showing one player where to go and what to do, the way a game does it: a
panel floating ahead of their eyes, a beam of light over the place, footprints
on the ground along the way, a mark on the locator bar, the thing to talk to
glowing. Since 1.391.0.

```java
PluginGuides guides = Guides.of(this);

GuideText panel = guides.companion(player, Text.of("{primary}&lTALK TO THE SMITH").build());

GuideBeacon beacon = guides.beacon(player, smithy.clone().add(0.5, 1, 0.5),
        BeaconStyle.of(Material.PURPLE_STAINED_GLASS.createBlockData(), 0x8A51C4),
        metres -> Text.of("{letters}Smith {letters_black}» {info}" + Math.round(metres) + " m").build());

GuideTrail trail = guides.trail(player, world, route,
        TrailStyle.of(Material.PURPLE_CONCRETE.createBlockData(), 0x8A51C4));

guides.waypoint(player, "smith", smithy, 0x8A51C4);
guides.glow(player, smithNpc, NamedTextColor.LIGHT_PURPLE);

// progress changes: in place, no respawn, no chat
panel.text(Text.of("{primary}&lTALK TO THE SMITH\n{letters}Progress {info}1{letters_black}/{info}2").build());

// the step is done
guides.clear(player);
```

Entry point: `net.exylia.lib.guide.Guides`.

## Everything is for one player

Every piece is packets to one client. It is not an entity, not ticked, not
saved and not in any chunk, and the player standing next to them sees none of
it. A server can guide a hundred new players at once through the same square
and none of them sees the others' beacons.

What that costs is that nothing else will take it away, so the module owns the
life of every piece: it goes when it is removed, when its player leaves, when
its plugin is disabled, and when the server stops. A death, a change of
world or a long teleport is **not** an end: the client threw the entities away by itself, and
the next tick draws them again where the player now is, because a journey does
not stop when somebody dies.

Every method returns a handle, never `null`. Without PacketEvents the handle
works and draws nothing, so a caller never branches on support.

## View text

```java
GuideText step = guides.view(player, text);                                   // the subtitle defaults
GuideText step = guides.view(player, text, ViewTextStyle.subtitle().screenY(0.3f).lineWidth(260));
step.text(updated);   // in place, one metadata packet; an equal text sends nothing
step.remove();
```

A cinematic subtitle in normal play (since 1.395.0): small, on a translucent
panel, centred, its bottom edge a quarter of the way up the screen, so it
clears the hotbar, hearts, hunger and experience bar. It grows upwards with
more lines.

- **It rides the view.** The text display is seated on the player, for their
  client alone, with a camera-facing billboard and a translation in the view's
  axes, the same trick as a [camera](cameras.md) subtitle. The client puts it
  back in place every frame, so it never trails a turn and turning costs no
  packet.
- **The seat is above the eyes** (a player's height minus their eye height,
  about 0.2 blocks in every pose). That offset is taken back off for the
  current pitch and pose, re-sent when either moves a few degrees, so standing,
  sneaking, swimming and riding all put it at the same spot.
- **Real passengers are kept.** The mount sends the passengers the server
  knows plus the panel, and every passenger list the server sends the player
  afterwards gets the panel added back, so somebody climbing on (or off) the
  player does not unseat it, and it does not unseat them.
- **It steps aside** while a camera shot plays to the player (the shot's own
  subtitle takes over), while they are dead and in spectator mode, and comes
  back on the next draw.
- **First person.** In third person the client still draws it a block ahead of
  the player's eyes, which is in the shot: smaller and lower.

| Field | Copy | Default |
| --- | --- | --- |
| `background` (ARGB, `0` none) | `background(argb)` | `0x70000000` |
| `opacity` (0-255) | `opacity(alpha)` | 255 |
| `shadow` | `shadow(on)` | true |
| `lineWidth` (pixels) | `lineWidth(px)` | 300 |
| `scale` | `scale(size)` | 0.2 (a camera subtitle) |
| `screenY` (0 bottom, 1 top) | `screenY(share)` | 0.25 |
| `seeThrough` | `seeThrough(on)` | true (it floats under a block ahead, so a wall that close would hide it) |

Sizes and positions are for the default 70° field of view; a wider one draws
it smaller and nearer the centre. A Bedrock client draws display entities
poorly: use a boss bar or chat for those players (`Guides.isBedrock`).

## Companion text

```java
GuideText panel = guides.companion(player, text);                         // the defaults
GuideText panel = guides.companion(player, text, GuideTextStyle.companion().ahead(3).lineWidth(180));
panel.text(updated);   // one metadata packet on the next tick; an equal text sends nothing
panel.remove();
```

A panel floating `ahead` blocks in front of the eyes (2.5 by default) and
`drop` below the line of sight (0.35), so it never sits on the crosshair.

- **It glides, it does not stick.** It is moved only when the view turns more
  than 6° or its spot moves more than 0.3 blocks, and the client takes six
  ticks to get there, so it eases after the view. A HUD pinned to the view
  every tick shakes with every mouse movement.
- **It follows the view up and down only so far** (25° up, 30° down), so a
  player looking at their feet to place a block is not looking through it.
- **It steps aside** while the player glides, moves faster than
  `hideAboveSpeed` blocks per second (7.5 by default: sprint-jumping, riding,
  flying), or is in combat (`hideInCombat`, through [Combat](combat.md)). It
  comes back after a second of calm.

## Anchored text

```java
GuideText sign = guides.anchored(player, npc.getLocation().add(0, 2.4, 0), text);
```

A text at a place, always facing the player. With `scaleWithDistance` (on by
default) it is drawn at full size up to eight blocks away and grows in
proportion after that, up to `maxScale` (4) times, so it stays readable from
across a square. Nothing is drawn while the player is in another world.

## Text style

`GuideTextStyle` is a record; `companion()` and `anchored()` are the two
starting points, and each field has a `with`-style copy:

| Field | Copy | Companion | Anchored |
| --- | --- | --- | --- |
| `background` (ARGB, `0` none) | `background(argb)` | `0x78000000` | `0x78000000` |
| `opacity` (0-255) | `opacity(alpha)` | 255 | 255 |
| `shadow` | `shadow(on)` | true | true |
| `lineWidth` (pixels) | `lineWidth(px)` | 220 | 220 |
| `scale` | `scale(size)` | 0.55 | 1 |
| `seeThrough` | `seeThrough(on)` | false | false |
| `ahead`, `drop` | `ahead(b)`, `drop(b)` | 2.5, 0.35 | — |
| `hideAboveSpeed`, `hideInCombat` | `stepAside(bps, combat)` | 7.5, true | — |
| `scaleWithDistance`, `maxScale` | `growWithDistance(on, max)` | — | true, 4 |

Every text is drawn at full brightness: guidance that goes dark at night or in
a cave is guidance players report as missing.

## Beacons

```java
GuideBeacon beacon = guides.beacon(player, target, BeaconStyle.of(glass, 0x59A4FF).height(64), label);
beacon.pulse();           // the beam swells for a moment: a step starting, a hint
beacon.retarget(next);    // in place
beacon.state();           // AT_TARGET, MARKER or ELSEWHERE
beacon.distance();        // blocks; NaN in another world
```

A beam of two block displays at full brightness: a thin white core and a wider
shell of the style's block, outlined in the style's colour so the pillar reads
through terrain. Stained glass makes the best shell, because the core shows
through it. Pass the target as a block's top centre,
`block.getLocation().add(0.5, 1, 0.5)`.

The label is a `DoubleFunction<Component>` given the distance in blocks. It is
asked again whenever the rounded distance changes, on the player's thread, so
it must only build a component. It grows with distance like an anchored text.

How it is drawn is the style's `label`, a `GuideTextStyle` (since 1.393.0).
Only its text fields apply; where it floats is the beacon's. The default,
`BeaconStyle.DEFAULT_LABEL`, is a light dark panel hidden by terrain, growing
up to 8 times. A waypoint that must read from anywhere:

```java
BeaconStyle style = BeaconStyle.of(glass, 0x8A51C4)
        .label(BeaconStyle.DEFAULT_LABEL.background(0).seeThrough(true).growWithDistance(true, 24));
```

Growing keeps it the same size on screen (full size up to 8 blocks, then in
proportion). The beam is drawn up to the cut-over, about 136 blocks at ten
chunks, so a `maxScale` under `cut-over / 8` (17 there) lets the label shrink
before the stand-in takes over. Beside the stand-in it is drawn at
`scale × markerDistance / 10`.

### When the target is too far

A client draws nothing past its view distance. Past the cut-over, the beam is
replaced by a 3-block stand-in `markerDistance` blocks (30) from the player in
the target's direction, at eye height, with the label above it. It glides as
the player moves, so it reads as a mark on the horizon. The cut-over is
`farDistance`, or by default a little short of the smaller of the player's and
the world's view distance (`chunks × 16 − 24`, never under 32 blocks), with 8
blocks of slack so walking along it does not flicker.

In another world than the target nothing is drawn and `state()` is
`ELSEWHERE`: there is no direction to point in. Say where to go in the
companion text instead.

| `BeaconStyle` | Default |
| --- | --- |
| `beam`, `colour` | given to `of(block, rgb)`; `colour` `-1` for no outline |
| `height` | 48 |
| `width` | 0.35 |
| `labelHeight` | 2.8 |
| `markerDistance` | 30 |
| `farDistance` | 0 (automatic) |
| `label` | `DEFAULT_LABEL`: anchored, background `0x60000000`, not see-through, grows up to 8 |

## Trails

```java
Route route = Route.ofLocations(recorded).simplified(0.4);
GuideTrail trail = guides.trail(player, world, route, TrailStyle.of(concrete, 0x8A51C4).ahead(24));
trail.progress();          // blocks along the route
trail.isOffRoute();
```

Small flat glowing panels on the ground, turned along the route, drawn only
from the point of the route nearest the player to `ahead` blocks along it.

- **Cheap because the markers stay put.** They sit at fixed multiples of
  `spacing` along the route, so walking costs one marker spawned ahead and one
  destroyed behind per spacing walked, not a redraw. A new marker is spawned
  flat and grown on the next tick, so the trail unrolls instead of popping in.
- **Off the route** by more than `offRoute` blocks (6), a short line of up to
  eight markers in the way-back block is drawn from the route towards the
  player, turned to point back at it. It goes the moment they are back.
- **On the ground.** A recorded route is already at foot height, so each
  marker is only nudged onto the surface below it (`Route.snap`), read once
  when it spawns, on the player's thread, from loaded chunks that thread owns.
  A block it may not read leaves the marker at the route's own height.

| `TrailStyle` | Default |
| --- | --- |
| `marker`, `colour` | given to `of(block, rgb)` |
| `ahead` | 20 (at most 64 markers) |
| `spacing` | 1.5 |
| `offRoute` | 6 |
| `returnMarker`, `returnColour` | the marker's; `wayBack(block, rgb)` |

### Route

`Route` is the geometry on its own, pure and immutable, for a plugin that
records, stores or measures routes:

| Method | Answers |
| --- | --- |
| `of(List<Vector>)`, `ofLocations(List<Location>)` | a route; consecutive duplicates dropped |
| `length()`, `size()`, `point(i)`, `points()` | its shape, for saving |
| `closest(x, y, z)` | `Nearest(arc, x, y, z, distance)`; ties go to the later stretch |
| `at(arc)` | `Station(x, y, z, yaw)`, the heading in Minecraft degrees |
| `stations(from, ahead, spacing)` | the marker indexes in a window; marker `k` sits at `k × spacing` |
| `simplified(tolerance)` | a thinner copy (Ramer–Douglas–Peucker), ends kept |
| `snap(x, y, z, Ground)` | the surface height near `y`, from one block above to four below |

`Route.Ground` answers the top of one block or `NaN`; the runtime answers it
from the world, a test from a lambda.

## Waypoints

```java
GuideWaypoint mark = guides.waypoint(player, "smith", smithy, 0x8A51C4);
mark.move(next);
mark.isDrawn();
```

The vanilla **locator bar** is used when the server and the client both speak
1.21.6 or newer and the PacketEvents installed knows the packet — all three are
checked, and any doubt is a no. It is untracked while the player is in another
world than the target. **Lunar and Feather** get a minimap waypoint through the
[client module](client.md) when the player runs one; that path works without
PacketEvents. `isDrawn()` is `false` when neither applies.

## Glow

```java
guides.glow(viewer, npcEntity, NamedTextColor.LIGHT_PURPLE);   // any entity
guides.glow(viewer, packetBodyId);                              // an entity that is only packets
guides.glow(viewer, chestBlock, TextColor.color(0x8A51C4));     // a block, through terrain
```

| Target | Through |
| --- | --- |
| a player | the [nametag module](nametags.md): `glowOnly()`, or a coloured style; removing the glow resets what this plugin painted on that target for that viewer |
| any other entity | the glowing bit added to its flags on their way to this viewer, so it stays lit through every flag change; a colour puts it in a team on that client only |
| a packet entity by id | the same rewrite; white, and putting it out sends its flags as `0` (right for an NPC body, not for something invisible or burning) |
| a block | `Packets.glowingBlocks()`: an invisible glowing display, read through terrain |

Two pieces glowing the same entity for the same viewer are counted: removing
one leaves the other lit.

## Bedrock

`Guides.isBedrock(player)` (the client module's Geyser/Floodgate check). A
Bedrock client draws display entities poorly or not at all and has no locator
bar: tell those players in chat instead.

## Threads and cost

- Every method and every handle method is safe from any thread. Handle methods
  change what the next draw sends; they never send from the caller's thread.
- Each plugin's guidance for one player is drawn by **one entity timer** on
  that player, every two ticks. On Folia it only reads the player from their
  own region thread; on Paper every entity timer of a plugin shares one server
  task. A session with nothing left to draw stops its timer.
- A still player costs nothing on the wire. A walking player costs a
  companion's glide every few steps, a beacon label when the rounded distance
  changes, and a trail's marker in and out per spacing walked.

## Lifecycle

| When | What happens |
| --- | --- |
| `remove()` / `clear(player)` | destroyed on that client now |
| the player leaves | forgotten, no packets |
| the player changes world or respawns | destroyed and drawn again next tick |
| the player teleports within the world further than half their view distance (at least 32 blocks) | destroyed and drawn again 5 ticks later, where they landed (since 1.393.0) |
| the plugin is disabled | everything it shows is destroyed |
| the server stops | everything is destroyed, the glow listener dropped |

Nothing is written to disk. Nothing in it is derived from the palette, so it
has nothing to invalidate on reload.

## Requirements

PacketEvents, for everything but a modded-client waypoint. Without it
`isSupported()` is `false`, handles are inert, and the console is told once.

## Source and tests

- Public: `net.exylia.lib.guide` — `Guides`, `PluginGuides`, `GuideHandle`,
  `GuideText`, `GuideTextStyle`, `ViewTextStyle`, `GuideBeacon`, `BeaconStyle`, `GuideTrail`,
  `TrailStyle`, `GuideWaypoint`, `Route`
- Internal: `net.exylia.lib.guide.internal` — `GuideRuntime`, `ViewerSession`,
  the pieces, `GuideGeometry`, and `GuidePackets`, the only class naming
  PacketEvents
- Tests: `RouteTest` (nearest point, window, heading, ground snap, thinning),
  `GuideGeometryTest` (the clamped stand-in, the companion's spot, distance
  scaling, the cut-over, the long-teleport threshold, the view text's seat
  offset at every pitch)
