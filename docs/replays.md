# Replay module

Recording what happened, and watching it again. A duel, a round, a report —
recorded from the server's own state and played back on the screens of whoever
asked to see it.

Since 1.175.0.

## Using

```java
PluginReplays replays = Replays.of(this);

ReplayRecorder recorder = replays.record(arena.spawn());
recorder.follow(red);
recorder.follow(blue);

// when the duel ends
duels.saveReplay(duelId, recorder.stop().toBytes());

// when somebody asks to see it
Replay replay = Replay.from(duels.replayOf(duelId));
ReplayPlayback watching = replays.play(replay, arena.spawn(), List.of(viewer));
watching.speed(0.25);
watching.onEnd(() -> lobby.send(viewer));
```

That is the whole flow. Everything below is what it is doing and where the
edges are.

## What a recording actually is

Not a copy of the packets that went out. Every tick, for everybody being
followed, the server's own answer to where they were:

| | |
| --- | --- |
| Position | to a thousandth of a block |
| Yaw, pitch and head | in 65536 steps a turn (format 4, 1.403.0; format 3 kept the protocol's 256) |
| Pose | every pose the protocol has: standing, sneaking, crawling, sleeping, gliding with an elytra, riptide, dying, and the mobs' own |
| Sprinting, on the ground | as the client reported them, which is what the server used |
| On fire, invisible, glowing | as the server had them |
| Which hand is raised | a bow drawn, a shield up, a gapple going down, in either hand |
| Health | to the nearest half point |

Plus a second track of things that happened once rather than being true for a
stretch: swings, hits, **attacks** (critical, sweep, sprint knockback, full or
weak swing, taken on a shield), deaths, respawns, totems, teleports, getting on
and off things, items picked up, blocks being mined, equipment changes, **block
changes** and **explosions**. All of it is read off the server's own events, so
a plugin that records a duel gets it without writing a listener.

### Stamped with the server's tick

Every sample and every mark carries the server tick it happened on, never the
wall clock. Before 1.241.0 a sample was stamped with `nanoTime / 50 ms`, and a
server tick is not fifty milliseconds: two ticks could land on one stamp (one
frame lost, the next one doubled) and two players sampled in the same tick
could be recorded a tick apart. That was the stutter and the hits landing on
nothing. On Folia the clock is a counter the global region advances, read once
per region tick.

**That is the state the fight was decided on.** For the question anybody
actually asks a replay — *did that hit land, was he in range* — the recording
is the authority, and the player's own screen is the thing that was guessing.

## The arena, and everything else in it

Players are not the only thing in a fight. Since 1.176.0 a recorder takes three
more things, and the plugin says when, because only the plugin knows which of
the server's events happened inside the match:

```java
recorder.follow(arrow);                      // anything that is not a player
recorder.block(at, block.getBlockData());    // a block that changed
recorder.explosion(at, event.getYield());    // the flash and the bang
```

**`follow(Entity)`** takes an arrow, a pearl, a splash potion, an end crystal, a
block of primed TNT — anything with a position. A track only covers the ticks
its actor was actually there for, which is what makes this affordable: an arrow
flight is sixty frames, not the seven thousand the fight around it ran for. A
recording follows four hundred things at most and drops the newest past that,
because a crystal fight can put one in the arena every tick.

**`block`** takes whatever the block is now — placed, broken, blown up, burnt.
The playback shows it to the viewer as a fake block, so the arena a replay is
watched in is never actually changed and somebody can be fighting in it a minute
later. A seek backwards puts a crater back the way it was, because the state is
rebuilt from every change up to that tick rather than carried forward.

**`explosion`** is only the visual. Which blocks a blast actually removed is the
server's answer, not a formula, so those come through `block` — a replay that
guessed would disagree with the crater everybody remembers.

## What it cannot give back

Everything a player's own client made up. A client predicts its own movement
and is corrected afterwards, so somebody on a bad connection watched their hit
land while the server was deciding it had not. The recording shows the
server's answer, and since 1.403.0 the black box also keeps how late each
player's screen was, which is enough to show what an attacker had in front of
them (*The attacker's view*, below).

Nothing in here reproduces client-side prediction, FOV, view bobbing or the
tilt on taking damage, and nothing ever will: none of it existed on this side
of the connection.

## The anchor

A recording holds no world and no coordinates. Every position in it is relative
to the anchor it was made against, which is what lets the same file play back in
an arena pasted somewhere else, on a different server, a week later.

```java
ReplayRecorder recorder = replays.record(arena.spawn());   // recorded against this
replays.play(replay, arena.spawn(), List.of(viewer));      // rebuilt around this
```

Both are usually the arena's spawn. There is no rotation: a recording played
back against an anchor facing another way is offset, not turned.

## What it costs

A three minute duel between two players measures about **29 kB** once it is
written out. The same duel with three hundred crystals, arrows and TNT blocks in
it and five thousand blocks taken out of the arena measures about **59 kB** —
still a column in a table rather than a file on a disk. Three things make that
true, in this order:

1. Position is quantised to a thousandth of a block, which is finer than a
   client can show and exactly what is held in memory, so the round trip is
   lossless.
2. Each tick is written as the difference from the one before it, zig-zagged and
   varint-encoded: standing still costs one byte an axis and walking costs two.
3. The whole thing is gzipped, which is where the long runs of identical flag
   and rotation bytes go.

A block change is three small integers and a block name, and a thing that lived
for sixty ticks costs sixty frames. That is why the second number above is twice
the first rather than fifty times it.

Recording itself is a handful of field reads per followed player per tick.
Playing back sends packets only when the tick being shown changes, so a playback
at a quarter speed sends one set every four ticks rather than four copies of the
same one.

## Where a recording lives

The module does not decide. `toBytes()` gives a blob and `Replay.from(byte[])`
reads one back, which goes in a column, a document, or a file — the same
decision, and the same `Databases` module, as anything else a plugin stores.

## Where the blocks go

Two answers, and which is right depends on whether the caller owns the place:

```java
replays.play(replay, arena.spawn(), List.of(viewer));                        // PACKET
replays.play(replay, arena.spawn(), List.of(viewer), ReplayWorld.SOLID);     // SOLID
```

`PACKET` draws the changed blocks for the viewer alone. It costs the server
nothing and needs no cleanup — but **the client collides with them and the
server does not**. A viewer who walks into a replayed wall is pushed back out of
it by the next correction, which is the rubber-banding that looks like the
client and the server disagreeing about where somebody is. They do.

`SOLID` writes them into the world and puts every one of them back when the
playback stops, exactly, from what the recording says was there before. Nothing
is corrected because nothing disagrees: a viewer can stand on the bridge, walk
into the crater and look around inside it. Only for a caller that owns the
arena outright.

## Watching is private

The bodies are packets, drawn by the NPC module. A playback exists on the
screens it was given and nowhere else: two players can watch two different
recordings standing in the same arena, and neither can touch what the other is
looking at.

What makes it look real is that nothing about it is animated. Every body is a
packet entity driven with exactly what the server sends for a real one: a
relative step and a head turn each tick, a position sync every few seconds,
metadata only when the pose or the flags change. The client interpolates between
steps the way it does for anybody it can see.

- **Slow motion is smooth.** Below real time the driver still sends a step every
  tick, to a position interpolated between the two frames either side.
- **Hits are the game's own.** The red flash and the tilt away from the blow are
  the hurt animation packet with the direction the hit came from (the old
  animation id the client ignores since 1.19.4 is gone); the critical sparks,
  the sweep, the knockback, strong and weak swing sounds are what the attacker's
  hit really was.
- **Deaths fall over.** A body that dies lies down, turns red and disappears in
  a puff twenty ticks later, as it does in the game.
- **Footsteps.** In the sound of whatever is underfoot, read from the region that
  owns it.
- **One thread draws.** Every frame, a seek included, is drawn by one driver, and
  a playback still being drawn is skipped rather than entered twice.

## It makes its own noise

A recording holds no audio and does not need to. A hit, a block breaking, an
arrow leaving a bow and a blast are already in it as marks, and every client
already knows what those sound like — so the playback plays them from the marks,
for free:

| Mark | What the viewer gets |
| --- | --- |
| `HURT` | the hit sound, and damage particles on the body |
| `SWING` | a quiet sweep |
| `BLOCK` | that block's own break or place sound, and its own pieces |
| `EXPLOSION` | the flash and the bang, scaled to the blast |
| `WORLD_EVENT` | a splash or lingering potion breaking: glass, sound and coloured cloud (recorded from the server's own packet) |
| a thing appearing | the bow, the pearl, the potion, the fuse |
| a mark written at a place | whatever the plugin draws there — a potion breaking, a totem popping |
| the frame's own `using` flag | the arm goes up: a bow drawn, a shield raised, an apple eaten |

A break is heard and shattered as **what was there**, not as what is there now,
which is why `block(at, became, was)` takes both. Pass the old block and a pane
of glass sounds like glass; leave it out and every break in the replay is
silent.

`playback.sounds(false)` turns all of it off.

Anything the module has no opinion about is the plugin's: `recorder.markAt(kind,
at, text)` writes down that something happened *somewhere* rather than to
somebody, and `playback.placeOf(mark)` gives it back in the arena the replay is
running in. A splash of potion lands where the bottle broke, not where the
person who threw it has since walked to.

## Controls

```java
watching.pause();
watching.speed(0.25);      // a sixteenth to eight
watching.seek(1200);       // a minute in
watching.loop(true);
```

A seek goes backwards as freely as forwards. What everybody is wearing, who is
riding what and who is lying dead are read from lists built once when the
playback starts, and the arena is rebuilt for that tick; only what differs from
what is on screen is sent.

```java
watching.step(1);          // one frame on, held: read a single hit
watching.follow(red.getUniqueId());   // look out of their eyes
watching.reveal(true);     // somebody invisible is drawn glowing instead
```

A playback that reaches the end **pauses on the last frame** rather than
deleting itself. That moment is the one people want to sit on.

## First person

`playback.follow(actor)` puts every viewer's camera on that body: the client is
told to look out of its eyes, and follows it with its own smoothing rather than
being teleported every tick. `follow(null)` gives them their own eyes back.
`playback.locationOf(actor)` and `Replay#at(tick, actor)` still say where
somebody was, for a plugin that wants its own camera.

`follow(actor)` shows everybody where the server had them. To judge a hit, use
`follow(actor, ReplayPlayback.View.ATTACKER)` (also on `ReplayViewer`):

## The attacker's view

In Minecraft the attacker's client picks the target on its own screen and
sends "attack entity X"; the server only checks reach with its own positions.
On that screen everybody else is late: by the connection, and by the client's
own interpolation of about three ticks. Watched by the server's positions, a
hit on a strafing target lands on nothing, and in first person it is worse:
the viewer's client smooths the camera body over the same three ticks, so the
crosshair trails the swing.

```java
ReplayViewer.openLive(plugin, viewer, replay, victim)
        .thenAccept(watching -> watching.follow(killer, ReplayPlayback.View.ATTACKER));
```

`ATTACKER` draws every other body at *tick − seen lag* of the followed player
on that tick, and the followed body three ticks ahead, which cancels the
viewer's smoothing of the camera. The viewer's client then interpolates the
other bodies exactly as the attacker's did. Swings, hits and their sounds keep
their own tick. `SERVER`, and plain `follow(actor)`, is everybody on the same
tick, as before.

What it reproduces: where everybody else was on the attacker's screen when
they clicked, to the tick, and where the attacker was looking. What it cannot:
the attacker's prediction of their own movement (the camera is the server's
answer for it, which is a tick or two behind what they felt), their FOV, and
the camera turning in the protocol's own steps of 1.4°: the file keeps the
look to a hundredth of a degree, but a body on a screen can only be turned in
256 steps.

### Seen lag

How late a player's screen is, tick by tick, measured rather than guessed.
While somebody is in combat (dealt or took entity damage in the last fifteen
seconds) their client gets a protocol ping every tick, after the entity updates
of the tick before, and the pong says it has drawn everything up to that tick.
Seen lag is *now − the newest tick answered*, 0 to 254. Outside combat it is
the ping's estimate (*ping / 50 + 1*) and says so. A bot on the same host
measures 1 to 3 ticks.

The ping ids are **`0x4558xxxx`** ("EX" and a counter). Grim and the other
anticheats that ping use negative shorts, which an id this large is never
confused with; a pong outside the range is left alone. A recording made
before 1.403.0 has no seen lag, and the attacker view falls back to the ping
written once a second (`ReplayMark.PING`), or to `SERVER` without one. A client
older than 1.17 behind ViaVersion may never answer an id outside a short: it
gets the estimate.

### Every attack as sent

Each attack the client sends is kept with the look it was aimed with. That is
the look sent *right after* the attack, in the same client tick: the client
picks its target and sends the attack early in its tick and its rotation at
the end. The turns of the three ticks before it are kept too. It is merged with
the server's own mark of the hit (same attacker and target, a tick either
side), so nothing is counted twice; one the server never counted (out of reach,
invulnerable, cancelled) is an `ATTACK` mark of its own that draws nothing.

