# NPC module

Player-shaped entities that exist only on a client. A body left where somebody
died, a statue in a lobby, a double that walks off while the real player stands
still.

## Using

Most callers never touch the API. An NPC is a line in the same `effects` list
every other effect is written in:

```yaml
effects:
  # The victim, face down, wearing what they died in, for four seconds.
  - '[NPC] {victim};pose:lying;life:4;equip:true'
  # Standing, turned to face whoever did it, outlined.
  - '[NPC] {victim};pose:standing;life:3;glow:{error};face:true'
```

The API, for the cases configuration cannot describe:

```java
PluginNpcs npcs = Npcs.of(this);

NpcModel corpse = NpcModel.of(victim)
        .wearing(victim)
        .pose(NpcPose.LYING)
        .glow(0xA33B53);

NpcHandle body = npcs.show(corpse, victim.getLocation(), 4000, observers);
body.lookAt(killer.getLocation());
```

A handle can also be driven after it is shown — since 1.175.0:

```java
body.moveTo(next);                          // a step, or a teleport when it is too far
body.equip(EquipmentSlot.HAND, sword);      // one slot, leaving the other five
body.swing();
body.hurt();
```

`moveTo` sends a relative step when the distance fits in one, which the client
draws smoothly over the frames it has until the next; anything further is sent
outright and arrives without being smoothed. That is what makes a body driven
twenty times a second look like a player walking rather than a prop being
nudged, and it is what the [replay module](replays.md) is built on.

## Nothing the server has to carry

These are not entities. They are not ticked, not saved, not in any chunk, have
no hitbox the server knows about, and cannot be hit, damaged or pushed. Two
players standing together can be shown different ones.

What that costs is that nothing else will clean them up. So the module owns
their lives itself: an NPC goes when its life ends, when its plugin is disabled,
or when the server stops, and there is no fourth case. A body left behind stands
there wearing somebody's name until that player relogs, which is why the
lifetimes are the part of this module with tests.

## Its own identity, always

An NPC is announced to the client under a UUID of its own, never the UUID of the
player it is wearing. Announcing a second entry under a real player's id is how
an NPC takes that player's own skin off their own body, and there is no way back
from it short of a relog.

It is announced **unlisted**: the player-list entry is what carries the skin,
being in the tab list is a separate flag, and an NPC that appears in the tab
beside real players is a bug report about ghost players.

`NpcModel.of(player)` keeps the player's name, so the client draws it over the
body, decorated by whatever scoreboard team that name is in for the viewer (a
rank prefix, a colour). `model.nameless()` (since 1.413.0) is the same body with
no name tag: announced under a generated name (`npc` and 13 hex digits) in a
team of its own that hides the tag, withdrawn with the NPC. Previews and
mannequins use it, and so does an `[NPC]` line of a scaled sequence.

## The skin costs nothing

`NpcModel.of(player)` reads the texture from the connection this server already
holds. No lookup, no waiting, no failure halfway — which matters, because the
moment an NPC is usually wanted is a moment nothing may block in.

That is also why `[NPC]` takes `{victim}` and `{killer}` and not a player name:
a name is a request to Mojang, and an effect cannot wait for one. A base64
texture written into the file works too, and is resolved when the file is read.

## Written in a sequence

| Parameter | What it does | Default |
| --- | --- | --- |
| `pose:` | `lying`, `standing`, `crawling`, `sneaking` or `spinning` | `lying` |
| `life:` | seconds it stays, from `0.2` to `120` | `5` |
| `equip:` | wears the armour and weapon they died in | `true` |
| `glow:` | outline colour: a name, `#rrggbb` or a `{palette}` token | none |
| `y:` | height above the anchor | `0` |
| `face:` | turns to face whoever set the sequence off | `true` |
| `from:x,y,z` | where it appears, relative to the anchor | `0,0,0` |
| `to:x,y,z` | where it ends up | `0,0,0` |
| `over:` | seconds the movement takes | `0.7` |
| `ease:` | `out`, `in`, `in_out` or `linear` | `out` |
| `gravity:` | falls at this many blocks per second squared, on top of the line | `0` |
| `turn:` | degrees it turns on the spot over the movement | `0` |
| `pose_to:` | a second pose, so it goes down while you watch | none |
| `after:` | seconds before that second pose | `0.4` |
| `hurt:` | flinches red when it is struck | `false` |
| `move_after:` | seconds it stands there before any of that happens | `0` |
| `spin:` | degrees a second it keeps turning, for its whole life | `0` |
| `bob:` | blocks it rises and falls, on a loop | `0` |
| `bob_every:` | seconds one rise and fall takes | `1.6` |
| `swing:` | seconds between arm swings | `0` |
| `scale:` | how big it is drawn, `1` being player-sized | `1` |
| `hold:` | a material put in its main hand | its own |
| `offhand:` | a material put in its off hand | its own |
| `pitch:` | head pitch in degrees, negative being up | `0` |

