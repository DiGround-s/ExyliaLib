# Cooldowns — the base every cooldown sits on

`net.exylia.lib.util.Cooldowns` is **the** cooldown implementation of the
ecosystem. Items, chat channels, rewards and anything future build on top of
it, never in parallel. ExyliaCommons had four separate implementations and one
of them returned `0` from `getRemainingSeconds()` forever — nobody reviews the
fourth copy of a thing. Since 1.10.0; scopes and persistence since 1.11.0;
decimal time since 1.12.0; network cooldowns since 1.184.0.

## The idea

A cooldown is a `CooldownScope` plus a key, mapped to an **expiry instant**.
No task counts anything down: the map is compared when read, so a thousand
idle cooldowns cost nothing. Expired entries are purged by the read that
notices them; a quitting player's map is forgotten whole.

## Scopes

`CooldownScope` — who owns a cooldown:

| Factory | Owner |
| --- | --- |
| `CooldownScope.player(UUID | Player)` | a player (instances are cached — 7 ns on the hot path, never `UUID.toString()` per call) |
| `CooldownScope.GLOBAL` | the whole server (a world boss) |
| `CooldownScope.group(id)` | a group such as a clan |
| `CooldownScope.of(type, id)` | anything else; the type is part of the identity, so `clan:red` and `team:red` are different owners and different files |

## Using

```java
if (!Cooldowns.tryStart(player, "pearl", Duration.ofSeconds(16))) {
    player.sendMessage("Wait " + Cooldowns.remainingFormatted(player, "pearl") + "s");
    return;
}
```

`Cooldowns` (static):

| Method | Contract |
| --- | --- |
| `start(player|UUID|scope, key, Duration)` / `startSeconds` / `startTicks` | start or restart |
| `tryStart(...)` | start only if free; the whole guard in one call |
| `isActive(player|UUID|scope, key)` | running? |
| `hasAny(player|scope)` / `hasAny(scope, keyPrefix)` | anything running for this owner? one lookup instead of one per key — for a per-tick readout |
| `remaining(...)` | millis (`long`, scope/UUID forms) or `Duration` (player form) |
| `remainingSeconds(...)` | seconds **with decimals** as `double` — `3.3`, not `3` |
| `remainingWholeSeconds(...)` | seconds rounded **up** — for "wait N seconds" refusals |
| `remainingFormatted(player, key)` / with a `TimeFormats.Style` | text ready to show, through the library's one time formatter |
| `clear(...)` / `clearAll(...)` | remove |
| `forget(UUID)` | drop a player entirely (done on quit) |
| `forPlugin(plugin)` / `namespaced(namespace)` | a namespaced view — two plugins both calling something `"pearl"` is usually a bug |
| `load(scope|UUID)`, `flush(scope)`, `flushAll()` | persistence, normally driven by the library itself |

`PluginCooldowns` (the namespaced view) mirrors the same methods minus
lifecycle.

## Persistence — automatic, by duration

`PERSIST_THRESHOLD = 5 minutes`. **Duration decides, not the caller:**
five minutes or more is written to disk; less is not. A 16-second cooldown is
worth less than the write and would expire before being read back; a daily
reward is not.

- Writes are async, only for owners whose long cooldowns changed (a dirty
  set), to a temporary file moved into place atomically — a server killed
  mid-write leaves either the old file or the new one.
- One compact file per scope under the plugin's data folder, expiry first.
- Loading is async on join. Anything that expired while the server was down
  is dropped by the reader, not loaded and swept.
- State started in memory after boot wins over older disk data
  (`putIfAbsent`).
- Flushes happen on quit, on shutdown, and on an async timer every 5 minutes
  (`ExyliaLib` drives all of this).

## Network cooldowns (since 1.184.0)

`Cooldowns` is one server's memory, and keeps only what runs five minutes or
more: a relog or a server hop resets anything shorter. For a cooldown worth
abusing — a repair, a heal, a feed, a repeat-kill reward, a sell wand — use
`net.exylia.lib.util.NetworkCooldowns`, which keeps every cooldown in the
plugin's database, whatever its length.

```java
private NetworkCooldowns cooldowns;

@Override
public void onEnable() {
    cooldowns = NetworkCooldowns.of(this);   // reads the players already online, and every join after
}

UUID id = player.getUniqueId();
if (!cooldowns.tryStart(id, "repair", Duration.ofMinutes(10))) {
    if (!cooldowns.isLoaded(id)) {
        messages.stillLoading().send(player);
        return;
    }
    messages.wait(cooldowns.remaining(id, "repair")).send(player);
    return;
}
```

