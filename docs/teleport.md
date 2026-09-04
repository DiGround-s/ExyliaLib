# Teleport

Moving a player, with everything that has to happen around it.

*Since 1.34.0.*

Entry point: `net.exylia.lib.util.teleport.Teleports`.

## The one-line case

```java
private PluginTeleports teleports;

@Override
public void onEnable() {
    teleports = Teleports.of(this).using(config.get().teleport());
}

// From anywhere:
teleports.to(player, spawn).start();
```

That is most of it. Of the roughly sixty real call sites across the ecosystem,
around fifty-five are exactly that line: move this player there, now. The rest
of this page is for the other five.

`Teleports.of(this)` returns the same instance every time for a given plugin.
`using(...)` is optional — without it the library defaults apply, which is no
countdown at all. `settings()` reads back whatever is in force, and `plugin()`
the plugin they belong to.

There are three ways to say where. `to(player, Location)` for a live one,
`to(player, ExyliaLocation)` for a stored place that may name another server, and
`to(player, String)` for a stored string, which is the first form with the
parsing done for you. None of the three throws for a place that cannot be used:
an unloaded world completes `WORLD_NOT_FOUND`, another server with no way to
reach it completes `CROSS_SERVER_UNAVAILABLE`, and an unreadable string completes
`FAILED`. A warp pointing at a world an owner removed is a message to one player,
not a stack trace in the console every time somebody types the command.

### Moving everybody at once

```java
CompletableFuture<Void> done = teleports.toAll(arena.players(), lobby);
```

`toAll(Collection<? extends Player>, Location)` moves them all **with no
countdown**, and completes when every one of them has been dealt with, however
each ended. For the end of a game, where everybody goes back to the lobby: a
countdown makes no sense there, because nobody asked, so there is nothing for
them to change their mind about.

## Why this is not each plugin's own code

Every part of a teleport is easy to write and easy to write subtly wrong, and
the wrong versions all look identical until a player finds them: a countdown
that keeps ticking after the player quit, a cooldown charged for a teleport that
was cancelled, a teleport performed off the owning thread that crashes a Folia
server, a destination inside a wall.

Written once, every plugin gets the version that handles them. Written per
plugin, the server gets one of each.

## What it guarantees

- **One result, always.** Every request completes exactly once and never
  exceptionally, including the ones that never move anybody.
- **Nothing outlives its player.** Quitting, being kicked or the plugin being
  disabled ends the countdown and its timer.
- **A cancelled teleport is free.** The cooldown it claimed is given back,
  because the player never received what they paid for.
- **Other plugins get a say.** `ExyliaTeleportEvent` is fired for every move, on
  the thread owning the player.
- **One countdown per player.** A second teleport cancels the first rather than
  dragging them through both.

## Describing a teleport

`PluginTeleports#to` hands back a `TeleportRequest`. **Nothing happens until
`start()`** — describing a teleport is free and does not touch the player. That
matters for the cooldown in particular: the key is claimed when the teleport is
started, so a request built and thrown away never charges anybody.

```java
teleports.to(player, warp.location())
        .warmup(3.0)
        .cancelOnMove()
        .cancelOnDamage()
        .cooldown("mywarps:warp", 30.0)
        .safe()
        .cause(TeleportCause.PLUGIN)
        .onStart(config.warpStarting())
        .onArrive(config.warpArrived())
        .onCancel(config.warpCancelled())
        .onTick(left -> Text.of("{primary}Teleporting in {highlight}%time%")
                .with("time", TimeFormats.render(left))
                .send(player))
        .then(result -> {
            if (result == TeleportResult.ON_COOLDOWN) {
                Text.of("{warning}Not yet.").send(player);
            }
        })
        .start();
```

