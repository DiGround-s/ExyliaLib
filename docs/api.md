# Public API

The contract third-party developers write against. One artifact,
`net.exylia:exylia-api`, covering every Exylia plugin. Since ExyliaLib 1.112.0;
the survival events arrived in 1.113.0, mine breaks in 1.131.0, and on-demand
actions and lifecycle events across the suite in 1.133.0 (`exylia-api` 1.3.0),
and the aim trainer in 1.152.0 (`exylia-api` 1.5.0).

Entry point: `net.exylia.lib.api.ExyliaAPI`.

## For developers consuming Exylia

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.DiGround-s.ExyliaLib:exylia-api:1.5.0'
}
```

```yaml
# plugin.yml
depend: [ ExyliaLib ]
```

```java
ExyliaAPI.get(ClansService.class)
         .flatMap(clans -> clans.clanOf(player.getUniqueId()))
         .ifPresent(clan -> player.sendMessage("Clan: " + clan.name()));
```

`compileOnly` is not a suggestion. The classes ship inside `ExyliaLib.jar`, and
a second copy shaded into a consumer's own jar would be a different `Class`
loaded by a different classloader — every service lookup would then miss.

Three ways in, all on `ExyliaAPI`:

| Call | For |
| --- | --- |
| `get(Service.class)` | soft integration — `Optional`, empty when the plugin is absent |
| `require(Service.class)` | hard `depend` — throws when the plugin is absent |
| `isAvailable(Service.class)` | a capability check, without unwrapping |

Services register when their plugin enables, so resolve one at the point of use
or from `ServerLoadEvent`, never by caching a reference in your own `onEnable`.

## Why the contract lives in the library

Every Exylia plugin ships as a Lukittu loader jar: the server loads a small
loader `JavaPlugin`, which decrypts the real plugin into a `LukittuClassLoader`
of its own. Bukkit's soft-dependency delegation reaches the loader's classloader
and stops there.

So an interface shipped inside a plugin is invisible to every other plugin, no
matter what either `plugin.yml` says — and a copy shipped in both is two
different classes, which makes every lookup miss. ExyliaLib is a normal plugin
with a normal `PluginClassLoader` that everything on the server can see, so the
contract lives there and nowhere else.

That is also why an `-api` artifact per plugin cannot work on its own: the
artifact would have to be loaded by ExyliaLib's classloader anyway, which makes
it one artifact rather than twenty.

## Structure

```
net.exylia.lib.api
├── ExyliaAPI                  the entry point
└── <domain>
    ├── <Domain>Service        the interface
    ├── <records, enums>       the values it returns
    └── event                  the Bukkit events the plugin fires