| Method | Contract |
| --- | --- |
| `NetworkCooldowns.of(plugin)` | the plugin's cooldowns, namespaced by its name, the same instance every time. Released with the plugin. |
| `tryStart(UUID, key, Duration)` | starts it only when it is **known** to be free; `false` when it is running **or not read yet** |
| `isActive(UUID, key)` | running — and `true` while not read yet |
| `remaining(UUID, key)` | what is left; `Duration.ZERO` when free or not read yet |
| `isLoaded(UUID)` | whether this server has read the player's cooldowns; tells "still loading" from "free" |
| `start(UUID, key, Duration)` | start or restart; zero or negative clears. Works for a player who is not here: the row waits for their next join |
| `clear(UUID, key)` | end it early, here and in the database |

- **Reads are memory.** A player's rows are read into memory when they join;
  every question is answered from there, synchronously, from any thread.
- **Unknown refuses.** Between a join and the read coming back, the answer is
  "wait", so nothing slips through the gap a hop opens. A failed read is retried
  every five seconds while the player stays.
- **Writes are async and ordered.** A start or a clear changes memory at once;
  the database write is chained behind the player's previous one, so a clear
  never lands before the start it undoes.
- **Expiry.** A row is deleted when cleared, when its owner next joins after it
  ended, or overwritten when the key starts again.
- **Who owns it.** The server the player is on. A cooldown started for somebody
  online on another server reaches that server on their next join.
- **Table.** `exylia_network_cooldowns`: `id` (`<namespace>:<uuid>:<key>`),
  `player`, `namespace`, `name`, `expires_at` (epoch ms), indexed on
  `(player, namespace)`. Point every server's plugin at one database for it to
  cross servers.

Use `Cooldowns` for everything a reset does not hurt: it costs nanoseconds and
no row.

## ItemCooldowns

`net.exylia.lib.util.ItemCooldowns` adds Bukkit's `setCooldown` sweep (the
client draws it well) on top of the base; the authoritative answer stays in
`Cooldowns`. Namespace `item:`.

- By material: `start/tryStart/isActive/remaining/remainingSeconds/
  remainingFormatted/clear(player, Material, ...)`.
- By name plus material: same methods with a `key` first — a named item
  (`"fire-wand"` on a blaze rod) gets its own key and still shows its
  material's overlay.
- `anyActive(player)` answers for every item at once: an action bar that
  would otherwise ask about sixty items per tick asks once, and a player who
  is waiting on nothing costs one lookup.
- `restore(player, material)` re-draws the sweep with the remaining time
  after a reconnect.
- `tryStart` does not restart or re-draw while active. Long item cooldowns
  persist for free — underneath they are ordinary cooldowns.

## Decimals and display

Time is stored in milliseconds and read as decimal seconds
(`remainingSeconds` → `double`). `remainingFormatted` renders through
`TimeFormats` (see [utilities](util.md)), so a chat message and a boss bar
over the same cooldown read the same way.

A display **reads** a cooldown via `Timer.ofCooldown(player, key)` (see
[effects](effects.md)) — a bridge, not a merge: the cooldown stays the truth,
the display finishes the moment the cooldown does.

## Measured cost

Benchmarked in the repo (`src/test/java/net/exylia/lib/util/CooldownsBenchmark.java`):

| Call | Cost |
| --- | --- |
| `isActive`, running | ~32 ns |
| `isActive`, key never set (the common case) | ~8 ns |
| `isActive`, unknown player | ~4 ns |
| `start` | ~49 ns |

200 players checking every tick ≈ 0.013% of a tick. When scopes were added
this first rose to 89 ns — `UUID.toString()` per call, the same sin commons
commits; storing the UUID and caching player scopes brought it back.

## Source and tests

- Public: `util/Cooldowns.java`, `util/CooldownScope.java`,
  `util/PluginCooldowns.java`, `util/ItemCooldowns.java`,
  `util/NetworkCooldowns.java` (row: `util/internal/NetworkCooldownRow.java`).
- Internal: `util/internal/CooldownStore.java` (persistence).
- Lifecycle wiring: `ExyliaLib.java` (join load, quit flush, shutdown flush,
  5-minute async flush timer).
- Tests: `src/test/java/net/exylia/lib/util/` — `CooldownsTest`,
  `CooldownsAdvancedTest`, `CooldownTimeTest`, `ItemCooldownsTest`,
  `NetworkCooldownsTest`, `CooldownsBenchmark`.
