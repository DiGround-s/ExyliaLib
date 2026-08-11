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
