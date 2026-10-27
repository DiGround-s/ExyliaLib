# Utilities

Self-contained helpers in `net.exylia.lib.util` that do not belong to a
specific module. Each is its own class, does not depend on the others, and
exposes an injectable seam (clock, resolver, overlay) so it can be tested
without a server.

Cooldowns live in this package too but have their own page:
[cooldowns.md](cooldowns.md).

## util.Effects — potion effects from a string

Since 1.9.0.

```java
Effects.apply(player, "SPEED|2|5");                  // Speed II, 5 seconds
Effects.apply(player, classDef.getPassiveEffects()); // a list of such lines
```

One effect per line, `NAME|LEVEL|SECONDS|PARTICLES|ICON|AMBIENT`,
pipe-separated — the notation every Exylia config already writes, taken from
ExyliaCommons unchanged and extended at the end:

- `LEVEL` is written the way a player reads it — `SPEED|2` is Speed II, which
  Bukkit calls amplifier 1. Missing means I.
- `SECONDS` is a duration in seconds; the words `infinite` and `-1` mean the
  effect does not end on its own. Missing means 10 seconds. It also reads a
  duration written out, so `90`, `90s` and `1m30s` are the same effect
  (1.171.0).
- `PARTICLES` is whether the swirls are drawn, `ICON` whether the effect shows
  in the corner of the screen, and `AMBIENT` whether the particles are the
  faint beacon kind. Written `true`/`false` — or `yes`/`no` — and missing means
  what a potion normally does: particles and icon shown, not ambient. Since
  1.170.0.

```java
Effects.apply(player, "SPEED|2|5|false|false");  // Speed II, nothing on screen
```

Anything malformed — an empty line, a name with a colon from some other
notation, an unparseable number — is skipped, never fatal. Several effects
are a `List<String>`, one line each, the way configs hand them over.

| Method | Contract |
| --- | --- |
| `parse(raw)` → `ParsedEffect?` | pure parse of one line — `ParsedEffect(name, amplifier, durationTicks, ambient, particles, icon)` holds standard Java types, no Bukkit types; `null` when malformed |
| `ParsedEffect.line()` | the effect written back as a config line; the display fields only appear when they differ from the vanilla behaviour |
| `parse(lines)` → `List<ParsedEffect>` | a list of lines, malformed skipped |
| `apply(player, raw)` / `apply(player, lines)` / `apply(player, ParsedEffect...)` | resolve and apply |
| `applyInfinite(player, raw/lines/ParsedEffect...)` | apply with no end; the duration on the line is overridden |
| `remove(player, raw/lines/ParsedEffect...)` | take back only the effects named |
| `clear(player)` | remove **every** effect the player has |

State a plugin owns — a class passive, a kit buff — is applied with
`applyInfinite` and taken back with `remove`:

```java
Effects.applyInfinite(player, classDef.passiveEffects());
Effects.remove(player, classDef.passiveEffects());
```

`applyInfinite` forces the infinite duration regardless of what the line
says; writing `|infinite` on the line achieves the same through `apply`.
`Effects.INFINITE` is that duration as a constant, for building a `ParsedEffect`
in code.

### Editing a list of effects

Since 1.56.0.

```java
Effects.editor(this, kit.passiveEffects())
       .title("{primary}&lPASSIVE EFFECTS")
       .onSave(edited -> kits.save(kit, edited))
       .open(player);
```

The [editor](editors.md) screen, over `ParsedEffect`. Levels are shown and asked
for the way a player reads them — Speed II, not amplifier 1 — and stored the way
Bukkit wants them. That one piece of arithmetic is what made the ExyliaCommons
potion editor hand back effects a level weaker than the admin asked for.

Pair those two, never `applyInfinite` with `clear`: the player may be carrying
effects from a potion they drank or from another plugin, and those are not the
caller's to take away. `clear` is for when the player really should end up with
nothing, such as respawning into a lobby.

### Putting effects back — `EffectSnapshot`

Since 1.140.0.