### Making a body read

A body that appears and disappears is a prop. Three lines separate that from
something that happened:

```
# Thrown backwards by the blast, tumbling, landing face down.
[NPC] {victim};pose:crawling;to:0,1.2,-3;gravity:9;over:0.8;turn:160;hurt:true;life:3

# On its feet for a beat, then it goes down while you watch.
[NPC] {victim};pose:standing;pose_to:lying;after:0.5;over:0;life:2.6;face:true

# Taken: it rises and turns as it goes.
[NPC] {victim};pose:crawling;to:0,3.5,0;over:1.6;ease:in;turn:200;life:2
```

`move_after:` is what lets a body be on screen from the first frame and still be
thrown by the blast that arrives a second later. Put the `[NPC]` line first in
the effect and give it a `move_after:`, rather than putting the line late: a body
that appears halfway through is a body the player watched not being there.
The flinch waits for it too.

`spin:` is the one to reach for when a body should not settle: `turn:` is one
turn spread over the movement and then finished, `spin:` never stops. A body
caught in a vortex is `spin:220`, one hanging in the light is `bob:0.3;spin:40`,
and one still swinging at whatever killed it is `swing:0.5;hold:NETHERITE_SWORD`.

`scale:` is the client's own scale attribute, so the whole model grows: `0.4` is
a doll and `2.5` is something the room is not big enough for. It needs a 1.20.5
client; an older one is sent nothing and sees a player-sized body.

`gravity` is added to the line rather than replacing it, so "thrown three blocks
back and let it drop" is two numbers instead of a launch velocity. A body reads
better at about half vanilla gravity — a real one is heavier than the eye
expects and slower than the number says.

A body that is not moving is never written to after it appears: the driver runs
every tick, and a still one costs three comparisons.

`pose:lying` is the pose a sleeping player is drawn in, which is the only way to
put a body on the floor without a model of your own. `pose:standing` with
`face:true` is a different effect entirely: somebody who has stopped, and is
looking at you.

## Placed NPCs

Since 1.291.0. An NPC that stands somewhere until it is removed, is seen by
whoever walks near it, and can be clicked: the lobby guide, the shopkeeper, the
quest giver.

```java
PluginNpcs npcs = Npcs.of(this);

PlacedNpc guide = npcs.place("guide", NpcSpec.builder()
        .skin(NpcSkin.player("Notch"))       // or texture(value, signature), mirror(), none()
        .pose(NpcPose.STANDING)
        .scale(1.0)
        .glow(0x8A51C4)                      // -1 for none
        .equip(EquipmentSlot.HAND, compass)
        .look(NpcLook.EACH_VIEWER)           // FIXED, NEAREST, EACH_VIEWER
        .lookRange(8)
        .viewDistance(48)
        .sitting(false)                      // true mounts it on an invisible seat
        .animations(new NpcAnimations(       // since 1.300.0; NpcAnimations.none() by default
                "", "wave", "nod", Duration.ofSeconds(20), "wave", 4.0, Duration.ofSeconds(30)))
        .build(), location);                 // reusing an id replaces the NPC

guide.onClick((player, click) -> {           // net.exylia.lib.interaction.Click
    if (click.isRight()) openShop(player);
});
guide.visibleIf(player -> player.hasPermission("lobby.guide"));
guide.moveTo(other);                         // a teleport, not a respawn
guide.update(guide.spec().toBuilder().pose(NpcPose.SNEAKING).build());
guide.swing();
guide.play(NpcAnimation.parse("wave"));      // for everybody who sees it
guide.play(NpcAnimation.parse("bow"), player); // for one viewer only
guide.glow(player, 0xFFD700);                // only this player sees it outlined
guide.resetGlow(player);                     // back to the spec's outline

npcs.placed("guide");                        // Optional<PlacedNpc>
npcs.allPlaced();
npcs.unplace("guide");
```

