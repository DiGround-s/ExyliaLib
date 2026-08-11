# ExyliaLib

Shared, high-performance library for Exylia Minecraft plugins.

ExyliaLib is installed **once** on the server and used as a dependency by every
Exylia plugin. It is not shaded into each plugin: one copy, one class, one cache.

The guiding principle is that the server should do as little work as possible.
Where an operation can be expressed as a packet instead of real server state,
ExyliaLib will prefer the packet, via
[PacketEvents](https://github.com/retrooper/packetevents).

- **Java 21**, Paper API **1.21.4**
- Runs on **Spigot, Paper, Purpur and Folia** from a single build

Contributing, or curious about why the library is built this way? The design
rules live in [AGENTS.md](AGENTS.md).

---

## Modules

| Module | Status | Description |
| ------ | ------ | ----------- |
| `task` | Available | Unified scheduling across Bukkit and Folia |
| `config` | Available | YAML configs declared as records, generated and upgraded automatically |
| `text` | Available | Colours and formatting: palette tokens, legacy codes and MiniMessage, parsed once and cached |
| `placeholder` | Available | One resolver type, grouped registration, formats and fallbacks, PlaceholderAPI both ways |
| `effect` | Available | Titles, action bars, boss bars, particles, sounds and fireworks, sent as packets and declared in config |

---

## Installation

### Server

Drop `ExyliaLib.jar` into `plugins/`. Plugins that depend on it will refuse to
load without it.

### Build script

ExyliaLib is `compileOnly`: the server provides it at runtime, so it must never
be shaded into your plugin.

<details open>
<summary><b>Gradle (Groovy)</b></summary>

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.DiGround-s:ExyliaLib:1.0.0'
}
```
</details>

<details>
<summary><b>Gradle (Kotlin)</b></summary>

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.DiGround-s:ExyliaLib:1.0.0")
}
```
</details>

<details>
<summary><b>Maven</b></summary>

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.DiGround-s</groupId>
    <artifactId>ExyliaLib</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```
</details>

### plugin.yml

```yaml
depend: [ExyliaLib]
```

---

## Task module

One API, correct on every platform. You write the same code for Spigot and
Folia.

### Getting a scheduler

```java
public final class MyPlugin extends JavaPlugin {

    private TaskScheduler tasks;

    @Override
    public void onEnable() {
        this.tasks = Tasks.of(this);
    }
}
```

There is nothing to shut down. When your plugin is disabled, ExyliaLib cancels
every task it scheduled for you.

### Choosing a method

The only decision is **what the task touches**. That is what makes a plugin
Folia-compatible.

| The task touches... | Use |
| ------------------- | --- |
| a specific entity or player | `runAtEntity(...)` |
| blocks, chunks, a world position | `runAtLocation(...)` |
| nothing thread-bound (HTTP, database, files) | `runAsync(...)` |
| global server state (weather, whitelist, plugin state) | `run(...)` |

On Spigot and Paper all non-async variants run on the main thread, so picking
the precise one costs nothing and makes your plugin run on Folia unchanged.

### Examples

```java
// Global state, next tick
tasks.run(() -> getServer().setWhitelist(true));

// After 5 seconds (100 ticks)
tasks.runLater(100L, () -> broadcast("Starting!"));

// Every second
TaskHandle timer = tasks.runTimer(0L, 20L, this::tick);
timer.cancel();

// A timer that stops itself
tasks.runTimer(0L, 20L, handle -> {
    if (--countdown <= 0) {
        handle.cancel();
        return;
    }
    showCountdown(countdown);
});

// Anything touching a player or entity
tasks.runAtEntity(player, () -> player.setHealth(20.0));

// ...with a fallback if the entity is gone by then
tasks.runAtEntity(player, () -> player.teleport(spawn), () -> log("player left"));

// An effect attached to an entity; stops on its own when the entity is removed
tasks.runAtEntityTimer(player, 0L, 10L, () -> player.getWorld()
        .spawnParticle(Particle.FLAME, player.getLocation(), 3));

