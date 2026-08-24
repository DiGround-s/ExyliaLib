# Skulls

Player heads: fetched once, remembered, and never on the main thread.

```java
// From a config value. Instant — nothing is fetched, ever.
ItemStack icon = Skulls.texture(config.icon()).item();

// A player's head in a menu: drawn now, corrected when it arrives.
Skulls.player("Notch")
      .name("<gold>Notch")
      .viewer(player)
      .build(head -> menu.setItem(slot, head));
```

The library is installed once on the server, so the cache is shared: two
plugins asking for the same head ask Mojang once between them.

## The four ways in
| Source | Cost | Use it for |
| --- | --- | --- |
| `Skulls.texture(base64)` | free | heads written in a config |
| `Skulls.url(url)` | free | heads from a head website |
| `Skulls.player(name)` | one lookup, once | a named player |
| `Skulls.player(uuid)` | one lookup, once | anything with an id already |
| `Skulls.player(player)` | free | somebody who is connected |

A bare texture hash, a full `https://textures.minecraft.net/texture/...` URL
and a scheme-less one are all accepted, because all three end up pasted into
YAML in practice.

Passing a `UUID` skips the name-to-id request, which is a whole round trip, and
survives the player renaming themselves. Passing an online `Player` costs
nothing at all: their skin arrived with them.

## Heads that are not ready yet
`build()` returns a `SkullHandle` immediately. It carries an item right away —
the finished head if it is known, the library's fallback head if it is not —
and calls back when the real one lands.

```java
SkullHandle handle = Skulls.player("Notch").viewer(viewer).build();
menu.setItem(slot, handle.item());          // never an empty slot
handle.onReady(head -> menu.setItem(slot, head));   // swapped in place
```

- **The callback runs on the right thread.** The main thread normally; on
  Folia, the thread that owns the viewer, which is the only one allowed to
  touch their inventory. Set `.viewer(player)` and it is handled.
- **A warm head costs no scheduling.** When the texture is already known,
  `onReady` runs immediately, in the calling thread. The cold path and the warm
  path are written identically.
- **Cancel when the menu closes.** `handle.cancel()` drops the callback, so a
  player who closes a menu early does not leave one holding a dead inventory.
- **The callback never fires with nothing.** A failed lookup leaves the plain
  head in place rather than calling back with null.

`build(Consumer)` is the short form: the action is called once now and again
when the head arrives.

## Warming a menu before it opens
```java
Skulls.warm(topPlayers.stream().map(SkullSource::player).toList());
```

Every head in that list is then instant. Worth doing on join, or when the data
behind a leaderboard changes — the difference between a menu that pops in and
one that does not.

`Skulls.isCached(source)` answers whether a head is ready with no waiting.

## What it costs
| | |
| --- | --- |
| Known texture | one item allocation, no scheduling |
| Unknown texture | one HTTP request, shared and remembered |
| Forty slots, same head | **one** request between them |
| Restart | nothing: textures are read back from disk |

Only textures are cached, never `ItemStack`s. An item is mutable and much
larger than the string it is built from, and building one is cheap; caching
items means cloning on every read, which is what the old implementation did.

Heads with the same skin share one profile id, derived from the texture. The
client caches by profile, so a menu of forty identical heads is one texture to
it rather than forty.

## When Mojang says no
A rate limit is an ordinary condition, not a crash. The whole module goes quiet
for ten minutes, logs **one** line, and keeps serving every head it already
knows. Nothing is retried in a loop and nothing is logged per head.

A name Mojang has never heard of is remembered as unknown for thirty minutes,
so a typo in a config is not asked about every time a menu opens. A lookup that
failed *because* of a rate limit is not remembered that way — being unable to
ask is not evidence the player is fake.

## The fallback head
A head with no texture of its own — a lookup that has not landed yet, or one
that failed — is drawn with a configured fallback texture rather than a plain
grey head. It comes from `fallback-head` in `plugins/ExyliaLib/config.yml`,
defaults to the same neutral head ExyliaCommons shipped, and is applied on
startup and on every `/exylialib reload`. An unreadable value is reported
once and the previous fallback stays in force.

## Persistence
Textures are written to `plugins/ExyliaLib/skull-cache.txt` on shutdown and
read back on start, so the first menu after a reboot is instant. Entries expire
after fourteen days. The file is written through a temporary file and an atomic
move, so a server killed mid-write does not leave a corrupt cache.

`Skulls.invalidate(source)` forgets one head — use it when a player changes
their skin. `Skulls.invalidateAll()` forgets everything, in memory and on disk.

## Text on heads
Names and lore go through `Text`, so palette tokens, MiniMessage and
placeholders work exactly as they do everywhere else. Italics are turned off
unless the text asks for them, because vanilla italicises item names and
nobody wants that.

Set `.viewer(player)` to have their placeholders resolved.

## Diagnostics
```java
Skulls.Stats stats = Skulls.stats();   // cached, pending, backedOff, backoffRemaining
```

## Threads
Nothing here blocks the main thread. Methods that could wait return a
`SkullHandle` or a `CompletableFuture`, never an item. `item()` is safe to call
from the main thread: it builds from what is already known.

## Source and tests
- Public: `skull/Skulls.java`, `SkullSource.java`, `SkullBuilder.java`,
  `SkullHandle.java`.
- Internal: `skull/internal/` (`SkullRuntime`, `MojangApi`, `Lookup`,
  `SkullStore`, `Textures`, `HeadFactory`, `OnlineSkins`, `Handle`, `Handles`,
  `Json`).
- Tests: `src/test/java/net/exylia/lib/skull/` (`SkullCacheTest`,
  `SkullStoreTest`).