| Spec | What it does | Default |
|---|---|---|
| `skin` | `texture(value, signature)`, `player(name)`, `mirror()` or `none()` | `none()` |
| `pose` | any `NpcPose` | `STANDING` |
| `scale` | `0.0625` to `16`, needs a 1.20.5 client | `1` |
| `glow` | `0xRRGGBB`, drawn in the nearest of the sixteen team colours | `-1` |
| `equip` | one item per slot | empty |
| `look` | `FIXED` faces the location's yaw; `NEAREST` turns to the closest viewer for everybody; `EACH_VIEWER` turns to each viewer on their own screen | `FIXED` |
| `lookRange` | how close a viewer has to be to be looked at | `8` |
| `viewDistance` | how close a player has to be to see it | `48` |
| `sitting` | sits on an invisible seat at its location; combines with `pose` | `false` |
| `animations` | `NpcAnimations`: what it plays on a click, every so often and to a player who walks up | `none()` |

How it behaves:

- **Visibility is the hologram module's.** One async pass four times a second, a
  squared distance per player, packets only when somebody crosses the edge. The
  identity is announced on one pass and the body drawn on the next, because a
  client that has not processed the player entry when the spawn arrives draws
  nothing. Changing world or respawning sends it again; leaving forgets the
  player; disabling the plugin takes every NPC it placed off every screen.
- **No name, anywhere.** Each NPC is announced unlisted under its own UUID and a
  generated name (`npc_<hex>`), in a team of its own that hides the nametag and
  turns off collision. The name is generated so that team can never take in a
  real player. A name above an NPC is a hologram's job.
- **Skins never block.** `texture` is drawn at once (unsigned skins draw as the
  default on modern clients). `player(name)` reads the connection when that
  player is online, otherwise asks Mojang (name, then signed profile) off the
  main thread and caches it for the run; until it arrives the NPC wears the
  default skin, and it is redrawn for its viewers when it does. A failed lookup
  is retried after a minute, not on every pass. `mirror()` gives each viewer the
  skin they are wearing.
- **The head turns only when it is worth a packet:** a head rotation and body yaw
  when the target angle moved more than 1.5 degrees.
- **A head let go of eases home.** When nobody it would look at is in range
  any more, it turns back to the location's facing by at most 45 degrees a pass
  (about 180 degrees a second) instead of snapping.
- **Sitting is a ride, not a pose.** A client only draws a player seated while it
  rides something, so `sitting(true)` spawns a block display of air (nothing to
  draw, no hitbox) at the location and mounts the body on it. The hips rest on
  the location: place a sitting NPC on the seat. Turning sitting on or off gives
  every viewer the NPC again; `moveTo` takes the seat along.
- **Clicks** are read from `INTERACT_ENTITY` on the NPC's entity id and kept from
  the server. An attack is a left click, the main-hand use a right click
  (`INTERACT_AT` and the off-hand duplicate are ignored), sneaking makes it a
  shift click. A second click from the same player within 200 ms is dropped. The
  handler runs on the clicking player's thread.
- **Other plugins observe clicks through `PlacedNpcClickEvent`** (since 1.392.0),
  fired for every counted click on every placed NPC, handler or not, on the
  clicking player's thread right before the owner's handler. It carries
  `getPlayer()`, `npc()`, `npcId()`, `owner()` (the plugin that placed it) and
  `click()`. ExyliaDisplays' NPCs are placed NPCs, so their clicks fire it too:

  ```java
  @EventHandler
  void on(PlacedNpcClickEvent event) {
      if (event.owner().getName().equals("ExyliaDisplays")) count(event.getPlayer(), event.npcId());
  }
  ```