// Anything touching blocks or chunks
tasks.runAtLocation(location, () -> location.getBlock().setType(Material.STONE));

// Off-thread work, then back to the entity's thread to apply the result
tasks.runAsync(() -> {
    PlayerData data = database.load(player.getUniqueId());
    tasks.runAtEntity(player, () -> apply(player, data));
});
```

### Time

Every delay and period is in **ticks** (20 ticks = 1 second), including the async
methods, which convert internally. Values below `1` are raised to `1`, because
Folia rejects non-positive delays.

### Thread checks

```java
tasks.isGlobalThread();     // may I touch global server state?
tasks.isOwnedBy(entity);    // may I touch this entity right now?
tasks.isOwnedBy(location);  // may I touch this location right now?
```

`execute(Runnable)` runs the task inline when the caller is already on the
global thread, avoiding a pointless one-tick delay. Note that it may therefore
run before the call returns.

### What ExyliaLib handles for you

| | |
| --- | --- |
| **Automatic cleanup** | Every task is cancelled when your plugin is disabled. Folia's region and entity schedulers do not do this on their own. |
| **Exception isolation** | A task that throws is logged against your plugin instead of escaping into the scheduler. A repeating task that throws is cancelled rather than repeating in a broken state. |
| **Consistent entity timers** | An entity timer stops when the entity is removed, on both platforms. |
| **Safe cancellation** | `cancel()` works even if called before the scheduler has finished registering the task, and is safe to call repeatedly and from any thread. |
| **No class-loading traps** | Folia types are confined to a single class, loaded only on Folia. |

---

## Config module

Configs are declared as records and used as records. You do not write YAML, and
you do not look up values by string.

### Declare

```java
@Comment("Storage settings.")
@Comment("Changes apply on the next restart.")
public record Storage(
        @Comment("Connections kept open. Rule of thumb: cores x 2.")
        int poolSize,

        @Comment("Where player data lives.")
        Backend backend,

        List<String> ignoredWorlds,

        Cache cache
) {
    public Storage() {
        this(10, Backend.MYSQL, List.of("world_nether"), new Cache());
    }

    @Comment("How long player data stays in memory.")
    public record Cache(int ttlMinutes, boolean enabled) {
        public Cache() {
            this(30, true);
        }
    }
}
```

The no-argument constructor holds the defaults, so the file shipped to servers
and the values read by the code come from the same place.

### Use

```java
ConfigFile<Storage> storage = Configs.define(this, "storage", Storage.class).load();

int pool = storage.get().poolSize();
int ttl = storage.get().cache().ttlMinutes();
```

Reading a value is a field access on a record: no map lookup, no parsing, no
reflection. That makes it safe on hot paths, unlike calling `getInt(...)` inside
an event handler.

### The file it generates

```yaml
# Storage settings.
# Changes apply on the next restart.

# Connections kept open. Rule of thumb: cores x 2.
pool-size: 10

# Where player data lives.
backend: mysql
ignored-worlds:
- world_nether

# How long player data stays in memory.
cache:
  ttl-minutes: 30
  enabled: true

# Layout version of this file. ExyliaLib uses it to upgrade the file automatically.
# Do not edit.
config-version: 1
```

`camelCase` becomes `kebab-case`, nested records become sections, and enums are
written in lower case. Use `@Key("...")` when a file has to keep a name the
convention would not produce.

### Upgrading existing files

Add a component to the record and the key appears in every existing file on the
next start, with its comment, without touching anything the user edited. Keys
ExyliaLib does not own are left alone.

Renaming or changing the meaning of a key needs a migration, so the value a
server owner already set is carried over instead of silently reset:

```java
Configs.define(this, "storage", Storage.class)
       .version(3)
       .migration(1, Migration.rename("pool", "pool-size"))
       .migration(2, Migration.transform("cache.ttl", seconds -> ((Number) seconds).intValue() / 60))
       .load();