| Method | Contract |
| --- | --- |
| `warmup(double seconds)` | Decimals allowed: `3.5` is three and a half. Zero removes a countdown the configuration set. |
| `cancelOnMove()` / `cancelOnMove(boolean)` | Leaving the **block** they started on cancels it. Looking around and sub-block drift do not. |
| `cancelOnDamage()` / `cancelOnDamage(boolean)` | Taking damage cancels it. |
| `safe()` / `safe(boolean)` | Land on the nearest survivable spot instead of the exact block. |
| `cooldown(String key, double seconds)` | Refuse when the key is running, claim it when it is not. |
| `cause(TeleportCause)` | What `ExyliaTeleportEvent` will carry. |
| `onStart(EffectConfig)` | Played when the countdown begins. `null` is allowed. |
| `onArrive(EffectConfig)` | Played on arrival. |
| `onCancel(EffectConfig)` | Played when it is called off. |
| `onTick(DoubleConsumer)` | The seconds remaining, four times a second. |
| `then(Consumer<TeleportResult>)` | How it ended, exactly once, **including the failures**. |
| `start()` | Returns a `TeleportHandle`, which may already be finished. |

`warmup`, `cancelOnMove` and `cancelOnDamage` start from what the server owner
configured; the request overrides them only if it says so.

### The cooldown key is not namespaced for you

`cooldown(key, seconds)` goes through
[`Cooldowns`](cooldowns.md), so it is the same cooldown everything else in the
ecosystem sees and it survives a restart when it is long enough to be worth
saving. Prefix it with something of your own: two plugins both using `"warp"`
share one cooldown.

A refusal plays nothing and moves nobody. Whoever wants to tell the player how
long is left asks `Cooldowns`, which still knows.

### `TeleportHandle`

```java
TeleportHandle handle = teleports.to(player, spawn).warmup(3.0).start();

handle.future();                  // CompletableFuture<TeleportResult>, never exceptional
handle.cancel();                  // safe from any thread, safe twice
handle.isDone();
handle.player();
handle.cause();
handle.remainingWarmupSeconds();  // 0.0 with no countdown, and 0.0 once finished
```

The countdown reports itself every five ticks — a quarter of a second, finer
than a player can read and twenty times less work than every tick.

## `TeleportResult`

Every request completes with exactly one of these.

| Value | What it means to the player |
| --- | --- |
| `SUCCESS` | They arrived. |
| `CANCELLED_BY_EVENT` | Another plugin refused it. |
| `CANCELLED_ON_MOVE` | They walked out of it. |
| `CANCELLED_ON_DAMAGE` | They were hit. |
| `CANCELLED_MANUALLY` | Something called `TeleportHandle#cancel()` — including a second teleport replacing this one, and the plugin being disabled. |
| `PLAYER_LEFT` | They quit or were kicked first. |
| `ON_COOLDOWN` | The key was still running; nothing was attempted. |
| `NO_SAFE_LOCATION` | A safe spot was asked for and the search found none. |
| `WORLD_NOT_FOUND` | The destination's world is not loaded here. |
| `CROSS_SERVER_UNAVAILABLE` | The destination is on another server and there is no way to send them. |
| `TARGET_NOT_FOUND` | The player to reach is on no server of the network. Without Redis, anybody not on this server. |
| `NOTHING_TO_GO_BACK_TO` | They asked to go back and nothing is recorded. |
| `FAILED` | Something else; the console says what. |

`isSuccess()` is `this == SUCCESS`.

`isCancelled()` is **deliberately narrower than "not success"**. It covers only
`CANCELLED_BY_EVENT`, `CANCELLED_ON_MOVE`, `CANCELLED_ON_DAMAGE` and
`CANCELLED_MANUALLY`. A destination whose world is missing was never going to
work; telling the player it was *cancelled* sends them looking for whoever
cancelled it. `ON_COOLDOWN` and `NOTHING_TO_GO_BACK_TO` are likewise not
cancellations — nobody called them off, the answer was simply no.

## `ExyliaTeleportEvent` — how another plugin says no

This is the point of the module having an event at all. The plugin that wants to
veto a teleport is almost never the plugin that asked for one: a combat-log
check, a staff freeze, a region that forbids entry. Each has to be able to
refuse without knowing which of the twenty plugins on the server started it, and
without any of them depending on it.

```java
@EventHandler
public void onTeleport(ExyliaTeleportEvent event) {
    if (combat.isTagged(event.player()) && event.cause() != TeleportCause.BACK) {
        event.setCancelled(true);
    }
}
```

Redirecting rather than refusing:

