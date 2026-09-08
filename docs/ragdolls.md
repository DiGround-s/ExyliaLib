# Ragdolls

Bodies that come apart into their own pieces, sent as packets. Since 1.120.0.

```java
PluginRagdolls ragdolls = Ragdolls.of(this);

ragdolls.show(
        RagdollModel.of(victim).detail(2).light(15),
        RagdollBurst.builder().life(2.4).intactFor(0.35).up(7).spin(2).build(),
        victim.getLocation(),
        nearby);
```

## What it is for

The moment a kill effect is actually about. A body that stands for a beat and
then leaves in six directions is the difference between a death that happened to
a player and a firework that happened near one.

## Why it is not an NPC

`[NPC]` is one fake player, and the only way to move a fake player is to
teleport it. That means it moves once a tick at best, stutters whenever the
server does, cannot turn smoothly, and cannot come apart at all.

A ragdoll is a handful of display entities whose entire flight — where every
piece is, and how it is turned, at every moment — is solved the instant the body
bursts and handed to the client in one go. The client draws every frame in
between at its own frame rate. It is smooth on a server that is not, and it
costs fewer packets than the NPC it replaces.

## How a body is drawn

The head is a real player head item, so the face, the hat layer and everything
anybody actually recognises are exact.

Every other part is a box of the nearest block to the colour that part of the
skin really is. A client cannot be handed an arm-shaped model wearing four
pixels of somebody's sleeve without a resource pack, and a server that needs a
download before its kill effects work is a server most players never see them
on. At the distance and the speed a body flies apart, a box of the right colour
is what the eye reads anyway.

Skins are read from Mojang's texture server once per skin, in the background,
when their owner joins — minutes before anybody kills them. A skin that has not
arrived costs one death its colours and nothing else.

## Detail

`detail` is how many cells each part is cut into on each axis.

| Detail | Displays | What it buys |
|---|---|---|
| 1 | 6 | One colour per limb. The cheapest a death can be, and what most effects should use |
| 2 | 21 | A sleeve apart from a hand, a shirt apart from a belt |
| 3 | 46 | A collar, a stripe, a tie |
| 4 | 81 | Nothing the eye can follow at the speed the pieces are moving |

Every piece is a display, so the server's `displays.yml` budget applies: a
`detail:3` body in front of thirty players is fourteen hundred display-viewer
pairs, and the ceiling exists for exactly that.

## Written in configuration

Most callers never touch the API. The sequence module reads a body from the same
file everything else is written in:

```yaml
effects:
  - '[RAGDOLL] {victim};life:2.4;intact:0.35;up:7;spin:2;detail:2;light:15'
```

| Parameter | What it is | Default |
|---|---|---|
| `life` | seconds the pieces last | `2.2` |
| `intact` | seconds the body stands whole first | `0.3` |
| `speed` | how fast the pieces leave, outwards | `3.2` |
| `up` | how fast they leave, upwards | `6.5` |
| `spread` | how much the pieces differ, 0 to 1 | `0.45` |
| `gravity` | blocks per second squared | `26` |
| `bounce` | speed kept on landing, 0 to 1 | `0.32` |
| `spin` | turns a second | `1.8` |
| `detail` | cells each part is cut into, 1 to 4 | `1` |
| `size` | how big it is; 1 is player-sized | `1` |
| `light` | 0 to 15, instead of the light where it falls | the world's |
| `glow` | an outline colour | none |
| `fade` | shrinks away at the end | `true` |
| `settle` | stops turning once it lands | `true` |
| `y` | height above the anchor | `0` |
| `face` | turns to face whoever did it | `true` |

`[RAGDOLL] {killer}` takes the killer apart instead, which is what a curse or a
recoil effect wants.

## Three things worth knowing

**`intact` is the whole effect.** A body already in pieces when the sword lands
was never a body. A third of a second standing there is what tells the eye it
was.

**Gravity is choreography, not physics.** Vanilla is about 32. A body thrown up
at seven and pulled down at twenty-four hangs long enough to be looked at, which
is the point of the effect and not something reality was going to give us.

**The floor is a plane, not the world.** Pieces bounce off the height the body
died at and pass through everything else. A piece that clips a staircase for a
fifth of a second is the price of never sticking inside one, and of never having
to read the world off the main thread.

## Lifetime

Every piece is a display, so this inherits the display module's whole lifecycle:
nothing is ticked, nothing is saved, and everything is taken off the clients
showing it when the plugin is disabled or the server stops. Keep the handle only
if you might want a body gone early — a preview the player closed, an arena
being reset.
