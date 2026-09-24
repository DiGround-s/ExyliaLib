# VFX module

Effects built in Java whose shape is only known when they play: a warning
circle on the spot a player was standing, a beam from a mob to its target, a
number rising out of whatever was just hit. Displays, sounds, particles,
sequences and screen shake at millisecond offsets, played and cancelled as one.

It sits on top of [displays](displays.md) and [sequences](sequences.md) and
replaces neither. A sequence is written in a file and compiled once, so its
geometry is fixed; a `Vfx` is for the geometry a file cannot know.

## Using

```java
Vfx slam = Vfx.at(mob.getLocation()).nearby(48);

// 0.9 s wind-up: a circle whose fill reaches the edge when the blow lands.
Telegraphs.circle(slam, 0, mob.getLocation(), 5, 900, 0xFF9500);

DisplayModel rubble = DisplayModel.block(under.getBlockData()).light(15);
DisplayMotion kick = DisplayMotion.chain(
        DisplayMotion.builder().life(150).to(0, 0.6, 0).ease(Easing.OUT).build(),
        DisplayMotion.builder().life(300).from(0, 0.6, 0).to(0, -0.2, 0)
                .ease(Easing.IN).build());

slam.display(900, rubble, kick, spot)
    .sound(900, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.6f)
    .shake(900, 10, 2, 60);

VfxRun run = slam.play(plugin);
// The mob died during the wind-up:
run.cancel();
```

| Method | What it adds |
| --- | --- |
| `Vfx.at(origin)` | an empty effect anchored at a place |
| `.viewers(players)` / `.nearby(radius)` | who sees it, decided now |
| `.lod(boolean)` / `.lod()` | forces, or reads, the lower level of detail |
| `.display(ms, model, motion[, where])` | one display, at the origin or elsewhere |
| `.ride(ms, model, motion, entity)` | a display seated on an entity |
| `.beam(ms, from, to, model, thickness, lifeMs)` | a segment: one stretched block, or items repeated along it |
| `.sound(ms, [where,] sound, volume, pitch)` | a sound to the viewers |
| `.particle(ms, particle, where, count[, dx, dy, dz, speed, data])` | particles to the viewers |
| `.lines(ms, sequence, target)` | a compiled sequence, started at an offset |
| `.shake(ms, radius[, times, everyMs])` | the camera tilt of a hit, no hit |
| `.call(ms, runnable)` | anything else, on the origin's thread |
| `.play(plugin)` → `VfxRun` | plays it |
| `.displays()`, `.dropped()`, `.lengthMillis()` | what it will cost and how long it lasts |

`VfxRun` has `cancel()`, `isCancelled()`, `isDone()`, `endsAtMillis()` (wall
clock) and `viewers()`.

## How it plays

- **Grouped by tick.** Everything added is rounded to the nearest tick and each
  tick with something in it is one `runAtLocationLater` on the origin. A
  forty-piece effect over two seconds is a handful of scheduler entries. What
  falls on the first tick runs inline when the calling thread already owns the
  origin.
- **Folia.** The work runs on the origin's region. Displays, sounds, particles
  and shakes are packets to players, so a piece placed away from the origin
  (the far end of a beam) is still correct. `ride` reads the entity's position
  when it is added, which is the caster's thread; a mount that is gone by the
  time its piece is due is skipped.
- **Viewers resolved once.** Every piece goes to the list the effect was built
  with. A player who walks up mid-effect does not see the second half of
  something whose first half they missed. Players who left, or who are in
  another world, are skipped.
- **Nobody watching, nothing built.** With no viewers, `play` schedules nothing
  and the run is already done. That includes `call` pieces: this module is for
  visuals, and gameplay must never wait on one.
- **Cancel is one unit.** `cancel()` stops every group still to come, takes
  every display already spawned off every screen and cancels the sequences the
  effect started — each exactly once, from any thread, however many times it is
  called. A group already firing when the cancel lands takes its own displays
  down as it spawns them. Particles and sounds already sent stay sent.

## What it may cost

- **`max-per-effect`** (in `plugins/ExyliaLib/displays.yml`, 128 by default)
  applies to the whole effect, not to each piece. Once it holds that many
  displays, further ones are dropped as they are added and counted in
  `dropped()`. What is added first survives, so add the telegraph before the
  debris.
- **`max-viewer-displays`** still rules each display when it spawns, so a
  crowded server loses the tail of an effect, never its tick rate.
- **Level of detail.** `lod()` is true above `Vfx.LOD_VIEWERS` (15) viewers, or
  when forced. `Telegraphs` halves its plates then; a recipe building its own
  rings and debris should read it and do the same.

## Telegraphs

Warnings drawn on the ground. Every shape is an outline plus a fill: the
outline pops in and stays, the fill moves at a steady rate over the wind-up,
so the attack lands the moment the fill reaches the edge. That makes a
telegraph a timer, not just a mark.