```

Each step is bound to the version it upgrades *from*, and the file records the
version it reached, so every step runs exactly once no matter how often the
server restarts.

### Reloading

```java
List<ConfigIssue> issues = Configs.reloadAll(this);

storage.onReload(values -> restartTimer(values.cache().ttlMinutes()));
```

`get()` returns an immutable snapshot that is swapped atomically, so code already
running finishes with the values it started with and no reader ever sees a
half-applied config.

### Writing

```java
storage.update(current -> new Storage(
        newPool, current.backend(), current.ignoredWorlds(), current.cache()));
```

Comments and unknown keys survive the write, and the file is written through a
temporary file so a crash mid-write cannot truncate it.

### When the file is wrong

A typo never stops a server. The default is used, and the problem is reported:

```
[storage.yml] pool-size: expected a whole number but found "ten", using 10
```

| Situation | What happens |
| --- | --- |
| Wrong type | Default is used, issue reported |
| Missing key | Default is used, key added to the file |
| Unknown key | Left untouched, mentioned in the log |
| Unparseable file | File is left as-is so nothing is lost; previous values stay in use |

Values are read the way people write YAML: `"25"` is a number, `yes` is `true`,
`very-safe` matches the `VERY_SAFE` enum constant, and a lone value where a list
belongs becomes a one element list. What is *not* guessed is anything that could
silently change behaviour, so `2.5` for a whole number is reported rather than
rounded.

---

## Text module

Every player-facing string goes through here: chat, titles, action bars, item
names, lore, scoreboards. Output is always an Adventure `Component`.

### Notations

All three work, mixed freely in the same string.

| Form | Example |
| --- | --- |
| Palette token | `{primary}`, `{error}`, `{highlight}` |
| Legacy code | `&a`, `&l`, `&r` |
| Legacy hex | `&#8a51c4`, `&x&8&a&5&1&c&4` |
| MiniMessage | `<bold>`, `<gradient:#8a51c4:#ff6b9d>` |

```java
Text.of("{primary}&lWELCOME &8[{success}online&8]").send(player);
Text.of("<gradient:#8a51c4:#ff6b9d>Exylia</gradient>").send(player);
```

### Values that change

Substitution happens after parsing, on the component tree, so the template still
hits the parse cache no matter how often the value changes:

```java
Text.of("{letters}Coins: {highlight}%coins%")
    .with("%coins%", coins)
    .send(player);
```

A substituted value is inserted as literal text, so a player named `&cX` shows
up as `&cX` rather than as red text.

### The palette

Messages name a role, not a colour. The palette lives in
`plugins/ExyliaLib/colors.yml`, so a server owner recolours every Exylia plugin
at once.

```java
TextColor accent = Colors.get("accent");   // when a colour is needed as a value
```

Both `{secondary_light}` and `{secondaryLight}` resolve to the same colour.

### Performance

This module is on the hot path of everything, so the work is staged. Measured on
this machine, 200k iterations after warmup:

| Case | Cost | Against a full parse |
| --- | --- | --- |
| Plain text, no formatting | 22 ns | 348x faster |
| Formatted, cached | 137 ns | 57x faster |
| Template + changing value | 429 ns | 18x faster |
| Full parse (cache miss) | 7829 ns | — |
| Raw MiniMessage, for reference | 7417 ns | — |

Three things get you there:

- **A one-pass scan.** Text with no `&`, `{` or `<` skips the pipeline entirely
  and becomes a plain component.
- **One parse, not three.** Palette tokens and legacy codes are rewritten into
  MiniMessage and parsed once, instead of running three parsers in sequence.
- **A bounded cache.** Repeated text is parsed once and reused, which is what
  makes a scoreboard line rebuilt every tick affordable. The cache is capped and
  expiring, so unique strings cannot turn it into a leak.

### Bad input

A malformed tag never costs a message: the raw text is shown instead. An unknown
`{token}` is left untouched, because plugins use braces for their own
placeholders and eating them silently would be a bug that only surfaces in
production.

### Adventure version