### `replay.hits()`

Every attack, with the numbers staff judge it by, as `ReplayHit` records:

| | |
| --- | --- |
| `reachServer` | eyes (by pose) to the target's hitbox (0.6 × 1.8, sneaking 1.5, by pose), by the server's positions: what the server checked |
| `reachSeen` | the same, with the target where the attacker's screen had it |
| `angleOff` | degrees between the look and that hitbox; 0 when the look goes through it |
| `flick` | the largest turn in the three ticks before, in degrees |
| `seenLag`, `estimated` | as above, -1 unknown |
| `damage`, `confirmed` | what it did after armour, `NaN` unknown; whether the server counted it |

A black box capture carries them worked out, once, when it is cut, off the
server thread. An older recording or a recorder's is worked out when asked,
with the sampled look and the ping's estimate, which is coarser and says so. A
number that cannot be worked out is `NaN`, never a guess; anything that is not
a player gets no hitbox numbers. A bot looking straight at its target measures
`angleOff` 0 and a reach under 3.

### What it costs

Measured below, under *What the black box costs*. The server thread reads two
fields per player per tick and writes one more column of the frame. Pings go
out from the replay driver's thread, only to players in combat; pongs, looks
and attacks are read on the connection's own thread into small rings written
in place, and an attack is handed over through a lock-free queue the box
drains once a tick. The analysis runs only when a capture is cut.

