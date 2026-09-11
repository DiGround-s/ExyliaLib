# Ragdolls

Bodies that come apart into their own pieces, sent as packets. Since 1.120.0.

```java
PluginRagdolls ragdolls = Ragdolls.of(this);

ragdolls.show(
        RagdollModel.of(victim).detail(2).light(15),
        RagdollMotion.builder().pose(RagdollPose.SPREAD).rise(1.4).hang(1.2).build(),
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

## Poses

`pose:` is what happens to the body. All of them are solved in advance and all
cost the same.

| Pose | What it is |
|---|---|
| `burst` | Thrown apart: outwards, up, down, bounce, rest. The default, and the one that reads as violence |
| `spread` | Lifted off the ground and opened out, arms and legs wide, turning slowly. Held there, then let go |
| `knocked` | Held open like `spread`, then struck several times from different sides. Each blow shoves it and spins it, and it drifts back before the next lands |
| `vortex` | Taken: the pieces spiral out, round and upwards, and are gone at the top. Nothing is left on the floor |
| `balloon` | The head swells to `swell` times its size, wobbles there, and bursts. The body stands underneath the whole time and then goes outwards with it |
| `helicopter` | The arms go flat above the head and turn into a rotor. The body lifts off, counter-turns underneath, and leaves forwards |
| `plane` | Arms out as wings, nose up, banking as it climbs. A negative `up` is a dive, and a dive stops at the floor |
| `flatten` | Driven straight down and left flat, at `squash` of its height, in its own colours |
| `melt` | Sinks where it stands, from the feet up, losing its height rather than its place |
| `thrown` | Sent somewhere, in one piece. The body keeps its shape and turns end over end as it goes; with no gravity it does not come back |
| `sign` | The pieces lay themselves out into letters and hold there. The letters *are* the body: every stroke is one or more of their own pieces, stretched along it |
| `animate` | Choreographed, since 1.132.0. The body keeps every joint and does what its `keys` say — crouches, jumps, flips, dances, walks off — and then whatever `then` says. See *Choreography* below |

## Choreography

Every other pose moves each part on its own curve. `animate` solves the body as
a skeleton instead: the hips are placed, the chest hangs off the hips, the head
and arms off the chest, the legs off the hips. A turn anywhere carries
everything below it, so nothing ever comes off the body until the file says so.

```yaml
effects:
  # A hop, a backflip in the air, and a landing that bounces.
  - '[RAGDOLL] {victim};intact:0.1;detail:2;light:15;then:burst;keys:0.25 crouch ease=anticipate | 0.4 stand up=1.6 flip=~-360 arms=0,0,150 ease=out | 0.35 up=0 ease=bounce'
