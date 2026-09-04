# Redis

A shared cache that makes one database look the same from every server.

*Since 1.31.0.*

## There is nothing to call

This is the unusual module: a plugin does not use it. It turns itself on from
`database.yml`, and every repository the plugin already has starts answering
from Redis instead of the database and telling the other servers when a row
changes. Code written before this module existed gains all of it without a line
changing.

```yaml
# plugins/<Plugin>/database.yml
database:
  type: mysql
  mysql:
    host: 10.0.0.5
    database: network
    username: exylia
    password: '...'
  redis:
    enabled: true
    host: 10.0.0.6
    server-id: lobby-1      # different on every server
```

That is the whole configuration.

## What it guarantees

A row written on one server is visible on every other **immediately**, without
waiting for a message to travel.

Two rules produce that:

1. **A write publishes only after the new value is stored.** Store, then
   announce — never the other way round. A peer woken by the message re-reads at
   once, so if the message could overtake the value it would cache the very row
   it was told to drop.
2. **A read that misses locally goes to Redis before the database.**

Rule 2 is what makes a server switch work. A proxy can move a player between
servers inside one tick; if the destination had to wait for an invalidation to
arrive before reading fresh data, the handoff would be a race and it would lose
sometimes — which is what "my kill effect reset when I switched servers" is. It
does not wait: the destination misses its own memory (the player was not there a
moment ago) and reads straight from Redis, where the previous server's write
already is. Pub/sub only spares servers that already had the row from doing the
same a moment later.

## What it does not do

- **It is not storage.** The database is the truth; every write completes
  against it *before* anything is cached. Losing Redis costs speed and
  cross-server freshness, never data.
- **It does not cache filtered queries.** `find` and `exists` are answered from
  the cache; `select` and `count` are not. A leaderboard changes whenever
  anyone's score does and no key predicts that.
- **It does not cache absence.** A row that is not there is not remembered as
  missing: a first join writes exactly that row moments later.

## When Redis stops answering

Everything keeps working, straight against the database, and the console says so
once — not once per lookup.

A store that fails is **not** announced. Telling peers to drop their copy and
re-read a value that was never written would turn one failed write into a
network-wide fallback for the whole TTL.

## Settings

Under `database.redis` in each consumer's `database.yml`, with the key names
ExyliaCommons used, so a server that had Redis working keeps its file.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Off unless asked for. A lone server gains nothing. |
| `host` / `port` | `localhost` / `6379` | Where Redis is. |
| `password` | `''` | Empty when the server needs none. |
| `database` | `0` | Which numbered Redis database. |
| `pool-size` | `8` | Connections at most, shared by every plugin. |
| `ttl-seconds` | `1800` | How long a row stays in Redis. |
| `local-seconds` | `300` | How long a row stays in this server's memory. |
| `local-entries` | `10000` | Rows kept in memory at most. |
| `key-prefix` | `exylia` | Isolates one network from another on a shared Redis. |
| `server-id` | `server-1` | **Different on every server.** |

`server-id` is how a server ignores its own invalidations. Two servers sharing
one ignore each other's changes, which looks exactly like Redis not working.

`local-seconds` is shorter than `ttl-seconds` on purpose: it is the backstop for
an invalidation that never arrived, so a shorter value bounds how long a server
can be wrong. It does not cost queries — a local miss is answered by Redis, not
by the database.

## Diagnostics

```java
Redis.isActive();   // whether a cache is connected and serving reads
Redis.stats();      // hits, misses, failures, rows held
```

A low hit rate across a network usually means the servers disagree about
`key-prefix`, or share a `server-id`.

## Channels

Cross-server events over the same Redis, since 1.75.0. For telling every
server that something *happened* — a staff alert, a broadcast, a punishment to
apply — never for carrying state: state lives in a repository, which the cache
above keeps consistent; a message can be missed by a server that was
restarting, a row cannot.

Nothing to configure beyond the block above. Without Redis (off, unreachable,
or library missing) a channel still delivers within this server, so
single-server code and network code are the same code.

```yaml
# plugins/<Plugin>/database.yml
database:
  redis:
    enabled: true
    host: 10.0.0.6
    server-id: lobby-1
```

```java
Channel alerts = Channels.of(plugin).channel("alerts");

alerts.subscribe(message -> {
    // Subscriber thread, never the main thread: hop before touching Bukkit.
    Tasks.of(plugin).run(() -> notifyStaff(message.sender(), message.payload()));
});

alerts.publish(player.getName() + " was reported");
```

Contracts:

- **Every message reaches every subscriber on every server, this one
  included, exactly once.** `Message.local()` is true for a message this
  server published; `Message.sender()` is the sender's `server-id`.
- **Channels are scoped by plugin and name.** Two plugins asking for
  `"alerts"` get two channels. Same name, same instance.
- **`publish` is one Redis round trip on the calling thread.** Publish from
  `runAsync` on a hot path. An unreachable Redis never throws: the message is
  delivered to this server only and the console says so once per call.
- **Handlers run on the subscriber thread.** Go through `Tasks` for anything
  that touches the Bukkit API. A handler that throws is logged and the rest
  still run.
- **Lifecycle is the plugin's.** A `Subscription` closes one handler;
  `PluginChannels.close()` closes everything, and ExyliaLib does that when the
  plugin disables — after its `onDisable`, so it can still announce its own
  shutdown.
- `Channel.isNetworked()` and `Redis.serverId(plugin)` are diagnostics.

## Differences from ExyliaCommons

The design is the same one, with four defects fixed:

- **`server-id` is the configured one.** Commons used a random 8-character UUID
  fragment regenerated on every boot, so a collision meant two servers silently
  ignoring each other permanently, and no log line could name the sender.
- **A save does not wipe the table.** Commons called `invalidateAll()` on every
  save, which did a network-wide `SCAN` + `DEL` of the entity's whole keyspace
  and broadcast a clear. Under write-behind that ran every 30 seconds per
  repository, which left the cache empty most of the time. The only `SCAN` left
  is the one a real wipe does (`deleteAll`, since 1.108.0): the table's Redis
  keys are deleted along with the rows, so a wiped row cannot be handed back by
  `find` until its TTL runs out.
- **Values are encoded the way the database encodes them.** Commons cached with
  bare Gson while writing through its serializers, so a field with a custom
  codec had two representations. Here the payload goes through the same
  `EntityModel` a column does.
- **A stalled Redis fails instead of hanging.** Commons left the pool's borrow
  wait at the library default, which is forever, so an outage turned into parked
  threads. Here the wait is bounded and the caller falls through to the
  database.

There is no `@PlayerSession` and no flush-on-quit. In commons that apparatus had
zero call sites — no entity in the ecosystem was ever annotated — and it is not
what made the handoff work. Writes here are durable when they complete, so there
is nothing buffered to flush.

## Where the code is

| | |
| --- | --- |
| Public API | `redis/Redis`, `redis/RedisSettings`, `redis/Channels`, `PluginChannels`, `Channel`, `Message` |
| Internal | `redis/internal/RedisRuntime`, `RowCache`, `CachedStorage`, `RowCodec`, `CacheKeys`, `Invalidation`, `RedisClient`, `JedisClient`, `MemoryClient` |
| Wiring | `database/PluginDatabase` wraps each repository's storage; `database/DatabaseSettings` carries the block |
| Lifecycle | `ExyliaLib` closes every connection after the datasources, so a last write still reaches the network |
| Isolation | `JedisClient` is the only class naming Jedis, so a server without it never loads one |
