# Mob module

Custom mobs from a template: a living entity type with a name, equipment,
attributes, switches, potion effects and skills, spawned anywhere. The library
applies the template, runs the skills, keeps the health in the name current,
tracks who hurt each mob and tells the plugin when one dies. Storage, spawners
and payouts stay with the plugin. Available since 1.192.0; hits mode,
lifetime, roam, looks, auras, skill effects and the `JUMP`, `SIZE`, `SPEED` and
`BABY` skills since 1.195.0; staged casts (wind-up, aim, conditions, rotation
groups, chains) and the fight (global cooldown, group periods, phases) since
1.198.0.

Entry point: `net.exylia.lib.util.mob.Mobs`.

## Using

```java
private PluginMobs mobs;

@Override
public void onEnable() {
    mobs = Mobs.of(this);
    for (MobTemplate template : store.loadAll()) {
        mobs.register(template);           // again on reload: same id replaces
    }

    mobs.onDeath(deaths = death -> {
        if (death.cause() == MobDeath.Cause.EXPIRED) return;   // it simply left
        Player killer = death.killer();
        // A mob the void, a farm or another mob finished is worth nothing.
        if (killer == null || death.playerShare() < 0.5) return;
        Rewards.of(this).give(killer, death.template().rewards());
        economy.deposit(killer, death.template().money());
    });
}

@Override
public void onDisable() {
    mobs.offDeath(deaths);   // a module turned off and on again must not be told twice
}

// A spawner of the plugin's own, from any thread.
MobTemplate knight = mobs.template("frost_knight");
if (knight != null && mobs.count("frost_knight") < 6) {
    mobs.spawn(knight, spot).thenAccept(entity -> ...);
}
```

A template is built in code or read back from the plugin's columns:

```java
MobTemplate knight = MobTemplate.of("frost_knight", EntityType.ZOMBIE)
        .withName("{primary}&lFROST KNIGHT &8[{success}%health%&8/{info}%max_health%&8]")
        .withEquipment(loadout)                         // Loadout order, see below
        .withAttributes(Map.of("max_health", 80.0, "attack_damage", 7.0))
        .withFlags(Set.of(MobFlag.NO_SUN_BURN, MobFlag.NO_VANILLA_DROPS))
        .withEffects(List.of(Effects.parse("SPEED|1|infinite")))
        .withSkills(List.of(MobSkill.of(MobSkill.Type.LEAP, MobSkill.Trigger.INTERVAL)))
        .withRewards(rewards)
        .withExp(30)
        .withMoney(250);
```

A piñata: forty hits to break, one per player every half second, gone after
five minutes, always running around within twelve blocks of where it appeared,
with a cycling carpet, a cycling outline and a spinning aura:

```java
mobs.auras(Map.of("confetti", List.of(
        "[CIRCLE] DUST;color:{accent};size:1.2;radius:1.1;points:1;y:1.3;rotate:%angle%",
        "[CIRCLE] DUST;color:{highlight};size:1.2;radius:1.1;points:1;y:1.3;rotate:%angle2%",
        "[CIRCLE] DUST;color:{info};size:1.2;radius:1.1;points:1;y:1.3;rotate:%angle3%")));

MobTemplate pinata = MobTemplate.of("pinata", EntityType.LLAMA)
        .withName("<rainbow>PIÑATA</rainbow> &8[{success}%health%&8/{info}%max_health%&8]")
        .withFlags(Set.of(MobFlag.WANDERS, MobFlag.NO_INTERACT, MobFlag.PASSIVE,
                MobFlag.NO_VANILLA_DROPS, MobFlag.NO_VANILLA_EXP))
        .withBehaviour(new MobBehaviour(40, Duration.ofMillis(500), Duration.ofMinutes(5), 12))
        .withLook(new MobLook("CREAMY", MobLook.CYCLE, MobLook.CYCLE, "confetti"))
        .withSkills(List.of(
                MobSkill.of(MobSkill.Type.EFFECT, MobSkill.Trigger.DAMAGED)
                        .withCooldown(Duration.ZERO).withText("[SOUND] ENTITY_LLAMA_HURT;1;1.4"),
                MobSkill.of(MobSkill.Type.JUMP, MobSkill.Trigger.DAMAGED)
                        .withChance(0.15).withCooldown(Duration.ZERO)
                        .withEffect("[PARTICLE] CLOUD;count:10")));

mobs.onHit(hit -> rewards.give(hit.player(), perHit));   // every counted hit
```

### A random mob

`MobTemplate.random(id, random)` (since 1.193.0) rolls a random but playable
template, for an admin who wants to see what the module can do:

- **Type** — weighted from a curated list: zombie, husk, drowned, skeleton,
  stray, wither skeleton, piglin brute, vindicator, pillager, evoker, spider,
  cave spider, blaze, enderman, slime, magma cube, witch, iron golem, wolf and,
  rarely, ravager. Never a warden.
- **Name** — `{primary}&l` + a flavour prefix (`ASTRAL`, `VOID`, `NEBULA`...),
  the type, sometimes a suffix (`HERALD`, `STALKER`...), and the health bar
  `&8[{success}%health%&8/{info}%max_health%&8]`. Palette tokens only.
- **Equipment** — only on types that show it: 0-4 armour pieces of one tier
  (leather to netherite) on the zombie and skeleton families and the piglin
  brute, and a fitting main hand (a tier sword or axe, a bow for skeletons and
  strays, a crossbow for pillagers, a trident for drowned). One in four is
  enchanted: protection, sharpness or power I-III.
