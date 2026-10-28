# Ability module

Triggered abilities: "when this happens to whoever holds it, if these hold,
roll, then do these". One engine for enchantments, classes, mob skills and
specials, compiled from YAML once and fired from events with nothing parsed on
the hot path. Includes `AreaBreaks`, the area-break engine (3x3, 3x3x3, veins,
trees) any plugin can call directly, and an in-game ability editor. Available
since 1.385.0.

Entry points: `net.exylia.lib.ability.Abilities` and
`net.exylia.lib.ability.AreaBreaks`.

## Using

```java
private PluginAbilities abilities;

@Override
public void onEnable() {
    abilities = Abilities.of(this);

    // The plugin's own vocabulary, registered before compiling.
    abilities.effect("bleed", BleedEffect::new);
    abilities.condition("in_mine", args -> ctx -> mines.contains(ctx.location()));
    abilities.trigger("MINE_DROP");
    abilities.sequences(name -> mySequences.get(name));   // for `sequence: <name>`

    // Load: compile once, never on a proc.
    Ability bleed = abilities.compile(section, problem -> getLogger().warning(problem));

    // Who holds what. Cached per player, asked again when it may have changed.
    abilities.holdings(player -> enchants.heldBy(player));   // List<Held>

    // Rules and observation.
    abilities.gate((ctx, ability) -> rules.factor(ctx, ability));
    abilities.onOutcome(outcome -> metrics.record(outcome));
}

// A trigger of the plugin's own:
abilities.fire(player, "MINE_DROP", ctx -> ctx.with(MY_DROPS, drops).block(block));

// After anything the library cannot see (an item renamed, an enchant added):
abilities.refresh(player);
```

| Type | Role |
| --- | --- |
| `Abilities` | `of(plugin)`: one `PluginAbilities` per plugin; released on disable |
| `PluginAbilities` | compile, holdings, refresh, refreshAll, held, effect, condition, scale, trigger, sequences, fire, gate, onOutcome, projectileLevels, stamp, carried, editor |
| `Ability` | immutable compiled definition: `id`, `trigger`, `chance(level)`, `cooldown(level)`, `cooldownKey`, `conditions`, `effects`, `chain`, `every`, `source` |
| `Held` | record `(Ability ability, int level, @Nullable EquipmentSlot slot)` |
| `AbilityContext` | holder, level, trigger, event, victim, attacker, location, block, scale, typed `ContextKey<T>` values, drops, `transformDrops`, `transformDropsLast` (*1.387.0*), `deliver` |
| `AbilityEffect` | `apply(context, report)`, `revert(context)`; built by `AbilityEffect.Factory` from `AbilityArgs` |
| `AbilityCondition` | `test(context)`; built by `AbilityCondition.Factory` from `AbilityArgs` |
| `AbilityScale` | what `bonus-damage: { by }` multiplies by; registered with `scale(name, factory)` (*1.387.0*) |
| `AbilityArgs` | what one line wrote: `value`, `string`, `number`, `integer`, `flag`, `formula`, `seconds`, `duration`, `strings`, `problem` |
| `AbilityReport` | what a proc did: damage, healing, targets, drops |
| `AbilityOutcome` | record per attempt for metrics; ids only, never a `Player` |
| `AbilityGate` | `factor(context, ability)`: 0 denies, 1 keeps, other values scale |
| `Triggers` | the built-in trigger names |
| `ContextKey` | typed context values; `DROPS` and `DROPS_TO_INVENTORY` built in |
| `AreaBreaks`, `AreaBreak`, `AreaShape` | the area-break engine |

## YAML

