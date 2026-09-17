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
| Yaw and pitch | to about a degree and a half, which is the protocol's own resolution |
| Pose | standing, sneaking, crawling, lying, spinning |
| Sprinting, on the ground | as the client reported them, which is what the server used |
| Health | to the nearest half heart |

Whether a bow is drawn, a shield is up or a gapple is going down rides in the
same byte as the pose.

Plus a second track of things that happened once rather than being true for a
stretch: swings, hits, deaths, respawns, equipment changes, **block changes**
and **explosions**. Swings and hits are read off the server's own events, so a
plugin that records a duel gets them without writing a listener.

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

What a player *saw*. A client predicts its own movement and is corrected
afterwards, so somebody on a bad connection watched their hit land while the
server was deciding it had not. The recording shows the server's answer.

That is the right answer and it is not always the popular one. Nothing in here
reproduces client-side prediction, FOV, view bobbing or the tilt on taking
damage, and nothing ever will: none of it existed on this side of the
connection.

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

What makes it look real is that nothing about it is animated. A body is put
where it was with the same relative-step packet a real player's movement
produces, and the client draws its own frames between one step and the next
exactly as it does for anybody else.

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
| a thing appearing | the bow, the pearl, the potion, the fuse |

A break is heard and shattered as **what was there**, not as what is there now,
which is why `block(at, became, was)` takes both. Pass the old block and a pane
of glass sounds like glass; leave it out and every break in the replay is
silent.

`playback.sounds(false)` turns all of it off.

## Controls

```java
watching.pause();
watching.speed(0.25);      // a sixteenth to eight
watching.seek(1200);       // a minute in
watching.loop(true);
```

A seek goes backwards as freely as forwards: what everybody is wearing is
rebuilt from the marks up to that point, so landing in the middle of a fight
puts the right sword in the right hand rather than whatever was last drawn.

A playback that reaches the end **pauses on the last frame** rather than
deleting itself. That moment is the one people want to sit on.

## First person

There is no first-person mode, because there does not need to be one. A
playback will tell you where somebody is:

```java
Location watching = playback.locationOf(red.getUniqueId());
if (watching != null) {
    viewer.teleport(watching);
}
```

Teleport the viewer there every tick and they are looking out of the recording
rather than at it. `Replay#at(tick, actor)` is the same thing on a recording
that is not being played.

## Marks

`ReplayMark.SWING`, `HURT`, `EQUIP`, `BLOCK` and `EXPLOSION` are drawn by the
module. `DEATH` and `RESPAWN` are recorded by it but drawn by nobody — what a death should look like
is the plugin's decision. Everything else is a plugin's own:

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

- **A thing's appearance beyond its type.** A non-player actor is drawn from its
  type alone, so an arrow is an arrow and a crystal is a crystal — but a splash
  potion is the default colour and a dropped item is invisible, because neither
  carries what it is made of.
- **Rotating an anchor.** A recording is offset, never turned. An arena played
  back against an anchor facing another way is in the right place and the wrong
  direction.
- **Anything nobody offered.** The module records what the plugin follows and
  tells it about. A fire that spread, a block that fell, a mob that wandered in
  — if no `follow` or `block` call named it, it is not in the recording. The
  ones that catch people out are the changes that fire *no event at all*: a
  schematic paste, a direct `setType`, a temporary block expiring. Those need
  `reset()` or a `block()` call from whatever does them, because nothing on the
  server can see them happen.
- **Pistons and flowing liquids.** A piston moves a column and has two ends;
  flowing water fires a change per block per tick and would spend a whole
  recording's budget on a puddle.

## A note on the format

`Replay.from` reads format 2 and refuses anything else with a clear message,
including the format 1 written by 1.175.0. That version was never on a live
server, so no recording exists in the old shape; carrying a reader for it would
be a branch that could only rot.