| Method | Outline | Fill |
| --- | --- | --- |
| `circle(vfx, ms, centre, radius, fillMs, argb)` | a ring at the radius | grows from the middle to the edge |
| `ring(vfx, ms, centre, radius, fillMs, argb)` | a ring at the radius | closes from the edge to the middle |
| `cone(vfx, ms, apex, yaw, radius, degrees, fillMs, argb)` | two edges and an arc | sweeps out from the apex |
| `line(vfx, ms, from, to, width, fillMs, argb)` | two sides and two ends | runs from `from` to `to` |
| `cross(vfx, ms, centre, length, fillMs, argb)` | an X | its arms grow to full length |

- The plates are stained glass 0.02 thick, laid 0.03 above the ground, lit at
  15 and outlined in the tint. The glass is the dye closest to the tint.
- `yaw` is a Minecraft yaw (0 south, 90 west), so a mob's own facing can be
  passed straight in.
- A line is flat at `from`'s height, whatever the height of `to`.
- Everything is gone when the fill ends.
- The tint is `0xRRGGBB`. Resolve a palette token to it before calling, so the
  server's palette decides the colour.

## Indicators

Numbers that pop out of an entity and float away.

```java
Indicators indicators = Indicators.of(this);
indicators.damage(mob, 7.5, false); // "7.5" in {error}
indicators.damage(mob, 12, true);   // "✦ 12", bold, in {warning}
indicators.heal(mob, 4);            // "+4 ❤" in {success}
indicators.text(mob, component);    // any line
```

- Two numbers of the same kind within **300 ms** are one: the old one is taken
  down and the sum shown where it was. A merged damage number stays critical if
  any of its hits was. `text` lines merge by replacement, so a counter updating
  on every hit reads as one number changing. Damage and healing never merge
  into each other.
- No entity carries more than **4** at once; the oldest goes first.
- Seen by the players within **24** blocks by default; `range(blocks)` changes
  it for that plugin.
- Each number pops from 0.6 to full size in 80 ms, then rises 0.8 blocks and
  shrinks to 0.3 by 700 ms. It is lit at 15 and turns to face the viewer.
- Call from the entity's own thread, which is where a damage or heal event
  already runs.
- What each entity has on screen is held in a Caffeine cache that expires three
  seconds after its last number, capped at 10,000 entities.

## Screen shake

The camera tilt a hit plays, sent to each player about themselves: no damage,
no sound, no knockback, one packet. It is tilted away from the origin.

- In Java: `vfx.shake(ms, radius)` or `vfx.shake(ms, radius, times, everyMs)`.
- In a sequence: `[SHAKE] radius:10;times:2;every:0.08` (see
  [sequences.md](sequences.md)).
- How far the camera tilts is the viewer's own *Damage Tilt* accessibility
  setting, not ours, and a player who turned it off sees nothing. So the
  strength of a shake is how many tilts there are and how close together.
- Sent with `Player#sendHurtAnimation`, which is that packet and nothing else
  on Spigot, Paper and Folia, so it needs no PacketEvents and is safe from any
  thread.

## Motion helpers

- `DisplayMotion.chain(a, b, ...)`: movements played back to back, each with
  its own easing. Offsets stay what each segment wrote, so a segment starts
  where the previous one ended by being written that way. A segment that
  holds still (`DisplayMotion.still(ms)`) holds, and the next one only starts
  moving when the hold ends. Loops are not carried over.
- `Easing.BACK` (overshoots and settles), `Easing.BOUNCE` (lands and hops
  twice) and `Easing.ELASTIC` (springs past a few times). Read from files as
  `back`/`overshoot`, `bounce`, `elastic`/`spring`, so `ease:back` works on
  every display line. They are the same curves ragdolls and cameras use.

## Bodies that are not players

`RagdollSkin.flat(Map<RagdollPart, Integer>)` makes a skin with one colour per
part. Nothing is fetched or decoded, so a mob can come apart as a
[ragdoll](ragdolls.md) in the tick it dies. A part left out of the map is drawn
in neutral grey. Build one per kind of body and keep it.

## Source and tests

- Public: `display/Vfx`, `VfxRun`, `Telegraphs`, `Indicators`;
  `DisplayMotion.chain` and the new `Easing` values; `ragdoll/RagdollSkin.flat`.
- Internal: `packet/internal/ScreenShake`; `Steps.Shake` and the `[SHAKE]` case
  in `util/sequence/internal/SequenceCompiler`.
- Tests:
  - `VfxTimelineTest` drives the fake scheduler and a counting sink. It covers
    tick grouping, cancel destroying every display once, the per-effect cut,
    the server budget, no viewers, shake range and times, and the level of
    detail.
  - `TelegraphsTest` checks plate positions, tangents and fill motion as
    numbers.
  - `DisplayMotionChainTest` checks that times rise monotonically, a spin is
    still cut, a hold holds, and the overshoot curves.
  - `IndicatorsTest` covers the merge window, sticky crits, label replacement
    and the cap of four.
  - `RagdollSkinFlatTest` checks the flat skin.
  - `SequenceCompileTest` and `SequenceLineTest` cover `[SHAKE]` and the
    picker offering it.
