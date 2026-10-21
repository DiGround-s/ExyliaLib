# Playtime

How long a player has actually played, on every server of the network.

Rank-ups, stats and playtime rewards all ask the same question. This module
answers it once, so no plugin counts time of its own.

*Since 1.341.0.*

---

## Opening it

```java
private Playtime playtime;

@Override
public void onEnable() {
    playtime = Playtime.of(this);
}
```

There is **one tracker per server**. Time is added, so two trackers would count
it twice: every plugin that calls `of` gets the same instance. The first plugin
to open it **hosts** it, and the table lives in that plugin's database. When
the host is disabled, its players' time is written (waiting up to ten seconds)
and the next enabled plugin that opened it takes over, reading every online
player's row from its own database. With nobody left, tracking stops.

Point the plugins of every server at one database and the totals are
network-wide.

## Reading

| Member | Answers |
| --- | --- |
| `long online(UUID)` | Total for a player online here, including what this server has not written yet. Memory only. `-1` when they are not online here or their row has not been read yet. |
| `CompletableFuture<Long> total(UUID)` | Total for anybody: memory for a loaded online player, the database otherwise. `0` for somebody never seen; completes exceptionally when the database cannot answer. |
| `long session(UUID)` | Milliseconds since they joined this server, idle or not; `0` when not online here. |
| `boolean loaded(UUID)` | Whether `online` answers yet. |
| `void onMinute(Plugin, Consumer<Player>)` | Runs for every online player once a minute, right after their time is written, on that player's own thread. Dropped when the owner is disabled. |

```java
playtime.onMinute(this, player -> {
    long played = playtime.online(player.getUniqueId());
    if (played >= 0) milestones.check(player, played);
});
```

## Idle time

A player who has not moved, looked, typed or clicked for the idle threshold
when the minute's write runs, or when they leave, has that stretch dropped.

| Static | Does |
| --- | --- |
| `Playtime.idleAfter(Duration)` | Sets the threshold for every plugin. Zero or negative turns idle detection off. Default `DEFAULT_IDLE_AFTER`, five minutes. |
| `Playtime.idleAfter()` | The threshold in use. |
| `Playtime.active(Player)` | Whether the player is inside the threshold. |
| `Playtime.playing(Player)` | Alive, in survival or adventure, and not vanished (`PlayerStates.isVanished`). For rewards for being somewhere. |

Use `active` and `playing` for anything else that pays for time (referrals,
power-ups) instead of carrying a copy.

## Storage

`exylia_playtime` (`id` UUID, `total_ms`), one row per player. Each server adds
what it counted since its own last write with `Repository.increment`, so
servers never overwrite each other and the server a player just left can still
add its last minute after the next one started counting. Every write reads the
row back past the cache (`findFresh`), which is how other servers' time reaches
`online`. Time is written every minute, on quit and on disable: a crash costs
at most the last minute. One player's reads and writes on one server run in
order.

**Legacy import.** The first time `exylia_playtime` is empty, the host's
ExyliaSurvivalCore `playtime_data` totals (`total_playtime_ms`) are copied into
it, once. `playtime_data` is never written; on a database without it, the
empty table is created with SurvivalCore's exact shape. Upgrade every server
sharing the database together: a server still running the old SurvivalCore
keeps writing `playtime_data`, which is no longer read.

## Threads

Safe from any thread. Futures complete off the server thread; hop back before
touching the world.

## Where it lives

- `net.exylia.lib.playtime.Playtime` — the API and the tracker.
- `net.exylia.lib.playtime.internal.PlaytimeRow`, `LegacyPlaytimeRow` — rows.
- `ExyliaLib.onPlayerJoin` / `onPlayerQuit` call `joined` / `left`;
  `releaseAfterDisable` calls `release`; `onDisable` calls `releaseAll` before
  the databases close.
