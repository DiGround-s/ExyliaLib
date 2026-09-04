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
| `Proxy.request(Player carrier, String module, String payload)` | Sends one request to a module on the proxy through the carrier's connection; completes with a `ProxyReply`, never exceptionally. Safe from any thread. |
| `Proxy.isAvailable()` | Whether the proxy has answered this server since the last silence. A diagnostic: `request` always tries. |
| `Proxy.bridge()` | What answered, as it introduced itself: `ExyliaProxyUtils 1.0.0 on Velocity`. |
| `Proxy.COMMANDS` | The module name behind `player-proxy:` lines. |

`ProxyReply.status()`:

| Status | Meaning |
| --- | --- |
| `OK` | The module did what was asked. |
| `REJECTED` | The module understood and refused; `detail()` says why — a command the proxy does not have, a bad actor. |
| `FAILED` | The module threw. |
| `UNKNOWN_MODULE` | The proxy has no such module: it is older than this library, or the name is wrong. |
| `NO_BRIDGE` | This server never registered the channel (no messenger) or is shutting down. |
| `TIMEOUT` | Five seconds without an answer. ExyliaProxyUtils is not installed, or the proxy is not forwarding the channel. |
| `NO_PLAYER` | The carrier left before the answer came. |

`reachedProxy()` is true for the first four. The command transport maps them
to `CommandResult`: `OK` → `DISPATCHED`, `REJECTED` → `REJECTED`, `FAILED` →
`FAILED`, `NO_PLAYER` → `NO_PLAYER`, and the rest → `NO_TRANSPORT`. Only
`DISPATCHED` continues a list, as always.

## How it travels

Plugin messages on the `exylia:bridge` channel, down a player's connection:
the only road a backend has to its proxy without either side opening a
socket, and the reason every request needs a carrier. Requests are numbered,
the proxy echoes the number, and the answer is matched back to its future,
so any number can be in flight at once.

```
server -> proxy : UTF module, int id, UTF payload
proxy  -> server: UTF module, int id, byte status, UTF detail
```

The channel is owned by the library, exactly like the `BungeeCord` channel
the teleport module sends `Connect` on: one owner, registered once.

### The first join says who is there

A second after the first player joins, the library sends `ping` through them.
The proxy answers with its name and version and the console prints
`Proxy bridge: ExyliaProxyUtils 1.0.0 on Velocity.` once. No answer prints,
once, that no bridge answered and what to install. Later joins cost nothing
while the proxy is known to be there; a request that times out marks it
unknown again, so the next join asks again.

### Nothing is assumed

The previous system wrote bytes into a channel nobody listened on and
reported success, so a proxy command that never ran looked exactly like one
that did. Here every request ends in a reply that says what happened, and a
server without the bridge finds out in five seconds rather than never.

## Adding a capability

A module on the proxy (one class, see the ExyliaProxyUtils README) and one
`Proxy.request(player, "name", payload)` here. The payload is a string the
module defines; nothing in the library needs to change for a new one.

## Where the code lives

| | |
| --- | --- |
| Public | `proxy/Proxy`, `proxy/ProxyReply` |
| Internal | `proxy/internal/ProxyRuntime` (channel, in-flight map, ping on join), `proxy/internal/Wire` (the bytes), `proxy/internal/BridgeCommands` (the default `ProxyCommands`) |
| Tests | `proxy/ProxyTest` — the wire format both sides agree on, `NO_BRIDGE` without a runtime, every reply status to a command result |
| Proxy side | [ExyliaProxyUtils](https://github.com/DiGround-s/ExyliaProxyUtils) |
