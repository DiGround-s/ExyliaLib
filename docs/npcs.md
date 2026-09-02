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
| `hurt:` | flinches red as it appears | `false` |

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

- Public: `npc/` — `Npcs`, `PluginNpcs`, `NpcModel`, `NpcPose`, `NpcHandle`.
- Internal: `npc/internal/` — the driver and the one class that names
  PacketEvents.
- In sequences: `Steps.Corpse`, behind the `[NPC]` token.
- Tests: `NpcLifetimeTest` asserts every way an NPC can be taken away, that it is
  taken away exactly once, that one plugin's release leaves another's alone, and
  that a life no file could have meant is brought back inside the limits.
