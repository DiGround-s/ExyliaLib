# Models

3D models made in Blockbench, worn by living entities, drawn with nothing but
the blocks and items every client already has. No resource pack, ever.
Since 1.317.0.

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

### Events

`onEvent` hears the scripts of the animation's timeline keyframes, and the names
of its sound and particle keyframes, at the moment the animation reaches them.
That is how a skill's damage lands on the frame the fist hits the ground. A
keyframe's script can hold several lines, and each is an event of its own.
Listeners are told on the carrier's thread.

### Ending

| Call | What happens |
| --- | --- |
| `die(false)` | Lets go of the carrier, plays `death` where it fell, then shrinks away |
| `die(true)` | The same, then comes apart; at once when it has no death animation |
| `comeApart()` | Every cube flies out from the middle, falls under gravity, lands and melts. These are the model's own cubes, handed to the display module, so no second model is involved |
| `remove()` | Off every client now. A living carrier is shown again, armour and name included |

A model goes by itself when its carrier dies (it comes apart), is removed or is
unloaded with its chunk, and when the plugin that attached it is disabled.

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
  third of the cubes (at least six).
- **A ceiling, counted in viewers.** `max-model-parts` in
  `plugins/ExyliaLib/displays.yml` (default 30 000) is cubes multiplied by the
  players drawing them. Past three quarters of it every viewer is drawn one
  distance further away, and past all of it two. Nothing is refused: a crowded
  fight loses detail, not models. `0` removes the ceiling.
- **Who is drawn it is who tracks the carrier.** On Paper the model's audience is
  the carrier's own tracker, read every five ticks, so a player sees the model
  exactly when the server would show them the mob. On Spigot it is everyone
  within `viewRange`.
- No benchmark is claimed. Every number here is a design figure.

## What it does not do

- **No meshes.** A mesh element cannot be drawn without a resource pack. It is
  reported once and left out. Locators and null objects are left out silently.
- **No Molang.** A keyframe whose value is an expression is reported once and
  left out, as ragdolls do. So are `anim_time_update`, `blend_weight` and the
  loop and start delays.
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
- Tests: `ModelReadTest` checks pivots and rotations against hand-worked
  Blockbench numbers, the yaw, the scale, the Bedrock signs, linear and bezier
  sampling, Molang reports, events, markers, colour matching, heads, state
  matching, block models and Bedrock exports. `ControllerTest` covers walk and
  run with hysteresis, fades, actions keeping the bones they don't key, one-shot
  hand-back, events once per pass, and death. `ModelBudgetTest` covers the
  ceiling's pressure. `MobCodecTest.modelRoundTrip` covers storage.
