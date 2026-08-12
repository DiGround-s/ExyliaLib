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
| `scoreboard` | Available | Packet-level sidebars declared in config, refreshed off the main thread and diffed line by line |
| `hologram` | Available | Floating text, items and blocks sent as display-entity packets, per-player or shared |
| `client` | Available | Waypoints, cooldowns and teammate markers on Lunar and Feather, without the caller knowing which |
| `clan` | Available | One API for SimpleClans, Kingdoms, UltimateClans or any external provider; alliances and rivalries included |
| `util` | Available | Small, self-contained utilities: potion effects from compact strings, and the cooldown base every other cooldown builds on |

---

## Installation

### Server

Drop `ExyliaLib.jar` into `plugins/`. Plugins that depend on it will refuse to
load without it.

Scoreboards need nothing extra installed: the packet-level sidebar library
travels inside the jar, relocated, so there is exactly one copy of it on the
server instead of one per plugin.

The clan module answers questions about clans — whose clan, are they allied,
are they enemies — without the caller knowing which clan plugin is underneath.
Built-in detection covers SimpleClans, Kingdoms and UltimateClans; an external
plugin hands in a bridge. The `util` module is where small, self-contained tools
live — today the potion-effect parser; tomorrow cooldowns, inventory helpers,
or whatever is useful across plugins.

Modified-client features (waypoints, client cooldowns, markers) light up on
their own when Apollo or the Feather server API is installed. With neither, the
calls still work and send nothing.

