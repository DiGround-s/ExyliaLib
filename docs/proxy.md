# Proxy

Talking to the proxy from a backend server.

*Since 1.101.0.*

## What it is for

A proxy owns things no backend can reach: `/server`, the network's player
list, anything a proxy plugin does. **ExyliaProxyUtils** is Exylia's plugin on
that side (one jar for Velocity and BungeeCord), and this module is the one
road to it from here.

Most plugins never call it. The `player-proxy:` and `console-proxy:` prefixes
every command list already accepts go through it by themselves:

```yaml
commands:
  - "player-proxy: server lobby"
  - "console-proxy: alert %player% was banned"
```

Until 1.101.0 those lines reported `NO_TRANSPORT` on every server, because
the library had the seam (`ProxyCommands`) and nothing behind it. Now the
default transport reaches ExyliaProxyUtils, and `NO_TRANSPORT` means what it
says: the proxy did not answer.

## Calling it yourself

```java
Proxy.request(player, "commands", "player-proxy:server lobby")
     .thenAccept(reply -> {
         if (!reply.isOk()) player.sendMessage(reply.detail());
     });
```

| Call | What it does |
| --- | --- |
| `Proxy.request(Player about, String module, String payload)` | Sends one request about a player to a module on the proxy; completes with a `ProxyReply`, never exceptionally, on the Redis subscriber thread. Safe from any thread. |
| `Proxy.request(String module, String payload)` | The same about nobody: a console command, a lookup. *Since 1.106.0.* |
| `Proxy.isAvailable()` | Whether the proxy has answered this server since the last silence. A diagnostic: `request` always tries. |
| `Proxy.bridge()` | What answered, as it introduced itself: `ExyliaProxyUtils 1.0.0 on Velocity`. |
| `Proxy.find(String nameOrId)` | A player anywhere on the network, by name or id, as a `ProxyPlayer(id, name, server)`; empty for nobody, and empty without a bridge. What resolves `/tp <name>` for somebody this server has never seen. *Since 1.103.0.* |
| `Proxy.players()` | Every name on the network as of the last refresh (every 10 s, through whoever is online). Synchronous, for tab completions and placeholders; empty until the bridge answers. *Since 1.104.0.* |
| `Proxy.COMMANDS`, `Proxy.PLAYER` | The module names behind `player-proxy:` lines and `find`. |

Modules ExyliaProxyUtils has today: `ping`, `commands` (`<actor>:<command>`),
`connect` (`<server>|<uuid>|<memo>`, the last two optional), `player` (a name
or a uuid) and `players` (every connected name). The [teleport
module](teleport.md) uses `connect` for handovers once the bridge has
answered, with the destination as the memo, and `player` to find where
somebody is.

### Pushes

The proxy can also send something unasked: an answer frame with id 0, which
the runtime hands to whatever `ProxyRuntime.listen(module, handler)`
registered for that module instead of to a waiting request. Today only
`arrive` exists — the memo of a `connect`, delivered to the destination
server through the player once they have joined it. The handler runs on the
thread the message arrived on, with the player it arrived through.

`ProxyReply.status()`:

| Status | Meaning |
| --- | --- |
| `OK` | The module did what was asked. |
| `REJECTED` | The module understood and refused; `detail()` says why — a command the proxy does not have, a bad actor. |
| `FAILED` | The module threw. |
| `UNKNOWN_MODULE` | The proxy has no such module: it is older than this library, or the name is wrong. |
| `NO_BRIDGE` | No plugin has Redis enabled, Redis could not carry the request, or the server is shutting down. |
| `TIMEOUT` | Five seconds without an answer. ExyliaProxyUtils is not installed, or it is on another Redis or `key-prefix`. |
| `NO_PLAYER` | Reserved; no request ends this way over Redis. |

`reachedProxy()` is true for the first four. The command transport maps them
to `CommandResult`: `OK` → `DISPATCHED`, `REJECTED` → `REJECTED`, `FAILED` →
`FAILED`, `NO_PLAYER` → `NO_PLAYER`, and the rest → `NO_TRANSPORT`. Only
`DISPATCHED` continues a list, as always.

## How it travels

Redis pub/sub, over the Redis the server already has: the `database.redis`
block of `plugins/ExyliaLib/database.yml` if it is enabled there, otherwise
the first plugin whose `database.yml` has it enabled (*since 1.107.0*). A
network has one Redis and every plugin on it already names it, so nothing has
to be configured twice; `key-prefix` and `server-id` come from the same block. Not plugin messages: those
travel down a player's connection, a modified client can write one, and a
bridge built on them has to trust the proxy to filter the client's bytes out.
Redis is on the network's own side of the wall, and it works with nobody
online. *Since 1.106.0; 1.101.0 to 1.105.0 used plugin messages.*

```
server -> proxy  on <prefix>:bridge:proxy      : <server-id>|<uuid or empty>|<module>|<id>|<payload>
proxy  -> server on <prefix>:bridge:<server-id>: <module>|<id>|<status>|<uuid or empty>|<detail>
```

Requests are numbered, the proxy echoes the number, and the answer is
matched back to its future, so any number can be in flight at once. A
request may be *about* a player (who a `player-proxy:` command runs as, who a
`connect` moves) without needing one online.

**Server names on the proxy must equal the backends' `server-id`s.** That is
the address an answer goes back to and the name a `connect` moves a player
to; without it the proxy answers into the void.

### Startup says who is there

A second after startup — once every plugin has enabled and loaded its
`database.yml` — the library looks for a Redis, and keeps looking every ten
seconds until one is enabled. With one, it prints which file it took the
settings from, then sends `ping` every ten seconds until the proxy answers. The proxy answers with its name and version and the console
prints `Proxy bridge: ExyliaProxyUtils 2.0.1 on Velocity.` once. No answer
prints, once, that no bridge answered and what to install. With Redis enabled
nowhere there is no bridge at all, and the console says that instead. A request that times out marks the proxy unknown again, so the
pings resume.

### Pushes

The proxy can also send something unasked: an answer frame with id 0, which
the runtime hands to whatever `ProxyRuntime.listen(module, handler)`
registered for that module instead of to a waiting request. Today only
`arrive` exists — the memo of a `connect`, sent to the destination server the
moment the proxy has connected the player. It usually beats the join, so the
teleport module holds it by player until they are here. Handlers run on the
Redis subscriber thread with the player's id.

### Nothing is assumed

The previous system wrote bytes into a channel nobody listened on and
reported success, so a proxy command that never ran looked exactly like one
that did. Here every request ends in a reply that says what happened, and a
server whose proxy has no bridge finds out in five seconds rather than never.

## Adding a capability

A module on the proxy (one class, see the ExyliaProxyUtils README) and one
`Proxy.request(player, "name", payload)` here. The payload is a string the
module defines; nothing in the library needs to change for a new one.

## Where the code lives

| | |
| --- | --- |
| Public | `proxy/Proxy`, `proxy/ProxyReply` |
| Internal | `proxy/internal/ProxyRuntime` (the two channels, in-flight map, ping and player-list timer, push handlers), `proxy/internal/Frames` (the strings), `proxy/internal/BridgeCommands` (the default `ProxyCommands`) |
| Tests | `proxy/ProxyTest` — the frames both sides agree on, `NO_BRIDGE` without Redis, every reply status to a command result |
| Proxy side | [ExyliaProxyUtils](https://github.com/DiGround-s/ExyliaProxyUtils) |