```

One package per plugin. Every type in it is paper-api, the JDK, or another type
from the same package — the module depends on nothing else, so nothing a
consumer resolves can drift from what the server loads.

## Versioning

`exylia-api` is versioned separately from ExyliaLib, and semantic versioning
applies to it and to nothing else in the project. ExyliaLib's own version moves
whenever its internals move; the API's version moves only when the contract
does, so a developer targeting `exylia-api:1.0.0` is not disturbed by library
releases.

Adding a method to a service interface is a minor release: it breaks
implementations, all of which are ours, and no consumers. Changing or removing
one is a major release, so it does not happen — curate the surface before
publishing it rather than after.

## For maintainers adding a plugin's API

Everything lives in `ExyliaLib/exylia-api/`, a Gradle module on ExyliaLib's
`shade` configuration: its classes are bundled into `ExyliaLib.jar` and kept out
of ExyliaLib's published POM.

1. Write `net.exylia.lib.api.<domain>.<Domain>Service` plus its records, enums
   and events in `exylia-api`. Only paper-api, the JDK, and
   `org.jetbrains.annotations` — no library internals, no plugin types.
2. Write `net.exylia.<plugin>.api.<Domain>ServiceImpl` in the plugin. It
   delegates to the plugin's existing internal facade and translates types.
   Nothing else.
3. Register from the plugin's `start(...)`, after the managers it reads exist:

   ```java
   loader.getPlugin().getServer().getServicesManager()
         .register(ClansService.class, new ClansServiceImpl(), loader.getPlugin(),
                   ServicePriority.Normal);
   ```

   Never unregister. Bukkit drops every registration a plugin owns when that
   plugin disables, which is exactly the lifetime wanted.

The plugin's own code keeps calling its own internal facade. Routing internal
calls through `ServicesManager` would mean asking Bukkit for a reference to
ourselves, would break on anything that runs before the registration, and would
freeze every internal signature into a contract we could no longer change.

Public events are the one thing that moves rather than being wrapped: a Bukkit
event is only listenable by a third party when its class is loaded by a
classloader that third party can see, so the class lives in `exylia-api` and the
plugin imports it from there.

## Services

Twenty-three services across twenty-two plugins, plus one plugin that publishes
only events. Every one is reached the same way:
`ExyliaAPI.get(<Service>.class)`. The event counts leave out abstract bases
such as `BetEvent` and `PracticeEvent`.

| Package | Service | Plugin | Events |
| --- | --- | --- | --- |
| `api.armorskin` | `ArmorSkinService` | ExyliaArmorSkin | 2 |
| `api.armortrims` | `ArmorTrimService` | ExyliaArmorTrims | 2 |
| `api.arrows` | `ArrowsService` | ExyliaArrows | 1 |
| `api.betcore` | `BetCoreService` | ExyliaBetCore | 8 |
| `api.capture` | `CaptureService` | ExyliaCapture | 3 |
| `api.chatcosmetics` | `CosmeticsService`, `ChatService` | ExyliaChatCosmetics | 10 |
| `api.clans` | `ClansService` | ExyliaClans | 8 |
| `api.classes` | `ClassesService` | ExyliaClasses | 3 |
| `api.events` | `EventsService` | ExyliaEvents | 6 |
| `api.ffa` | `FfaService` | ExyliaFFA | 4 |
| `api.hiteffect` | `HitEffectService` | ExyliaHitEffect | 1 |
| `api.killeffect` | `KillEffectService` | ExyliaKillEffect | 1 |
| `api.pearls` | `PearlsService` | ExyliaPearls | 1 |
| `api.practice` | `PracticeService` | ExyliaPracticeCore | 8 |
| `api.practicebot` | `PracticeBotService` | ExyliaPracticeBotV3 | 2 |
| `api.sandbox` | `SandBoxService` | ExyliaSandBox | 3 |
| `api.shields` | `ShieldsService` | ExyliaShields | 2 |
| `api.specials` | `SpecialsService` | ExyliaSpecialsV3 | 3 |
| `api.staff` | `StaffService` | ExyliaStaff | 5 |
| `api.survival` | `SurvivalService` | ExyliaSurvivalCore | 10 |
| `api.totems` | — | ExyliaTotems | 2 |
| `api.totemtrainer` | `TotemTrainerService` | ExyliaTotemTrainer | 8 |
| `api.aimtrainer` | `AimTrainerService` | ExyliaAimTrainer | 7 |

ExyliaTotems has no service on purpose: it holds nothing worth asking about, and
it cancels the death it handles, so neither `PlayerDeathEvent` nor
`EntityResurrectEvent` reaches an observer. Its two events are the whole of its
API: `PlayerTotemSaveEvent` after a save, and `PlayerTotemHoldEvent` before a
death is held open for one, which a plugin running its own deaths cancels.

ExyliaProxyUtils is not here. It runs on Velocity and BungeeCord, which have no
Bukkit `ServicesManager` and no Lukittu Spigot loader, so it needs a different
mechanism than this one.

## What a service drives

Since 1.3.0 a service does more than answer questions. Throughout the suite:

- **On-demand actions** go through the same path the plugin's own trigger uses:
  a kill effect played without a kill, a duel started without a request, a kit
  given as a reward. The gates a real trigger passes (regions, cooldowns,
  visibility) still apply, and each method says which ones it skips.
- **Menu openers** open a player-facing screen exactly as its command does, so
  an NPC, a sign or a lobby item can lead into it.
- **Lifecycle events** are fired at the moment that matters, and the cancellable
  ones fire after the plugin's own checks and before anything is written, so a
  cancelled event leaves nothing half done.
- **Result enums** replace booleans wherever a call can be refused for more than
  one reason.

## What each service does not expose

Every service is curated rather than a mirror of the plugin's internal facade.
Left out throughout: interactive editor flows, administrative and destructive
writes, anything that spends or stakes a player's money on their behalf, session
arbitration, raw configuration rows, and anything returning a type that only
makes sense inside one classloader.

The plugin keeps all of it internally. A method that is absent here is absent
because it was judged not to belong in a contract that cannot be broken later,
not because it does not exist.
