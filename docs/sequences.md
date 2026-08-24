# Sequence module

Choreographed effects, written in configuration. A ring of flame, a sound, a
pause, an explosion — in that order, from a list of strings. Since 1.30.0.

Entry point: `net.exylia.lib.util.sequence.Sequences`.

## Using

```java
private PluginSequences sequences;
private Sequence onKill;

@Override
public void onEnable() {
    sequences = Sequences.of(this);
    onKill = sequences.compile(config.getStringList("effects"), "ember_burst");
}

// On every kill:
sequences.play(onKill, SequenceTarget.at(victim.getLocation()).by(killer));
```

```yaml
effects:
  - '[CIRCLE] FLAME;radius:1.5;points:24'
  - '[SOUND] ENTITY_BLAZE_DEATH;1.5;0.8'
  - '[DELAY] 0.15'
  - '[EXPLOSION]'
```

## When to use this, and when to use `Effects`

| | Use |
| --- | --- |
| One title, one sound, one particle, declared in a record | [`Effects`](effects.md) |
| A list of things in order, with pauses and shapes | this |

A menu's open sound is an `EffectConfig`. A hundred-line firework display is a
`Sequence`.

## Effects that may or may not play

Since 1.57.0.

A sequence says **what** happens. An `EffectEntry` says whether it happens, to
whom, and when:

```java
EffectEntry crit = EffectEntry.of(List.of(
                "[SOUND] ENTITY_PLAYER_ATTACK_CRIT;1;1.4",
                "[PARTICLE] CRIT;count:20"))
        .name("Critical hit")
        .chance(25.0)
        .condition("%player_level% >= 10")
        .nearby(12.0)
        .build();

sequences.play(mine.breakEffects(), SequenceTarget.at(block.getLocation()).by(miner));
```

| Field | Means |
| --- | --- |
| `lines` | what plays, in the notation above |
| `name`, `icon` | what an editor shows |
| `chance` | the percentage chance of playing at all |
| `condition`, `permission` | who it may play for |
| `priority` | higher plays first; equal priorities keep their written order |
| `delayTicks` | how long after the trigger **this one** starts |
| `radius` | how far it reaches |

`delayTicks` is not a `[DELAY]` line. A delay inside the sequence holds up the
lines after it; this holds up the whole effect and lets the ones beside it play
on time.

`radius` says everything the ExyliaCommons `EffectScope` enum said, in one
number: `0` or less is the player it is about and nobody else, a finite number
is everyone within that many blocks, and `EffectEntry.WHOLE_WORLD` is everyone
in the world it happens in. One number rather than an enum *and* a number,
because an enum whose meaning is "read the other field" is two ways to say one
thing and a way to say a contradiction.

**Permission and condition run before the dice.** Who *may* see something does
not depend on luck, and asking in the other order is what made a rare effect
report "it did not come up" when the truth was a misspelled permission.

**Compiled once.** The lines are compiled the first time they play and kept,
keyed by the lines themselves, so an effect that fires on every block break in a
mine parses nothing after the first one. An edited entry is a different list and
misses the cache, which is exactly right.

**A broken condition is reported once, not once per play.** An effect on a mine
fires thousands of times; a console line per firing is how a log becomes
unreadable in the minute somebody most needs to read it.

### Why ten fields and not forty

ExyliaCommons' effect entry carried a field for every property of every type it
knew — eight types, forty fields, and a `switch` that had to grow for a ninth.
Its own javadoc said it mirrored `RewardEntry`.

Splitting it in two is what makes this small: the payload is a sequence, which
already expresses **every one of those eight types and five more** — lightning,
explosions, block breaks, commands and shapes — and the gating is the ten fields
above. The value never grows again, and the payload already does more than the
forty fields did.

### Stored, and read from what commons stored

```java
List<EffectEntry> effects = EffectCodec.decode(mine.effectsJson());
String stored = EffectCodec.encode(effects);
```

`decode` reads **both shapes**. A row carrying a `type` key is an ExyliaCommons
row and is translated on the way in — its particle, sound, potion, firework,
title, action bar, message or sequence becomes the line that plays the same
thing, and its gating comes across untouched. Nothing has to be re-authored.

The one unit conversion is the title: commons stored its three times in ticks
and a `[TITLE]` line writes them in seconds, because a file that says `0.5`
means half a second everywhere else in it.

Translation is one way on purpose. Writing the old form again would pin every
effect back to the eight types it knew, which is the ceiling this replaced. A
type this library cannot play keeps its gating, arrives with nothing to play and
is reported: the row said something, and losing it silently is worse than showing
it empty.

An empty list stores as `NULL` rather than `[]`, like every other codec here.

### Editing them

```java
sequences.editor(mine.breakEffects())
         .title("{primary}&lBREAK EFFECTS")
         .onSave(edited -> mines.save(mine, edited))
         .open(player);
```

The [editor](editors.md) screen. One form per row — the gating, and a tall box
of lines — rather than the type-select menu and eight per-type screens commons
needed, because the payload is text.

## Contracts

- **Compiled once, played many times.** `compile` resolves every name, parses
  every number and runs every shape's trigonometry. Playing is arithmetic and
  packets. ExyliaCommons re-parsed the strings and re-derived the points on
  every play, on the region thread.
- **A bad line costs its own line.** It is reported through `Debug` naming the
  sequence, and the rest still plays. An effect never reaches the event that
  triggered it: a kill effect cannot cancel a death.