Holograms do require [PacketEvents](https://github.com/retrooper/packetevents);
without it everything keeps working and nothing is drawn. The holograms
themselves are packets, so the server holds no state for them and they do not
appear in the world file.

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

## Scoreboard module

Sidebars written at the packet level, declared in config, and refreshed off the
main thread.

### Declared in config, not in Java

```java
// player joined the arena
Scoreboards.show(this, player, config.get().scoreboards().ffa());

// the context changed
Scoreboards.get(player).ifPresent(board -> board.updateData(Map.of("arena", arena)));

// player left
Scoreboards.hide(player);
```

The board itself is the server owner's:

```yaml
ffa:
  enabled: true
  title: '{primary}&lFFA'
  lines:
    - ''
    - ' {muted}❙ {letters}Arena: {highlight}%arena_name%'
    - ' {muted}❙ {letters}Kills: {success}%ffa_kills%'
    - ''
    - ' {highlight}exylia.net'
  update:
    interval: 15
    smart: true
    cache: true
```

Write the title as a list and it animates, one frame per refresh:

```yaml
title:
  - '{primary}&lFFA'
  - '{secondary}&lFFA'
```

A placeholder that returns text with line breaks expands into several lines, so
one `%ffa_top%` fills a whole top three. Boards are capped at the client's 15
lines; anything past that is dropped with a warning rather than an exception.

### Files from ExyliaCommons load unchanged

This is the same scoreboard library ExyliaCommons uses, and the section reads
the same keys: `enabled`, `title`, `lines`, `update.interval`, `update.smart`,
`update.cache`. A server moving to a plugin built on ExyliaLib keeps its
scoreboard file exactly as it is.

That is also why `interval` is in **ticks** rather than the seconds the effect
module uses: an existing `interval: 15` has to keep meaning fifteen ticks. The
deviation is deliberate and limited to this section.

`cache` is read and ignored. It did nothing in ExyliaCommons either, and text
parsing here is cached for every plugin at once, with a size limit and an
expiry — which is what a per-board map of values that change every second can
never be.

### Boards stack

Showing a board pauses the one the player already had; taking it down brings it
back:

```
lobby board  ->  event board  ->  event ends  ->  lobby board is back
```

Neither plugin knows about the other. A paused board renders nothing at all
while it waits, and the plugin that showed it can still stop exactly its own
board through the returned `Board` handle, wherever it sits in the stack.

### Cost

An eight line board, four of them with placeholders, measured over 50k
refreshes:

| Case | Cost per refresh |
| --- | --- |
| Nothing changed | ~1.0 µs |
| One value changed | ~2.7 µs |
| No diff (`smart: false`) | ~2.8 µs |

Three decisions produce that. Templates are compiled once, when the board is
shown. Every refresh compares the rendered lines with what the player already
has and sends **only** the ones that differ, so a board whose values did not
move writes zero packets. And what gets parsed is the line's *raw* text, which
never changes and is therefore always a cache hit, with the resolved values
substituted into that parsed component: parsing the resolved string instead
measured 26.8 µs against 4.2 µs per changed line.

One async timer drives every board on the server. Each board renders on a slot
staggered by the player's id, so a reload that recreates two hundred boards in
the same tick spreads their work across the interval instead of piling it into
one.

### Nothing outlives its owner

Boards end when `stop()` or `hide()` is called, when the player leaves, or when
the plugin that showed them is disabled — including boards buried under someone
else's. When the shared palette is reloaded, every board is re-sent, because the
text is unchanged but what it parses into is not.

On a server version without a packet adapter, every call keeps working and the
boards are simply invisible. `Scoreboards.isSupported()` says so, and nothing
requires a caller to check.

---

## Hologram module

Floating text, items and blocks sent as display-entity packets.

Holograms here are **packets and nothing else**. The server does not know they
exist: nothing is ticked, nothing is saved to a chunk, nothing survives into a
world file, and two players can be shown different text at the same
coordinates. Without PacketEvents every call keeps working and nothing is
drawn.

### Declared in config, not in Java

```java
// the event started
Holograms.show(this, "koth-" + arena.id(), arena.centre(), config.get().koth());

// the score changed
Holograms.get(this, "koth-" + arena.id()).ifPresent(Hologram::refresh);

// the event ended
Holograms.remove(this, "koth-" + arena.id());
```

The hologram itself is the server owner's:

```yaml
koth:
  enabled: true
  type: TEXT
  lines:
    - '{warning}⚔ {highlight}&l%event_name%'
    - ' '
    - '{letters}Time: {info}%event_time%'
    - '{letters}Capturing: {highlight}%koth_capturer%'
  view-distance: 32.0
  offset-y: 2.0
  properties:
    billboard: CENTER
    scale-x: 1.2
    scale-y: 1.2
    scale-z: 1.2
    background-color: '#00000000'
  config:
    update-interval: 20
    auto-update: true
```

Set `type` to `ITEM` or `BLOCK` and write `item` or `block` instead of `lines`:

```yaml
trophy:
  enabled: true
  type: ITEM
  item: DIAMOND_SWORD
  properties:
    billboard: FIXED
```

### Files from ExyliaCommons load unchanged

Every key the old `HologramTemplateSerializer` wrote is read with the same
meaning here. Two that are not read are `persistent` (nothing is written back
to disk by the library) and `config.spawn-on-chunk-load` /
`config.remove-on-chunk-unload` (a hologram is packets, so it costs nothing
when nobody can see it).

### Viewers

A player only receives packets when they cross the hologram's view distance
and only while they stay inside it; nothing is sent every tick. A visibility
filter on the returned `Hologram` can hide it from specific players entirely.
Moving it teleports the displays rather than respawning them, so a hologram
that follows something does not flicker.

Holograms are shared by default: one render goes to every viewer. Turn on
`per-player` only when the lines must differ per viewer, since then each one
renders and spawns separately.

### Cost

A hologram whose lines contain no placeholders never schedules a refresh at
all, so a sign that says "Spawn" costs one packet per viewer, once. A changing
line is only re-sent when its text actually changed, and only the changed line
is sent, not the whole hologram.

Visibility is checked four times a second: a squared distance per player per
hologram, sending packets only when someone crosses the edge.

### Nothing outlives its owner

Holograms end when `remove()` is called, when the plugin is disabled, or on
shutdown. A player who leaves is forgotten without sending them a packet. When
the shared palette is reloaded, every hologram is re-sent, because the text is
unchanged but what it parses into is not.

On a server without PacketEvents, every call keeps working and nothing is
drawn. `Holograms.isSupported()` says so.

---

## Client module

Talks to modified clients — Lunar through Apollo, and Feather — without the
caller ever asking which one a player runs.

```java
Clients.waypoints().show(player, Waypoint.at("Koth", arena.centre()).colour("#8a51c4"));
Clients.cooldowns().show(player, Cooldown.seconds("pearl", 16).icon(Icon.item("ENDER_PEARL")));
Clients.markers().updateTeam(team.members());
```

### A vanilla player is not a special case

Every call answers for any player. Vanilla, a client that does not support that
feature, or a server with no integration installed at all: the call costs a map
lookup and sends nothing. That is the whole design — a plugin says what the
player *should* see, and whoever can show it, shows it.

| Feature | Lunar | Feather | Vanilla |
| --- | --- | --- | --- |
| Waypoints | yes | yes | — |
| Cooldowns | yes | — | — |
| Teammate markers | yes | — | — |

`Clients.brandOf(player)` is there for a join message or a statistic, not for
branching before a call.

### What is remembered, and why

Clients forget everything when a player reconnects, and Feather forgets
waypoints along with the world they belonged to. The library remembers what it
sent and puts it back — after a world change, only the waypoints belonging to
the world the player is now in.

Without that, every plugin grows its own "re-send my waypoints on join"
listener and they all get it slightly wrong. Nothing is written to disk: a
waypoint is a thing on a screen, not a record, so a restart clears them.

Detection is asked once, a second after the player joins, and remembered until
they leave — a modified client announces itself a moment *after* joining, so an
immediate question gets "vanilla" and would poison the answer for the whole
session.

### Adding a client

One class implementing `ClientLink`, one line in `ClientRegistry`. Nothing else
in the module, and nothing at all outside it, knows how many clients exist. A
client that cannot do something returns `false` from the matching `supports`
method instead of throwing, because a missing feature is normal, not
exceptional.

An integration that throws is contained and logged: the plugin that asked for a
waypoint did nothing wrong, and somebody else's bug must not surface as theirs.

---

## Clan module

A single API for every clan plugin. The caller never branches on which one runs.

```java
Clans.clanOf(player).ifPresent(clan -> {
    for (String ally : clan.allies()) {
        // notify everyone who would care
    }
});

boolean friendlyFire = Clans.areAllied(attacker, defender);
boolean war = Clans.areRivals(attacker, defender);
```

### Three providers built in

SimpleClans, Kingdoms and UltimateClans are detected automatically when their
plugin is enabled. All three go through reflection — nothing is compiled
against, so a server with none of them never tries to load a missing class.

| Feature | SimpleClans | Kingdoms | UltimateClans |
|---|---|---|---|
| Members, leaders, moderators | yes | yes | leader and members |
| Alliances | yes | yes | no |
| Rivalries | yes | yes | no |

What a plugin does not have returns an empty set. The caller never needs to know
whether "no allies" means the plugin lacks the concept or the clan simply has
none — the answer is the same either way.

### External providers

A plugin outside ExyliaLib implements `ClanBridge` and registers it:

```java
Clans.registerBridge(new MyClanBridge(), 10);
```

The bridge speaks only UUIDs, strings and primitives — no Bukkit, no Exylia
types — so it can live anywhere. A registered bridge beats automatic detection,
and a higher priority beats a lower one.

### What is remembered

A player's clan is cached for a few seconds, because the question sits on hot
paths: a damage event, a kill message, a scoreboard refresh. The cache is
emptied by `Clans.invalidate()` and when a player leaves.

---

## Util module

A pocket for small, self-contained tools. Each one stands alone: no dependencies
between utilities, and nothing outside the module knows how they work inside.

### Effects

Potion effects from a compact string — the format kits and minigames keep in
their configs:

```java
Effects.apply(player, "SPEED:1:300|JUMP_BOOST:2:120");
```

- `|` separates effects, `:` separates name, amplifier and duration
- Amplifier defaults to `0`, duration to `200` ticks (10 seconds), so `"SPEED"`
  on its own is valid
- The same string is parsed once and cached for 30 seconds
- Unknown names are skipped and an empty string yields nothing: a typo in a
  config is not worth an exception

The parser is pure. `Effects.parse("...")` returns `ParsedEffect` records made
of strings and ints, with no Bukkit types, so config parsing can be tested
without a server.

### Cooldowns

The thing every plugin rewrites — an ability on a timer, a command that cannot
be spammed:

```java
if (!Cooldowns.tryStart(player, "pearl", Duration.ofSeconds(16))) {
    player.sendMessage("Wait " + Cooldowns.remainingSeconds(player, "pearl") + "s");
    return;
}
```

`tryStart` is the whole guard in one call: it starts the cooldown and says
whether it was free. A refusal never extends the cooldown already running —
that is the bug this exists to replace.

Nothing is ticked down. An expiry instant is stored once and compared on read,
so a thousand idle cooldowns cost nothing until somebody asks. Expired entries
are dropped by the read that notices them, and a player's entire set is dropped
when they leave, so the map cannot grow without bound.

Seconds round **up**: with 400 ms left `remainingSeconds` says `1`, not `0`.
Telling players "0 seconds" while still refusing the action is a lie.

#### Owners other than a player

Most cooldowns belong to a player, and every method has an overload for that.
Anything else uses a scope:

```java
Cooldowns.start(CooldownScope.GLOBAL, "world-boss", Duration.ofHours(4));
Cooldowns.start(CooldownScope.group(clanId), "war-declare", Duration.ofDays(1));
Cooldowns.start(CooldownScope.of("region", "spawn"), "pvp-grace", Duration.ofMinutes(1));
```

A scope is its kind *and* its id, so a clan called `red` and a team called `red`
are two different owners.

#### Keys that cannot collide

A key is any string, and two plugins both using `"pearl"` share one cooldown —
occasionally what you want, usually a bug. Take a namespaced view and stop
thinking about it:

```java
private final PluginCooldowns cooldowns = Cooldowns.forPlugin(this);
cooldowns.tryStart(player, "pearl", Duration.ofSeconds(16));  // myplugin:pearl
```

#### Surviving a restart

Cooldowns of **five minutes or more** are written to disk and come back with the
server. Shorter ones are not: a sixteen-second cooldown is worth less than the
disk write it costs, and it expires before anybody could read it back.

Nothing is configured and nothing is asked of the caller — the duration decides.
Writes go off the main thread, only for owners whose long cooldowns changed, and
happen on quit, every five minutes, and at shutdown. Loading is async on join.

The file is one line per cooldown, expiry first so keys may contain spaces:

```
1763925600000 myplugin:daily-reward
```

Written to a temporary file and moved into place, so a server killed mid-write
leaves either the old file or the new one, never half of either.

#### What it costs

Measured over two million calls with two hundred players:

| Call | Cost |
| --- | --- |
| `isActive`, cooldown running | ~32 ns |
| `isActive`, key never set (the common case) | ~8 ns |
| `isActive`, unknown player | ~4 ns |
| `start` | ~49 ns |

Two hundred players checking once a tick is about **0.013% of a tick**. Player
scopes are cached rather than rebuilt per call, which is worth 7 ns on the hot
path and about half the cost of a miss.

### Item cooldowns

The base, plus the sweep the client draws over the item:

```java
if (!ItemCooldowns.tryStart(player, Material.ENDER_PEARL, Duration.ofSeconds(16))) {
    return; // the player is already watching the overlay
}
```

Bukkit's own `setCooldown` draws the overlay and blocks the item, which is most
of the job — so it is kept for exactly that. What it will not do is survive a
restart, apply to something that is not a material, or tell another plugin what
is going on, so the authoritative answer stays in `Cooldowns`.

An item that is more than its material gets its own key and still shows the
overlay of whatever it is made of:

```java
ItemCooldowns.tryStart(player, "fire-wand", Material.BLAZE_ROD, Duration.ofSeconds(30));
```

Item keys live under `item:`, so a plugin using the plain key `"ender_pearl"`
for something else is untouched. Long item cooldowns persist for free, because
they are ordinary cooldowns underneath.

`ItemCooldowns.restore(player, material)` redraws the sweep for what is still
running — the client forgets it on reconnect, so a cooldown that survived on the
server would otherwise look free until used.

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
