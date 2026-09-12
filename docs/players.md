# Players

Working on a player who is not here.

Every table in the ecosystem is keyed by a player's `UUID`, and every command
is typed on their name. The one thing standing between a plugin and a
`/punish`, `/stats` or `/reset` that works on somebody offline is turning that
name into that id — and doing it without going to Mojang on the main thread.

This module does that once, for every plugin.

*Since 1.146.0.*

---

## The type

`ExyliaPlayer` is an address, not a snapshot:

```java
public record ExyliaPlayer(UUID id, String name, @Nullable String server)
```

| Member | What it answers |
| --- | --- |
| `id()` | Their unique id. **Never null** — that is the point of the type. |
| `name()` | The name that id last answered to. |
| `here()` | The live `Player`, or `null` when they are not on this server. |
| `player()` | The same, as an `Optional`. |
| `offline()` | Them as an `OfflinePlayer`, for APIs that still ask for one. |
| `isHere()` | Whether they are on this server right now. |
| `isConnected()` | Whether they are playing anywhere on the network. One set lookup. |
| `isOnNetwork()` | Whether the proxy named the server they are on. |
| `serverName()` | That server's name, or empty. |
| `is(other)` | Whether two addresses are the same player. |

Two addresses for the same id are equal even if the name or the server
differs: identity is the id alone, so a rename or a server switch does not
turn one player into two, and a `Set<ExyliaPlayer>` never holds them twice.

It is an address and not a cache of their data. Read what you need and let it
go; a field of type `ExyliaPlayer` that outlives a command is a name that will
eventually be wrong.

---

## Finding one

`ExyliaPlayers` has two questions, and which one you ask decides what it
costs.

### `cached` — from memory, safe anywhere

```java
ExyliaPlayer target = ExyliaPlayers.cached(name);   // null when unknown
```

Three tiers, no I/O at all:

1. **Online here** — a lookup inside the server.
2. **The directory** — this module's own name↔id cache, which holds every join
   and everything the expensive tiers ever resolved.
3. **Bukkit's user cache** — the `usercache.json` the server already holds in
   memory: `getOfflinePlayerIfCached` for a name, the id overload of
   `getOfflinePlayer` for an id. A map lookup either way. No file, no Mojang,
   nothing to block on.

Tier 3 is why offline commands are free: on an established server it answers
for nearly everybody a command is ever pointed at. Safe from a tab completion,
a placeholder or a packet handler.

### `resolve` — the whole network, never blocking

```java
ExyliaPlayers.resolve(name).thenAccept(found -> { ... });
```

Adds two tiers after the cheap ones:

4. **The proxy** — somebody connected to another backend who has never set
   foot here. A Redis round trip, five seconds at worst.
5. **Mojang** — somebody nobody on the network has ever seen. Shares the skull
   module's client, so one rate limit backs both off at once.

Never fails exceptionally: a player nobody can find is an empty answer. Six
staff members looking up the same name is one request, not six. A name that
resolved to nobody is not asked about again for five minutes, so a typo in a
command costs one lookup rather than one per press of enter.

**Tier 5 is skipped on an offline-mode server.** Mojang's ids are not the ids
such a server stores, so its answer would be an id no row is keyed by — and
one that looks valid. Deriving the offline id from the name is not safe
either: a proxied network runs its backends in offline mode while forwarding
the real ids, so the derivation would be wrong exactly where it looks most
obviously right.

### `then` — resolve and come back

```java
ExyliaPlayers.then(sender, name, target -> {
    // On the server thread. The sender is still here.
    history.open(sender, target);
});
```

Resolves, hops back to the thread that may touch the world (the sender's own
region on Folia), and sends the sender the library's not-found line when
nobody answers. This is the shape every plugin was writing by hand.

A plugin whose "never played here" line is already a key in its own
`messages.yml` keeps it — the lookup and the threading are the library's, the
sentence stays the plugin's:

```java
ExyliaPlayers.then(sender, name,
        target -> history.open(sender, target),
        () -> Msg.send(sender, messages.get().neverPlayed(), Msg.of("player", name)));
```

### Names for an id

```java
String name = ExyliaPlayers.nameOf(id);              // null when unknown
String shown = ExyliaPlayers.nameOr(id, "unknown");
```

From memory: online here, the directory, then the server's profile cache.
What a menu listing rows by id should call — it puts a name on a hundred
stored ids without leaving the thread it was drawn on.

The id overload of `getOfflinePlayer` is safe and the name overload is not,
which is worth knowing: the first reads the user cache and stops, the second
goes to Mojang when it misses. Nothing in this module calls the name one.

### Teaching it a name

```java
ExyliaPlayers.remember(id, name);
```

Joins are recorded by the library. Call this when a plugin learns a pairing
the server has no other way to know — a row out of its own database, an answer
from an external service. A leaderboard loaded on start makes every name on it
resolvable without a single lookup.

---

## In a command

Copy `PlayerArguments.java` from any Exylia plugin — ExyliaStaff's
`command/PlayerArguments.java` is the reference — into your own command
package, and install it on your builder:

```java
Lamp<BukkitCommandActor> lamp = PlayerArguments.install(BukkitLamp.builder(plugin))
        .build();
```