```java
@EventHandler
public void onTeleport(ExyliaTeleportEvent event) {
    if (claims.forbids(event.player(), event.to())) {
        event.setTo(claims.edgeNearest(event.to()));
    }
}
```

`setTo(...)` exists for the plugin whose answer is "not there, here". Cancelling
and starting a fresh teleport would fire this event again, which is how loops
between two well-meaning plugins get written.

Accessors come in both shapes, so a listener written in either style reads
naturally: `player()`/`getPlayer()`, `from()`/`getFrom()`, `to()`/`getTo()`,
`cause()`/`getCause()`, `requester()`/`getRequester()`. `requester()` is the
plugin that asked.

**Threading:** always fired on the thread owning the player, one statement
before the move, so a listener may touch the player and read the world.

`TeleportCause` carries why: `PLUGIN`, `BACK`, `TPA`, `RANDOM`, `CROSS_SERVER`.

A countdown is deliberately not one of them. A warmup is *how* a teleport
happened, not why: a warp with a countdown is still a warp. Folding it in would
mean a listener that blocks warps stops seeing the ones that have a countdown —
which is every warp worth blocking. A listener that genuinely cares whether the
player waited asks `TeleportHandle#remainingWarmupSeconds()`.

A vetoed teleport gives back the cooldown it claimed, and records nothing in the
back history — the player never moved.

## Back

```java
teleports.lastLocationOf(player);   // Optional<ExyliaLocation>, without spending it
teleports.back(player);             // a TeleportRequest, like any other
teleports.forgetHistory(player);
```

`lastLocationOf` is for a message or a menu that names the place before the
player commits to it. Reading does not take the entry, so asking twice is free.

`back(Player)` returns a request you describe and start like any other, so a
server that makes people stand still for a `/back` can:

```java
teleports.back(player)
        .warmup(config.backWarmup())
        .then(result -> {
            if (result == TeleportResult.NOTHING_TO_GO_BACK_TO) {
                Text.of("{warning}There is nowhere to go back to.").send(player);
            }
        })
        .start();
```

Nothing recorded yields a request that completes `NOTHING_TO_GO_BACK_TO` rather
than `null`. A first `/back` of a session is normal, and a caller who has to
null-check before describing a teleport writes the check once per command
instead of never.

### Both bounds, and why

The history is bounded twice, and both bounds matter.

- **By count** (`back-history-size`, three by default). An unbounded deque per
  player is a leak with a nicer name. This is an undo, not a travel log: a
  player who wants the fourth place back wants a home, and a home is something
  they set on purpose rather than something we guessed.
- **By age** (`back-history-minutes`, thirty by default). A place somebody left
  an hour ago is not somewhere they meant to come back to; offering it turns an
  undo into a surprise. They type the command expecting the arena they just left
  and land in a mine they forgot about.

The age is checked **on the way out**, not on a timer — a stale entry costs
nothing until somebody asks for it, and the read that notices it is the one that
drops it. Same reasoning as [`Cooldowns`](cooldowns.md).

### Nothing is written to disk

A place a player stood is worth exactly as much as a client waypoint: real while
they are here, pointless once they leave, cheap to rebuild by walking somewhere.
Persisting it would buy a player the ability to undo a teleport they made last
Tuesday, at the cost of a file that grows with everybody who has ever joined.
Quitting forgets a player's history entirely.

### The pop/restore rule

`back(Player)` **takes** the entry when the request is built rather than reading
it. That is what stops a player bouncing between two points from growing the
stack: going back pops one and arriving pushes one, so they hold exactly one
forever.

But a `/back` that does not succeed **puts it straight back**. A cooldown
refusal, a countdown they walked out of, a veto from another plugin — an undo
the player never received is not one they should have spent. It is the same rule
the cooldown refund follows, and it is the one thing about `/back` that is
invisible until somebody counts their history.

Two exceptions, both deliberate:

- A destination whose **world is no longer loaded** is not restored. It will not
  work on the next attempt either, and handing it back would make every `/back`
  from now on fail on the same dead world.
- A place with **no world** is never recorded in the first place, and that is
  not worth a console line every time somebody is moved out of one.