- **One viewer's own outline:** `glow(player, 0xRRGGBB)` (since 1.392.0) shows
  that player alone an outline, or none with a negative colour; everybody else
  keeps the spec's. It wins over the spec, also across `update`, until
  `resetGlow(player)`, and is forgotten when the player leaves. A viewer who has
  the NPC on screen gets it at once (entity flag plus the NPC's team colour,
  both per player), anybody else with their next spawn.
- **`update` sends only what changed:** equipment and pose are one packet each; a
  new skin, scale or glow gives every viewer the NPC again.
- **Thread-safe.** Every method can be called from any thread; everything that
  sends packets for one NPC holds its lock.

Without PacketEvents, `place` returns an inert NPC that is already `removed()`
and the module says so once.

### Animations

Since 1.300.0. Short gestures made of vanilla packets, so they need no resource
pack and cost nothing while idle. `NpcAnimation.parse` reads one from a file:

```yaml
right-click: wave
idle: swing, 300ms, offhand, 300ms, crouch, 500ms, stand
```

| Word | What it does |
|---|---|
| `swing` | swings the main arm |
| `offhand` | swings the off-hand arm |
| `hurt` | flinches red |
| `crouch` | crouches |
| `stand` | back to the spec's pose |
| `250ms`, `1s`, `5t` | waits before the next word; a bare number is seconds |
| `wave`, `wave-offhand`, `nod`, `bow`, `flinch`, `punch`, `cheer` | presets, expanded in place |

- Words are separated by commas and played left to right; moves with no wait
  between them happen together. Tick resolution: under 50 ms is the same moment.
- An unknown word is skipped, never thrown; `parse(text, unknown)` hands each
  one to the caller so a command can tell the admin.
- An animation that crouched always ends standing in the spec's pose, even
  when the file forgot the `stand`.
- `play` returns at once: the first moves go out now, the rest from the
  scheduler, off the main thread. Two animations at once both play.

`NpcAnimations` is a config record for the triggers; nest it in a plugin's own
record:

| Key | What it does | Default |
|---|---|---|
| `left-click` | played on a left click, for everyone who sees it | `''` |
| `right-click` | played on a right click, for everyone who sees it | `''` |
| `idle` | played every `idle-every` while anyone sees it | `''` |
| `idle-every` | how often `idle` plays; `0` never | `20s` |
| `greet` | played for a player who comes within `greet-range`, seen only by them | `''` |
| `greet-range` | blocks | `4.0` |
| `greet-cooldown` | how long before the same player is greeted again | `30s` |
| `perform-range` | since 1.302.0: how close a player has to be to be shown a [performance](#performances); an idle performance with nobody this close is skipped | `24.0` |
| `idle-continuous` | since 1.401.0: an idle performance [loops for as long as anybody is in `perform-range`](#continuous-idle) instead of playing every `idle-every` | `false` |
| `idle-group` | since 1.401.0: NPCs of one plugin with the same group keep their continuous idles [on one clock](#groups-on-one-clock) | `''` |

Since 1.302.0 any of the four animation keys can hold a [performance](#performances)
instead of vanilla moves: a value with a colon (`anim:wave`, `emote:bow`,
`follow:2;keys:...`) is a performance, one without is moves. One value is one or the
other; a list mixing both is not read.

The click animation plays before `onClick`'s handler runs, and only for a click
that survived the 200 ms dedupe. The greeting is per viewer so a crowd walking
up one by one does not make it wave at nobody for everybody else; the
cooldown survives the player walking out of view and back, and is forgotten
when they quit. Idle and greeting are checked on the visibility pass, so they
cost no timer of their own.

### Performances

Since 1.302.0. A whole-body animation: the ragdoll body ExyliaEmotes dances with,
wearing the NPC's skin and standing exactly in its place. Vanilla moves can swing an
arm; a performance can bow, salute or dance.

```java
PlacedNpc guide = npcs.placed("guide").orElseThrow();

guide.perform(NpcPerformance.parse("anim:wave"));                // for everybody in range
guide.perform(NpcPerformance.parse("emote:bow"), player);        // for one viewer only
guide.perform(NpcPerformance.parse("follow:2;breathe:0;keys:0.3 arm_r=150,0,20 | 0.4 stand"));
guide.perform(NpcPerformance.lines(List.of(                       // raw sequence lines
        "[RAGDOLL] {victim};intact:0;follow:1;anim:dance",
        "[SOUND] BLOCK_NOTE_BLOCK_BELL;0.6;1.4")));
guide.isPerforming();
```

The notation, in code and in `NpcAnimations`' keys:

| Written | What it plays |
|---|---|
| `anim:<name>` | an animation file from `plugins/<plugin>/animations` (Blockbench or Emotecraft), as `[RAGDOLL] ...;anim:` reads it ([ragdolls.md](ragdolls.md)) |
| `keys:<frames>` | frames written on the line, in `[RAGDOLL]`'s frame format |
| `<option>:<value>;...;keys:<frames>` | any `[RAGDOLL]` options first (`follow:`, `breathe:`, `intact:`, `tempo:`, `loop:`, `loop_from:`, `hold:`...), `keys:` or `anim:` last |
| `emote:<id>` | whatever the plugin's resolver says `<id>` is (below) |

It is a `[RAGDOLL]` line without its `{victim};` head. Left unwritten, `intact:0`,
`follow:1` and `breathe:1` are added: it starts on the first tick, the loose joints
lag a little, and a held pose breathes.

```yaml
animations:
  right-click: anim:wave
  idle: emote:stretch
  idle-every: 45s
  greet: 'follow:1;keys:0.25 arm_r=150,0,30 elbow_r=40 | 0.3 arm_r=150,0,60 | 0.3 stand'
  perform-range: 24.0
```

**`emote:<id>`** is resolved by the plugin, so the library depends on no emote plugin:

```java
Npcs.of(this).emotes(id -> cache.get(id));   // Function<String, NpcPerformance>, null = unknown
```

Asked every time such a performance plays, from any thread, so keep it a map lookup.
An unknown id, a resolver that throws, or no resolver at all skips the performance.
ExyliaEmotes' emotes live in its `emotes.yml` under `emotes.<id>`; the playable lines are
the `effects` list, so `NpcPerformance.lines(effects)` is the whole conversion. Its
`[CAMERA]` lines find no player and do nothing; its `[SOUND]` lines play at the NPC.

How it plays:

- **Hidden, not removed.** For each viewer in range the NPC turns invisible, its
  equipment and outline go with it, and its hitbox stays, so **clicks still reach
  it** (and its click handler runs as usual). Viewers out of range, or who walk up
  mid-performance, see the NPC as it was.
- **The body takes its place.** Same location, same scale, outline, held items and hat
  (the spec's `HAND`, `OFF_HAND` and `HEAD`), facing the way the NPC faces: the
  viewer's own facing for a one-viewer performance, the nearest's for `NEAREST`, the
  location's yaw otherwise. Its head stops turning while it plays.
- **The skin.** `texture` and `player(name)` are worn by the body too, through the
  ragdoll skin cache ([ragdolls.md](ragdolls.md#the-real-skin)): real-skin pieces, in
  blocks while they are on their way. `mirror()` is the viewer's own skin for a one-viewer
  performance and the default skin for everybody's, since one body is shared.
  `none()` is the default skin.
- **No pop at the end.** The NPC comes back a tick after the body reaches its last
  pose, and the body is taken away in the same moment. Keep the default finish
  (`then:hold`) and end the frames on `stand`, as every ExyliaEmotes emote does; a body
  that bursts or collapses is still flying when the NPC comes back.
- **A loop** (`loop:true`) has no end of its own: it plays for
  `NpcPerformance.loopLimit` (8 seconds unless set), stands back up over 0.4 s, and
  then the NPC returns.
- **One at a time.** While one plays, any other (a click, the idle, a greeting, a
  call) is **skipped** and `perform` returns `false`; nothing is queued, so a crowd
  clicking does not stack dances. The one exception, since 1.401.0, is a
  [continuous idle](#continuous-idle): anything else replaces it.
- **Ended at once** when the NPC is moved, `update`d, removed, unplaced, replaced
  under its id or released with its plugin: the NPC comes back (unless it is gone)
  and the body is removed.
- **Skipped**, returning `false`, when nobody is within `perform-range`, for a sitting
  NPC (a body is drawn standing at the seat, and standing up and sitting back down
  on screen is exactly the pop this avoids), when the lines draw no body, and without
  PacketEvents.
- **Poses.** A body starts standing, so an NPC in another `pose` (sneaking, lying)
  visibly changes posture as it starts; performances are meant for standing NPCs.
- **Click triggers** that perform run off the network thread: the first play of a
  performance compiles its lines and may read an animation file.

What it costs: a body is a few dozen display entities per viewer (6 to about 120 by
`detail:` in blocks, 5 to 19 pieces with real skins), sent and moved by the client
for the whole performance. That is why there is at most one body per NPC, only
viewers within `perform-range` get one, and an idle performance with nobody that
close is not played at all. Keep `idle-every` long on NPCs in busy places, and
prefer vanilla moves for anything that fires on every click of a crowd.

### Continuous idle

Since 1.401.0. `idle-every` plays the idle now and then, and a looping idle is cut at
`loopLimit`. With `idle-continuous: true` an idle **performance** instead plays for as
long as anybody is within `perform-range`: the NPC breathes, sways or dances the whole
time somebody is near, and nobody near means no body and no cost.

```yaml
animations:
  idle: anim:idle_sway          # a looping clip: loop:true, or one that loops in its file
  idle-continuous: true
  right-click: anim:wave
  greet: anim:bow
```

```java
NpcAnimations animations = new NpcAnimations("", "anim:wave", "anim:idle_sway", Duration.ZERO,
        "anim:bow", 4.0, Duration.ofSeconds(30), 24.0, true, "");
// or, from an existing record:
animations.withIdleContinuous(true).withIdleGroup("stage");
```

- **No limit.** A looping idle is never cut at `loopLimit`. A clip that does not loop
  plays to its end, the NPC is back for a moment, and it starts again on the next pass
  (a quarter of a second): write the idle as a loop.
- **Interrupted, not skipped.** A click, a greeting, `perform` and `PluginNpcs.perform`
  replace the idle at once (its body goes, theirs takes the place, the NPC stays hidden
  between them), and the idle starts again on the pass after they end. Anything other
  than the idle keeps the one-at-a-time rule against everything else.
- **Who sees it.** A body's audience is fixed when it starts. Somebody who walks up
  while it plays sees the NPC until the end of the current cycle, when the idle is given
  again to everybody in range, on the cycle's boundary, so the loop does not jump. A clip
  with an entry (`loop_from:`) plays that entry again at that moment.
- **Ended** with the 0.4 s stand-up when the last viewer leaves `perform-range`, when the
  NPC is paused, or when it becomes sitting; the NPC comes back after.
- Only for performances. Vanilla idle moves (`swing, 300ms, offhand`) keep `idle-every`.
- Off by default: `idle-every` behaves exactly as it did.

### Pause and resume

Since 1.401.0. Runtime state, not saved:

```java
guide.pause();       // no idle, no greeting, no click animation, the head stays put
guide.isPaused();
guide.perform(NpcPerformance.parse("anim:bow"));   // still plays: a call is not automatic
guide.resume();      // back to normal; a continuous idle starts again on the next pass
```

Paused, a playing continuous idle stands up and ends; any other performance finishes.
Click handlers and `PlacedNpcClickEvent` still run; `perform`, `play` and
`stopPerformance` still work. A new NPC, or one placed again under its id, starts
unpaused.

### Stopping a performance

Since 1.401.0. `guide.stopPerformance()` ends whatever plays now the way a loop ends at
its limit: the body stands back up over 0.4 s, then the NPC is back. It returns whether
anything was playing. A continuous idle stopped this way comes back on the next pass if
somebody is near; pause the NPC to keep it off.

### Groups on one clock

Since 1.401.0. Several NPCs that move together, a choir, a line of dancers:

```java
PluginNpcs npcs = Npcs.of(this);
int started = npcs.perform(List.of(left, middle, right), NpcPerformance.parse("anim:dance"));
```

`perform(Collection<? extends PlacedNpc>, NpcPerformance)` starts it on every NPC in the
same call: one tempo (a `tempo:1-1.4` range is rolled once, by the first NPC that starts,
and handed to the rest) and one start, so their loops stay in phase. Each NPC follows its
own `perform` rules (one already performing is skipped, a continuous idle is replaced,
one with nobody in range is skipped); the return is how many started. NPCs in different
Folia regions start their bodies on their own region's thread, on the next tick of each.
`stopPerformance()` on each ends them.

Continuous idles stay in step through **`idle-group`**: NPCs of one plugin naming the same
group share one clock, started by the first of them to idle (its start time, its rolled
tempo, its cycle length). Every later idle in the group, an NPC somebody walked up to or
one coming back after a click, waits for the next whole cycle on that clock and plays at
its tempo, so it starts in the pose the others are passing through. Give a group one idle
clip: the cycle is the first member's. `accel:` changes the cycle as it plays and so
drifts the group; leave it at `1`. The clock lives until the plugin is disabled.

## Contracts

- **Owned by the module, not the caller.** Every way out of the queue is
  covered, and a run that is cancelled — a preview the player closed — takes its
  bodies with it.
- **Announced unlisted, under its own id.** Never a real player's.
- **All skin layers on.** Left alone an NPC has no jacket and no sleeves, which
  every player notices on their own skin immediately.
- **One driver, once a second.** Unlike a display, an NPC has nothing to send
  between its first packet and its last, so a tick timer would be twenty checks a
  second to learn that nothing has changed.
- **PacketEvents or nothing.** Without it the module says so once and draws
  nothing. It is careful never to load the packet class before it has checked.

## Source and tests

- Public: `npc/` — `Npcs`, `PluginNpcs`, `NpcModel`, `NpcPose`, `NpcHandle`;
  placed NPCs: `PlacedNpc`, `NpcSpec`, `NpcSkin`, `NpcLook`, `NpcAnimation`,
  `NpcAnimations`, `NpcPerformance`, `PlacedNpcClickEvent`.
- Internal: `npc/internal/` — the driver, `PlacedRuntime` and `PlacedNpcImpl`
  for placed NPCs, `NpcStage` (where a performance is played, a seam for tests), and the one class that names PacketEvents (`NpcPackets`,
  which also holds the click listener).
- In sequences: `Steps.Corpse`, behind the `[NPC]` token.
- Tests: `NpcLifetimeTest` asserts every way an NPC can be taken away, that it is
  taken away exactly once, that one plugin's release leaves another's alone, and
  that a life no file could have meant is brought back inside the limits.
- `PlacedNpcTest` asserts visibility by distance and world, announce-then-draw,
  click mapping and dedupe, replacing by id, release on plugin disable, look
  angles and both turning modes, mirrored and looked-up skins, and that `update`
  and `moveTo` send only what changed; since 1.300.0 also the head easing home,
  animations sent at their moments (to all or to one viewer), click and idle
  triggers, the greeting range and cooldown, and the seat following the body.
- `NpcAnimationTest` asserts the word format: waits, presets, the closing
  stand and unknown words.
- `NpcPerformanceTest` asserts the performance notation: what counts as one, the
  `[RAGDOLL]` line and its defaults, `emote:` references, raw lines, loop limits and
  `NpcAnimations`' old constructor. `PlacedNpcTest` (since 1.302.0) asserts hide, play
  and reveal-before-removal, one at a time, the perform range, ending on move and
  unplace, loops cut at their limit, the emote resolver, click triggers and sitting;
  since 1.401.0 also the continuous idle (no limit, ends when nobody is near, comes
  back, replaced by a click and a greeting and resumed after, a newcomer given it at the
  cycle's end), `idle-every` staying the default, pause and resume, `stopPerformance`,
  performances started together at one tempo, and idle groups sharing a tempo and a
  beat.
