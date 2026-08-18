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

One effect per line, `NAME|LEVEL|SECONDS`, pipe-separated — the notation
every Exylia config already writes, taken from ExyliaCommons unchanged:

- `LEVEL` is written the way a player reads it — `SPEED|2` is Speed II, which
  Bukkit calls amplifier 1. Missing means I.
- `SECONDS` is a duration in seconds; the words `infinite` and `-1` mean the
  effect does not end on its own. Missing means 10 seconds.

Anything malformed — an empty line, a name with a colon from some other
notation, an unparseable number — is skipped, never fatal. Several effects
are a `List<String>`, one line each, the way configs hand them over.

| Method | Contract |
| --- | --- |
| `parse(raw)` → `ParsedEffect?` | pure parse of one line — `ParsedEffect(name, amplifier, durationTicks)` holds standard Java types, no Bukkit types; `null` when malformed |
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

Pair those two, never `applyInfinite` with `clear`: the player may be carrying
effects from a potion they drank or from another plugin, and those are not the
caller's to take away. `clear` is for when the player really should end up with
nothing, such as respawning into a lobby.

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
  remover (`removePotionEffect`) are injectable for tests.
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
```

| Style | Output | Notes |
| --- | --- | --- |
| `AUTO` | tenths under 10s, whole seconds to a minute, clock past it | the default |
| `SECONDS` | `3` | floored |
| `TENTHS` | `3.3` | |
| `HUNDREDTHS` | `3.34` | |
| `CLOCK` | `1:35`, `1:05:03` past an hour | padded |
| `FULL` | `1h 5m 3s` | for durations read once |

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

## Source and tests

- Public: `util/Effects.java`, `util/TimeFormats.java` (plus the cooldown
  classes on their own page).
- Internal: `util/internal/CooldownStore.java`.
- Tests: `src/test/java/net/exylia/lib/util/EffectsTest.java`,
  `TimeFormatsTest.java`.