A caller's own `then(...)` callback throwing cannot cost the player their entry:
the module's bookkeeping runs first and is guarded separately.

## TPA

Requests are filed, not acted on. Nobody moves until the target answers.

```java
switch (teleports.request(sender, target, TeleportDirection.TO_TARGET)) {
    case SENT            -> Text.of("{success}Request sent.").send(sender);
    case ALREADY_PENDING -> Text.of("{warning}You already asked them.").send(sender);
    case SELF            -> Text.of("{error}You are already there.").send(sender);
    case TARGET_BUSY     -> Text.of("{warning}They have too many requests.").send(sender);
    default              -> { }
}
```

| Method | Returns |
| --- | --- |
| `request(Player from, Player to, TeleportDirection)` | `TpaOutcome` |
| `pendingFor(Player target, Player from)` | `Optional<TeleportRequestTicket>` |
| `pendingFor(Player target)` | `List<TeleportRequestTicket>`, soonest to run out first |
| `accept(Player target, Player from)` | `TpaAcceptance` |
| `deny(Player target, Player from)` | `TpaOutcome` |
| `cancel(Player from, Player to)` | `TpaOutcome` |
| `expireStale()` | `int` — how many were dropped |

`TpaOutcome` has more values than a boolean on purpose, because each is a
different message: `SENT`, `ALREADY_PENDING`, `SELF`, `TARGET_BUSY`,
`NO_REQUEST`, `EXPIRED`, `ACCEPTED`, `DENIED`, `CANCELLED`. "There is no request
from that person" and "there was, and it ran out" send the player to check two
different things — whether they typed the name right, and how long they left it.

### Direction

`TeleportDirection` names which way round it goes, and the ticket works it out
so the accepting side cannot guess:

| Value | The command | Who moves |
| --- | --- | --- |
| `TO_TARGET` | `/tpa` | The sender goes to the target. Answering costs the target nothing. |
| `TO_SENDER` | `/tpahere` | The target comes to the sender. The one who answers is the one who moves. |

A `/tpahere` that moves the wrong player looks exactly like a working `/tpa` to
the code and exactly like a kidnapping to the person it happened to. That is why
it is a separate request rather than a flag: agreeing to be visited and agreeing
to be summoned are different answers.

`TeleportRequestTicket` carries `from()`, `to()`, `direction()`, `expiresAt()`
and `requester()`, plus `isExpired()`, `remainingSeconds()` (decimals included,
never negative), `traveller()` (who actually moves) and `anchor()` (whose
location they go to). Identities rather than `Player` objects: a ticket outlives
the tick it was made in, and a `Player` held past that is a reference to an
object the server may already have thrown away.

### `accept` hands back an **unstarted** request

```java
TpaAcceptance accepted = teleports.accept(target, sender);

accepted.teleport().ifPresent(request -> request
        .warmup(config.tpaWarmup())
        .cooldown("mytpa:tpa", config.tpaCooldown())
        .onArrive(config.tpaArrived())
        .start());
```

`TpaAcceptance` is `outcome()` plus `teleport()`, an `Optional<TeleportRequest>`
present only when the outcome is `ACCEPTED`; `isAccepted()` is the shorthand.

The module knows who moves and where. It has no idea whether this server makes
people stand still for three seconds first, charges a cooldown, or plays
anything, and answering all three on the caller's behalf would be wrong on most
servers. So the teleport arrives described and unstarted, and the caller
finishes it. The accepted request already carries `TeleportCause.TPA`.

The ticket is removed either way. A request that was accepted and then cancelled
by a countdown has been answered; leaving it on file would let the target accept
the same one twice.

If either player went offline between the request and the answer, the outcome is
`NO_REQUEST` — not `EXPIRED`. The request was live; the person was not.

### Expiry is read, never counted down

Nothing watches a clock. A ticket carries the moment it stops being answerable,
and every read drops the ones past it. This is exactly how
[`Cooldowns`](cooldowns.md) works and for exactly the same reason: a hundred
requests nobody answered cost nothing at all until somebody looks at them, while
a hundred scheduled expiries are a hundred tasks the server runs to discover
that a player who already logged off is still not answering.