- **Attributes** — 2-5 of the nine: `max_health` 20-150, `attack_damage` 2-14,
  `movement_speed` 0.2-0.4, `armor` 2-12, `armor_toughness` 1-6,
  `knockback_resistance` 0.1-0.8, `follow_range` 16-40, `attack_knockback`
  0.5-2, `scale` 0.7-1.8 (1.3 at most for golems and ravagers).
- **Flags** — 0-3 the type can use: `BABY` only on zombies, husks, drowned and
  wolves, `NO_SUN_BURN` only on what burns, `FIRE_IMMUNE` never on what already
  is, `AGGRESSIVE` on neutral types and always on wolves and golems.
- **Effects** — 0-2 infinite buffs (speed, strength, resistance, regeneration,
  fire resistance, jump boost) at level I-II.
- **Skills** — 1-4 different types, each on a trigger that suits it, with sane
  numbers. `SUMMON` summons the template itself, `EFFECT` plays one of a few
  known particle and sound lines, `COMMAND` is never rolled.
- **Payout** — 5-60 experience, 5-80 money, no rewards (those are the admin's).
- Never hits mode, never a look, never a skill type added after 1.193.0: a
  seed rolls the same mob it always did.

The same seed gives the same template. The equipment is real items, so the
call needs a running server.

## API

| Call | Contract |
| --- | --- |
| `Mobs.of(Plugin)` | the plugin's `PluginMobs`, started on first use; the same instance every time |
| `register(MobTemplate)` / `unregister(String)` | the registry; a live mob keeps the template it was spawned from |
| `template(String)` / `templates()` | a registered template, or `null`; all of them |
| `spawn(MobTemplate, Location)` → `CompletableFuture<LivingEntity>` | any thread; inline on the location's own thread, otherwise its next tick. Fails with `IllegalArgumentException` for a type that is not a spawnable living entity, `IllegalStateException` when another plugin cancelled the spawn or the plugin is going away |
| `isMob(Entity)` | carries this plugin's tag, alive in this run or not |
| `templateOf(Entity)` | the template it was spawned from, or `null` |
| `live()` / `count(String)` | this plugin's live mobs; how many of one template (minions included) |
| `onDeath(Consumer<MobDeath>)` | told every death, break and expiry, on the mob's thread; a handler that throws is reported and the rest still run |
| `offDeath(Consumer<MobDeath>)` → `boolean` | since 1.195.0; stops telling that same instance; whether it was listening |
| `onHit(Consumer<MobHit>)` / `offHit(Consumer<MobHit>)` → `boolean` | since 1.195.0; every counted hit in hits mode, the breaking one included, on the mob's thread |
| `auras(Map<String, List<String>>)` / `auras()` | since 1.195.0; the named auras a look can wear, replacing the ones before; in order, the first is the fallback |
| `effectRadius(double)` / `effectRadius()` | since 1.195.0; blocks from which skill effects, effect skills and auras are seen; 32 until set. Entrances and deaths are seen from here too |
| `visuals(MobVisuals)` / `visuals()` | since 1.199.0; the reactions switch, the numbers' range, the shake multiplier and the cap on large effects at once; `MobVisuals.DEFAULT` until set, see [Reactions](#reactions-since-11990) |
| `skillsEditor(List<MobSkill>)` → `ListEditor<MobSkill>` | the list editor over skills |
| `attributesEditor(Player, Map<String, Double>)` → `CompletionStage<Optional<Map<String, Double>>>` | one form, a field per attribute |
| `flagsEditor(Player, Set<MobFlag>)` → `CompletionStage<Optional<Set<MobFlag>>>` | one form, a checkbox per flag |
| `behaviourEditor(Player, MobBehaviour)` → `CompletionStage<Optional<MobBehaviour>>` | since 1.195.0; one form: hits, hit cooldown, lifetime (durations), roam |
| `lookEditor(Player, EntityType, MobLook)` → `CompletionStage<Optional<MobLook>>` | since 1.195.0; asks APPEARANCE or REACTIONS (since 1.199.0). APPEARANCE is one form: variant and body (only for types that have them), outline colour, aura; each hint lists the choices. REACTIONS lists spawn, hurt, death, low and numbers with what each is set to; picking one opens a choice of AUTO, NONE and its ids with the current one ticked, and saves that one change |
| `fightEditor(Player, MobFight, Set<String> groupsInUse)` → `CompletionStage<Optional<MobFight>>` | since 1.198.0; asks TIMING or PHASES. TIMING is one form: the global cooldown and one period per group in `groupsInUse` (a group not in it loses its period). PHASES is a list editor over `MobPhase` |

### The template

| Part | Type | Notes |
| --- | --- | --- |
| `id` | `String` | unique within the plugin |
| `type` | `EntityType` | a living type; a `Mob` gets targets, hunting and drop chances |
| `name` | `String` | Exylia text notation; `%health%` and `%max_health%` are rendered on spawn and after every hit and heal, rounded up — in hits mode they are the hits left and the hits it spawned with. A `<rainbow>` turns one step every 4 ticks. Blank for no name |
| `equipment` | `List<ItemStack>` | `Loadout` order: `0-3` armour, `4` offhand, `32` (`MobTemplate.MAIN_HAND`, the first hotbar slot) main hand; everything else is ignored. Never drops |
| `attributes` | `Map<String, Double>` | base values by registry key: `max_health`, `attack_damage`, `movement_speed`, `armor`, `armor_toughness`, `knockback_resistance`, `follow_range`, `attack_knockback`, `scale` (`MobTemplate.ATTRIBUTES`). A key the server or the type lacks is reported once and skipped. The mob spawns at full health |
| `flags` | `Set<MobFlag>` | see below |
| `effects` | `List<ParsedEffect>` | applied on spawn, the `Effects` notation |
| `skills` | `List<MobSkill>` | see below |
| `rewards` | `List<RewardEntry>` | stored, never given by the library |
| `exp` | `int` | added to the experience it drops |
| `behaviour` | `MobBehaviour` | since 1.195.0; hits mode, lifetime and roam, see below. `MobBehaviour.NONE` by default |
| `look` | `MobLook` | since 1.195.0; variant, body, outline colour and aura, and since 1.199.0 its reactions, see below. `MobLook.NONE` by default: vanilla appearance, AUTO reactions |
| `fight` | `MobFight` | since 1.198.0; global cooldown, rotation group periods and phases, see below. `MobFight.NONE` by default |
| `money` | `double` | stored, never paid by the library. One amount in no named currency: a consumer with several currencies keeps its own per-currency amounts beside the template and reads this as its default currency's (SurvivalCore does) |

### Flags

`GLOWING`, `BABY`, `SILENT`, `FIRE_IMMUNE` (no fire, fire tick, lava, magma or
campfire damage, never catches fire), `NO_VANILLA_DROPS`, `NO_VANILLA_EXP`,
`NO_ITEM_PICKUP`, `NO_SUN_BURN`, `AGGRESSIVE` (targets the nearest survival or
adventure player within its follow range, 48 blocks at most, whenever it has no
target; a type with no attack of its own follows but cannot hurt anybody).

Since 1.195.0:

- `WANDERS` — whenever it has no target and no path, runs (pathfinder speed
  1.8) to a random spot 30-100% of its `roam` from where it spawned, or of ten
  blocks around it when it has no roam. Checked every other tick. Needs Paper's
  pathfinder; on plain Spigot it is reported once and skipped.
- `NO_INTERACT` — cancels right-clicking, leashing, mounting (either way),
  opening its inventory, love mode and breeding.
- `PASSIVE` — cancels every `EntityTargetEvent` of it, so it never attacks or
  spits; wins over `AGGRESSIVE`. Its skills still aim (at the nearest player).

No library mob goes through a portal (since 1.195.0): the other side would be
an entity the runtime is not tracking.

The constructor without `behaviour` and `look` (11 components) is kept and
means `NONE` for both; the one without `fight` (13 components) means
`MobFight.NONE`.

## Behaviour (since 1.195.0)

`MobBehaviour(int hits, Duration hitCooldown, Duration lifetime, double roam)`,
`MobBehaviour.NONE`, `usesHits()`, `withHits`/`withHitCooldown`/`withLifetime`/`withRoam`.
Negative values read as zero.

### Hits mode

With `hits > 0` the mob's health stops mattering:

- A listener at `HIGHEST` that **also reads cancelled events** cancels every
  damage event on the mob except `VOID` and `KILL`. A protection plugin that
  cancelled the hit does not make it unbreakable.
- An `ENTITY_ATTACK` by a `Player` counts one hit, at most once per
  `hitCooldown` per player. Arrows, fire and other mobs count nothing.
- A counted hit plays the hurt animation, adds **1** to that player's entry in
  the damage ledger, casts the `DAMAGED` skills (target: the hitter), then the
  `LOW_HEALTH` skills with the share `hits left / hits`, then tells the
  `onHit` handlers, then redraws the name.
- The hit that leaves 0 is the break: it casts only the `DAMAGED` skills of
  type `EFFECT` (so the hit still looks and sounds like one), tells `onHit`
  with `hitsLeft = 0`, then casts the `DEATH` skills (target: the breaker),
  drops an experience orb of `exp`, tells `onDeath` with `Cause.BROKEN` and
  `killer` = the breaker, and removes the mob. There is no death event and no
  vanilla drop.
- The void or `/kill` still kill it: a normal death, `Cause.KILLED`.
- `MobHit(template, entity, player, hitsLeft, maxHits)`. `maxHits` is the
  template's `hits` as it spawned: scale it per spawn with
  `mobs.spawn(template.withBehaviour(template.behaviour().withHits(n)), at)`.

### Lifetime

Checked once a second. When it runs out the `onDeath` handlers get
`Cause.EXPIRED` with no killer, then the mob is removed: no `DEATH` skills, no
experience, no drops. Works in either mode.

### Roam

`roam > 0` leashes the mob to where it spawned. Checked once a second: past
`roam` blocks it walks back (pathfinder, speed 1.8 with `WANDERS`, 1
otherwise); past `roam + 8`, or in another world, it is teleported back.
`WANDERS` and a radius `TELEPORT` stay inside it too.

## Look (since 1.195.0)

`MobLook(String variant, String body, String glow, String aura)`,
`MobLook.NONE`, `withVariant`/`withBody`/`withGlow`/`withAura`. Every part is a
name, `CYCLE` (a new one each second from the part's list, starting on the
first as it spawns), `RANDOM` (one from that list, as it spawns) or blank
(vanilla); `NONE` reads as blank and keywords read in any case.

| Part | Names | The list CYCLE and RANDOM use |
| --- | --- | --- |
| `variant` | `MobLook.variants(type)`: llama and horse colours, sheep wool, parrot, axolotl, rabbit, fox and mooshroom kinds | the same |
| `body` | any material: a llama's carpet (its decor), a horse's armour, anything else's body slot (wolf armour). Never drops | `MobLook.bodies(type)`: ten bright carpets, the four horse armours, `WOLF_ARMOR` |
| `glow` | a named text colour (`light_purple`...); any value also turns the outline on | `MobLook.GLOWS`: light purple, aqua, yellow, green, red, blue, gold, white |
| `aura` | a name from `mobs.auras(...)`; an unknown one wears the first registered | every registered aura |

The outline colour goes through a team `exylia_mob_<colour>` on the main
scoreboard; the mob leaves it when it dies, breaks, expires, is removed on
disable, or is found gone by the prune. Folia has no main scoreboard to write
to, so there the outline stays white. A name or material the type cannot use is
reported once and skipped.

### Reactions (since 1.199.0)

`MobLook` carries five more components: `spawn`, `hurt`, `death`, `low` (a
reaction id, `MobLook.AUTO` = blank, or `MobLook.OFF` = `none`) and `numbers`
(a boolean, `true` by default). The four-argument constructor is kept and means
every reaction AUTO with numbers on; `withSpawn`/`withHurt`/`withDeath`/
`withLow`/`withNumbers` change one. Ids read in any case; `auto` reads as blank
and `off` as `none`. An id this version does not draw plays AUTO.

| Reaction | Ids (`MobLook.SPAWNS`, `HURTS`, `DEATHS`, `LOWS`) | AUTO |
| --- | --- | --- |
| `spawn` | `rise`, `portal`, `drop`, `bolt` | `drop` in hits mode, else `rise`; a mob standing on nothing solid comes through a `portal`, and a `drop` with no room above rises |
| `hurt` | `spark`, `pop` | `pop` in hits mode, else `spark` |
| `death` | `ragdoll`, `shatter`, `pinata` | `pinata` in hits mode, `ragdoll` for a humanoid (zombie, husk, drowned, zombie villager, skeleton, stray, bogged, wither skeleton, piglin, piglin brute, zombified piglin, vindicator, pillager, evoker, illusioner, witch), else `shatter`; a `ragdoll` on anything else, or where ragdolls cannot be drawn, shatters |
| `low` | `wounded`, `frantic` | `frantic` in hits mode, else `wounded` |

What each one draws:

| Id | Recipe |
| --- | --- |
| `rise` | Hidden for 12 ticks. 12 chunks of the block it stands on erupt around its feet on an overshoot, 22 ms apart, hold 180 ms and are thrown aside to land and melt; the ground crumbles four times, then it appears in a spray of that block with its break sound |
| `portal` | Hidden for 12 ticks. An upright ring of 14 crying obsidian plates grows in across the way it faces, an inner ring of 8 purple glass shards with it, reverse-portal particles pull inwards; it steps out at 600 ms and both rings collapse into the middle. End portal frame, respawn anchor and teleport sounds |
| `drop` | Spawns 6 blocks up with 1.5 s of slow falling over a warning circle (`{accent}` in hits mode, else `{warning}`) that fills for 1.6 s, trailing cloud; on landing (polled every 2 ticks, 4 s at most) ground chunks kick outwards, a puff, a thud and a 6-block shake, and confetti for a hits-mode mob. The one reaction that touches the mob: it needs `6 + height` free blocks above, or rises instead |
| `bolt` | Hidden for 6 ticks. Sparks at its feet and a beacon charge, then at 300 ms a jagged glowing bolt (a white core, a pale blue halo) strikes from 16 blocks up, a thinner flicker follows at 420 ms; impact, a quiet thunder, end-rod and spark bursts, scorch smoke and an 8-block shake. No real lightning: nothing burns and nobody across the world hears it |
| `spark` | 8 crits at the wound (plus enchanted-hit sparks on a critical hit); a hit of 15% of its health or more also knocks 3 chips of its palette off the side away from the attacker, which land and melt |
| `pop` | Squashes a scale modifier +12% for 3 ticks (on top of any SIZE skill), 4 candies spill off the far side from the hitter and land, confetti in `{accent}`, `{highlight}` and `{info}`, an egg pop and a sparkle |
| `ragdoll` | The body in flat per-type colours (`RagdollSkin.flat`), detail 2, light 15, at the mob's scale (half for a baby), wearing its type's head (`ZOMBIE_HEAD`, `SKELETON_SKULL`, `WITHER_SKELETON_SKULL`, `PIGLIN_HEAD`; a plain head otherwise) or a head it wears; its helmet as a hat, its hands' items carried. Thrown apart away from the killer (a burst: 0.1 s intact, 2 s life) with a puff |
| `shatter` | 14 to 20 blocks (more for a bigger body) from its whole body in its palette &mdash; the type's colours, the variant's where it changes them, the wool of a carpet on its back leading &mdash; arc out 0.6-1.6 blocks, land exactly on the ground, rest and melt; a puff, block dust and the first block's break sound |
| `pinata` | `shatter`, plus 20 candies fountaining 1.2-3 blocks out that lie on the ground almost a second, two rounds of confetti, two firework bursts, a blast, a twinkle and a level-up chime, and a 6-block shake |
| `wounded` | Below its lowest `LOW_HEALTH` threshold, or a quarter: red dust drips from it every second, and every other second a two-beat heartbeat plays to players within 12 blocks |
| `frantic` | The same moment in hits mode: sweat splashes off its head twice a second and every other second it squeaks |

`numbers`: damage as `{error}7.5` (a critical hit `{warning}&l✦ 12`), healing as
`{success}+4 ❤`, and in hits mode the hits left as `{highlight}✦ 23`, through
`Indicators`. A heal of 5% of its health or more (regeneration, a HEAL skill)
also raises three hearts around it.

**What it costs.** Every reaction is a visual, built on the mob's thread with
`Vfx` and packets, and gives up rather than waits:

- Nobody within `effectRadius` (entrances and deaths) or 32 blocks (hits,
  healing, the low look) &rarr; nothing is built.
- `MobVisuals.reactions` off, or no display runtime &rarr; nothing at all.
- Hurt bursts at most every 4 ticks per mob, heal bursts every 10; the
  numbers merge on their own within 300 ms.
- Entrances and deaths count against `MobVisuals.maxCasts` (24 by default)
  large effects at once per plugin; past that the next one is simply not drawn.
- Above 15 viewers bursts halve (and a ragdoll drops to detail 1).
- Shakes play `MobVisuals.shake` times their designed beats; `0` turns them off.

A death with a drawn body hides the real one: invisible at once, removed a
tick later, once the drops and experience are in the world; a death another
plugin cancelled is shown again. A break in hits mode is drawn while the mob
still stands and removed with it, as before. With nothing drawn the vanilla
death plays.

```java
mobs.visuals(new MobVisuals(24, 24, true, 1.0));   // indicatorRange, maxCasts, reactions, shake
template.withLook(template.look().withSpawn("portal").withDeath(MobLook.OFF).withNumbers(false));
```

### Auras

`mobs.auras(Map<String, List<String>>)`: each entry is an aura's name and the
sequence lines of **one frame**. The frame is drawn at the mob every other
tick, seen within `effectRadius`. `%angle%` is `0, 20, 40 ... 340` across 18
frames (a full turn in 36 ticks), and `%angle2%`/`%angle3%` are 120 and 240
degrees ahead of it, so `rotate:%angle%` spins a shape and three of them spin a
third of a turn apart. Call it again on reload: live mobs keep the aura they
spawned with (a `CYCLE` reads the new set).

### The fast timer

A mob with an aura, a `<rainbow>` in its name or `WANDERS` runs its timer
every 2 ticks instead of every 20 and does the one-second work (lifetime,
leash, look cycles, hunting, interval skills) on every tenth pass. Every other
mob keeps the one-second timer.

## Skills

A `MobSkill` is one flat record: `trigger`, `type`, `chance` (0-1), `cooldown`,
`threshold` (0-1), `radius`, `amount`, `duration`, `text`, `effect` (since
1.195.0) and `cast` (since 1.198.0, see *Casting*). Each type reads a few of
those fields and ignores the rest. The constructors without `effect` (9
components) and without `cast` (10 components) are kept; the latter means
`Cast.NONE`.

`effect` works for every type: sequence lines, one per line, played at the mob
each time the skill goes off (after it did something: a `BABY` on a baby or a
`TELEPORT` with nowhere to go plays nothing). A `TELEPORT` plays them where it
leaves and again where it lands. Seen within `effectRadius`.

| Trigger | Fires |
| --- | --- |
| `SPAWN` | once, as it appears |
| `INTERVAL` | every `cooldown` (one second at least), while somebody is there to aim at; the period is spent whether or not the dice land, and the first cast waits one period |
| `ATTACK` | when it hurts an entity, its projectiles included |
| `DAMAGED` | when it is hurt and survives the hit; in hits mode on every counted hit (the breaking one only casts `EFFECT` skills) |
| `LOW_HEALTH` | once, the first time its health drops to `threshold` of its maximum; in hits mode, hits left over hits |
| `DEATH` | as it dies |
| `PHASE` | since 1.198.0; as its fight enters a new phase; with `cast.when.phase` set, only as it enters that one |

| Type | Reads | Does |
| --- | --- | --- |
| `LEAP` | `amount` strength | jumps at the target |
| `PULL` | `amount` strength | yanks the target to the mob |
| `PUSH` | `radius`, `amount` strength | throws every player in range away |
| `POTION` | `text` effect line (`SLOWNESS\|2\|5`), `radius` | the effect on the target, or on every player in range when `radius` > 0 |
| `SUMMON` | `text` template id, `amount` alive at once (10 at most), `radius` spread (8 at most) | tops its minions up to `amount`; they target its target. A minion never summons |
| `LIGHTNING` | `amount` damage | a lightning flash on the target that sets nothing alight, plus the damage |
| `PROJECTILE` | `text` kind, `amount` speed | `FIREBALL`, `SMALL_FIREBALL`, `WITHER_SKULL`, `ARROW` or `SNOWBALL` at the target; its explosions break no blocks |
| `HEAL` | `amount` percent | restores that share of its maximum health |
| `TELEPORT` | `radius` | `0`: a step and a half behind the target, if it fits there. Above `0` (since 1.195.0): needs no target and blinks to a random spot 1-`radius` blocks away, on top of the highest block, in a loaded chunk this thread owns and inside its `roam`; eight tries |
| `AREA_DAMAGE` | `radius`, `amount` damage | hurts every player in range |
| `IGNITE` | `duration` | sets the target on fire |
| `JUMP` | `amount` upward speed (0.8 by default) | since 1.195.0; jumps straight up |
| `SIZE` | `text` `min\|max` or one number (`0.7\|1.8` by default) | since 1.195.0; a random `scale` in the range, either order, `0.1` at least |
| `SPEED` | `amount` times, `duration` (1.5, 5s) | since 1.195.0; that many times the base speed it spawned with, then back to it; a later boost is not cut short by an earlier one ending |
| `BABY` | `duration` (5s) | since 1.195.0; an adult of a type that ages turns baby (its age locked), then adult again |
| `EFFECT` | `text` sequence lines, one per line | plays them at the mob ([sequences](sequences.md)), seen within `effectRadius` |
| `COMMAND` | `text` command | from the console; `%player%` is the target's name (skipped when the target is not a player), `%mob%` the template id |

`chance` is rolled every time the skill is tried; `cooldown` is the shortest gap
between two casts and only starts when the skill fires. The target is the mob's
current target, else whoever the event is about (the entity it hit, the one that
hit it, its killer), else the nearest survival or adventure player within 16
blocks. A skill that needs a target (`MobSkill.needsTarget()`: the type's
answer, except `POTION` and `TELEPORT` with a radius) and finds none is not
tried at all. `JUMP`, `SIZE`, `SPEED` and `BABY` need none, so an interval one
fires whether or not anybody is near.

A skill cast while another of the same mob's skills is being cast is ignored,
so an attack skill that deals damage cannot trigger itself. A skill that throws
is reported once and keeps being tried.

## Casting (since 1.198.0)

`MobSkill.Cast(String name, Aim aim, Duration windup, String style, String tint,
double spread, Gate when, String group, String then, String windupLines)`,
`Cast.NONE`, `withName`/`withAim`/`withWindup`/`withSpread`/`withWhen`/`withGroup`/`withThen`/`withWindupLines`;
`skill.withCast(cast)`. `Cast.NONE` is exactly the behaviour before 1.198.0.

```java
MobSkill slam = MobSkill.of(MobSkill.Type.AREA_DAMAGE, MobSkill.Trigger.INTERVAL)
        .withCast(MobSkill.Cast.NONE.withName("slam").withAim(MobSkill.Aim.SELF)
                .withWindup(Duration.ofMillis(900))
                .withWindupLines("[SOUND] ENTITY_RAVAGER_ROAR;1;0.7")
                .withWhen(MobSkill.Gate.ANY.withNearby(16).withRange(0, 6)));
```

### Who may cast, and when

Checked in this order, all before the dice, so a skill kept out keeps its
cooldown for later:

1. the trigger;
2. a minion never summons;
3. `when` (`MobSkill.Gate(minHealth, maxHealth, minRange, maxRange, nearby, phase)`,
   `Gate.ANY`, `withHealth`/`withRange`/`withNearby`/`withPhase`, `admits(...)`):
   its share of health (hits left in hits mode) within `minHealth`-`maxHealth`;
   its target within `minRange`-`maxRange` blocks (`0` is no limit; with either
   set, no target means no cast); a survival or adventure player within
   `nearby` blocks when `nearby > 0`; the fight in `phase` when `phase > 0`;
4. a target, where the skill needs one (`needsTarget()`), and for the aims that
   find their own players, at least one within reach;
5. for a major skill (`major()`: anything but `EFFECT` and `COMMAND`), the
   fight's global cooldown and no other staged cast under way;
6. then its chance and cooldown, as before.

### Stages

- **Wind-up** (`windup > 0`, 10 s at most): the mob is rooted (a
  `movement_speed` modifier of −100% plus Paper's `stopPathfinding`), turns
  towards its target, and `windupLines` play at it. Nothing lands yet.
- **Impact**: the mechanics land where the aim says, and the skill's `effect`
  lines play; then `then` is cast.
- **Recovery**: 8 ticks later the root comes off.

A major staged cast holds the mob's turn from wind-up to the end of recovery;
a death, a break, an expiry or the plugin going away drops it without landing.
An `EFFECT` or `COMMAND` with a wind-up is only delayed: it neither roots nor
holds the turn. With `windup = 0` the skill lands inline, in the same call,
exactly as before. Each stage is one delayed entity task; nothing runs per tick.

### Aim

`MobSkill.Aim`: where it lands, worked out at impact against what was locked
as the wind-up started (the mob's spot and facing, where its target stood,
capped at 24 blocks).

| Aim | Lands on |
| --- | --- |
| `AUTO` | each type's own targeting, as before |
| `TARGET` | the target, wherever it is now |
| `NEAREST` / `FARTHEST` / `RANDOM` | one survival or adventure player within `radius` (16 when 0) |
| `ALL` | every such player within `radius` (16 when 0) |
| `CONE` | every such player within `radius` (16 when 0) and half of `spread` degrees (60 when 0) of the facing locked at the wind-up; 2.5 blocks of height either way |
| `LINE` | every such player within half of `spread` blocks (1.6 when 0) of a line `radius` long (16 when 0) towards where the target stood |
| `SELF` | the mob; an area type reaches everybody within `radius` |
| `GROUND` | where the target's feet were as the wind-up started, so it can be dodged; an area type reaches everybody within `radius` of it, any other type whoever stands within 1.5 blocks |

`AREA_DAMAGE`, `PUSH` and a `POTION` with a radius aimed at one body (`TARGET`,
`NEAREST`, `FARTHEST`, `RANDOM`) reach everybody within `radius` of it. `PULL`,
`POTION`, `LIGHTNING`, `IGNITE`, `PROJECTILE` (8 at most) and `COMMAND` act on
each body found; `LIGHTNING` with nobody strikes the spot. `LEAP` jumps at the
landing point, `TELEPORT` appears behind the first body (or on a free `GROUND`
spot), `SUMMON` sets its minions on the first body. `HEAL`, `JUMP`, `SIZE`,
`SPEED`, `BABY` and `EFFECT` act on the mob and ignore the aim. On Folia the
mechanics run on the landing spot's region when the mob's thread does not own it.

`style` and `tint` are stored for the style library and read by nothing yet.

### Rotation groups and chains

An `INTERVAL` skill with a `group` does not roll on its own (`grouped()`). Once
per the group's period (`MobFight.groups`, 10 s when unset, 1 s at least; the
first waits one period) exactly one member is cast: among those that pass the
checks above and their own `cooldown` (zero: every period), picked with
`chance` as the weight. A period in which nobody may be cast is not spent, so
the group goes off as soon as one can.

`then` names another skill of the same mob (`name`, any case). It is cast right
after this one lands, past the dice, its cooldown, the global cooldown and the
turn, never past its `when`. Chains stop after 4 hops, so `A → B → A` does not
loop.

## Fight (since 1.198.0)

`MobFight(Duration globalCooldown, Map<String, Duration> groups, List<MobPhase> phases)`,
`MobFight.NONE`, `period(group)`, `phaseAt(share)`, `phase(n)`,
`withGlobalCooldown`/`withGroups`/`withPhases`. Groups are sorted by name, phases
by `below`, highest first; a phase at 0 or 1 is dropped.

- `globalCooldown`: after any major cast starts, the wind-up plus this long
  before another major one may start.
- Phases: `MobPhase(double below, String style, String suffix, double speed,
  double damage, double resist)`. The fight starts in phase 1 and is in phase
  `1 + ` the number of `below` shares its health (hits left in hits mode) has
  dropped under, checked on every hit it survives. It never goes back. Entering
  a phase replaces the previous one's `movement_speed` and `attack_damage`
  multipliers (attribute modifiers, so they stack with `SPEED` skills), appends
  ` suffix` to its name, and casts its `PHASE` skills. `resist` divides the
  damage it takes, in health mode. Multipliers are kept within 0.1-10. When one
  hit crosses two thresholds only the phase it lands in casts. `style` is
  stored for the style library and read by nothing yet.

## Death

`MobDeath(template, entity, location, killer, damage, topDamager, playerShare, cause)`:

- `cause` (since 1.195.0) — `KILLED` (it died: health, `/kill`, the void, in
  either mode), `BROKEN` (its last hit in hits mode) or `EXPIRED` (its lifetime
  ran out). A consumer pays nothing for `EXPIRED`, and for a mob in hits mode
  only on `BROKEN`.

- `killer` — the player the server credits, or `null`.
- `damage` — damage by player id, through arrows, tamed animals and lit TNT,
  each hit capped at the health the mob had left.
- `topDamager` — whoever dealt most, or `null`.
- `playerShare` — the share of all damage taken (the environment and other
  mobs included) that players dealt, 0 to 1. A consumer refuses loot to farms
  with it.

For `KILLED`, before the handlers run, the library has applied
`NO_VANILLA_DROPS` and `NO_VANILLA_EXP`, removed the look's body item from the
drops, added `exp`, and cast the `DEATH` skills. For `BROKEN` it has cast the
`DEATH` skills and dropped an `exp` orb; the entity is still in the world and
is removed after the handlers. For `EXPIRED` nothing is cast or dropped. In
hits mode `damage` counts one per hit, so `topDamager` is who hit most and
`playerShare` is 1. It gives no rewards and pays no money.

## Storing a template

`MobCodec` writes the parts that go in columns; equipment stores the way the
plugin already stores items, and rewards through `RewardCodec`.

```text
skills      [{"trigger":"INTERVAL","type":"LEAP","cooldown":8.0,"amount":1.2}]
attributes  {"attack_damage":7.0,"max_health":80.0}
flags       ["NO_VANILLA_DROPS","NO_SUN_BURN"]
effects     ["SPEED|1|infinite"]
behaviour   {"hits":40,"hitCooldown":0.5,"lifetime":300.0,"roam":12.0}
look        {"variant":"CREAMY","body":"CYCLE","glow":"CYCLE","aura":"confetti","spawn":"portal","death":"none","numbers":false}
fight       {"gcd":1.5,"groups":{"melee":6.0,"tricks":4.0},"phases":[{"below":0.5,"style":"enrage","suffix":"&c⚡","speed":1.3,"damage":1.25}]}
```

A skill cast some other way than `Cast.NONE` writes a `"cast"` object (since
1.198.0), defaults left out: `{"name":"slam","aim":"SELF","windup":0.9,
"style":"slam","tint":"{warning}","spread":60.0,"when":{"minHealth":0.1,
"maxHealth":0.8,"minRange":2.0,"maxRange":6.0,"nearby":16.0,"phase":2},
"group":"melee","then":"stomp","windupLines":"[SOUND] ..."}`. A skill without
one reads as `Cast.NONE` and writes back byte for byte as it was stored. An
unknown `aim` is reported and read as `AUTO`. `encodeFight`/`decodeFight` write
`null` for `NONE`; a group period that is not a number or a phase without a
`below` costs itself and is reported.

A skill writes `"effect"` when it has one; a skill stored before 1.195.0 reads
with none. `encodeBehaviour`/`decodeBehaviour` and `encodeLook`/`decodeLook`
(since 1.195.0) write `null` for `NONE`, omit defaults, keep times in seconds,
and read a field that is missing or not a number as its default. A look's
reactions (since 1.199.0) are written only when not AUTO and `numbers` only
when `false`, so a look stored before them reads with every reaction AUTO and
writes back byte for byte. A chance or
threshold outside 0-1 reads clamped.

A skill field holding its default is not written, times are seconds, and an
empty part is `null`. Reading is tolerant: an unknown trigger, type or flag, a
non-numeric attribute or an unreadable effect line costs that one piece and is
reported through the `(where, problem)` callback; the rest is read. Attribute
keys written as `minecraft:max_health` or `generic.max_health` read as
`max_health`.

## Editing

| Part | Screen |
| --- | --- |
| skills | `mobs.skillsEditor(skills)` — add asks the type, then the trigger, then a form with only the fields that type reads, plus "Effect lines" for every type (`NONE` clears them: a blank box keeps a prefilled value). A row click asks which section (since 1.198.0): MECHANICS (that form), TIMING & AIM (wind-up, aim, spread, rotation group for `INTERVAL`, name, then, wind-up lines) or CONDITIONS (health band, target range, a player nearby, phase). A grouped row shows its weight and share of its group |
| fight | `mobs.fightEditor(player, fight, groupsInUse)` — TIMING (global cooldown, a period per group) or PHASES (list: below, name suffix, style, speed, damage, resistance) |
| behaviour | `mobs.behaviourEditor(player, behaviour)` — hits, hit cooldown and lifetime as durations, roam |
| look | `mobs.lookEditor(player, type, look)` — APPEARANCE (variant and body only for types that have them; `NONE` clears a part) or REACTIONS (pick one, then AUTO, NONE or an id, the current one ticked) |
| attributes | `mobs.attributesEditor(player, attributes)` — blank keeps the vanilla value |
| flags | `mobs.flagsEditor(player, flags)` |
| equipment | `Editors.of(plugin).loadout(template.equipment())` |
| potion effects | `Effects.editor(plugin, template.effects())` |
| rewards | `Rewards.of(plugin).editor(template.rewards())` |

## Threads and lifecycle

- Spawning happens on the location's thread; everything a mob does after that
  runs on its own thread: its events and one entity timer, once a second (every
  2 ticks for the fast timer above), for interval skills, hunting, lifetime,
  leash and look. That per-mob timer is deliberate: Folia has no
  thread that may walk every mob, and a stopped timer is also how the runtime
  learns a mob is gone without a Paper-only event. `live()` and `count()` can
  therefore include a mob for up to a second after it unloaded.
- Mobs are not saved with their chunk and do not despawn with distance; they
  unload with their chunk and are gone. A tagged mob that loads without being
  alive in this run (a crash, a reload) is removed as its chunk loads, and on
  Spigot and Paper also on start.
- Every mob carries `exylialib:mob` = `<plugin>:<template id>` in its
  persistent data.
- Slimes do not split and zombies do not convert: a transformed mob would be a
  vanilla one wearing the tag.
- When the plugin is disabled its live mobs are removed, its templates and
  death handlers forgotten, its listeners unregistered.
- Palette reloads: not applicable. Nothing derived from the palette is kept; a
  name is rendered onto the entity at spawn and on every health change, and a
  reaction reads its colours as it plays.

## What it deliberately does not do

- Store templates, spawn mobs on its own, or cap them: spawners, regions and
  caps are the plugin's.
- Give rewards or money.
- Keep mobs across restarts.

## Where the code lives

| | |
| --- | --- |
| Public API | `util/mob/Mobs`, `PluginMobs`, `MobTemplate`, `MobSkill` (with `Cast`, `Gate`, `Aim`), `MobFlag`, `MobDeath`, `MobCodec`, `MobBehaviour`, `MobLook`, `MobHit`, `MobFight`, `MobPhase`, `MobVisuals` |
| Random templates | `util/mob/RandomTemplate` (package-private, behind `MobTemplate.random`) |
| Editor | `util/mob/MobSkillDescriptor`, `MobPhaseDescriptor` |
| Runtime | `util/mob/internal/MobEngine` (listeners, spawn, what each type does, hits mode, look, wander, leash), `MobCaster` (conditions, rotation groups, staged casts, aimed impacts, chains, phases), `MobAim` (aim geometry, no server), `MobReactions` (entrances, hits, heals, deaths, the low look; what AUTO picks; the large-effect cap), `MobBodies` (humanoid skins and heads, block palettes, vanish), `LiveMob` (cooldowns, groups, global cooldown, cast under way, phase, damage ledger, hits, lifetime, leash) |
| Tests | `util/mob/MobCodecTest`, `util/mob/RandomTemplateTest`, `util/mob/MobSkillDescriptorTest`, `util/mob/GateTest`, `util/mob/internal/LiveMobTest`, `util/mob/internal/MobEngineTest`, `util/mob/internal/MobAimTest`, `util/mob/internal/RotationTest`, `util/mob/internal/MobCasterTest`, `util/mob/internal/MobBodiesTest`, `util/mob/internal/MobReactionsTest` |