```java
EffectSnapshot own = EffectSnapshot.of(player, List.of("SPEED", "STRENGTH"));
Effects.remove(player, arenaEffects);
Effects.apply(player, arenaEffects);   // the arena's Speed and Strength
// ... the round ends ...
own.restoreTo(player);                  // the arena's are gone, theirs are back
```

For anything that lends a player effects for a while — a duel, an arena, a
zone — and has to hand back what they came in with.

| Method | Contract |
| --- | --- |
| `EffectSnapshot.of(player)` | holds every active effect; restoring clears every effect first |
| `EffectSnapshot.of(player, types)` | holds only the named types (names as `parse` reads them, any case; unknown names skipped); restoring touches only those types |
| `restoreTo(player)` | removes what is on now within the scope — including anything added in between — then puts back what was held |
| `effects()` → `List<Entry>` | what was held: type, ticks left, amplifier, ambient, particles, icon |
| `isEmpty()` | nothing in the scope was on at capture |

- **Scope.** A scoped snapshot never touches a type it was not given: the Night
  Vision a player walked into a duel with keeps ticking as it was.
- **Durations come back as captured.** The snapshot exists because something is
  about to replace those effects, so the time in between is time the player did
  not have them. Infinite stays infinite.
- **Pair it with the removal.** Take the snapshot, then `remove` the types before
  applying your own: Minecraft keeps the stronger of two effects of one type, so
  applying on top of a player's Strength II would leave the arena's Strength I
  hidden under it.
- **Not a death.** A player who died has already lost their effects; restoring
  onto them gives effects the death took. Skip the restore in that case.
- **`Snapshot` is the wide tool.** `SnapshotPart.POTION_EFFECTS` restores every
  effect as part of the whole player; this is the narrow one for a few types.
- The value is immutable and safe on any thread; `of` and `restoreTo` touch the
  player and follow the thread rules below.

### Threads

`parse` is pure data and safe from any thread. Everything that touches a player
— `apply`, `applyInfinite`, `remove`, `clear` — must be called from the thread
that owns that player:

```java
tasks.runAtEntity(player, () -> Effects.applyInfinite(player, passives));
```

On Folia the caller's own thread is the contract, and calling from anywhere else
throws. Two ways to get it wrong:

- **`runTimer` instead of `runAtEntityTimer`.** `run`/`runTimer` are the global
  region thread, which owns no player. A timer that ends up applying an effect
  belongs on `runAtEntityTimer`.
- **Applying to somebody else.** Being on one player's thread says nothing about
  another player near a region border. Each target needs its own
  `runAtEntity` hop.

This module deliberately does not schedule that hop for you. Folia's entity
scheduler always defers, even when the calling thread is already the right one,
so wrapping every line would turn one synchronous call into a task per effect
and leave the caller unable to read back what it just applied.

- Parsing is cached in Caffeine for 30 seconds.
- The resolver (`PotionEffectType.getByName`), applier (`addPotionEffect`) and
  remover (`removePotionEffect`) are injectable for tests, and so are
  `EffectSnapshot`'s reader (`getActivePotionEffects`) and restorer
  (`addPotionEffect` with every flag kept).
- Infinite is sent as duration `-1`, spelled out rather than taken from
  `PotionEffect.INFINITE_DURATION` so the class keeps compiling against older
  server API.

## TimeFormats — how time is written for a player

Since 1.12.0. The library's **one** implementation of time rendering, shared
by cooldowns, countdowns and `%time%`. Formatting time is the sort of thing
every plugin writes slightly differently until one shows `3,3` and another
`3.30`.

```java
TimeFormats.render(3.34, TimeFormats.Style.TENTHS);  // "3.3"
TimeFormats.render(95.0, TimeFormats.Style.CLOCK);   // "1:35"
TimeFormats.render(3.34);                            // AUTO → "3.3"
TimeFormats.render(3665, Style.FULL);                // "1h 1m 5s"
TimeFormats.render(432000, Style.FULL);              // "5d"
TimeFormats.render(9000, Style.COMPACT);             // "2h 30m"
TimeFormats.render(0.4, Style.PRECISE);              // "0.4s"
```