`expireStale()` is therefore **not needed for correctness** — every read already
drops what it finds. It exists because a command that reports the server's own
state wants a number, and because requests waiting for a player who logged off
would otherwise sit there until somebody listed them.

Quitting forgets every request a player is on **either** side of. Leaving only
the ones they sent would let somebody accept a visit from a person who is not on
the server.

## Random

```java
RandomArea wild = RandomArea.around(world.getSpawnLocation(), 500, 5_000);

teleports.random(player, wild)
        .warmup(5.0)
        .cooldown("myrtp:rtp", 300.0)
        .start();
```

`RandomArea.around(Location centre, int minRadius, int maxRadius)` is a ring
that blocks no biomes. The full record is
`RandomArea(World world, int centreX, int centreZ, int minRadius, int maxRadius,
Set<String> blockedBiomes)`; `blocks(String biome)` answers whether a name is
refused, compared without case. Biomes are named rather than typed because
several registries stopped being enums in 1.21, and a config naming a biome the
server does not have must be a line in the console rather than an exception at
startup — a name nobody recognises simply never matches.

`RandomArea.MAX_RADIUS` is `30_000_000`, the vanilla world border, and both
radii are clamped into it. The minimum matters: without one, every random
teleport is a lottery weighted towards spawn, which is the one part of the map
that is already built on, already claimed and already crowded.

**The countdown runs before any chunk work.** Every attempt loads a chunk, and
doing that for a player who is about to walk out of their countdown is chunk
generation nobody asked for. A player who cancels causes no search at all.

**Chunk loading is asynchronous and bounded** by `random-max-attempts`. Each
candidate is fetched without blocking and inspected on the thread that owns it,
so nothing here reads a block from the wrong thread and nothing stalls a tick.
Running out of attempts completes `NO_SAFE_LOCATION` and moves nobody — refused
rather than dropped into whatever the search kept finding, because a random
teleport that lands a player in lava is worse than one that did not happen.

There is no need to add `safe()` to a random teleport: the search only ever
returns places that already passed the safety check.

## Cross-server

### `ExyliaLocation`

A place, which may be on another server. Unlike a `Location` it needs no loaded
`World` and can say which server it belongs to, which is what a network of
servers writing to one database needs.

```java
ExyliaLocation spawn = ExyliaLocation.fromString(config.spawn());

teleports.to(player, spawn).warmup(3.0).start();
```

The record is `ExyliaLocation(String server, String world, double x, double y,
double z, float yaw, float pitch)`. A `null` `server()` means "this server", so
nothing that only ever runs locally has to know the module can cross a network.

| Method | |
| --- | --- |
| `of(Location)` | This server, from a live location. |
| `of(String server, Location)` | A named server, from a live location. |
| `fromString(String)` | Reads either stored format. Throws `IllegalArgumentException` on anything else. |
| `isLocal()` | Whether `server()` is `null`. |
| `isSameServer(String currentServer)` | A local place answers `true` for whatever the caller says it is running as. Not case sensitive. |
| `toBukkitLocation()` | The live location, or `null` when the world is not loaded here — which is also the answer for a place on another server. |
| `toString()` | Always the seven-part form. |

### The stored format is frozen

ExyliaCommons already wrote these strings into databases and configuration files
across the ecosystem, in two shapes:

```
world,x,y,z,yaw,pitch                 # six parts, always local
server,world,x,y,z,yaw,pitch          # seven parts; "-" for the server means "here"
```

Both are read here, byte for byte, and `toString()` keeps emitting the seven-part
form — never the shorter one, which would lose the server of anything that later
crosses a network with no way to tell afterwards which it was.

Changing either would not break a compile. It would orphan every warp, home and
arena a server already has stored, silently, at the moment somebody updated a
jar. That is why the parser is deliberately dull.

### Handing a player over

```java
if (!teleports.isCrossServerAvailable()) {
    // Do not offer a button that cannot work.
}
```

`isCrossServerAvailable()` is `false` on a server with no Redis configured, and
that is a **normal arrangement rather than a fault**: a single server has
nowhere to hand anybody to. Everything local is completely unaffected, and a
cross-server destination on such a server completes
`CROSS_SERVER_UNAVAILABLE` with a console line explaining what to turn on.

