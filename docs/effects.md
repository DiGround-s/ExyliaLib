# Effect module

Titles, action bars, boss bars, sounds, particles and fireworks — declared in
config, played by one call. Since 1.4.0.

Entry point: `net.exylia.lib.effect.Effects`.

## Declared in config, not in Java

The plugin says *what happened*; the owner decides what that looks like.
`EffectConfig` nests inside the plugin's config record:

```java
Effects.play(config.onWin(), player);
```

`EffectConfig` has six optional sections; an unwritten section does nothing
(new `EffectConfig()` is the all-empty effect):

| Section | Fields |
| --- | --- |
| `Title` | `text`, `subtitle`, `fadeIn`, `stay`, `fadeOut`, `countdown`, `timeStyle` |
| `ActionBar` | `text`, `duration`, `countdown`, `timeStyle` |
| `BossBar` | `text`, `colour`, `overlay`, `countdown`, `countUp`, `progress`, `timeStyle` |
| `Sound` | `name`, `volume`, `pitch`, `category` |
| `Particle` | `name`, `count`, `spread`, `speed` |
| `Firework` | `colours`, `fades`, `shape`, `flicker`, `trail` |

Times are **seconds with decimals** — `countdown(3.3)` is 3.3 real seconds,
and `%time%` displays it as `3.3`. `%time%` belongs to the effect, never to
the global registry: two countdowns on screen must not show the same number.
`timeStyle` is a `TimeFormats` style name (`auto`, `tenths`, `clock`, ...).

Programmatic builders also exist: `Effects.title(text)`, `.actionBar(text)`,
`.bossBar(text)`, `.particle(name)`, `.sound(name)`, `.firework()`.

## A whole effect on one config line

Class and kit YAMLs often carry the whole sound or particle in a single
field, so the caller should not have to split it:

```java
Effects.soundFrom("BLOCK_ANVIL_PLACE|1|1").show(player);
Effects.particleFrom("CLOUD|80|1.5|1.5|1.5|1.5").at(location).show(player);
Effects.particleFrom("FLAME|20|0.5").at(location).show(player);  // one spread value
```

Notation: `NAME|volume|pitch` for sounds; `NAME|count|dx|dy|dz|speed` for
particles, with a lone `NAME|count|spread|speed` also accepted. Pipe is the
one separator — it is what every production config already writes, and a
namespaced key such as `minecraft:flame` carries a colon that must survive
the split. Missing parts fall back to full volume/pitch, count 1, no spread.

## Timers

`net.exylia.lib.effect.Timer` is the clock behind a timed effect — a value,
not a task:

- `Timer.countdown(seconds)` / `countdownTicks(ticks)` — runs to zero.
- `Timer.countUp()` / `countUp(total)` — elapsed-time displays.
- `Timer.ofCooldown(player, key)` / `ofCooldown(player, key, totalSeconds)` /
  `ofCooldown(scope, key)` — **reads a running `Cooldowns` cooldown** instead
  of counting on its own. The cooldown stays the truth (shared, persistent);
  the display just looks at it and finishes the moment the cooldown does.
  `advance`/`extend` do nothing on such a timer — give time through
  `Cooldowns` and the bar shows it.

Members: `advance(ticks)`, `extend(ticks)`, `remaining()`, `elapsed()`,
`remainingTicks()`, `elapsedTicks()`, `total()`, `progress()` (always 0–1),
`finished()`, `isCountdown()`, `displayed()`.

`Ticks`: `MILLIS = 50`, `PER_SECOND = 20`, `fromSeconds(double)`,
`toSeconds(long)`, `fromMillis`, `toMillis`, and `parse(text, fallback)`
understanding `s`, `ms`, `t`, `m`, `h` suffixes.

## Playing and stopping

- `Effects.play(EffectConfig, viewer)` → `Display`.
- `Effects.playAll(EffectConfig)` — every online viewer.
- `Effects.stopAll(pluginName)` / `stopFor(viewer)` / `active()`.

`Display`: `stop()`, `isShowing()`, `text(String)` (re-render),
`addTime(seconds)`, `onEnd(Runnable)` (runs **exactly once**, however it
ends), `progress(float)`.

## Contracts

- **Packets are a preference, never a requirement.** If the packet registry does
  not know a name — or PacketEvents cannot even be seen through the classloader
  — the effect goes out through the Bukkit API instead. A false from the packet
  path means "I do not know this name", not "it played".
- **A sound name resolves through the enum, not through string rules.**
  `BLOCK_NOTE_BLOCK_PLING` is `block.note_block.pling` — an underscore *inside*
  the key — while `ENTITY_PLAYER_LEVELUP` is `entity.player.levelup`. No
  underscore-to-dot rule survives both, and inventing the wrong key is answered
  by the client with silence.
- **A custom key is passed to Bukkit unchanged, namespace included.** The string
  API cannot validate resource-pack sounds server-side; mutating them only
  breaks the valid ones.



- Without a timer the effect stays until stopped.
- Static text never schedules a task: nothing changing means one packet, not
  a per-tick task.
- Everything is packets; fireworks are the exception (spawned and detonated
  the same tick).
- Nothing outlives its owner: quit, plugin disable and palette reload all
  clean up.

## Source and tests

- Public: `effect/Effects.java`, `Timer.java`, `Ticks.java`, `Display.java`,
  `EffectConfig.java`.
- Internal: `effect/internal/` (`SimpleTimer`, `CooldownTimer`,
  `ActiveDisplay`, `Rendered`, `Displays`, `Bars`, `Packets`, `PacketSender`,
  builders, `ConfigPlayer`, `EffectRuntime`).
- Tests: `src/test/java/net/exylia/lib/effect/` (`TimerTest`, `DisplayTest`,
  `EffectConfigTest`, `TimeTextTest`).