| Style | Output | Notes |
| --- | --- | --- |
| `AUTO` | tenths under 10s, whole seconds to a minute, clock past it | the default |
| `SECONDS` | `3` | floored |
| `TENTHS` | `3.3` | |
| `HUNDREDTHS` | `3.34` | |
| `CLOCK` | `1:35`, `1:05:03` past an hour | padded |
| `FULL` | `1h 5m 3s`, `2d 3h 4m 5s` | every part, days downwards; for durations read once |
| `COMPACT` | `3d`, `2h 30m`, `59m 50s` | two largest whole units, up to years, floored; for a duration inside a sentence |
| `PRECISE` | `0.4s`, `3.3s`, `45s`, `1m 30s` | tenths with the unit under 10s, `COMPACT` above; carries its unit, so the text must not append one. For cooldowns that can be under a second (1.247.0) |

API: `render(double, Style)`, `render(Duration, Style)`, `render(double)` —
AUTO, `render(double, String)` — style named the way a config names it
(`"tenths"`, `"1"`, `"s"`, ...), `styleOf(name)` — parse with AUTO fallback
(a typo in a config must not stop a boss bar from drawing).

Contracts:

- **Locale is fixed to US.** A host in Europe would otherwise render `3,3`
  from the same config another renders `3.3`.
- **Rounding is half-up.** Java's default half-even renders `0.25` as `0.2`,
  which reads as the countdown stalling.
- **Negative or non-finite input renders as zero** — a finished countdown
  reads `0.0`, never `-1.2`.
- Thread-safe: `DecimalFormat` is not, so each thread gets its own.
- **`FULL` rolls into days** (since 1.87.0). It counted hours upwards before,
  so a five day cooldown read `120h` and asked the reader to divide. It stops
  at days: a week and a month are approximations, and spelling out every part
  of a duration only to approximate the largest one is the worst of both.
- **Everything these styles write, `InputParser.duration()` reads back** — the
  same unit ladder and the same decimal. A duration a player can read is a
  duration an admin can type.

## Expressions — arithmetic from a config file

Since 1.63.0.

```java
double damage  = Expressions.evaluate("2 + %distance% * 0.5", player, 4.0);
double clamped = Expressions.evaluate("min(20, %hearts% * 2)", player, 20.0);
```

The companion of the condition parser: that one answers *whether*, this one
answers *how much*. A config that can say `damage: 2 + %kills% * 0.5` is the
difference between a server owner tuning a number and asking for a plugin
update.

Understood: `+ - * / % ^`, parentheses, unary sign, and the functions `min`,
`max`, `abs`, `floor`, `ceil`, `round`, `sqrt`. Nothing else, deliberately —
this is a formula in a YAML file, not a scripting language. `^` binds right,
so `2 ^ 3 ^ 2` is 512.

API:

| Call | Contract |
| --- | --- |
| `evaluate(formula, fallback)` | no placeholder pass |
| `evaluate(formula, player, fallback)` | player's placeholders resolved first |
| `evaluate(formula, player, data, fallback)` | with extra values, as `Placeholders.apply` takes them |
| `tryEvaluate(resolved)` | `OptionalDouble`; empty when unreadable |

Contracts:

- **An unreadable formula returns the fallback.** A typo costs the owner their
  tuning, not the feature. `tryEvaluate` is for callers who need to tell "the
  formula said zero" from "the formula was broken" — to report the typo.
- **A division by zero is a broken formula, not an infinity.** So is any
  non-finite result: `Infinity` propagates into damage and cooldown numbers
  where it does far more harm than the fallback would.
- **A leftover placeholder is unreadable, not zero.** `%missing% + 1` returns
  the fallback rather than quietly becoming 1.
- **Deep nesting is a typo, not a crash.** `((((((...` is caught and reported
  as unreadable.
- Parsing touches no Bukkit state and is safe anywhere. Resolving placeholders
  is only as thread safe as the resolvers involved, which for PlaceholderAPI
  means the main thread; pass a `null` player from elsewhere.