A `to(player, elsewhere)` whose `ExyliaLocation` names a different server
becomes a handover automatically, carrying `TeleportCause.CROSS_SERVER`. A
destination naming *this* server is a local teleport, not a handover to itself.

### Write, then Connect — this is the central contract

**The destination is written to Redis first, and only a write that came back
without throwing is followed by the `Connect` message that moves the player.**

Store, then announce — the same rule the [cache module](redis.md) lives by, and
here it is even less forgiving. A `Connect` sent before the write is a player
standing on the destination server while the key that says where to put them
either does not exist yet or never will. They are not teleported, nothing is
logged, and the server they left has already forgotten them.

A write that throws therefore **sends nothing at all**. The player stays where
they are and is told the handover is unavailable, which is a message they can act
on; being silently dumped in another lobby is not.

The queued destination lives under the network's own `key-prefix` and expires
after `cross-server-ttl-seconds`, so two networks sharing one Redis cannot hand
each other's players around.

### Who moves the player, and whether it says so

*Since 1.102.0.* Two roads, chosen per move:

- **The bridge**, whenever [ExyliaProxyUtils](proxy.md) has answered this
  server: the `connect` module moves the player and *answers*. `SUCCESS` then
  means the proxy connected them. A server name the proxy does not know comes
  back as `CROSS_SERVER_UNAVAILABLE` with `The proxy did not move <player> to
  server "<name>": no server "<name>"` in the console — a typo in a config
  file, found the first time it is used rather than never. A target who is no
  longer on the network is `TARGET_NOT_FOUND` for `bring` and `PLAYER_LEFT`
  for a handover.
- **`Connect`/`ConnectOther` on the `BungeeCord` channel** otherwise. Every
  proxy understands it and none of them answers, so `SUCCESS` there means
  the message was sent. This is what every version before 1.102.0 did.

Neither road changes the write-then-Connect order above.

### Arriving

The library's own join listener claims whatever was queued for the arriving
player. The read and the delete happen **before** the settle wait, so the
destination belongs to this server from the moment it is found; only the move
waits.

That wait, `cross-server-settle-seconds`, exists **for the client** and is not a
race we are betting on. A player who has just joined is still loading the world
they logged into, and moving them in the same tick is how a client ends up in
grey void until something nudges it. Nothing about correctness depends on its
length. ExyliaCommons hardcoded 150 ms and relied on it, which is a race with a
constant in front of it, and it showed up as players arriving in the wrong place
on a server under load.

An absent key is the normal case for every ordinary join, so it does exactly
nothing: no log line, no task, no move. A world the other server named and this
one does not have is reported and moves nobody. A Redis that will not answer
costs the handover and nothing else.

### Reaching a player, wherever they are

```java
teleports.toPlayer(staff, report.target())
        .then(result -> {
            if (result == TeleportResult.TARGET_NOT_FOUND) {
                Msg.send(staff, messages.offline());
            }
        })
        .start();
```

A staff member answering a report knows who, not where. `toPlayer(player, UUID)`
is a plain local teleport when the target is on this server, and a handover
carrying `TeleportCause.CROSS_SERVER` when they are elsewhere. Which server is
read when the request starts, and what is queued is `player:<uuid>` rather than
a place — the arriving server finds them itself, so nothing holds a location
that would be stale by the time anybody got there. A target the network does
not know completes `TARGET_NOT_FOUND`, and on a server with no Redis that is
the answer for anybody not here. A target who arrived *here* during the
countdown is moved locally rather than handed to ourselves.

| Method | |
| --- | --- |
| `toPlayer(Player, UUID)` | A request to wherever that player is. |
| `bring(Player to, UUID target, String targetName)` | The reverse — `/tphere` across a network. The destination is queued under the *target's* id and the proxy pulls them with `ConnectOther`, sent through the player they are pulled to. No countdown, no cooldown: the person moved never asked. Call from the puller's thread. |
| `serverOf(UUID)` | `CompletableFuture<Optional<String>>` — this server's own `server-id` when they are here, answered without Redis; the network's answer otherwise; empty for nobody. |

### The presence map