## Marks

`SWING`, `HURT`, `ATTACK`, `EQUIP`, `BLOCK`, `EXPLOSION`, `MOUNT`, `DISMOUNT`,
`PICKUP`, `BREAKING` and `SHIELD_DISABLED` are drawn by the module and never
reach a plugin. `DEATH`, `RESPAWN`, `TOTEM`, `TELEPORT` and `CHAT` are drawn by
it too and still reach the plugin's `onMark`, so it can put a line on the screen
for them. Everything else is a plugin's own:

```java
recorder.mark("round", null, "2");
recorder.mark("combo", red, String.valueOf(hits));

playback.onMark(mark -> {
    if (mark.kind().equals("combo")) {
        overlay.show(viewer, mark.text());
    }
});
```

Marks a plugin wrote arrive on the main thread, in the order they happened.

## A recorder has to be stopped

It holds arrays that grow for as long as it runs. `stop()` gives back what was
recorded and `cancel()` throws it away, and one of the two has to happen on
every path out of whatever is being recorded — including the one where the
match was abandoned.

One that is forgotten stops itself at half an hour and says so in the console,
which is a warning about a bug, not a feature to rely on. One whose plugin is
disabled is cancelled.

## Where things go

| | |
| --- | --- |
| Recording needs | nothing; it reads the server's own state |
| Playing back needs | PacketEvents, because the bodies are packets |
| A playback ends when | it is stopped, the last viewer leaves, the plugin is disabled, or the server stops |