## Weighted — one entry, rolled by weight

Since 1.258.0.

```java
Material block = Weighted.pick(blocks, BlockEntry::weight).material();
```

| Call | Contract |
| --- | --- |
| `pick(entries, weight)` | `null` only for an empty list |
| `pick(entries, weight, random)` | the same with a given `RandomGenerator`, for tests |

- **Zero or negative weights are never rolled.**
- **All-zero weights pick evenly** rather than nothing: a list an admin filled
  with zeros still works.

## FairRoll — a weighted roll that remembers the player

*Since 1.379.0.*

```java
FairRoll<Kit> roll = FairRoll.of(kits, Kit::weight, Kit::id, Kit::rank)
        .avoidRecent(3)                                        // not one of the last 3 results
        .rules(List.of(new FairRoll.Rule("epic", 3, 10)));     // 10 rolls in a row below rank 3 force one

FairRoll.Result<Kit> result = roll.roll(profile.luck());       // picked(), forced(), luck()
profile = profile.withLuck(result.luck());                     // stored by the caller, per player

List<Kit> offer = roll.rollDistinct(luck, 3, random);          // a draft: distinct, the first with pity
luck = roll.record(luck, chosen);                              // what the player kept
```

| Call | Contract |
| --- | --- |
| `of(entries, weight, key, tier)` | a roll over a copy of the entries; tiers are numbers, higher is rarer |
| `avoidRecent(n)` / `rules(list)` | a new roll with anti-repeat / guarantees; the roll itself is immutable |
| `roll(luck[, random])` | one entry, and the `Luck` after it; `picked` is `null` only for an empty list |
| `rollDistinct(luck, n, random)` | up to `n` different entries, the first rolled as `roll` rolls; records nothing |
| `record(luck, entry)` | the luck after receiving `entry` |

- **`Luck(recent, misses)`** is plain data the caller stores: the keys of the
  latest results (at most `avoidRecent`) and, per rule name, the rolls in a row
  below its tier. `Luck.NONE` is a player who never rolled.
- **Anti-repeat gives way.** When leaving the recent keys out would leave
  nothing, they are rolled again rather than nothing.
- **A rule is due** once its misses reach `after`; the roll then draws only
  among entries of at least its tier and the count restarts. Several due: the
  highest tier wins. A rule nothing in the list satisfies is ignored, and its
  count keeps growing. `after` of `0` or less turns a rule off.
- Weights follow `Weighted`. Rules are keyed by name, so reordering them keeps
  every stored count.

## PermissionNumbers — numbers granted by permission

*Since 1.319.0.*

```java
int vaults = PermissionNumbers.highest(player, "myplugin.vaults.amount.", settings.defaultVaults());
```

The largest whole number behind the prefix among the player's effective
permissions, or the fallback when none carries one. A node set to `false`
grants nothing and a tail that is not a number is skipped. Read it on the thread
that owns the player.

## PlayerStates — god mode and vanish kept by other plugins

*Since 1.332.0.*

```java
if (PlayerStates.disableGod(player)) { ... }
PlayerStates.disableVanish(player);
boolean hidden = PlayerStates.isVanished(player);
```

| Method | Contract |
| --- | --- |
| `isGod(player)` | god mode in EssentialsX or CMI, or Bukkit `isInvulnerable()` |
| `disableGod(player)` | turns it off in each of them; `true` when it was on anywhere |
| `isVanished(player)` | the `vanished` metadata (SuperVanish, PremiumVanish, EssentialsX, CMI), or EssentialsX/CMI's own flag |
| `disableVanish(player)` | turns vanish off in EssentialsX and CMI; other vanish plugins have no public switch and are left alone |

EssentialsX and CMI are reached by reflection through their plugin instance, so
neither is a dependency; a server without them pays a lookup, and a call that
cannot be made answers `false`. Call it on the thread that owns the player.