The library keeps one entry per online player, `<prefix>:players:<uuid>` →
`server-id`, written by its own join listener, renewed every 30 seconds by a
heartbeat, withdrawn on quit and expiring 90 seconds after the last renewal.
The expiry is what makes a crashed server harmless: its players fall off the
map on their own instead of sending staff to a server that died a minute ago.

A quit withdraws the entry **only if it still names this server**. A proxy
connects the player to the next server before it disconnects them from this
one, so the other server's write may already be there, and deleting blindly
would erase it. Read, compare, delete is not atomic; the window is one round
trip on a server the player just left, and the next heartbeat corrects it.

The heartbeat starts on the first join a Redis is there for. A server that never
configures one never runs a timer for it.

## Settings

`TeleportSettings` nests inside a plugin's own configuration record like any
other section, so the countdown length and whether moving cancels it are
decisions the server owner makes rather than the plugin author.

```java
public record MySettings(TeleportSettings teleport) {
    public MySettings() {
        this(new TeleportSettings());
    }
}
```

A request may still override any of these — a death respawn should not sit
through a warmup because warps do — but what the owner writes is what applies
when nobody says otherwise.

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `warmup-seconds` | `0.0` | `>= 0` | How long a player waits before being moved. Decimals allowed. Zero by default, so nothing waits unless it was asked to. |
| `cancel-on-move` | `true` | — | Whether walking during the countdown calls it off. This is what stops a player escaping a fight by warping out of it. |
| `cancel-on-damage` | `true` | — | Whether taking damage calls it off. Turn off where fall damage in a parkour lobby would cancel every teleport out of it. |
| `safe-search-radius` | `5` | `0`–`32` | How far to look for somewhere safe to land, in blocks. Only used when the teleport asked to be safe. A large radius quietly turns a warp into a lottery. |
| `safe-max-attempts` | `32` | `1`–`256` | How many blocks that search may check. This is the cost ceiling: the search runs on the thread owning the destination, so an unbounded scan is a stall the whole server feels. |
| `cross-server-ttl-seconds` | `300` | `30`–`3600` | How long a destination queued for another server stays valid. |
| `cross-server-settle-seconds` | `0.5` | `0.05`–`5` | How long the destination server waits after a player arrives before moving them. Raise it on a server with slow chunk loading. |
| `back-history-size` | `3` | `1`–`16` | How many places a player may walk back through. Held in memory only, per online player. |
| `back-history-minutes` | `30` | `1`–`1440` | How long one of those places stays offered. |
| `tpa-expiry-seconds` | `60` | `5`–`3600` | How long an unanswered request stays askable. |
| `tpa-max-pending` | `8` | `1`–`64` | How many requests one player may be sitting on. The anti-spam limit. |
| `random-max-attempts` | `16` | `1`–`64` | How many places a random teleport may try. Each attempt loads a chunk, so this is the cost ceiling of the whole feature. |

Everything is clamped into its range rather than refused, and a non-finite
`warmup-seconds` becomes `0.0` — a `NaN` countdown would make every comparison
in the timer false and never finish.

```yaml
teleport:
  warmup-seconds: 3.0
  cancel-on-move: true
  cancel-on-damage: true
  safe-search-radius: 5
  safe-max-attempts: 32
  cross-server-ttl-seconds: 300
  cross-server-settle-seconds: 0.5
  back-history-size: 3
  back-history-minutes: 30
  tpa-expiry-seconds: 60
  tpa-max-pending: 8
  random-max-attempts: 16
```

## Threading and lifecycle

A request may be **built from any thread**, and `start()` may be **called from
any thread** — it hops onto the player's own before touching them.

| Part | Where it runs |
| --- | --- |
| The countdown timer | The thread owning the player, as an entity timer |
| `onTick` | Same, four times a second |
| The safe search | The thread owning the **destination** — a different one from the player's on Folia |
| Random chunk loading | Asynchronously; each candidate inspected on the thread owning it |
| The Redis write of a handover | Asynchronously; the `Connect` that follows it on the player's thread |
| `ExyliaTeleportEvent` and the move | The thread owning the player |
| `then` and `onCancel` | Whichever thread ended it |