`Replays.isSupported()` answers the second row.

## What is not in it

Worth knowing before designing around it:

- **A mob's variant.** A non-player keeps its type, its item (a dropped stack,
  a thrown potion, a firework), its block (a falling block), whether it is a
  baby and its custom name. A sheep's colour, a cat's breed or a horse's armour
  are left to the client's defaults.
- **Block entities.** The ground keeps every block state, not what a sign says,
  what a chest holds or the pattern on a banner.
- **Rotating an anchor.** A recording is offset, never turned. An arena played
  back against an anchor facing another way is in the right place and the wrong
  direction.
- **Anything nobody offered.** The module records what the plugin follows and
  tells it about. A fire that spread, a block that fell, a mob that wandered in
  — if no `follow` or `block` call named it, it is not in the recording. The
  ones that catch people out are the changes that fire *no event at all*: a
  schematic paste, a direct `setType`, a temporary block expiring. A recorder
  that `watch`es its zone and the black box are told about
  `Schematics.regenerate` and expiring temporary blocks by the library; any
  other direct write needs `reset()` or a `block()` call from whatever does it,
  because nothing on the server can see it happen.
- **Pistons and flowing liquids, in a recorder that is told about blocks.** A
  plugin's own `block()` calls are what that recorder has. A recorder that
  `watch`es its zone, and the black box, record pistons (both ends) and liquids
  (at most 256 flowing blocks a tick) by themselves.

