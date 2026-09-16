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

Plus a second track of things that happened once rather than being true for a
stretch: swings, hits, deaths, respawns, and equipment changes. Swings and hits
are read off the server's own events, so a plugin that records a duel gets them
without writing a listener.

**That is the state the fight was decided on.** For the question anybody
actually asks a replay — *did that hit land, was he in range* — the recording
is the authority, and the player's own screen is the thing that was guessing.

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

A three minute duel between two players is about **twenty kilobytes** once it is
written out — a column in a table rather than a file on a disk. Three things
make that true, in this order:

1. Position is quantised to a thousandth of a block, which is finer than a
   client can show and exactly what is held in memory, so the round trip is
   lossless.
2. Each tick is written as the difference from the one before it, zig-zagged and
   varint-encoded: standing still costs one byte an axis and walking costs two.
3. The whole thing is gzipped, which is where the long runs of identical flag
   and rotation bytes go.

Recording itself is a handful of field reads per followed player per tick.
Playing back sends packets only when the tick being shown changes, so a playback
at a quarter speed sends one set every four ticks rather than four copies of the
same one.

## Where a recording lives

The module does not decide. `toBytes()` gives a blob and `Replay.from(byte[])`
reads one back, which goes in a column, a document, or a file — the same
decision, and the same `Databases` module, as anything else a plugin stores.

## Watching is private

The bodies are packets, drawn by the NPC module. A playback exists on the
screens it was given and nowhere else: two players can watch two different
recordings standing in the same arena, and neither can touch what the other is
looking at.

What makes it look real is that nothing about it is animated. A body is put
where it was with the same relative-step packet a real player's movement
produces, and the client draws its own frames between one step and the next
exactly as it does for anybody else.

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

`ReplayMark.SWING`, `HURT` and `EQUIP` are drawn by the module. `DEATH` and
`RESPAWN` are recorded by it but drawn by nobody — what a death should look like
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

## What is not in it yet

Worth knowing before designing around it:

- **Projectiles and other entities.** Only players are followed. An arrow or a
  pearl is not in the recording, and re-simulating one would drift away from the
  death it caused — they have to be recorded as actors, which they are not yet.
- **Blocks.** A block placed or broken during a recording is not replayed.
- **Item use.** A drawn bow or a raised shield is not in the frame; the metadata
  the NPC module writes has no room for it yet.
- **Rotating an anchor.** A recording is offset, never turned.
