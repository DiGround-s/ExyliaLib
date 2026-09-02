# Display module

Solid objects that move by themselves, sent as packets. Swords that fall out of
the sky, shockwaves of real blocks, a body that comes apart into the floor it was
standing on — the effects particles cannot do.

## Using

Almost nobody touches the API. A display effect is written in the same
`effects` list every other effect is written in, and the sequence module draws
shapes out of displays exactly as it draws them out of particles:

```yaml
effects:
  # Twelve swords that fall out of the sky in a ring, spinning as they come.
  - '[CIRCLE] NETHERITE_SWORD;as:item;radius:2.6;points:12;from:0,9,0;to:0,0,0;spin:2;axis:x;life:0.9;face_out:true;light:15'
  # The floor buckling: a ring of blocks, flattened, growing outwards.
  - '[CIRCLE] CRYING_OBSIDIAN;as:block;radius:0.3;points:20;size:0.1;size_to:1.4;to:0,0.2,0;life:0.5;light:15'
  # One item, thrown up, tumbling, falling back down.
  - '[DISPLAY] TRIDENT;from:0,0.5,0;to:0,4,0;gravity:14;spin:3;axis:z;life:1.4;size:1.5'
```

The API, for the cases configuration cannot describe:

```java
PluginDisplays displays = Displays.of(this);

DisplayModel blade = DisplayModel.item(new ItemStack(Material.NETHERITE_SWORD))
        .glow(0xFF6B9D)
        .light(15);

DisplayMotion thrown = DisplayMotion.builder()
        .life(1200)
        .from(0, 7, 0).to(0, 0, 0)
        .spin(Rotation.Axis.Z, 3)
        .build();

displays.show(blade, thrown, where, observers);
```

## The client does the animating

A display is told a pose and how long it has to get there, and draws every frame
in between at the viewer's own frame rate. A two-second animation is about six
packets a viewer, and it is smooth on a server running at fifteen ticks a
second, because the smoothness never depended on the tick rate.

That is the whole reason this module exists. Moving something by sending its
position every tick is a twenty-frames-a-second animation that gets worse under
load, and it is what a particle trail pretending to be an object looks like.

## Not a replacement for particles

Displays give an effect weight, silhouette and shadow. Particles give it light,
smoke and atmosphere. Effects that look expensive are both: a shockwave of block
displays with dust around its edge reads as an impact, and either half on its own
reads as a tech demo.

## Written in a sequence

Any shape line becomes a display line by naming what it is drawn with:

```
[CIRCLE] NETHERITE_SWORD;as:item;radius:2;points:12
[CIRCLE] FLAME;radius:2;points:12
```

Everything the shape already understood still applies — `radius:`, `points:`,
`ticks:`, `interval:`, `rotate:`, `face:`, `scale:`, `y:` — because the geometry
never knew what it was being drawn with. `[DISPLAY]` is the same thing with one
point, for a single object.

### What to draw

| `as:` | The head of the line is |
| --- | --- |
| `item` | an item name, `NETHERITE_SWORD` |
| `block` | a block name, or a full block state like `oak_stairs[facing=east]` |
| `head` | a base64 texture, or `{killer}` or `{victim}` for a face |
| `text` | the line itself, in the usual formatting |

`{killer}` and `{victim}` are resolved from players who are on the server, so
they cost no lookup and never wait on anything. A player who has already gone
leaves a plain head rather than delaying the effect.

### How it moves

| Parameter | What it does | Default |
| --- | --- | --- |
| `life:` | seconds it exists | `1` |
| `from:x,y,z` | where it starts, relative to its point | `0,0,0` |
| `to:x,y,z` | where it ends | `0,0,0` |
| `rise:` | shorthand for `to:0,n,0` | |
| `gravity:` | falls at this many blocks per second squared, on top of the line | `0`; vanilla is about `32` |
| `ease:` | how the movement is spread over its life: `in`, `out`, `in_out` | `linear` |
| `spin:` | turns over its whole life | `0` |
| `axis:` | which axis it spins around: `x`, `y` or `z` | `y` |
| `size:` | size it starts at; one number, or `x,y,z` for a plate, a pillar or a blade | `1` |
| `size_to:` | size it ends at | same as `size` |
| `tilt:` `roll:` `turn:` | a fixed rotation, in degrees | `0` |
| `face_out:` | each point faces away from the centre | `false` |
| `pull:` | how far towards the centre each point travels: `1` reaches it, a negative number throws it outwards | `0` |
| `glow:` | outline colour: a name, `#rrggbb` or a `{palette}` token | none |
| `light:` | fixed light level, `0` to `15` | the light where it stands |
| `model:` | custom model data, for a resource pack model | none |
| `billboard:` | `FIXED`, `VERTICAL`, `HORIZONTAL` or `CENTER` | `FIXED`, `CENTER` for text |
| `hold:` | item display context: `0` the model itself, `5` head, `7` dropped, `8` item frame | `0` |