## Watching a place, not a list

```java
ReplayRecorder recorder = replays.record(arena.spawn());
recorder.watch(48);   // everything within 48 blocks, by itself
```

`watch(radius)` follows whatever is in the box or comes into it — players,
mobs, items, arrows, boats — and writes down every block that changes inside it
from the server's own events, with what it was and what it became.

`watch(BoundingBox)` (1.389.0) watches a box instead of a cube around the
anchor: an arena's own bounds, so a copy pasted next door, or a fight in it, is
never part of the recording.

## The black box

A recorder has to be started before something happens. The black box is always
running and keeps only the last stretch, so a recording can be cut out *after*
the fact:

```java
ReplayBlackBox box = replays.blackBox(BlackBoxSettings.defaults()
        .hidden(vanish::isVanished));

// in a death listener
box.capture(event.getEntity()).thenAccept(replay ->
        store.save(replay.toBytes(chunks)));
```

- **It follows the person.** A capture of a player covers everything within the
  radius of anywhere they were during the window: a chase, a pearl, an elytra
  flight. Whoever and whatever was near them is in it.
- **Scenes.** A teleport further than two corridors can bridge, a portal or a
  change of world cuts the recording into scenes, each with its own anchor, and
  the playback cuts between them like a broadcast cuts between cameras.
- **The ground.** With terrain on, every chunk section along the way is kept as
  it was when the scene started: read now, then walked back through every block
  change the box saw since. What each change *became* is what the block held
  just before being walked back, so the log only ever stores what a block was.
  Height is decided chunk by chunk: each chunk keeps the sections between the
  lowest and highest point anybody in the recording stood at above it, plus
  `verticalMargin` (6 by default). A fight on a floor keeps that floor, not
  the caves under it; a block change outside that band brings its own section.
- **Regenerations.** `Schematics.regenerate` and a temporary block expiring
  write the world without an event; both report what they changed to the box,
  so a capture taken after the arena was put back still shows the fight.
- **Cost.** A frame per player per tick, a frame per other entity only when it
  moves (and a heartbeat twice a second). Nothing is written anywhere until a
  capture is asked for. One box serves the server; two plugins that open one
  share it and it keeps the larger of what each asked for (and the finer
  `entitySampleEvery`). Measured figures are under *What the black box costs*.
- **Hidden players.** `hidden` is read every sample: staff in vanish are never in
  a recording.

### After the fact, and a little after it

`box.capture(focus, seconds, afterSeconds)` carries on for a few seconds past
now: for a death, the fall, the red and the puff are the second after the blow.
The person it is about is followed only up to now, so a respawn on the other
side of the map in those seconds is not a scene.

