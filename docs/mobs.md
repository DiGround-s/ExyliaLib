# Mob module

Custom mobs from a template: a living entity type with a name, equipment,
attributes, switches, potion effects and skills, spawned anywhere. The library
applies the template, runs the skills, keeps the health in the name current,
tracks who hurt each mob and tells the plugin when one dies. Storage, spawners
and payouts stay with the plugin. Available since 1.192.0.

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

    mobs.onDeath(death -> {
        Player killer = death.killer();
        // A mob the void, a farm or another mob finished is worth nothing.
        if (killer == null || death.playerShare() < 0.5) return;
        Rewards.of(this).give(killer, death.template().rewards());
        economy.deposit(killer, death.template().money());
    });
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
| `onDeath(Consumer<MobDeath>)` | told every death, on the mob's thread; a handler that throws is reported and the rest still run |
| `skillsEditor(List<MobSkill>)` → `ListEditor<MobSkill>` | the list editor over skills |
| `attributesEditor(Player, Map<String, Double>)` → `CompletionStage<Optional<Map<String, Double>>>` | one form, a field per attribute |
| `flagsEditor(Player, Set<MobFlag>)` → `CompletionStage<Optional<Set<MobFlag>>>` | one form, a checkbox per flag |

### The template

| Part | Type | Notes |
| --- | --- | --- |
| `id` | `String` | unique within the plugin |
| `type` | `EntityType` | a living type; a `Mob` gets targets, hunting and drop chances |
| `name` | `String` | Exylia text notation; `%health%` and `%max_health%` are rendered on spawn and after every hit and heal, rounded up. Blank for no name |
| `equipment` | `List<ItemStack>` | `Loadout` order: `0-3` armour, `4` offhand, `32` (`MobTemplate.MAIN_HAND`, the first hotbar slot) main hand; everything else is ignored. Never drops |
| `attributes` | `Map<String, Double>` | base values by registry key: `max_health`, `attack_damage`, `movement_speed`, `armor`, `armor_toughness`, `knockback_resistance`, `follow_range`, `attack_knockback`, `scale` (`MobTemplate.ATTRIBUTES`). A key the server or the type lacks is reported once and skipped. The mob spawns at full health |
| `flags` | `Set<MobFlag>` | see below |
| `effects` | `List<ParsedEffect>` | applied on spawn, the `Effects` notation |
| `skills` | `List<MobSkill>` | see below |
| `rewards` | `List<RewardEntry>` | stored, never given by the library |
| `exp` | `int` | added to the experience it drops |
| `money` | `double` | stored, never paid by the library |

### Flags

`GLOWING`, `BABY`, `SILENT`, `FIRE_IMMUNE` (no fire, fire tick, lava, magma or
campfire damage, never catches fire), `NO_VANILLA_DROPS`, `NO_VANILLA_EXP`,
`NO_ITEM_PICKUP`, `NO_SUN_BURN`, `AGGRESSIVE` (targets the nearest survival or
adventure player within its follow range, 48 blocks at most, whenever it has no
target; a type with no attack of its own follows but cannot hurt anybody).

## Skills

A `MobSkill` is one flat record: `trigger`, `type`, `chance` (0-1), `cooldown`,
`threshold` (0-1), `radius`, `amount`, `duration`, `text`. Each type reads a
few of those fields and ignores the rest.

| Trigger | Fires |
| --- | --- |
| `SPAWN` | once, as it appears |
| `INTERVAL` | every `cooldown` (one second at least), while somebody is there to aim at; the period is spent whether or not the dice land, and the first cast waits one period |
| `ATTACK` | when it hurts an entity, its projectiles included |
| `DAMAGED` | when it is hurt and survives the hit |
| `LOW_HEALTH` | once, the first time its health drops to `threshold` of its maximum |
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
| `TELEPORT` | — | a step and a half behind the target, if it fits there |
| `AREA_DAMAGE` | `radius`, `amount` damage | hurts every player in range |
| `IGNITE` | `duration` | sets the target on fire |
| `EFFECT` | `text` sequence lines, one per line | plays them at the mob ([sequences](sequences.md)) |
| `COMMAND` | `text` command | from the console; `%player%` is the target's name (skipped when the target is not a player), `%mob%` the template id |

`chance` is rolled every time the skill is tried; `cooldown` is the shortest gap
between two casts and only starts when the skill fires. The target is the mob's
current target, else whoever the event is about (the entity it hit, the one that
hit it, its killer), else the nearest survival or adventure player within 16
blocks. A skill that needs a target and finds none is not tried at all.

A skill cast while another of the same mob's skills is being cast is ignored,
so an attack skill that deals damage cannot trigger itself. A skill that throws
is reported once and keeps being tried.

## Death

`MobDeath(template, entity, location, killer, damage, topDamager, playerShare)`:

- `killer` — the player the server credits, or `null`.
- `damage` — damage by player id, through arrows, tamed animals and lit TNT,
  each hit capped at the health the mob had left.
- `topDamager` — whoever dealt most, or `null`.
- `playerShare` — the share of all damage taken (the environment and other
  mobs included) that players dealt, 0 to 1. A consumer refuses loot to farms
  with it.

Before the handlers run, the library has applied `NO_VANILLA_DROPS` and
`NO_VANILLA_EXP`, added `exp`, and cast the `DEATH` skills. It gives no rewards
and pays no money.

## Storing a template

`MobCodec` writes the parts that go in columns; equipment stores the way the
plugin already stores items, and rewards through `RewardCodec`.

```text
skills      [{"trigger":"INTERVAL","type":"LEAP","cooldown":8.0,"amount":1.2}]
attributes  {"attack_damage":7.0,"max_health":80.0}
flags       ["NO_VANILLA_DROPS","NO_SUN_BURN"]
effects     ["SPEED|1|infinite"]
```

A skill field holding its default is not written, times are seconds, and an
empty part is `null`. Reading is tolerant: an unknown trigger, type or flag, a
non-numeric attribute or an unreadable effect line costs that one piece and is
reported through the `(where, problem)` callback; the rest is read. Attribute
keys written as `minecraft:max_health` or `generic.max_health` read as
`max_health`.

## Editing

| Part | Screen |
| --- | --- |
| skills | `mobs.skillsEditor(skills)` — add asks the type, then the trigger, then a form with only the fields that type reads |
| attributes | `mobs.attributesEditor(player, attributes)` — blank keeps the vanilla value |
| flags | `mobs.flagsEditor(player, flags)` |
| equipment | `Editors.of(plugin).loadout(template.equipment())` |
| potion effects | `Effects.editor(plugin, template.effects())` |
| rewards | `Rewards.of(plugin).editor(template.rewards())` |

## Threads and lifecycle

- Spawning happens on the location's thread; everything a mob does after that
  runs on its own thread: its events and one entity timer, once a second, for
  interval skills and hunting. That per-mob timer is deliberate: Folia has no
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
| Public API | `util/mob/Mobs`, `PluginMobs`, `MobTemplate`, `MobSkill`, `MobFlag`, `MobDeath`, `MobCodec` |
| Random templates | `util/mob/RandomTemplate` (package-private, behind `MobTemplate.random`) |
| Editor | `util/mob/MobSkillDescriptor` |
| Runtime | `util/mob/internal/MobEngine` (listeners, spawn, skills), `LiveMob` (cooldowns, damage ledger) |
| Tests | `util/mob/MobCodecTest`, `util/mob/RandomTemplateTest`, `util/mob/internal/LiveMobTest` |
