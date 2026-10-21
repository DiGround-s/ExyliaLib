# Models

3D models made in Blockbench, worn by living entities, drawn with nothing but
the blocks and items every client already has. No resource pack, ever.
Since 1.317.0; feet on the ground, real falls, fitted casts, phases, tweaks and
their editor since 1.321.0.

Entry point: `net.exylia.lib.model.Models`.

```java
PluginModels models = Models.of(this);

// On the mob's thread, as it spawns:
ModelInstance golem = models.attach(zombie, "stone_golem", ModelOptions.DEFAULT.withScale(1.3));

golem.onEvent(event -> {
    if (event.equals("impact")) shockwave(zombie.getLocation());
});
golem.play(ModelState.cast("slam"));
```

## What it is

A model is a `.bbmodel` saved by Blockbench into the plugin's own folder,
`plugins/<Plugin>/models/`. Its groups become bones and its cubes become
displays: blocks, items or player heads wearing the cube's own texture. Its
animations play the way Blockbench plays them.

The entity underneath, the carrier, is still the mob. Its AI, health, hitbox,
pathfinding and drops are untouched. It is made invisible, and its armour and
name are kept from every client by rewriting those two packets. The model is
drawn where the carrier is, turns with its body and walks when it walks.

A custom mob wears one by setting `MobTemplate.model` (see
[mobs.md](mobs.md#models-since-13170)). Then the mob engine plays the
animations for it, and the plugin writes nothing.

## Why there is no resource pack

A server that needs a download before its mobs look right is a server most
players see the wrong mobs on. Every cube is therefore something the client
already knows how to draw:

- a **block display** of one block,
- an **item display** of one item, or what the carrier holds,
- a **player head** wearing a picture of the cube's own texture, made once
  through Exylia Skins and shared with every server (see
  [ragdolls.md](ragdolls.md#the-real-skin)). This is the same path ragdoll
  bodies wear their skins through.

The result is not a resource-pack model and does not pretend to be one. It is
a voxel model with real depth, real light and real colour, shown to anybody
who joins.

## Making a model

1. In Blockbench, make a **Generic Model** (any format works; Java block models
   are centred on their middle automatically). Build it from cubes, grouped into
   bones with their pivots where the joints are. The model stands on `y = 0`,
   centred on `x = 0, z = 0`, facing **north**, which is Blockbench's front.
2. Animate it in the Animate tab. Name the animations after what they are for:
   `idle`, `walk`, `run`, `attack`, `hurt`, `death`, `spawn`, or the name of a
   skill. `animation.golem.walk` also counts, by its last part, and so does a
   name holding the word (`golem_walk_cycle`).
3. Save the project (`File > Save Model`) with its textures embedded into
   `plugins/<Plugin>/models/<name>.bbmodel`. The model is called `<name>`.
4. It is read the next time the plugin asks for it. Saving again is the whole
   reload: the folder is checked at most once a second, by its files' names,
   sizes and times.

A Bedrock or GeckoLib animation file exported from Blockbench beside it adds
its animations to the model, matched to bones by name. It must be named after
the model: `golem.animation.json`, or `golem.json`.

Projects saved by Blockbench 4 and by Blockbench 5 both read as their author
saw them. Blockbench 5 stopped storing keys in the Bedrock convention (X and Y of
a turn and X of a move the other way round) and flips them only on a Bedrock
export, so a project whose `meta.format_version` is 5 or later has those keys
flipped back as it is read (Blockbench `js/formats/bbmodel.js`, the `< 5.0`
migration, and `Keyframe.compileBedrockKeyframe`). Groups and cubes turn in
Blockbench's own order, X then Y then Z about the fixed axes (`euler_order`
`ZYX`, the default of every format), and a model faces north.

### Naming for the engine

| Name | What it gets |
| --- | --- |
| `head` | turns towards what the carrier looks at |
| `leg`, `thigh`, `shin`, `calf`, `knee`, `foot`, `paw`, `hoof`, `toe`, `ankle`, as a word | its feet stand on the ground (see [Feet on the ground](#feet-on-the-ground-since-13210)) |
| a timeline key `release` (else `impact`) | the moment a cast strikes (see [On a skill's beat](#on-a-skills-beat-since-13210)) |
| an animation `walk_p2`, `golem_attack_p3` | what that state plays in a fight's second or third phase |

## What a cube is drawn with

In this order:

1. **A name ending in `@something`.** `body@stone` and
   `trim@minecraft:gold_block` are those blocks. `blade@diamond_sword` is that
   item. `grip@mainhand`, `grip@offhand` and `crown@helmet` show whatever the
   carrier holds or wears in that slot, updated when it changes. `face@head`
   forces the cube's texture onto a head, even a plain one. `pivot@hide` draws
   nothing. A group's `@` applies to every cube in it that has none of its own.
   A name that is no block or item is reported, and the cube is drawn from its
   texture.
2. **Its texture.** When every texel of every face matches the same block, the
   cube is that one block. When it has a design, the cube becomes a textured
   head, drawn as the block most of its texels are until the head's texture
   arrives. With `ModelOptions.heads` off, it stays that dominant block.
3. **No texture at all.** Light grey concrete.

Blocks come from the same measured palette as ragdoll bodies: concrete,
powder, terracotta, wool and a few flat stones, compared the way the eye
compares colours. A head is eight texels to a face, so a cube with finer detail
than that is averaged. Small cubes with a design look best as heads; large flat
areas look best as blocks.

## Wearing one

```java
ModelInstance live = models.attach(carrier, "wisp", ModelOptions.DEFAULT
        .withScale(0.8)
        .withState(ModelState.ATTACK, "animation.wisp.lash")
        .withHeads(true));
```

| Option | Default | What it does |
| --- | --- | --- |
| `scale` | 1 | Size it is drawn at. Multiplied by the carrier's own `scale` attribute, so a mob that grows takes its model with it |
| `states` | none | The animation each state plays; a state left out is matched by name |
| `lookAt` | on | A bone called `head` (or the first whose name holds it) turns towards the carrier's target, or the nearest player within ten blocks, up to 70° aside and 40° up or down |
| `heads` | on | Cubes with a design may be textured heads |
| `viewRange` | 48 | How far away it is drawn, in blocks |
| `hitboxes` | on | A model more than a quarter bigger than its carrier gets boxes of its own |
| `nametag` | on | The carrier's name is drawn above the model rather than at the carrier's own height |
| `tweaks` | none | What this wearer changes about the model without touching its file (since 1.321.0; see [Tweaks](#tweaks-since-13210)) |

`attach` is called on the carrier's thread. It returns `null` when the plugin
has no such model or the server has no PacketEvents. A carrier that already
wears a model takes the new one instead.

## Playing

| State | Played |
| --- | --- |
| `idle`, `walk`, `run` | By itself, from how fast the carrier moves, sped up and slowed down with it so the feet do not slide. A model with no `run` walks |
| `attack`, `hurt`, `spawn`, `cast:<skill>` | When asked: `live.play(ModelState.ATTACK)`. The mob engine asks by itself |
| `death` | By `die`, held on its last frame |

An action plays **on top of** the base and takes only the bones it keys. A
swing that animates the arms leaves the legs walking. It fades in over 120 ms,
and a one-shot hands the bones back over its last 180 ms. A looping or
held action keeps them until `stop()` or the next action. A new base fades over
the old one in 250 ms.

`play` also takes an animation's own name, so a plugin can play anything the
model has.

### On a skill's beat (since 1.321.0)

`play(state, strikeInMillis)` fits an action to a wind-up. The animation
strikes on its `release` timeline key, else its `impact` key, else at its end,
and it is sped up or slowed down so that moment lands exactly when asked:
between a quarter and four times its own pace. What follows the strike plays at
its own pace, so a follow-through never drags. The mob engine fits every cast to
its wind-up, so the fist meets the ground when the skill lands and the warning
circle fills.

### Fight phases (since 1.321.0)

`phase(n)` moves the model to a fight phase. From then on a state plays the
phase's own animation where the model has one, and the usual one where it does
not: the options' choice for `p<n>:<state>`, then an animation called
`<state>_p<n>` (by its last part too), then one holding both the state and
`p<n>` as words. A cast's word is the skill's name: `slam_p2`. The base fades
over to its new clip; an action under way plays on. A phase's walk is never
taken for the walk: `walk_p2` is not matched by name in phase 1.

`resize(multiplier)` grows or shrinks it over half a second, and `glow(rgb)`
outlines it until told otherwise, under any `flash`. The mob engine does all
three from the phase's `size` and `glow` (see [mobs.md](mobs.md)).

### Events

`onEvent` hears the scripts of the animation's timeline keyframes, and the names
of its sound and particle keyframes, at the moment the animation reaches them.
That is how a skill's damage lands on the frame the fist hits the ground. A
keyframe's script can hold several lines, and each is an event of its own.
Listeners are told on the carrier's thread.

Since 1.324.0 an event that names a sound or a particle the server knows is
also played, where it happens: a sound key (`entity.iron_golem.step`) or a
Bukkit name, or a particle that needs no data (`cloud`, `minecraft:flame`).
Written `name@bone` it plays at that bone (`entity.iron_golem.step@foot_l`),
otherwise in the model's middle. Words the server does not know as either
(`release`, `impact`) are only told to listeners.

### Its shadow (since 1.324.0)

The carrier is invisible, and an invisible entity casts no shadow, so the root
every cube rides casts one: round, about as wide as the model's footprint, at
the carrier's feet.

### Ending

| Call | What happens |
| --- | --- |
| `die(false)` | Lets go of the carrier, plays `death` where it fell, then shrinks away |
| `die(true)` | The same, then comes apart; at once when it has no death animation |
| `comeApart()` | Every cube flies out from the middle, tumbles, bounces off the floor and the walls around it, slides to a stop and melts (see [Coming apart](#coming-apart-since-13210)). These are the model's own cubes, handed to the display module, so no second model is involved |
| `comeApart(x, y, z)`, `die(apart, x, y, z)` | The same, the cubes also thrown that way, blocks a second: away from the blow that killed it. The mob engine throws them away from the killer |
| `remove()` | Off every client now. A living carrier is shown again, armour and name included |

A model goes by itself when its carrier dies (it comes apart), is removed or is
unloaded with its chunk, and when the plugin that attached it is disabled.

## Feet on the ground (since 1.321.0)

A model with legs stands on the ground it is over: a step, a slab, a slope.

- **Which bones are legs.** Any bone whose name holds one of the words in
  [Naming for the engine](#naming-for-the-engine). The end of a leg is such a
  bone with no such bone below it.
  - *Upper, lower, foot*: the end is a `foot`, `paw`, `hoof`, `toe` or `ankle`
    and hangs off two leg bones. The hip and the knee bend so the ankle reaches
    its spot, and the foot keeps the turn its animation gave it.
  - *Upper, lower*: the end hangs off one leg bone (`leg_0` → `leg_0_low`). The
    hip and the knee bend so the lowest point of the lower bone reaches its spot.
  - *One bone*: nothing bends; the leg still tells the body how far to sink and tilt.
- **What it does.** Each pose, every sole is found where its animation put it
  and the ground under it is read, within reach of the leg. The body sinks to the
  lowest and tilts towards them, up to 22° front to back and 15° side to side,
  and each bending leg reaches its own ground, keeping the lift its animation
  gave it. A knee keeps bending the way it already bends; a straight one bends
  forwards. The ground is followed a third of the way each pose, so stepping onto
  a slab is a movement, not a jump.
- **Planted.** While the carrier walks, a sole that is down stays on the spot
  it was put down on until the animation lifts it or the body has gone more than
  60% of the leg's length past it. A foot does not skate. Standing, nothing is
  planted, and a teleport lets go of every foot.
- **What it costs.** Two passes over the bones and one ground read per foot per
  pose, read through a half-second cache, only while somebody is within the
  near distance and the carrier is on the ground. It fades in and out over a few
  poses, so a jump or a fall does not snap the legs.

## Coming apart (since 1.321.0)

Every cube's flight is worked out once, on the server, the moment the model
breaks: thrown out from the middle and up, lighter cubes further, and away from
the blow when there is one; tumbling about its own axis; bouncing off the floor
(38% of its speed kept) and the walls (30%); landing on a slab at the slab's
height; sliding to a stop; lying still a third of a second, then melting into
the ground. It is handed to the display module as poses a tenth of a second
apart that the client glides between, so a cube costs a couple of dozen packets
however far it flies, and the server spends nothing on it afterwards.

The blocks are read as a cube asks for them and only where the server owns
them. The 160 largest cubes are worked out; any beyond fly the simple arc.

## Tweaks (since 1.321.0)

`ModelTweaks` is what one wearer changes about a model without touching its
file. A custom mob stores them with its model.

| Part | What it does |
| --- | --- |
| `offset` | Moves the model, in blocks: its own right, up and back, before it turns with the carrier |
| `bones` | Per bone, by name: `hidden`, or a `material` (a block or item) its cubes are drawn in instead. Every bone below it that says nothing of its own follows |
| `speeds` | Per state or animation name: how fast it plays, `0.1-10` |
| `hitbox` | One box of this width and height instead of the ones the model works out for itself |

### The editor

```java
models.tweakEditor("stone_golem", template.model().options())
        .title("{primary}&lMODEL TWEAKS {letters_black}» {highlight}" + template.id())
        .onSave(options -> store.save(template.withModel(template.model()
                .withScale(options.scale()).withTweaks(options.tweaks()))))
        .onCancel(() -> ModelMenu.open(player))
        .open(player);
```

A list editor whose rows are the bones it changes (add asks which bone, hidden
or not, and what to draw it in), with four buttons: SIZE & PLACE (scale and
offset), SPEEDS (one field per state), HITBOX, and PREVIEW, which stands the
model in front of the viewer for 30 seconds, turning slowly and playing each of
its animations, as the edits stand. Every field opens on its current value.
Nothing is written until save; any other ending takes the preview down and calls
`onCancel`.

## Bigger than its carrier

The carrier's own hitbox keeps working. A model more than a quarter wider or
taller than it gets up to four boxes of its own, laid along its long axis and
turned with it. They are packet-only interaction entities, and a hit on one is
handed to the carrier as that player's attack, if the player is within reach.
Right-clicks on them are dropped.

## What it costs

- **One teleport a tick for the whole model, while it moves.** Every cube rides
  one invisible root display, and the client glides the root after the carrier
  over two ticks. A cube's pose is sent only when it changed, with an
  interpolation as long as the gap to the next one, so the client draws every
  frame in between.
- **Three distances.** Within 16 blocks a viewer gets a pose every other tick,
  within 32 every fourth, and beyond that every eighth, with only the larger
  third of the cubes (at least six). A viewer moves to the next distance only
  two blocks past its edge, so standing on one does not swap the small cubes in
  and out.
- **A ceiling, counted in viewers.** `max-model-parts` in
  `plugins/ExyliaLib/displays.yml` (default 30 000) is cubes multiplied by the
  players drawing them. Past three quarters of it every viewer is drawn one
  distance further away, and past all of it two. Nothing is refused: a crowded
  fight loses detail, not models. `0` removes the ceiling.
- **Who is drawn it is who tracks the carrier.** On Paper the model's audience is
  the carrier's own tracker, read every five ticks, so a player sees the model
  exactly when the server would show them the mob. On Spigot it is everyone in
  the world within `viewRange`, and somebody already watching keeps it until
  they are eight blocks further than that, so walking along the edge does not
  blink it.
- No benchmark is claimed. Every number here is a design figure.

## What it does not do

- **No meshes.** A mesh element cannot be drawn without a resource pack. It is
  reported once and left out. Locators and null objects are left out silently.
- **No Molang.** A keyframe whose value is an expression is reported once and
  left out, as ragdolls do. So are `anim_time_update`, `blend_weight` and the
  loop and start delays.
- **Boxes do not grow.** A model resized by a phase keeps the boxes it was
  given; the carrier's own hitbox grows with its scale attribute as ever.
- **Feet read the ground under them, not beside them.** A foot over the edge of
  a block stands on whatever is below its sole's middle.
- **No shear.** A bone scaled unevenly that also turns a child by something
  other than right angles is drawn with the child's rotation and the scale
  multiplied axis by axis. Blockbench shears there, and a display entity cannot.
- **No per-face textures beyond eight texels.** A head has eight texels to a
  face; more detail is averaged.
- **Bedrock players** (Geyser) see the carrier's vanilla body: their client has
  no display entities.
- **`rideOn` displays on a carrier** are replaced by the model's own passenger
  list for that carrier.

## Threads and lifecycle

- `Models.of`, `model`, `names` and `reload` are safe from any thread. The
  folder is read on whichever thread asks first after it changed.
- `attach` runs on the carrier's thread.
- Every `ModelInstance` method is safe from any thread. Requests are queued and
  done on the carrier's next tick.
- Each model ticks on its carrier's scheduler. Once it lets go of the carrier,
  it ticks on the scheduler of the place where it fell. A sweep once a second
  takes off every client any model whose ticks stopped: the carrier went away
  in a way no event reported.
- `Models.release(plugin)` runs in `ExyliaLib.onPluginDisable`, and
  `Models.releaseAll()` on shutdown. Nothing outlives its plugin.
- Reload: nothing here is derived from the palette, so there is no
  `invalidateAll`. The folder reloads itself.

## Source and tests

- Public: `model/` — `Models`, `PluginModels`, `Model`, `ModelInstance`,
  `ModelOptions`, `ModelState`; `util/mob/MobModel`, `MobTemplate.model`,
  `MobCodec.encodeModel`/`decodeModel`.
- Internal: `model/internal/` — `BbModelReader` (the project, textures, looks,
  in-file and Bedrock animations), `Rig`, `Look`, `Clip`, `Poser` (every pivot
  and convention, in one place), `Controller` (layers, fades, events, death),
  `LiveModel` (drawing, distances, boxes, heads, ending), `ModelRuntime`
  (registry, ceiling, sweep), `ModelFolder`, `ModelPackets` (the only class
  naming PacketEvents). Shared with ragdolls: `util/internal/KeyTrack`
  (sampling, catmull-rom, bezier), `util/internal/BedrockKeys` (the Bedrock
  keyframe walker), `ragdoll/internal/SkinCubes.painted`, `BlockPalette`,
  `RagdollTextures`, `SkinTextureQueue`. Display packets come from
  `DisplayRuntime.sink()`.
- Since 1.321.0: `ModelTweaks`, `ModelTweakEditor`, `PluginModels.tweakEditor`,
  `ModelInstance.play(state, millis)`/`phase`/`resize`/`glow`/`comeApart(x, y, z)`/
  `die(apart, x, y, z)`, `Model.animationFor(state, options, phase)`; internal
  `Legs` (limbs, two-bone IK, planting), `Debris` (the flight integrator), and
  `Poser.bones`/`parts`/`reworld`/`sole`. `effect/internal/LiveBars` keeps the
  boss bars of [mobs](mobs.md).
- Tests: `LegsTest` covers leg names, the two-bone solve (reach, bone lengths,
  knee direction, out of reach), flat ground changing nothing, a step under one
  foot and a planted foot staying put while the body walks on. `DebrisTest`
  covers determinism, never falling through the floor, lower bounces, walls,
  a blow's direction and slabs. `ControllerTest` covers fitted casts, the
  fitting's limits, per-state speeds and a phase's own walk. `ModelReadTest`
  pins the Blockbench 5 keys, the `ZYX` order and the facing to Blockbench's
  source, and the phase lookup. `ModelBudgetTest` covers the distance
  hysteresis. `MobCodecTest` covers tweaks and a phase's size and outline.
- Tests (1.317.0): `ModelReadTest` checks pivots and rotations against hand-worked
  Blockbench numbers, the yaw, the scale, the Bedrock signs, linear and bezier
  sampling, Molang reports, events, markers, colour matching, heads, state
  matching, block models and Bedrock exports. `ControllerTest` covers walk and
  run with hysteresis, fades, actions keeping the bones they don't key, one-shot
  hand-back, events once per pass, and death. `ModelBudgetTest` covers the
  ceiling's pressure. `MobCodecTest.modelRoundTrip` covers storage.
