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
Effects.apply(player, "SPEED:1:300|JUMP_BOOST:2:120");
```

Format: `NAME:amplifier:durationTicks`, entries joined with `|`. Missing
parts default to amplifier `0` and duration `200` ticks. Unknown or malformed
entries are skipped, never fatal.

| Method | Contract |
| --- | --- |
| `parse(raw)` → `ParsedEffect[]` | pure parse — `ParsedEffect(name, amplifier, duration)` holds standard Java types, no Bukkit types |
| `apply(player, raw)` / `apply(player, ParsedEffect...)` | resolve and apply |
| `clear(player)` | remove what was applied |

- Parsing is cached in Caffeine for 30 seconds.
- The resolver (`PotionEffectType.getByName`) and applier
  (`addPotionEffect`) are injectable for tests.

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