```yaml
trigger: ATTACK
chance: 6 + level * 4        # percent; a formula of level, 15% also reads
cooldown: 8s                 # 8s, 500ms, 2m, a number of seconds, or a formula of level in seconds
chain: false                 # may its damage fire ATTACK/HIT_TAKEN again
every: 2s                    # INTERVAL only: the period, whole seconds, 1 at least
conditions:
  - target: player
  - not: blocking
  - placeholder: '%player_level% >= 10'
effects:
  - damage: { target: victim, amount: 0.5 * level, every: 0.5s, times: 4 }
  - potion: { target: victim, type: SLOWNESS, level: 1, duration: 2s }
  - sequence: bleed
  - action: 'enchants:notify bleed'
```

`compile(section, problems)` takes the section's name as the id;
`compile(id, map, problems)` takes a map, as a YAML list of abilities gives
them. The id is also the cooldown's key, so keep it stable across reloads.
`compile` answers `null` only when the trigger is missing or unknown.

**Nothing is dropped.** Unknown keys, effects and conditions are reported to
`problems`, once per compile, and kept in `Ability.source()`, so the file and
the editor keep them. An unknown effect is skipped, an unknown condition holds.
An unreadable formula is reported and the key's default is used; arithmetic
that has no answer at some level (a division by zero) falls back the same way.

Every number below marked *formula* is a [compiled formula](util.md) of
`level`. A *duration* is `8s`, `500ms`, `2m`, a number of seconds or a formula
in seconds.

## Triggers