```

### A frame

Frames are separated by `|`. Each starts with the **seconds it takes to reach
it from the one before** — the same numbers `[DELAY]` lines add up to — and
lists only what changes. Everything a frame leaves out is carried over. The
body starts standing where it died, and starts moving once `intact` is over.

Everything is in the body's own terms: forward is towards whoever it faces,
right is its own right, angles are degrees.

| Channel | What it moves |
|---|---|
| `at=right,up,forward` | the hips, in blocks; also `right=` `up=` `forward=` one at a time |
| `flip=` `turn=` `lean=` | the whole body about its hips: forwards, to its left, to its right |
| `head=` `body=` | `pitch,yaw,roll`: nods and bows forward, turns to its left, tilts to its right |
| `arm_r=` `arm_l=` `leg_r=` `leg_l=` | `pitch,yaw,roll`: swings forward and up, swings a raised limb outwards, raises it away from the body |
| `arms=` `legs=` | both sides at once, mirrored: `arms=0,0,90` opens both arms |
| `<joint>_at=out,up,forward` | pulls a part off its joint, in blocks: a head popping off, arms stretched out of their sleeves |
| `size=` `<joint>_size=` | how big the whole body, or one part, is |
| `shake=` | how hard the whole body trembles, in blocks |
| `ease=` | how the frame is reached |

A rotation given one number sets its pitch, two set pitch and yaw. A number
written `~90` is added to where the channel already is: `turn=~360` is one more
full turn however many came before it.

### Named poses

A bare word is a whole pose, and anything written after it in the same frame is
written on top: `stand` `tpose` `star` `cheer` `reach` `zombie` `hug` `crouch`
`sit` `kneel` `lie` `prone` `bow` `pray` `dab` `superman` `splits` `float`
`limp` `fetal` `swoon` `heart` `arabesque`.

A named pose sets every joint and how the hips sit, and nothing else — where
the body has walked to, which way it has turned and how big it is stay. Angles
go back to the nearest whole turn rather than to zero, so `stand` after two
backflips does not spin the body backwards through both of them.

### Easing

| Ease | What it looks like |
|---|---|
| `in_out` | the default: leaves gently, arrives gently |
| `in` | winds up and strikes |
| `out` | lands |
| `linear` | one speed throughout; a spin |
| `back` | overshoots and settles |
| `anticipate` | pulls back before it goes |
| `elastic` | springs |
| `bounce` | lands twice |
| `snap` | cuts straight to the pose |
| `smooth` | runs a curve *through* its neighbours instead of stopping at every frame — a sway, a walk, a path |

A frame that turns a joint faster than the client can follow the right way
round is reported when the file is read, with the frame number.

### What happens after the last frame

`then:` is worked out piece by piece from where each piece was and how fast it
was moving and turning, so nothing snaps: a body flung upwards and burst at the
top keeps rising for a moment as it comes apart.

| `then` | What it does |
|---|---|
| `hold` | stays in its last pose and shrinks away — the default |
| `burst` | every piece is thrown outwards on `speed up spread gravity bounce spin`, on top of the speed it already had |
| `collapse` | every piece simply drops, keeping its momentum |
| `implode` | every piece spirals into the middle of the body and is gone; `turns` is how far round |
| `dissolve` | blows away as dust, from the head down |

Leave `life` out and a choreography lives exactly as long as `intact`, its
frames and its finish need. `RagdollMotion.finishAt()` is the millisecond the
last frame lands, which is the number to time the finale's `[DELAY]` lines to.

### Follow-through

`follow:` adds the motion nobody animates: arms left behind when the hips shoot
up and floating when they stop, a head that nods on landing, arms and legs flung
outwards by a spin. Each loose joint is a damped spring pushed by how the hips
accelerate, felt in the body's own axes, and added on top of the frames. `0` is
exactly what the frames say, `1` is a body, `2` is a cartoon. Since 1.134.0.

### Carrying things

`hold:` and `offhand:` put an item in the right and left hand, `hat:` one on the
head (`hold_size` `hat_size` `hat_y`). They ride the part that carries them, so
a rose goes wherever the arm goes and a glass helmet turns with the head, and
they come apart with the body. Since 1.134.0.

### Puppet strings

`strings:` runs a string from each hand and the top of the head straight up to
that height, re-measured at every pose, so a string shortens when its arm is
jerked up and follows the hand everywhere. `snip:` is the second, from the start
of the sequence, when they are cut and whip up out of sight; left out, it is the
last frame. Since 1.136.0.

### Spelling

`then:spell` flies every piece into the strokes of `sign:` (`letters:` tall,
`rise:` off the floor), floats the head above the word, holds it while it is
read and drops it. Since 1.134.0.

### Faces

An item display draws its model turned half round. The head is turned back
before it is posed, so a body shows its face to whoever it faces — before
1.134.0 every body, and every preview, showed the back of its head.

### What it costs

Each piece is sampled every tick, and every pose the client would have drawn
anyway — a leg standing still, an arm moving in a straight line — is dropped
before it is sent. The curves keep their curves and still limbs cost two
packets. This thinning applies to every pose, not only `animate`.

`spread` and `knocked` exist for the beat in the middle. A body hanging open in
the air is a whole second in which something else can happen to it — and the
blows of `knocked` land on a fixed clock, so whatever is doing the hitting is
written as its own lines and timed to them with `[DELAY]`:

```yaml
effects:
  - '[RAGDOLL] {victim};pose:knocked;intact:0.3;lift:0.45;hits:4;every:0.34;force:0.9;hang:1.7;life:3.6;detail:2;light:15'
  # The first blow lands at intact + lift = 0.75s. Line the snowball up with it.
  - '[DELAY] 0.55'
  - '[DISPLAY] SNOWBALL;from:5,2.4,0;to:0.4,1.8,0;ease:in;life:0.2;size:1.4;light:15'
  - '[DELAY] 0.2'
  - '[SOUND] ENTITY_SNOWBALL_THROW;1.6;0.8'