**The parameter types cannot come from this library, and that is not an
oversight.** Lamp is declared in `libraries:`, and the server's library loader
builds one of those per plugin, so this library's `ParameterType` and yours are
different classes with the same name. A factory built here and handed to a
builder there does not link:

```
LinkageError: loader constraint violation ...
  addParameterTypeFactory(ParameterType$Factory)
  ... have different Class objects for the type ParameterType$Factory
```

What does cross the boundary is everything that is not a Lamp type:
`ExyliaPlayer` and `PlayerTarget` are library classes, of which there is one
copy, and `ExyliaPlayers.cached`, `names()`, `notFound(...)` and
`Suggestions.matching` take and return nothing but those, Bukkit and the JDK.
`PlayerArguments` is twenty lines of glue over them, and it is the same twenty
lines in every plugin.

Then declare the parameter and the library resolves it:

```java
// Somebody this server knows. Rejected before the handler runs otherwise.
@Command("stats")
public void stats(Player viewer, ExyliaPlayer target) {
    stats.of(target.id()).thenAccept(...);
}

// Somebody who may be anywhere. Looked up in the handler.
@Command("punish")
public void punish(Player staff, PlayerTarget target, String reason) {
    target.then(staff, found -> punishments.apply(found.id(), reason));
}
```

| Parameter | Resolves | When to declare it |
| --- | --- | --- |
| `ExyliaPlayer` | Tiers 1–3, while parsing | The common case: anybody who has played here |
| `PlayerTarget` | Tiers 1–5, in the handler | The target may be on another backend, or new to the network |
| `Player` | Lamp's own, online here | The command genuinely needs a live player |

Both suggest this server's players and the network's, filtered to what is
being typed. Nothing else is needed: the not-found line is sent by the
exception in `PlayerArguments` itself, reading the library's own
`messages.yml`, so a plugin adopting these types registers no exception
handler and writes no message of its own.

`PlayerTarget` carries what was typed and defers the work:

| Member | What it does |
| --- | --- |
| `name()` | What the sender typed — the name to put in a message |
| `cached()` | Tiers 1–3, synchronous; `null` when a lookup is needed |
| `resolve()` | Tiers 1–5, as a future |
| `then(sender, action)` | Resolve, hop back, and report not-found for you |
| `then(sender, action, notFound)` | The same, when the plugin has its own wording for it |

### Why the cheap type does no I/O

Lamp calls `parse` for every keystroke of a tab completion and several times
per execution while it works out which overload is meant. A parameter type
that went to a database or to the proxy would be doing it dozens of times a
second, on whichever thread the completion arrived on. That is the whole
reason the network tiers live behind `PlayerTarget` instead.

---

## Messages

Two lines, in `plugins/ExyliaLib/messages.yml` under `players:`:

```yaml
players:
  # Sent when no player anywhere answers to the name that was typed.
  not-found: '[sound:ENTITY_VILLAGER_NO|1|1]{error}✖ {letters}No player named {highlight}%player% {letters}was found.'
  # Sent when the player is known but is not on this server.
  not-here: '[sound:ENTITY_VILLAGER_NO|1|1]{error}✖ {highlight}%player% {letters}is not on this server.'
```

Here rather than in every plugin because it is the same sentence everywhere
and it is the library that decides when it is true. Eleven plugins had eleven
wordings of it, several saying "is not online" about a player who had simply
never played there.

---

## Threads

| What | Thread |
| --- | --- |
| `cached`, `nameOf`, `nameOr`, `names`, `remember` | Any. Memory only. |
| `resolve` | Returns immediately; completes off the server thread. |
| `then` | The action runs on the thread that owns the sender. |
| `ExyliaPlayer.here()`, `offline()` | Any; they read, they do not fetch. |

A future from `resolve` handled by hand needs `Tasks` before it touches
anything in the game, as everywhere else in the library.

---

## What it deliberately does not do

- **It stores nothing.** No table, no new file. The library opens no database
  of its own, so an identity table would live duplicated in every consumer's
  `database.yml`; the server's user cache, the proxy's player list and Mojang
  already hold the three answers between them.
- **It never calls `Bukkit.getOfflinePlayer(String)`.** That method reads like
  tier 3 and behaves like tier 5: for a name the user cache has never seen it
  goes to Mojang, on whatever thread asked, with no back-off and no negative
  cache. Three plugins were calling it from a command handler.
- **It does not offer the user cache as tab completions.** An established
  server's cache is a hundred thousand names and a tab key that hangs.
- **It does not tell you whether they are allowed.** Permissions, punishments
  and vanish are the asking plugin's business; this module answers "who".

---

## Where the code lives

| Part | File |
| --- | --- |
| The type | `player/ExyliaPlayer.java` |
| The facade | `player/ExyliaPlayers.java` |
| The deferred target | `player/PlayerTarget.java` |
| The tiers and the caches | `player/internal/PlayerRuntime.java` |
| The Lamp glue | `PlayerArguments.java`, in each consumer plugin |
| The messages | `text/LibraryMessages.Players` |
| Joins recorded | `ExyliaLib.onPlayerJoin` |