ExyliaLib compiles against **Adventure 4.20.0**, the version `paper-api 1.21.4`
ships. Both the server's copy and any newer one live in the `net.kyori.adventure`
package, so the server's classes win at runtime regardless of what a plugin
compiles against. Building against a newer Adventure would compile cleanly and
then fail with `NoSuchMethodError` on a real server, so the version is pinned in
`build.gradle` rather than merely requested.

---

## Placeholder module

### Registering

There is **one** resolver type and **one** way to register. A placeholder is a
function from a request to a value; whether it needs a player, arguments, both
or neither is decided by what it reads, not by choosing a category first.

```java
Placeholders.group(this, "clan")
        .add("name", r -> clans.of(r.requireViewer()).name())
        .add("members", r -> clans.of(r.requireViewer()).size())
        .add("top", r -> clans.leaderboard().at(r.arg(0, 1)))
        .register();
```

That declares `%clan_name%`, `%clan_members%` and `%clan_top_3%`. The prefix is
written once, and the whole group is removed automatically when the plugin is
disabled.

### Syntax

| Form | Meaning |
| --- | --- |
| `%eco_balance%` | plain |
| `%clan_top_3%` | argument `3`, already parsed |
| `%eco_balance:comma%` | formatted as `1,250,000` |
| `%clan_name\|No clan%` | fallback when there is no value |
| `%eco_balance:comma\|0%` | both |
| `%%` | a literal percent sign |

Formats: `comma`, `compact`, `percent`, `upper`, `lower`, `yesno`, `time`,
`fixed1`, `fixed2`, or any `DecimalFormat` pattern such as `#,##0.00`.

Formatting lives here so the server owner controls presentation from the config,
and the plugin only supplies the number.

### Using

```java
String text = Placeholders.apply("Welcome %player_name%", player);
```

Together with colours, which is the common case:

```java
Text.of("{primary}&lWELCOME {letters}%player_name%").forPlayer(player).send(player);
```

For a line rendered again and again, compile it once:

```java
Template line = Placeholders.compile("Coins: %eco_balance:comma%");
String rendered = line.render(player);   // every tick
```

### Performance

Median of nine batches of 200k iterations, after warmup:

| Case | Cost | Against the old approach |
| --- | --- | --- |
| Plain text, no placeholders | 33 ns | 12x faster |
| Held template, two placeholders | 58 ns | 6.9x faster |
| Cached template, two placeholders | 196 ns | 2.0x faster |
| Formatted value | 214 ns | — |
| Regex approach, same line | 401 ns | — |

The old approach re-ran a regex over the whole string until it stopped changing,
up to ten times. This walks the text once, at compile time only, and rendering
just resolves and joins.

Holding a `Template` is **3.4x faster** than passing the same string to `apply`,
because it skips the cache lookup entirely. That is why scoreboards should
compile their lines once.

### Threading

Registering and rendering are safe from any thread, and the registry is built
for concurrent reads. Whether a *specific* resolver may run off the main thread
is the resolver's own claim, declared with `.async()`. The built-in ones read
live Bukkit state and are therefore not marked async.

### Failure is contained

A resolver that throws is logged **once** and treated as having no value, so one
broken placeholder cannot take down a scoreboard that renders every tick. An
unknown placeholder is left visible rather than blanked, because a silent empty
string hides a typo until a player reports it.

`Placeholders.unresolved(text)` returns the names nothing can resolve, which is
what a diagnostics command should show a server owner.

### PlaceholderAPI

The bridge works in both directions, and neither requires writing an expansion:

- everything registered here is exposed to PlaceholderAPI automatically, under
  the owning plugin's name;
- `%...%` placeholders from other plugins resolve inside Exylia text.

PlaceholderAPI is optional. Every reference to its classes is confined to a
single class that is only loaded once the plugin is known to be present, and the
module is verified to run with it absent from the classpath entirely.

### Built-in placeholders