There is no Folia branch anywhere in the module. `runAtEntity` already lands on
whichever thread owns the player on whichever platform this is; ExyliaCommons
asked `Platform.isFolia()` first and then picked between two paths that did the
same thing.

Nothing survives its owner:

- **A player quitting or being kicked** ends their countdown and its timer,
  forgets their back history, and drops every request they are on either side
  of. The result is `PLAYER_LEFT`.
- **A plugin being disabled** ends the countdowns it started, before its
  scheduler goes away — a countdown owns an entity timer belonging to it. Other
  plugins' countdowns are untouched. `Teleports.release(pluginName)` is what the
  library calls; `PluginTeleports#endAll()` does the same for one plugin and
  reports how many.
- **The server stopping** ends everything and clears both in-memory stores.
  Neither is written to disk, so there is nothing to flush.

`Teleports.isWarmingUp(Player)` and `PluginTeleports#isWarmingUp(Player)` are
worth asking before anything that would fight a countdown: a combat check, a
menu that moves them, a second teleport. `Teleports.active()` is how many are
running across every plugin, and `PluginTeleports#cancelWarmup(Player)` ends one.

The library's own listeners are registered once, against ExyliaLib rather than
against a consumer. A move event fires for every player on every tick they move;
twenty plugins each registering a handler is twenty lookups per player per tick
for a feature that is idle almost always. One handler returns immediately for
the ninety-nine players who are not teleporting.

## Reloading

Nothing to invalidate. This module caches nothing derived from the colour
palette — a countdown holds ticks, a ticket holds identities, a history entry
holds coordinates — so there is no `invalidateAll()` and no hook in
`ExyliaLib.loadPalette`. The effects it plays go through
[`Effects`](effects.md) and the text a caller sends goes through
[`Text`](text.md), both of which the palette reload already covers. See
[reload.md](reload.md).

## Migrating from ExyliaCommons

| ExyliaCommons | Here |
| --- | --- |
| `TeleportAPI.initialize(plugin)` | `Teleports.of(this)` — no initialisation step; the view is created on first use and is the same instance every time |
| `TeleportAPI.teleport(p, loc)` | `teleports.to(p, loc).start()` |

**The stored `ExyliaLocation` string format is unchanged, so there is no data
migration.** Both the six-part and seven-part forms still read, `-` still means
"this server", and what is written is still the seven-part form. Every warp,
home and arena a server already has stored keeps working.

Two behaviours changed on purpose:

- **The arrival wait is configurable rather than a hardcoded 150 ms**, and the
  handover no longer depends on its length — the queued destination is claimed
  before the wait begins.
- **There is no platform branch.** A single scheduling call replaces the
  `Platform.isFolia()` fork and its two paths.

## Source and tests

| | |
| --- | --- |
| Public API | `util/teleport/` — `Teleports`, `PluginTeleports`, `TeleportRequest`, `TeleportHandle`, `TeleportResult`, `TeleportCause`, `TeleportSettings`, `ExyliaLocation`, `ExyliaTeleportEvent`, `RandomArea`, `TeleportDirection`, `TeleportRequestTicket`, `TpaAcceptance`, `TpaOutcome` |
| Internal | `util/teleport/internal/` — `TeleportRuntime` (the registry and every interrupting listener), `RunningTeleport`, `TeleportPlan`, `Teleporter` (the one place a player is moved), `SafeLocations`, `RandomLocations`, `BackHistory`, `TpaBook`, `CrossServer` |
| Lifecycle wiring | `ExyliaLib` — `TeleportRuntime.init` on enable, `Teleports.release(name)` per plugin disable, `Teleports.releaseAll()` on shutdown |
| Isolation | Every Redis type is confined to `CrossServer`, so a server without the library never loads it |
| Tests | `src/test/java/net/exylia/lib/util/teleport/` — `TeleportTest` (the countdown and everything that ends it, the cooldown refunds, the event), `TeleportBackTest` (both bounds and the restore rule), `TeleportTpaTest` (both directions, expiry, the unstarted request), `TeleportRandomTest` (the search runs after the countdown), `TeleportCrossServerTest` (the write-then-Connect ordering, against a real key under the real prefix), `ExyliaLocationTest` (the frozen format) |