```

`RagdollMotion.hitAt(index)` gives the exact millisecond of each blow, which is
the number those `[DELAY]` lines add up to.

## Detail

`detail` is how finely each part is cut, from 1 to 4. Since 1.135.0 a part is cut
into a grid of its own shape rather than a square one: a limb four pixels wide
gets fewer columns than rows, because a skin is drawn in bands — a cuff, a
sleeve, a belt, a shoe — and those bands are what make a body look like its
owner. Every cell is the average of the skin's own pixels, with the second layer
(jackets, hoods, rolled sleeves) painted over the first.

| Detail | Displays | Torso / each limb | What it buys |
|---|---|---|---|
| 1 | 6 | 1×1 / 1×1 | One colour per limb. The cheapest a death can be |
| 2 | 19 | 2×3 / 1×3 | Shoulders, hands and shoes apart from what is between them |
| 3 | 56 | 3×5 / 2×5 | Close to three skin pixels a cell: belts, stripes, collars |
| 4 | 73 | 4×6 / 2×6 | Two skin pixels a cell, as near the skin as blocks get |

The blocks come from a palette of about 120 flat, opaque textures matched by the
eye's weighting rather than plain RGB, so a peach face is not rounded to orange.
Every piece is a display, so the server's `displays.yml` budget applies.

The ceiling is the block itself: without a resource pack a client cannot be
handed a texture, so a cell is always one colour. The head is the real head.

## Written in configuration

Most callers never touch the API. The sequence module reads a body from the same
file everything else is written in:

```yaml
effects:
  - '[RAGDOLL] {victim};life:2.4;intact:0.35;up:7;spin:2;detail:2;light:15'
  - '[RAGDOLL] {victim};pose:spread;rise:1.4;open:0.7;hang:1.2;turns:0.5;life:3.4'
```

| Parameter | What it is | Default |
|---|---|---|
| `pose` | any pose in the table above | `burst`, or `animate` when `keys` is written |
| `keys` | the choreography of an `animate` body | none |
| `then` | what an `animate` body does after its last frame: `hold` `burst` `collapse` `implode` `dissolve` `spell` | `hold` |
| `follow` | how much loose joints lag and overshoot | `0` |
| `hold` `offhand` `hat` | items carried in a hand or on the head | none |
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
| `rise` | how far off the ground it hangs — every pose but `burst` | `1.1` |
| `open` | how far the arms and legs open out | `0.55` |
| `lift` | seconds the lift takes | `0.45` |
| `hang` | seconds it hangs there before it is let go | `0.9` |
| `turns` | turns it makes while it hangs, or spirals through | `0.35` |
| `hits` | how many times a `knocked` body is struck | `3` |
| `every` | seconds between blows | `0.32` |
| `force` | how far a blow shoves it, in blocks | `0.85` |
| `swell` | how many times its size a `balloon` head reaches | `3` |
| `squash` | what is left of a flattened piece's height | `0.14` |
| `sign` | what a `sign` body spells | `EZ` |
| `letters` | how tall one letter is, in blocks | `2.4` |
| `dir` | which way it is thrown or flies, in degrees: 0 is east, 90 is south | away from the killer |

`[RAGDOLL] {killer}` takes the killer apart instead, which is what a curse or a
recoil effect wants.

## What each pose reads

`burst` reads the throw: `speed up spread gravity bounce spin settle`.
`spread` and `knocked` read `rise open lift hang turns` first, and the throw
afterwards for the fall. `knocked` adds `hits every force`. `vortex` reads
`rise open turns`. `balloon` reads `swell lift hang` and then the throw in full.
`dir` is worth knowing about before writing any of the three poses that
travel. Everything a sequence draws is written on the world's own axes — an
airlock at `from:4.5,1.7,0` is four and a half blocks east, whoever did the
killing — so a body that leaves on a bearing taken from the kill leaves in a
direction nothing else in the effect knows about. Write `dir:` to send it into
the scene the effect actually drew; leave it out, and it leaves away from
whoever killed it, which is the right answer when the effect draws nothing.

`thrown` reads `speed up gravity spin lift dir` and nothing else — it is the one
pose that carries a body rather than taking it apart, so it has no floor, no
bounce and no rise. `helicopter` reads `rise lift speed up spin`, `plane` the same plus `open` and
`turns`, `flatten` reads `lift squash open`, and `melt` reads `hang squash open`.

A rotor is the one thing here drawn on a finer beat than everything else — the
client turns a display the short way round, so half a turn per pose is the
ceiling, and a tenth of a second per pose would cap a helicopter at the speed of
a desk fan. `helicopter` therefore sends a pose every fiftieth of a second and
buys itself twice the rotor speed for two extra packets a second.

## Limbs turn about their joints

Every pose that opens a body out turns each limb about its own shoulder or hip
rather than moving it outwards. This is not a detail: a limb moved half a block
to the side comes away from the body, and the gap is the first thing anybody
sees — it reads as a corpse that already came apart and then froze. `open` is
therefore an angle, from 0 to 1, where 1 is a quarter turn.

The same rule is why the helicopter is the whole body turning with its arms out,
and not a rotor above its head. The first version flew the arms off the
shoulders to a hub, which is correct and looks like two planks orbiting a
corpse: a limb that leaves the body stops reading as a limb.

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