### How the connection and the server were doing

Once a second the black box writes every player's ping (`ReplayMark.PING`) and
the server's TPS and MSPT (`ReplayMark.SERVER`, `"tps;mspt"`); a recorder writes
the pings of whoever it follows. A player leaving writes `ReplayMark.QUIT` with
Paper's reason (`TIMED_OUT`, `KICKED`…). `replay.last(kind, actor, tick)` reads
the figure in force at any tick, which is what a board shown while watching is
made of.

### What the black box costs

Everything the box does on the server thread is its players' timers, so the
work is laid out to read each thing once and nothing it cannot keep:

- **Each thing is read once, by whoever gets to it first.** A crowd is a
  hundred players with the same mobs around them; once one timer has read a
  mob, every other one skips it until it is due again. Players are read every
  tick; everything else every `entitySampleEvery` ticks (2 by default,
  `BlackBoxSettings.entitySampleEvery(int)`, 1 to 5).
- **The ticks in between are filled in when a capture is cut.** The last
  `entitySampleEvery` ticks before each frame move the body there in a straight
  line, rotation included, so a mob read every other tick walks as smoothly as
  one read every tick. A jump of more than four blocks a tick is a teleport and
  stays a jump.
- **Past `maxEntities`, what has no tape is not read at all.** A look around a
  player (once a second, or at once after a teleport) leaves it out, so a full
  box costs nothing for the mobs it cannot keep. Past ninety-six things around
  one player the nearest are kept, each distance worked out once.
- **Equipment is read when it changes.** Paper's `EntityEquipmentChangedEvent`
  marks the slot, and everything is read once a second besides. On a server
  without that event, hands every other tick and armour every five.
- **A frame is twenty-nine bytes**, the seen lag included. Positions in thousandths of a block (what
  the file holds, so nothing a capture writes is lost), the world as an index
  into a table shared by every tape, angles in 65536 steps a turn and health in
  half points.

Measured on Paper 1.21.4 with 100 bots walking and fighting in clusters among
about 2,500 mobs, at `BlackBoxSettings.defaults()` (the 1,500-entity cap full
the whole run), against the same server with no box run right after it. The
host was saturated by other work (40 % CPU pressure before, 61 % after), so
absolute tick times are high and only the differences mean anything:

| | Before (1.399.0) | Now |
|---|---|---|
| Replay share of server-thread samples | 4.9 % | 1.6 % |
| Server-thread time in replay code | 2.26 ms a tick | 0.80 ms a tick |
| Heap after a full GC, against no box | +35 MB | +26 MB (tapes 23 MB → 6 MB; the rest is marks) |
| Allocation in replay code, two minutes | 0.80 GB | 0.12 GB |

`Replays.stats()` returns a `BlackBoxStats` with what the box holds right now:
tapes of players and of everything else, frames, rough heap bytes of the tapes,
the marks and the block changes, how often in the last second a look left
something out because the box was full, and the time spent sampling in the last
second. It walks every tape, so it is for a command, not for every tick.

### Keeping the ground once

`replay.toBytes(chunks)` hands every terrain section to a `ReplayChunks` store
under the SHA-1 of its content and writes only the keys into the blob;
`Replay.from(blob, chunks)` reads them back. The same unchanged spawn recorded a
hundred times is stored once. A section the store has lost is a hole in the
ground, not a recording that cannot be played.

## Stages

A recording with terrain can be watched anywhere: `replays.stage(replay)` lays
each scene's ground down on its own plot of the temporary world, at its real
height, spread over ticks so no tick places more than eight thousand blocks.
The playback runs on it with the arena written for real, so a viewer can walk on
what was built and the server agrees with every block they see.

```java
replays.stage(replay).thenAccept(stage -> Tasks.at(viewer, () ->
        ReplayViewer.open(plugin, viewer, stage, focus).thenAccept(watching -> {
            watching.hud(status -> bar(status));
            watching.onLeave(() -> overlay.hide(viewer));
        })));
```