- **Anchored to a location, not a player.** A kill effect plays at the victim's
  feet while the killer owns it.
- **Runs on the thread that owns the location**, through `Tasks`, so it is
  correct on Folia with no branching.
- **Instant sequences schedule nothing.** No delays and no animation means it is
  played inline, in the calling tick.
- **Cancellable.** `play` returns a `SequenceRun`; `cancel()` stops the steps
  that have not run and the animation frames already scheduled.
- **Nothing survives its plugin.** Disabling a plugin cancels its runs before
  the task module releases its scheduler.

## The syntax is ExyliaCommons'

Deliberately identical — token names, parameter names, defaults and positional
arguments — so a migrating plugin edits no configuration files.

### Tokens

| Token | Arguments |
| --- | --- |
| `[DELAY] 0.15` | seconds |
| `[PARTICLE] FLAME` | `count:` `offset:x,y,z` `speed:` `y:` `color:` `size:` `block:` |
| `[SOUND] NAME;volume;pitch` | also `volume:` `pitch:` |
| `[LIGHTNING]` | `volume:` `pitch:` — flash, sparks and thunder; no strike, no fire, no damage |
| `[EXPLOSION]` | `count:` `y:` |
| `[FIREWORK]` | `color:` `fade:` `type:` `trail:` `flicker:` `power:` |
| `[BLOCK_BREAK] STONE` | `count:` `offset:` `y:` |
| `[POTION] speed;100;1` | also `duration:` `amplifier:` |
| `[TITLE] title;subtitle;in;stay;out` | times in **seconds** |
| `[ACTION_BAR] text` | |
| `[COMMAND] give {player} ...` | `{player}` `{world}` `{x}` `{y}` `{z}` |
| `[MESSAGE] text` | a chat line, since 1.57.0; centre it with `<center>` like any other message |

### Shapes

`CIRCLE` `SPHERE` `BEAM` `SPIRAL` `DOUBLE_HELIX` `TORNADO` `STAR` `CAGE` `DISC`
`VORTEX` `WAVE` `CROSS` `GALAXY` `TORUS` `BURST` `PYRAMID` `RING_PULSE` `WINGS`
`ARCH` `CLAW`

Every shape takes `color:` `size:` `count:` `ticks:` `interval:` `y:` on top of
its own parameters. `ticks:1` draws the whole shape at once; above that it draws
itself over time.

## What was fixed

Four behaviours are deliberately different from ExyliaCommons. All four were
bugs.

- **`SPHERE` ignored `y:`.** Nineteen of the twenty shapes honoured it; the
  sphere was nailed one block above the anchor. It now honours `y:`, and its
  default height is still one block, so an existing file draws it unchanged.
- **`TORUS` added a block** on top of whatever `y:` said. Same fix, same
  default, same picture from an unchanged file.
- **A dust particle with no `color:` drew nothing at all**, silently. It is now
  drawn white — visible, obviously wrong, and it leads you to the line.
- **`STAR` divided by `points:`** with no floor, so `points:0` produced
  infinities and drew at the world origin. Every count is now floored at one.

## What was added

- **`color:` takes a palette token.** `color:{primary}` follows `colors.yml`
  like everything else a player sees. Commons took decimal triples only, so
  every coloured effect hardcoded a colour the server owner could not change.
  `#rrggbb` and `R,G,B` still work.
- **`rotate:` and `face:`.** A fixed rotation in degrees, or `face:true` to turn
  the shape with whoever triggered it.
- **`scale:`.** Resizes a whole shape without editing its every parameter.
- **`strands:` on `DOUBLE_HELIX`** — Commons hardcoded two.
- **`points:` on `DISC`** — Commons derived it from the radius, so a wide disc
  was always expensive.
- **`flicker:` on `FIREWORK`.**
- **Custom shapes.** `sequences.shape("heart", args -> ...)` registers a token
  that inherits colour, animation, rotation, scaling and visibility for free.
- **Sounds by key.** A resource pack's own sound can be written in the file.
- **`durationMillis()`** — how long a sequence lasts, known without playing it,
  animation included. Commons summed only the explicit delays, so a preview
  handed the player back mid-animation.
- **Cancellation.** Commons had no handle at all; a sequence always ran to the
  end, whatever happened to the arrow, menu or plugin that started it.
- **A visibility predicate** on the target, and `onlyTo(player)` for previews.
  All three consumer plugins wrote their own.
- **The radius is a value.** Commons hardcoded 32 blocks.

## Performance

- Parsing and trigonometry happen at load. A twenty-line kill effect played by a
  full arena costs no parsing at all.
- **Animation frames are grouped by tick.** Commons scheduled one task per
  point, so a 600-point animated torus scheduled 600 tasks, most landing in the
  same tick. Points that share a tick now share one task.
- **Observers are resolved once per frame**, not once per point. Asking per
  point made a shape's cost quadratic in its detail.
- A firework detonates in the tick it spawns, so the entity never ticks and
  never needs hiding from distant players.

## Source and tests

- Public: `util/sequence/` — `Sequences`, `PluginSequences`, `Sequence`,
  `SequenceTarget`, `SequenceRun`, `SequenceStep`, `Shape`.
- Internal: `util/sequence/internal/`.
- Tests: `ShapeGeometryTest` asserts the maths of every shape as numbers,
  including the fixes and every count set to zero at once;
  `SequenceCompileTest` compiles real lines from ExyliaArrows and
  ExyliaKillEffect unchanged.