`gravity:` is added to the straight line rather than replacing it, so "throw it
four blocks east and let it drop" is two independent numbers instead of one
solved trajectory. An effect is choreography, not physics.

`face_out:` is what turns twelve swords into a ring of blades rather than twelve
swords lying the same way, and `pull:` is what sends them inwards: together they
are a ring that closes on whatever is in the middle.

`pull:` read backwards is the other half of the module's range. A tight sphere of
block displays with `pull:-8` is every fragment of a floor thrown outwards from
where it stood, and with `gravity:` on top of it, thrown outwards and falling —
which is what an explosion looks like and what a particle cannot be. `turn:` is next to it because which way a model's own
geometry points is a fact about that model: a resource pack whose blade reads
sideways is corrected with a number, not a rebuild.

### Making a movement land

A straight line at a constant rate is a thing sliding. Three parameters turn it
into a blow, and effects that feel weak are usually missing all three.

- **`ease:in`** holds the movement back and then spends it. A sword that covers
  a tenth of its distance in the first half of its life and the rest in the
  second half reads as a wind-up and a strike, from the same two numbers.
- **`gravity:`** is for debris, not for slams. It is added to the line rather
  than replacing it, so `from:0,9,0;to:0,1,0;gravity:40` descends eight blocks
  *and then* falls another twelve, straight through the floor. Aim a slam with
  `ease:in` and leave gravity for things thrown outwards, where a gentle drop
  reads as weight: half of `gravity` times the life squared is how far it falls,
  so a second in the air wants a `gravity:` of about `5`, not `50`.
- **`size:3,0.15,3`** is a block flattened into a plate. Grown outwards from
  nothing it is a shockwave; stretched the other way it is a pillar or a blade
  far too large to be an item. The models are blocks the server already has.
- **`size_to:`** slightly larger than `size:` on impact, or slightly smaller on a
  wind-up. A few percent is enough; it is the difference between an object
  arriving and an object being placed.

Then give the impact a second line at `ease:out` — dust, a ring, a block
shockwave settling — and the whole thing has a beginning, a middle and an end.

### Which way a model points

Worth knowing before writing rotations, because it is not guessable:

- An item model is a flat plate standing in the model's **XY plane**, facing
  **south**. A sword's tip points up and to the right, at forty-five degrees.
- So a blade is aimed with **`roll:`**, which turns it within its own plate, not
  with `tilt:`, which tips the plate over. `roll:225` puts a sword tip-down;
  `roll:45` puts it tip-up; `roll:135` lays it point-first along its own face.
- `face_out:` and `turn:` are applied **after** `roll:` and `tilt:`, so a blade
  is aimed in its own axes first and swung into place second. That is the order
  a ring needs; the other way round tips every blade of the ring differently.
- A block display is a cube and needs none of this.

`light:15` is worth setting on nearly every effect. A display lit by the world is
black at night and black in a cave, and an effect that disappears after sunset is
an effect players report as broken.

## Contracts

- **Compiled once.** The item, the quaternions and the poses are all worked out
  when the file is read. Playing spawns entities and sends packets, and parses
  nothing.
- **Nothing the server carries.** These are not entities: not ticked, not saved,
  not in any chunk. Two players standing together can be shown different ones.
- **Nothing can be left behind.** Because the server has no record of them,
  nothing else will clean them up. A display goes when its motion ends, when its
  plugin is disabled, or when the server stops, and there is no fourth case.
- **One timer, not one per display.** Every display on the server is moved from a
  single asynchronous driver. Forty displays in an effect cost forty entries in
  one queue, not forty scheduler entries.
- **A spin is cut up for you.** The client turns a display by the shortest arc,
  so a pose more than half a turn from the last one spins backwards. Spins are
  split into sixths of a turn; `spin:3` is correct without anybody knowing that.
- **PacketEvents or nothing.** Displays are packets and there is no fallback
  worth pretending about. Without it the module says so once and draws nothing.

## Performance

- A two-second animation is about six packets per viewer, whatever it looks like.
- Poses are shared: one ring of twelve blades builds one animation and turns it
  twelve times, which is a quaternion multiply per blade.
- The driver walks the live queue once a tick. For a display with nothing due
  that is a comparison of two longs.
- A file asking for two hundred turns is capped at forty-eight poses rather than
  sending two hundred packets.

## Source and tests

- Public: `display/` — `Displays`, `PluginDisplays`, `DisplayModel`,
  `DisplayMotion`, `DisplayKeyframe`, `DisplayHandle`, `Rotation`.
- Internal: `display/internal/` — the driver and the one class that names
  PacketEvents.
- In sequences: `util/sequence/internal/DisplayPaint`, `DisplayReader`, and the
  `Paint` seam that lets one shape be drawn either way.
- Tests: `RotationTest` asserts the quaternion composition as numbers;
  `DisplayMotionTest` asserts the poses a described movement produces, including
  that a spin is cut finely enough never to run backwards; `LiveDisplayTest`
  asserts when each pose is sent and that a display is always destroyed, once.