A recording made without terrain against a place the plugin owns (an arena
it leased for the viewer) needs no laying down: `replays.stage(replay, anchor)`
(1.389.0) is a stage over that place, played `SOLID` around the anchor, ready
at once. `close()` stops its playbacks; the place stays the plugin's.

```java
ReplayStage stage = replays.stage(replay, lease.point("a"));
ReplayViewer.open(plugin, viewer, stage, null);
```

`ReplayViewer` is the camera and the controls: adventure mode, flying,
untouchable, a hotbar the plugin fills; `toggle`, `skip`, `frame`, `faster`,
`slower`, `next(kinds)`, `cycleFollow`; the line above the hotbar fed every few
ticks; and the viewer put back exactly as they were when they leave, quit or the
plugin stops, through the teleport module (on the spot only when they quit or
the server stops). A player who logs in on the temporary world after a crash is
sent to the main world's spawn.

A stage is the expensive way to watch: every block of the ground is placed on
the server's thread, and a busy server staging a recording a second pays for it
in ticks and in the chunks it keeps loaded. For a recording watched where it
happened, see the next section.

## Watching where it happened

A recording watched seconds after it was made, on the server it was made on
(a killcam), does not need its ground copied anywhere: the ground is still
there. `ReplayViewer.openLive` (1.402.0) takes the viewer to the real place and
plays it there, with nothing staged:

```java
box.capture(victim.getUniqueId(), 7, 2, false)            // no terrain: not needed here
        .thenAccept(replay -> Tasks.at(viewer, () ->
                ReplayViewer.openLive(plugin, viewer, replay, victim.getUniqueId())
                        .thenAccept(watching -> watching.onLeave(() -> overlay.hide(viewer)))));
```

- **Drawn, never written.** The recorded bodies are packet bodies, as always;
  every block that changed during the clip is drawn for the viewer alone
  (`PACKET`), from what it was when the clip started, and taken off their
  screen when it stops. Every scene plays on its own recorded anchor, and a cut
  to another scene carries the viewer there.
- **Alone with the recording.** Whatever the recording would draw that is really
  there now (the killer walking off, a mob that came later, players, items) is
  hidden from the viewer while it plays, swept every 12 ticks within 48
  blocks; holograms, frames and the rest of the scenery stay.
- **A ghost.** The viewer is hidden from every other player (and from anybody
  who joins meanwhile, tab list included, as vanish does), pushes nothing, is
  targeted and hurt by nothing, uses, breaks and places no block, clicks and
  hits no entity, drops and picks up nothing, and is never sampled by the black
  box. The guards run at `HIGHEST`, so a plugin's own hotbar listener still
  hears its click.
- **Put back.** Leaving sends them back through the teleport module, and only
  once they have arrived are their game mode and flight restored and everybody
  shown again on both sides. Quitting or the server stopping puts them back on
  the spot.
- **Controls.** The same `ReplayViewer`: follow, the line above the hotbar,
  `onLeave`, speed, seek, `next(kinds)`.

The trade-off is `PACKET`'s: the ground is the place as it is *now*, and a
block the recording changed is there for the viewer's eyes but not for the
server. A camera that flies and looks is right; one meant to land on the bridge
somebody built is a stage. And it needs every scene's world loaded here: when
one is not, `openLive` stages the recording instead, which needs one that
carries its terrain, and completes exceptionally without it.

`box.capture(focus, seconds, after, false)` (1.402.0) is the capture for it:
no ground is read but the sections a block changed in, which is most of what a
capture costs and most of what one weighs while it is kept.

## A note on the format

Format 4 (1.403.0) keeps angles in 65536 steps a turn, written as the turn
since the tick before, and every player's seen lag; the three minute duel above
went from 29 kB to 31 kB. `Replay.from` still reads formats 3 and 2, a format 3
recording's angles widened and with no seen lag.

Format 3 (1.241.0) adds scenes, head yaw, the wider flag word, what non-player
actors look like and the terrain. `Replay.from` still reads format 2 — a duel
recorded before the upgrade plays back as it did — and refuses anything else
with a clear message.