CMI keeps god mode in two places, both checked against the CMI API jar
(9.8.6.4): `/god` lives on its NMS layer (`getNMS().getGodMode(player)` /
`changeGodMode(player, false)`) and timed god on the user (`isTgod()` /
`setTgod(0)`). `CMIUser` has `isGod()` but no `setGod`, which is why
1.332.0's CMI branch never turned god mode off (fixed in 1.335.0).

## PvpZones — is PvP refused here

*Since 1.335.0.*

```java
Set<PvpZones.Source> sources = EnumSet.allOf(PvpZones.Source.class);
if (PvpZones.isDenied(to, player, sources)) {
    event.setTo(event.getFrom());       // a tagged player walking into a safe zone
}

// a scan: no Location per block
boolean wall = PvpZones.denies(PvpZones.Source.TOWNY, world, x, y, z, player);
```

| Source | Refuses where |
| --- | --- |
| `WORLDGUARD` | a region (or `__global__`) sets `pvp deny` |
| `EXYLIA_REGIONS` | an ExyliaLib region resolves `pvp` to `false` |
| `LANDS` | an area where the player's role lacks `ATTACK_PLAYER` (needs the player; `null` skips it) |
| `GRIEF_PREVENTION` | a claim `claimIsPvPSafeZone` says yes to, in a world where GP's PvP rules apply |
| `TOWNY` | `TownyAPI.isPVP(location)` is `false` (town blocks and wilderness) |
| `SUPERIOR_SKYBLOCK` | an island without the `PVP` island flag |

The caller names the sources, so a plugin exposes each as a switch in its own
config. `Source.available()` says whether the plugin behind one is enabled (for `EXYLIA_REGIONS`, whether any region is registered).
Every claim plugin is reached by reflection through its own class loader and
resolved once per plugin instance; one that is missing, cannot be resolved or
throws answers "allowed" and is logged once. Ask on the thread that owns the
location.

## Totp — authenticator app codes

Since 1.352.0. RFC 6238 one-time passwords, the six digits Google
Authenticator, Authy, Aegis or 1Password show: HMAC-SHA1, 30-second steps,
six digits (the only parameters every app honours).

```java
String secret = Totp.newSecret();                    // 160 bits, base32; store it sealed
String uri = Totp.uri("My Server", name, secret);    // what the app imports

long step = Totp.verify(secret, typed, System.currentTimeMillis());
if (step < 0 || step <= lastStep) refuse();          // wrong, or a code already used
```

`verify` accepts the step on either side of now, ignores spaces (`123 456`),
compares in constant time, and returns the matched step so the caller can
refuse a replay. `code(secret, step)`, `base32` and `base32Decode` are public
for tests and manual entry. Time is a parameter: no clock to inject.

## QrCodes — QR codes and map pixels

Since 1.352.0. `QrCodes.matrix(text)` is the code as `boolean[row][column]`;
`QrCodes.mapPixels(text)` draws it centred on a 128×128 map (`MAP_WHITE` and
`MAP_BLACK` colour ids, row by row), ready for
[`HeldMaps.show`](packets.md#heldmaps). Error correction M; a map fits about
200 characters, more throws `IllegalArgumentException`. Encoding is ZXing core,
shaded and relocated to `net.exylia.lib.internal.zxing`.

## Source and tests

- Public: `util/Effects.java`, `util/TimeFormats.java`,
  `util/Expressions.java`, `util/Weighted.java`, `util/FairRoll.java`, `util/PlayerStates.java`, `util/PvpZones.java`, `util/Totp.java`, `util/QrCodes.java` (plus the cooldown classes on their own page).
- Internal: `util/internal/CooldownStore.java`, `util/internal/ClaimPvp.java` (the claim plugins), `util/worldguard/internal/WorldGuardAccess.pvpDenied`.
- Tests: `src/test/java/net/exylia/lib/util/EffectsTest.java`,
  `TimeFormatsTest.java`, `ExpressionsTest.java`, `WeightedTest.java`, `FairRollTest.java`, `TotpTest.java`,
  `QrCodesTest.java` (scans the map pixels back with ZXing's reader).