Provided once so no plugin re-declares them: `%player_name%`,
`%player_displayname%`, `%player_uuid%`, `%player_world%`, `%player_health%`,
`%player_level%`, `%player_food%`, `%player_gamemode%`, `%player_ping%`,
`%player_x%`, `%player_y%`, `%player_z%`, `%target_name%`, `%target_uuid%`,
`%server_online%`, `%server_max%`, `%server_tps%`.

---

## Effect module

Everything a player sees or hears: titles, action bars, boss bars, particles,
sounds and fireworks.

### Declared in config, not in Java

The point of the module. A plugin says *what happened*; the server owner decides
what it looks like.

```java
Effects.play(config.get().onWin(), player);
```

```yaml
on-win:
  title:
    text: '{primary}VICTORY'
    subtitle: '{letters}Well played, %player_name%'
    stay: 3.0
  sound:
    name: ENTITY_PLAYER_LEVELUP
    volume: 0.8
  firework:
    colours: ['#8a51c4', '#ff6b9d']
    shape: BALL_LARGE
```

`EffectConfig` nests inside a plugin's own config record like any other section,
so the file is generated with its comments and read back as a record. Every
section is optional: what is not written does not play.

### Timers, with decimals

Time is written in seconds and decimals are real, because a countdown showing
`3.3s` has to be driven by something finer than a whole second.

```java
Effects.bossBar("{primary}Starting in {highlight}%time%s")
        .countdown(10.5)
        .timeStyle("tenths")
        .onEnd(this::startMatch)
        .show(player);
```

Inside effect text, the timer's own clock is available as `%time%`, plus
`%time_total%`, `%time_elapsed%` and `%time_remaining%`. It is not a globally
registered placeholder: a timer belongs to one effect, so two countdowns on
screen report their own values rather than sharing one.

| Direction | What it does |
| --- | --- |
| `countdown(seconds)` | runs to zero; a boss bar empties |
| `countUp()` | runs upwards forever; a bar stays full |
| `countUp(seconds)` | runs upwards to a total; a bar fills |

Time styles: `auto` (tenths under ten seconds, then whole, then a clock),
`seconds`, `tenths`, `hundredths`, `clock`, `full`.

Durations in config accept units: `3.3s`, `500ms`, `40t`, `2m`, `1h`.

### Effects that stay

Without a timer, a title, action bar or boss bar stays until stopped:

```java
Display bar = Effects.bossBar("{letters}Waiting for players").show(player);
bar.stop();
```

A `Display` can be re-texted, extended, or given an `onEnd` action that runs
**exactly once**, whether it ended by timer, by `stop()`, or because the plugin
was disabled.

### Sent as packets

With PacketEvents installed, effects go out as packets and the server keeps no
state: no boss bar object in a registry, no entity ticking, nothing to clean up
if a player disconnects mid-effect. Without it, everything still works through
the Bukkit API.

That also makes effects per-player by default, which is what allows a particle
outline or a preview to be shown to one person and nobody else.

The one exception is fireworks: the explosion is driven by an entity, so one is
spawned and detonated in the same tick rather than left to tick.

### Cost

Median of nine batches of 200k redraws:

| Case | Cost |
| --- | --- |
| Static text redraw | 5 ns |
| Countdown text redraw | 652 ns |
| Timer advance and progress | 18 ns |

Two things keep it there. Text with nothing dynamic is rendered once and reused,
so a permanent bar reading "Waiting for players" **schedules no task at all**.
And only the time placeholders a line actually uses are substituted: doing all
four when the text says `%time%` was 2.5x slower, measured.

### Nothing outlives its owner

An effect that is never stopped is a bar the player can see and no command can
remove. Every display is registered and ends when its timer finishes, when
`stop()` is called, when its viewer leaves, or when the plugin is disabled.

`Effects.active()` returns how many are showing, which is the number to watch if
a plugin is suspected of leaking them.

---

## Building

```bash
./gradlew build                 # compile, test, jar
./gradlew publishToMavenLocal   # install into ~/.m2 for local development
```

To consume the local build, add `mavenLocal()` to your repositories and depend on
`net.exylia:ExyliaLib:1.0.0`.

---

## License

[MIT](LICENSE)
