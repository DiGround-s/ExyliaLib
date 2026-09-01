# Heads

*Since 1.82.0. `net.exylia.lib.util.head`.*

The decorative head catalogue, searchable from a menu. Separate from
[skulls.md](skulls.md), which answers *what does this player look like*; this
one answers *which of the eighty-six thousand decorative heads did you want*.
The two meet at the end: what a player picks here is a `urlhead-` value the
skull module draws.

```java
Heads.browse(inputs, player, "{warning}Browse a head")
     .open(head -> arenas.save(arena.withIcon(head.icon())));
```

`browse` returns the `SearchInput<Head>` rather than opening it, so a timeout, a
validation or a page size are still the caller's to set. It is also the third
way an icon can be given — see `Way.BROWSE` in [input.md](input.md), where it
needs no wiring at all.

## API

| `Heads` | |
| --- | --- |
| `browse(inputs, player, prompt)` | a search over the whole catalogue, ready to open |
| `catalog()` | the same catalogue as a `SearchInput.Pages<Head>`, for a search built by hand |
| `invalidate()` | forgets the remembered pages, so the next search asks again |

| `Head` | |
| --- | --- |
| `id()` | the catalogue id |
| `name()` | what the head is called; what a search matches |
| `texture()` | the texture hash, as `textures.minecraft.net` names it |
| `category()` | the catalogue section it came from |
| `icon()` | the head as a `material` value: `urlhead-<texture>` |
| `item()` | the head, drawn |

The query is matched by the catalogue against names, ids and tag names, so
`cat` finds the cats and `flag` finds the flags without anything local knowing
what a tag is.

## What it costs

Nothing until somebody searches, and one page at a time after that.

- **The catalogue is never downloaded, never indexed, never held.** A search is
  one request for the results on screen, answered by the catalogue's own index.
- **Only the visible page is in memory** — forty-five heads. The texture hash is
  kept and the base64 value the API also answers with is dropped: it is the same
  skin written the long way.
- **A few recently fetched pages are remembered**, so paging back is instant and
  a page turn usually reuses half of what it already has. That cache is the
  whole memory cost of the module, and the oldest page falls out as soon as a
  newer one needs the room.
- **Drawing is free.** A texture hash needs no lookup, so a page of heads costs
  one item allocation each and nothing on the network.
- **Nothing blocks the main thread.** The fetch is asynchronous and its answer
  is applied on the thread that owns the window.

The other side of that trade: a server with no way out to the internet has no
catalogue. The picker says so in the window and every other way of choosing an
icon still works, which is why this is an extra way and not a replacement.

## Why not a local snapshot

The catalogue publishes a full snapshot and a revision feed, and syncing it
would make searching instant and offline. It would also cost about forty
megabytes on disk and an index of eighty-six thousand names in memory, on every
server, so that somebody can pick an icon twice a month. Asking for the page on
screen is the same answer for a thousandth of the footprint.

## What is stored

`urlhead-<texture>` — about seventy characters, rather than the four hundred of
the equivalent `basehead-`. It fits every column an icon is stored in, it is
wrapped into a texture locally, and a head saved today still draws when the
catalogue is unreachable.

## Where the code lives

| | |
| --- | --- |
| Public | `util/head/Heads`, `util/head/Head` |
| Internal | `util/head/internal/HeadDb` |
| Source | [headdb.net](https://headdb.net), maintained by BitworksMC |
