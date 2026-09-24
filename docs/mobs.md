# Mob module

Custom mobs from a template: a living entity type with a name, equipment,
attributes, switches, potion effects and skills, spawned anywhere. The library
applies the template, runs the skills, keeps the health in the name current,
tracks who hurt each mob and tells the plugin when one dies. Storage, spawners
and payouts stay with the plugin. Available since 1.192.0; hits mode,
lifetime, roam, looks, auras, skill effects and the `JUMP`, `SIZE`, `SPEED` and
`BABY` skills since 1.195.0.

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
| `effectRadius(double)` / `effectRadius()` | since 1.195.0; blocks from which skill effects, effect skills and auras are seen; 32 until set |
| `skillsEditor(List<MobSkill>)` → `ListEditor<MobSkill>` | the list editor over skills |
| `attributesEditor(Player, Map<String, Double>)` → `CompletionStage<Optional<Map<String, Double>>>` | one form, a field per attribute |
| `flagsEditor(Player, Set<MobFlag>)` → `CompletionStage<Optional<Set<MobFlag>>>` | one form, a checkbox per flag |
| `behaviourEditor(Player, MobBehaviour)` → `CompletionStage<Optional<MobBehaviour>>` | since 1.195.0; one form: hits, hit cooldown, lifetime (durations), roam |
| `lookEditor(Player, EntityType, MobLook)` → `CompletionStage<Optional<MobLook>>` | since 1.195.0; one form: variant and body (only for types that have them), outline colour, aura; each hint lists the choices |

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
| `look` | `MobLook` | since 1.195.0; variant, body, outline colour and aura, see below. `MobLook.NONE` by default |
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
means `NONE` for both.

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
1.195.0). Each type reads a few of those fields and ignores the rest. The
constructor without `effect` (9 components) is kept.

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
look        {"variant":"CREAMY","body":"CYCLE","glow":"CYCLE","aura":"confetti"}
```

A skill writes `"effect"` when it has one; a skill stored before 1.195.0 reads
with none. `encodeBehaviour`/`decodeBehaviour` and `encodeLook`/`decodeLook`
(since 1.195.0) write `null` for `NONE`, omit defaults, keep times in seconds,
and read a field that is missing or not a number as its default. A chance or
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
| skills | `mobs.skillsEditor(skills)` — add asks the type, then the trigger, then a form with only the fields that type reads, plus "Effect lines" for every type (`NONE` clears them: a blank box keeps a prefilled value) |
| behaviour | `mobs.behaviourEditor(player, behaviour)` — hits, hit cooldown and lifetime as durations, roam |
| look | `mobs.lookEditor(player, type, look)` — variant and body only for types that have them; `NONE` clears a part |
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
  name is rendered onto the entity at spawn and on every health change.

## What it deliberately does not do

- Store templates, spawn mobs on its own, or cap them: spawners, regions and
  caps are the plugin's.
- Give rewards or money.
- Keep mobs across restarts.

## Where the code lives

| | |
| --- | --- |
| Public API | `util/mob/Mobs`, `PluginMobs`, `MobTemplate`, `MobSkill`, `MobFlag`, `MobDeath`, `MobCodec`, `MobBehaviour`, `MobLook`, `MobHit` |
| Random templates | `util/mob/RandomTemplate` (package-private, behind `MobTemplate.random`) |
| Editor | `util/mob/MobSkillDescriptor` |
| Runtime | `util/mob/internal/MobEngine` (listeners, spawn, skills, hits mode, look, wander, leash), `LiveMob` (cooldowns, damage ledger, hits, lifetime, leash) |
| Tests | `util/mob/MobCodecTest`, `util/mob/RandomTemplateTest`, `util/mob/MobSkillDescriptorTest`, `util/mob/internal/LiveMobTest`, `util/mob/internal/MobEngineTest` |