| Trigger | Fired when | Context |
| --- | --- | --- |
| `ATTACK` | the holder hits a living entity in melee | event, victim |
| `HIT_TAKEN` | the holder is hurt by an entity | event, attacker (a projectile's shooter) |
| `KILL` | the holder kills | event, victim, `DROPS` (the death's own list) |
| `DEATH` | the holder dies | event, attacker |
| `BOW_SHOOT` | the holder shoots a bow or crossbow | event |
| `PROJECTILE_HIT` | the holder's projectile lands; with `projectileLevels(true)`, the levels the shot left the bow with | event, victim, block, location |
| `BLOCK_BREAK` | the holder breaks a block; never for an area break's extra blocks | event, block |
| `FISH` | the holder catches something | event, victim (a caught entity) |
| `JUMP` | the holder jumps (the jump statistic, Spigot and Paper alike) | event |
| `SNEAK` | the holder starts sneaking | event |
| `LOW_HEALTH` | a hit takes the holder from above 30% of max health to 30% or less | event, attacker |
| `EQUIP` / `UNEQUIP` | a refresh finds the holding added / removed | — |
| `INTERVAL` | every `every` seconds while held; one timer per player | — |
| `PASSIVE` | sugar: the effects apply on equip, `sustain` every 10 seconds while held, and `revert` on unequip | — |

Bukkit triggers listen at `HIGHEST`, ignoring cancelled events where the event
can be cancelled. A plugin adds its own with `trigger(name)` and fires them with
`fire(player, name, setup)`.

## Targets

Effects that land on someone read `target` and, for `nearby`, three more keys.

| `target` | Who |
| --- | --- |
| `self` | the holder |
| `victim` | the victim, if any |
| `attacker` | the attacker, if any |
| `nearby` | living entities around the holder: `radius` (5), `filter` (`enemies`, `allies`, `players`, `mobs`, `all`; `enemies` by default), `max` (16) |
| `block` | nobody; effects that use a place use the block |

Allies are the holder's clan and allied clans (`Clans`) and their own tamed
pets. Armour stands are never `nearby` targets.

## Conditions

| Condition | Keys | Holds when |
| --- | --- | --- |
| `target` | value: `player`, `mob`, `monster`, `animal`, `any` or an entity type, or a list | the other party (victim, else attacker) is one of them |
| `health` | `below`, `above` (`30%` of max, or absolute), `of: self \| victim` | the subject's health is in the band |
| `in-combat` | value: `true` / `false` | the holder's combat tag (`Combat.isTagged`) matches |
| `sneaking`, `sprinting`, `blocking`, `gliding`, `in-water` | value: `true`, `false`, `self` or `victim` | the subject is (or with `false` is not) in that state; `self` by default |
| `world` | value: a name or a list | the location is in one of them |
| `region-policy` | value: a boolean policy name or a list (`pvp`, `build`, `break`, `interact`, `fall_damage`, `item_drop`, `item_pickup`, `entry`, `exit`) | every one resolves to true at the location (`Regions.resolve`) |
| `mode` | value: a game mode or a list (`survival`, `creative`, `adventure`, `spectator`); or `id`: a [mode](modes.md) id or a list (*1.386.0*) | the holder's game mode is one of them; with `id`, `Modes.current(holder)` is one of them |
| `block` | `types` (materials), `tags` (block tags, `minecraft:logs`); or a list where `#minecraft:logs` is a tag (*1.386.0*) | the context block is one of them; no block never holds. Tags expand into an `EnumSet` at load |
| `permission` | value: a node | the holder has it |
| `placeholder` | value: `'%player_level% >= 10'` | the shared comparison holds; resolves placeholders on every attempt, the one condition that does; an unreadable one is reported once and holds |
| `not` | value: a condition name, or a condition line | it does not hold |
| `any` | value: a list of condition lines | at least one holds |

## Effects

| Effect | Keys (defaults) | What it does |
| --- | --- | --- |
| `bonus-damage` | `amount` formula (10), `mode` `percent \| flat` (percent), `by` (none), `max` formula (1000) | raises the trigger's hit itself (*1.387.0*): armour still applies, knockback stays vanilla, nothing extra lands. The bonus is `min(max, amount * units)`, `units` 1 or what the `by` [scale](#hit-shaping) says |
| `armour-pierce` | `ignore` formula, percent (100), `of` `armor \| protection` (armor) | that share of what armour points, or Protection enchantments, take off the hit is ignored, applied after every ability changed the hit (*1.387.0*) |
| `damage` | `target` (victim), `amount` formula (1), `mode` `flat \| percent \| true` (flat), `every` duration (0), `times` (1) | hurts; `percent` of max health, `true` ignores armour; `every`/`times` spread it over time on the target's scheduler with the proc's numbers |
| `heal` | `target` (self), `amount` formula (1), `mode` `flat \| percent` | restores health |
| `absorption` | `target` (self), `amount` formula (2), `max` (20) | adds absorption up to `max` |
| `lifesteal` | `amount` formula, percent (10) | heals the holder that share of the hit's final damage |
| `knockback` / `pull` | `target` (victim), `strength` formula (1), `vertical` (0.35) | pushes away from / towards the holder |
| `launch` | `target` (victim), `strength` formula (1) | straight up |
| `ignite` | `target` (victim), `duration` (3s) | sets on fire, never shortening a longer fire |
| `freeze` | `target` (victim), `duration` (3s) | powder-snow frost |
| `potion` | `target` (victim), `type`, `level` formula (1), `duration` (5s) | a potion effect; on `PASSIVE` with no `duration` it lasts 30 seconds, is topped up every 10 seconds while held and removed on unequip (never infinite, see *Contracts*) |
| `cleanse` | `target` (self), `types`: `negative`, `all` or names (`negative`) | removes potion effects |
| `cancel` | — | cancels the trigger's event |
| `drops` | `multiplier` formula, `to: inventory` | multiplies the firing's drops once, through the `drops` modifiers; `to: inventory` sends delivered drops to the holder |
| `xp` | `multiplier` formula, or `amount` formula | multiplies the event's experience (kill, block), or gives `amount`; through the `xp` modifiers |
| `money` | `amount` formula | pays the holder through `Modifiers.pay` |
| `smelt` | — | turns the drops into their furnace results, once |
| `break-area` | `shape` `plane \| cube \| vein \| column` (plane), `radius` formula (1), `depth` formula (0), `max` formula (64, vein), `length` formula (8, column), `vertical` (false, column; *1.387.0*), `budget` (64), `compat-events` (0), `exclude` (materials), `only` (what `block` takes, *1.386.0*), `to: inventory` | an [area break](#area-breaks) around the trigger's block; its drops join the firing's drops |
| `teleport` | `target` (self), `to` `forward \| victim \| attacker \| block` (forward), `distance` formula (5) | players through `Teleports` with no warm-up and a safe landing; mobs directly |
| `sequence` | value: a name, or `lines` + `target` (self) | plays the plugin's named sequence (`sequences(lookup)`) or lines compiled once |
| `vfx` | `target` (victim), `indicator` (true), `shake` radius (0) | floats this proc's damage or healing over the target; tilts nearby cameras |
| `action` | value: `'namespace:id args'` | runs an ExyliaLib action as the holder, compiled at load |
| `cooldown-reset` | value: an ability id or `all` | clears the holder's cooldowns of abilities they hold, this one excepted |

Effects that work out an amount multiply it by the gate's factor
(`ctx.scale()`): damage, heal, absorption, lifesteal, knockback, pull, launch,
drops, xp, money, bonus-damage, armour-pierce. Chance and cooldown are not
scaled.

`potion` resolves its type through the server's registry; with no server (a
unit test) it compiles anyway and resolves on its first proc (*1.387.0*). On a
server an unknown type is still reported at load.

### Hit shaping

*1.387.0.* `bonus-damage` and `armour-pierce` change the trigger's own
`EntityDamageEvent` (ATTACK, HIT_TAKEN, or a plugin trigger fired with a damage
event), so armour, knockback and the hit's other plugins see one hit.

```yaml
- bonus-damage: { amount: 6 + level * 6 }                         # +12% at level 1
- bonus-damage: { mode: flat, amount: level * 3 }
- bonus-damage: { by: missing-health, amount: level * 15 }        # all of it at no health left
- bonus-damage: { by: sprint, amount: 2 + level, max: 6 + level * 6 }   # a plugin's scale
- armour-pierce: { ignore: 100, of: armor }
```

`by` built in: `missing-health` (0 at full health, 1 at none) and `health-gap`
(0 when the victim's max health is the holder's or less, 1 at twice it or
more). A plugin adds its own before compiling; the factory reads any key of its
own from the same line:

```java
abilities.scale("combo", args -> {
    long window = args.duration("window", Duration.ofSeconds(3)).toMillis();
    return ctx -> combos.hit(ctx.holder(), window);      // units: hits stacked
});
```

**Pierces apply last.** `setDamage` recomputes armour, so a bonus after a
pierce would undo it. Pierces are noted per event (the largest share of each
kind wins) and applied after the library's damage handler fired ATTACK and
HIT_TAKEN, or, for a plugin trigger, when that `fire` ends. A plugin listener
that changes the damage later, at `HIGHEST`, recomputes armour again.

### Drops, once

On `BLOCK_BREAK`, the first effect that asks for `ctx.drops()` takes the
block's drops over: the event stops dropping them and the library spawns the
list once, after every ability ran, at the block or into the holder's
inventory. `smelt` and `drops` register a transform with
`ctx.transformDrops`, applied at that moment to everything, area-break drops
included, whatever order the effects ran in. On `KILL` the list is the death's
own and transforms apply at once.

`ctx.transformDropsLast` (*1.387.0*) runs after every `transformDrops`, also
on `KILL` (when the firing ends) and on area-break drops delivered on later
ticks: how a cap that must see the final amounts (a curse undoing Fortune and
multipliers) goes after every multiplier, whichever effect ran first.

### Projectile levels

*1.387.0.* By default `PROJECTILE_HIT` fires what the shooter holds when the
shot lands, so switching to a sword mid-flight changes the arrow.
`abilities.projectileLevels(true)` stamps the shooter's PROJECTILE_HIT holdings
(ability ids and levels, in the projectile's data) on BOW_SHOOT, and the hit
fires those, each resolved to the ability last compiled under its id. A
projectile with no stamp falls back to the current holdings. `stamp(projectile,
shooter)` does it for a projectile a plugin launches itself; `carried(projectile)`
reads it back, `null` when there is none.

## Order of an attempt

1. **Gate**: `0` or less stops here (`GATED`).
2. **Conditions**, in order; the first that fails stops (`CONDITIONS`).
3. **Cooldown**: `Cooldowns` under `CooldownScope.player(holder)` and
   `ability.cooldownKey()`; active stops (`COOLDOWN`).
4. **Chance**: 100 or more never rolls; 0 or less never procs (`CHANCE`).
5. The **cooldown starts**, then the **effects** run (`PROC`). A miss never
   locks the ability.

`onOutcome` is told every attempt; outcomes are built only while somebody
listens.

## Contracts

- **Threads.** Triggers run on the event's thread, the holder's region on
  Folia; `fire`, `refresh` and `held` are called there too. `refreshAll`
  schedules each player on their own thread. An effect reaching an entity owned
  by another region goes through that entity's scheduler (`ctx.at`). Nothing
  blocks and nothing touches a database.
- **Hot path.** Holdings are cached per player as an array per trigger: a
  trigger nobody holds costs one map read, and each listener asks that first.
  Formulas, conditions and effects are compiled objects.
- **Holdings** are asked again on join, held-slot change, hand swap, drop,
  pick-up, item break, inventory close and, on Paper, armour change and a
  change to the held, armour or offhand slot (an item replaced in place by
  `/clear` + `/give` or a kit; classes loaded only there), coalesced to once per tick; on respawn and after milk
  everything held equips again. A refresh that answers the same list fires
  nothing; changes fire `UNEQUIP` then `EQUIP`. `refresh`, and every refresh
  the library schedules, does nothing for a player who is offline or no longer
  valid (dead, removed) and creates no cache entry for them.
- **Passive potions are finite.** An untimed `PASSIVE` `potion` is applied for
  30 seconds, never infinite, and the player's existing timer (the one INTERVAL
  uses, one per player, every second) calls `sustain` every 10 seconds to top
  it back up while it is still the one granted. If the server dies before the
  unequip, the saved effect runs out within 30 seconds of the next login
  instead of staying forever. Infinite effects left by versions before 1.387.1
  are removed on the next unequip of the same ability. A custom effect keeps a
  PASSIVE state alive the same way by overriding `AbilityEffect#sustain`.
- **No proc chains.** Damage an ability deals (`ctx.damage`, `damage`) never
  fires `ATTACK` or `HIT_TAKEN` again unless the ability says `chain: true`,
  and chains stop at three levels deep. The guard is per thread and shared by
  every plugin.
- **Leaks.** Caches, INTERVAL timers and area breaks are keyed to the plugin
  and released on quit and on disable; `PASSIVE` effects revert then. A PASSIVE
  holding keeps a per-player timer (shared with INTERVAL) for `sustain`.
- **Reload.** Compile again and call `refreshAll()`: old holdings unequip, new
  ones equip. Damage over time already running finishes with its old numbers.
  Palette reloads: not applicable, nothing here derives from the palette.

## Writing an effect

```java
final class BleedEffect implements AbilityEffect {
    private final Formula amount;

    BleedEffect(AbilityArgs args) {
        this.amount = args.formula("amount", "1");     // read every key here
    }

    @Override
    public void apply(AbilityContext ctx, AbilityReport report) {
        LivingEntity victim = ctx.victim();
        if (victim == null) return;
        double hit = amount.applyOr(0, ctx.level()) * ctx.scale();
        ctx.damage(victim, hit);                       // guarded, on the victim's thread
        report.damage(hit).target();
    }
}
```

A key nobody read while compiling is reported as unknown, so read every key in
the constructor. Throw `IllegalArgumentException` for arguments that cannot be
used: the line is reported and skipped. The editor asks the factory which keys
it reads by compiling it with nothing written, so a factory must not fail on an
empty line before it has read its keys.

## The editor

```java
abilities.editor(enchant.abilities())
        .title("{primary}&lABILITIES {letters_black}» {highlight}Bleed")
        .onSave(edited -> enchants.save(id, edited.stream().map(Ability::source).toList()))
        .open(player);
```

A `ListEditor<Ability>` on the [editors](editors.md) engine. Adding asks the
trigger. A row click asks which part: *Mechanics* (trigger, chance, cooldown,
period, chain) is one prefilled form; *Conditions* is one box, a condition per
line as written in YAML (`target: player`); *Effects* is a list of its own
where adding asks which effect and the form shows exactly the keys that effect
reads, plus any the line already has. Nothing is written until the viewer
saves; the plugin writes each `Ability.source()` back to its file. Unknown keys
are shown and kept.

## Area breaks

```java
AreaBreaks.of(plugin)
        .around(player, event.getBlock(), AreaShape.plane(1, 0))   // 3x3
        .check((breaker, block) -> worldGuard.canBreak(breaker, block))
        .toInventory(telekinesis)
        .onFinish(summary -> metrics.blocks(summary.broken()))
        .start();
```

| Shape | Blocks |
| --- | --- |
| `AreaShape.plane(radius, depth)` | a square `2r+1` wide across the way the breaker looks (flat when looking up or down past 45 degrees), `depth + 1` layers along it |
| `AreaShape.cube(radius)` | a cube `2r+1` wide |
| `AreaShape.vein(max)` | the connected blocks (26 neighbours) of the origin's material, at most `max` |
| `AreaShape.column(length)` | a line `length` long, origin included, away from the breaker along the way they look (*1.387.0*) |
| `AreaShape.column(length, true)` | the same, always straight down, or up when the breaker looks above the horizon |

Every shape plans at most `AreaShape.LIMIT` (4096) blocks. The origin is never
in the plan: its own event breaks it.

| `AreaBreak` | |
| --- | --- |
| `budget(n)` | blocks per tick (`DEFAULT_BUDGET` 64); the rest continues next tick on the breaker's scheduler |
| `compatEvents(n)` | fire a real `BlockBreakEvent` for up to `n` blocks (0, off); a cancelled one is not broken |
| `exclude(materials)` | never broken, on top of spawners, containers, beacons and portal frames |
| `only(materials)` | only these break, after `exclude`; empty lifts it (*1.386.0*) |
| `replace(Replace)` | changes blocks instead of breaking them (*1.387.0*); see below |
| `check(Check)` | per block, after the region check; how WorldGuard and claims get a say |
| `deliver(consumer)` | takes the merged drops once instead of the default delivery |
| `toInventory(boolean)` | default delivery to the inventory, overflow dropped once |
| `sequence(sequence)` | one sequence at the origin when it ends |
| `onFinish(consumer)` | `Summary(planned, broken, items, experience)` |
| `start()` | plans and breaks; answers how many blocks were planned; once |

What makes it cheap, and the contract other plugins rely on:

- **Planned once** into packed `long` coordinates in one primitive array: no
  `Location` or `Block` per planned block. Air, liquids, unbreakables
  (hardness below 0), the exclusions and blocks the tool is not effective on
  (`BlockData.isPreferredTool`, cached per material for the operation) are left
  out while planning. A vein is a bounded breadth-first walk over the plan
  itself with a primitive visited set: no recursion.
- **No event per block by default.** Protection is one `Regions.resolve(...,
  break)` per block plus the caller's `check`. `compatEvents` is for jobs and
  skills plugins, capped; while those events fire `AreaBreaks.isBreaking()` is
  true, and abilities ignore them, so an area break never sets off `BLOCK_BREAK`
  abilities.
- **Budget.** A 3x3x3 (26 blocks) or a 64-block vein ends in the tick it
  started; more continues on the breaker's scheduler.
- **Merged.** Drops merge by similarity into one list and land once at the end
  (`deliver`, the inventory or the origin, split into stacks); experience is
  one orb (vanilla's ore values, none with Silk Touch); the tool loses
  durability once, Unbreaking rolled per block in one pass.
- **Physics** only where a neighbour must notice: a block next to a liquid or a
  falling block changes with physics, every other without.
- **Folia.** Only blocks owned by the current region are touched; the rest are
  skipped.
- **Endings.** A breaker who quits, and a plugin that disables, end the
  operation; the drops collected so far land at the origin.

### Replace mode

*1.387.0.* `replace(AreaBreak.Replace)` changes each planned block instead of
breaking it, with the same planning, budget, region check and `check`: the
block becomes what the replacer answers (`null` leaves it), without physics.
Nothing drops, no experience, no compat events, no durability, and the tool's
preferred blocks do not filter the plan. `Summary.broken` counts the blocks
changed.

```java
AreaBreaks.of(plugin).around(player, block, AreaShape.cube(1))
        .only(tillable)
        .replace(AreaBreak.Replace.till())
        .start();
```

| Built-in | Becomes |
| --- | --- |
| `Replace.till()` | grass, dirt and dirt path with air above: farmland |
| `Replace.path()` | grass, dirt, coarse dirt, podzol, mycelium and rooted dirt with air above: dirt path |
| `Replace.strip()` | logs, wood, stems and hyphae: their `STRIPPED_` variant, axis kept |

A plugin that hands breaks inside its own regions to a bulk path of its own (a
mine) does so in its `check` or before calling `start`.

## What it deliberately does not do

- Read item lore or enchantments: who holds what is the plugin's `holdings`.
- Store anything, or show cooldowns: `Ability.cooldownKey()` is there for a
  tooltip to read `Cooldowns`.
- Parse placeholders in formulas: variables are `level` only.
- Fire `BLOCK_BREAK` for blocks an area break takes.

## Where the code lives

| | |
| --- | --- |
| Public API | `ability/Abilities`, `PluginAbilities`, `Ability`, `Held`, `AbilityContext`, `ContextKey`, `AbilityEffect`, `AbilityCondition`, `AbilityArgs`, `AbilityReport`, `AbilityOutcome`, `AbilityGate`, `AbilityScale`, `Triggers`, `AreaBreaks`, `AreaBreak`, `AreaShape`; `util/Formula`, `util/Expressions.compile` |
| Engine | `ability/AbilityListener` (Bukkit events to triggers, holdings refresh points), `ability/PaperArmourListener` and `ability/PaperSlotListener` (Paper only, loaded by name), `ability/AbilityDescriptor` (the editor) |
| Hit shaping | `ability/DamageShaping` (`bonus-damage`, `armour-pierce`, built-in scales, pending pierces) |
| Internal | `ability/internal/BuiltinEffects`, `BuiltinConditions`, `Targets`, `ChainGuard`, `AreaPlanner`, `PackedBlocks`, `BlockMatcher` (`block` and `only`) |
| Tests | `ability/AbilityShapingTest` (bonus-damage and scales, pierce applied last and deferred, `transformDropsLast` order, stamps, carried levels, potion without a registry, column shape, strip table), `ability/internal/PassivePotionTest` (finite PASSIVE duration, revert match), `ability/PluginAbilitiesTest` (order, gate, cooldown start, chance 0 and 100, formulas and fallback, chain guard, unknown keys, holdings cache, outcomes, `block` and `mode: { id }`, PASSIVE sustain timer, refresh offline guard), `ability/internal/BlockMatcherTest` (types, tags, short form, refused names), `ability/internal/AreaPlannerTest` (packing, shapes, filters, vein bound, budget), `util/ExpressionsTest` (compiled formulas) |
| Test seam | `PluginAbilities.detached(plugin)` (no listeners) and `dice(DoubleSupplier)` (the roll), package-private |
