# Network

Messages from a plugin to the same plugin on one other server, or on the
server a player is on, and the wait for players the proxy is sending here.

*Since 1.347.0.*

## What it is for

[Channels](redis.md#channels) tell *every* server that something happened.
A network game needs to talk to *one*: the server a challenged player is on,
the arena server a match is starting on. That is this module, on the same
channels, with the address in front of the payload and the receivers that
are not addressed dropping it.

Entry point: `net.exylia.lib.network.Network`.

```java
PluginNetwork network = Network.of(this);

network.on("duel-request", message -> Tasks.of(this).run(() ->
        showRequest(message.payload(), message.sender())));

network.send(targetId, "duel-request", requesterId + "|" + kit)
       .thenAccept(delivery -> {
           if (delivery == Delivery.NO_PLAYER) { /* not online anywhere */ }
       });
```

| Call | What it does |
| --- | --- |
| `Network.of(Plugin)` | The plugin's view. Cheap; the channels behind it are cached and closed with the plugin. |
| `send(String server, String channel, String payload)` | Delivers to this plugin's `on(channel)` handlers on that server. Returns `Delivery` at once; one Redis round trip on the calling thread, like `Channel.publish`. Server names are not case sensitive. |
| `send(UUID player, String channel, String payload)` | The same, on whichever server the player is on. Here: at once. Elsewhere: `Proxy.find` first, then the send; completes on the Redis subscriber thread, never exceptionally. |
| `on(String channel, Consumer<Message> handler)` | Handlers for messages addressed to this server. Subscriber thread, never the main one: `Tasks` before touching Bukkit. `Message.sender()` is the sending server, the address to answer to. Returns a `Channel.Subscription`. |
| `awaitArrival(Collection<UUID> players, Duration timeout)` | Completes with an `Arrival(arrived, missing)` once every player is online here, or when the timeout passes with whoever is. Players already here count at once. Checked every five ticks off the main thread. |
| `serverId()` | This server's name as the proxy addresses it (the `server-id` otherwise). |

`Delivery`:

| Value | Meaning |
| --- | --- |
| `SENT` | Published, or delivered here when the target is this server. Pub/sub has no receipt: it left, nobody promised to read it. |
| `NO_NETWORK` | The target is another server and nothing reaches it: no Redis, or (for a player) no proxy bridge to say where they are. |
| `NO_PLAYER` | The bridge answered and the player is on no server. |

## Contracts

- **Scoped by plugin and channel**, like channels: ExyliaDuels on the lobby
  talks to ExyliaDuels on the arena server and to nothing else.
- **Single server works.** Without Redis a send to this server is delivered
  here; a send anywhere else is `NO_NETWORK`, never a silent drop.
- **Events, not state.** A server that was restarting misses the message.
  What has to be *known* afterwards (a match result, an Elo) goes to a
  repository; the message only says "go and look".
- **An `awaitArrival` belongs to the plugin's tasks**: a plugin that disables
  leaves it uncompleted. A player who arrived and left again before it ends is
  missing.
- Nothing to close by hand; reload: not applicable.

## A match on another server

What a network duel needs, as three sends and two library calls. No new
proxy module: moving players is the [teleport module](teleport.md)'s.

**1. The request.** The lobby addresses the challenged player wherever they are;
their server shows the clickable request and answers to the sender.

```java
network.send(target, "request", requester + "|" + kit);              // lobby-1
network.on("request", m -> tasks.run(() -> prompt(m)));              // lobby-2
network.send(m.sender(), "accepted", requester + "|" + target);      // lobby-2, on accept
```

**2. The match.** Whoever accepted picks the arena server, tells it who is
coming and from where *first*, then sends them. The arena waits for both.

```java
// lobby-1
network.send("arena-1", "match", matchId + "|" + a + "|" + homeA + "|" + b + "|" + homeB);
teleports.toServer(playerA, "arena-1");
network.send(b, "go", "arena-1");   // b's server runs teleports.toServer(b, "arena-1")

// arena-1
network.on("match", m -> network.awaitArrival(List.of(a, b), Duration.ofSeconds(20))
        .thenAccept(arrival -> tasks.run(() -> {
            if (arrival.complete()) startMatch(...);        // Stays, kit, arena teleport
            else cancelAndSendHome(arrival.arrived());
        })));
```

The message is published before the `connect`, and the arrival counts
players already here, so it does not matter which lands first.
`toServer` rather than `to(player, ExyliaLocation)`: the arena server places
them itself once they are all there, through `Teleports` as always.

**3. Home.** The arena kept each player's home from the match message.

```java
teleports.toServer(player, home);
```

Inventory on the arena server is that server's own; a plugin that dresses
players there keeps them in a [stay](stays.md) on the arena server and
releases it with `leaveHere` before sending them home.

## Instances: one plugin on several servers as a pool

*Since 1.383.0.* Several servers running the same game (every RandomKits
server of a group) publish their head count on a heartbeat and send each new
player to the best one. Entry point `Instances.of(plugin, group)`, one per
plugin and group, made once in `onEnable`.

```java
PluginInstances instances = Instances.of(this, "randomkits")
        .publish(() -> Instances.status(!routerOnly,
                new Instance.Load(game.playing(), 80),               // total, soft cap (0 = none)
                Map.of("castle", new Instance.Load(game.count("castle"), 40))))
        .onArrival((player, note) -> game.join(player, note));    // their thread, after the join

Optional<Instance> best = instances.pick(Instances.Mode.FILL_FIRST, "castle");
best.filter(found -> !instances.isThisServer(found.server()))
    .ifPresent(found -> instances.handover(player, found.server(), "castle"));
```

| Call | What it does |
| --- | --- |
| `publish(Supplier<Status>)` | Sends this server's `Status(accepting, total, parts)` every period (2 s) on an async timer, and stops with the plugin. The supplier reads memory: it runs off the main thread, and on every read of the pool for this server's own entry. |
| `period(Duration)` | The heartbeat period, at least 1 s; an instance is forgotten 3 periods after its last heartbeat. Same value on every server of a group. |
| `instances()` | Every live instance: this server's own first, read from its supplier now, then each other one heard within 3 periods. A map read, safe from any thread and from a menu's supplier. |
| `pick(Mode, part)` | The best instance for one more player, `Instances.pick` over `instances()` with this server preferred on a tie. |
| `players(part)` | Players in one part (or in total for `null`) across the group. |
| `handover(player, server, note)` | Sends the note to that server, then `toServer`; a move that fails withdraws the note there (1.383.1), so a normal join within the wait is not taken for the arrival. To this server: no move, the arrival runs here. `CROSS_SERVER_UNAVAILABLE` when no Redis carries the note. |
| `onArrival(BiConsumer<Player, String>)` | Runs for a player handed over here once they are online (up to 30 s), on their thread, with the note. |
| `expected(UUID)` | The note of a player on their way here, for a join listener that would otherwise place them itself. |
| `stop()` | Stops publishing and tells the group at once, so nobody is sent to a server that is shutting down. From `onDisable`. |

`Instances.pick(instances, mode, part, preferred, now, maxAge)` is the pure
choice: instances seen within `maxAge`, `accepting`, with room under their
total cap and, when a part is named, having that part with room under its
cap. `LEAST_PLAYERS` takes the emptiest, `FILL_FIRST` the fullest (players of
the part when one is named). Ties: `preferred`, then the higher TPS, then the
name.

Contracts:

- **Heartbeats are stamped with the receiver's clock**, so clock skew between
  servers never makes one look stale or immortal.
- **No Redis, no pool**: the heartbeat stays on this server and `instances()`
  is this server alone, so `pick` answers this server or nothing.
- **The note travels before the move**, like a match: the arriving server
  knows why they came before they join. A failed move sends `!<uuid>|<note>`
  after it, which drops that note (1.383.1); older servers ignore the line.
- **Nothing is carried but the note.** Inventories, stays and snapshots stay
  with their server: a plugin that keeps players in [stays](stays.md) hands
  over only players it is not keeping, and recovers with `recoverLocal`.
  Player data stored as deltas under `RowLocks` should be saved before the
  handover, so the arriving server reads it current.

## What a network needs configured

- `database.redis.enabled: true` in the consumer's `database.yml`, the same
  Redis and `key-prefix` on every server, and a different `server-id` on each.
- [ExyliaProxyUtils](proxy.md) on the proxy for sending to a *player* and for
  `toServer` to report whether the proxy moved them, with **server names on the
  proxy equal to the backends' `server-id`s** (the address messages and moves
  share).

## Where the code lives

| | |
| --- | --- |
| Public | `network/Network`, `PluginNetwork`, `Delivery`, `Arrival`, `Instances`, `PluginInstances`, `Instance` |
| Transport | `redis/Channels`, channel `net:<name>` per plugin, frame `<target server>` + pipe + payload |
| Transport (instances) | channel `instances:<group>` for heartbeats (tab-separated lines), network channel `instances-arrive:<group>` for notes |
| Tests | `network/NetworkTest` — addressing, case, payload pipes, no Redis, no bridge, the arrival split; `network/InstancesTest` — picking per mode, caps, parts, staleness and ties, the heartbeat wire form; `network/InstanceNoteTest` — notes and their withdrawal |
