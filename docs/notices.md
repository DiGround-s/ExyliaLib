# Notices

Messages left for a player — mail for words, not items. A sale, an expired
auction, a clan invite: whatever a plugin wants a player to read, whether or not
they are online to read it. *Since 1.278.0.*

```java
private Notices notices;

@Override
public void onEnable() {
    notices = Notices.of(this)
            .summary("sold", (player, sold) -> List.of(Text.from(this,
                    "%prefix% {letters}While you were away {highlight}%count% {letters}items sold for {success}%total%")
                    .with("%count%", sold.size())
                    .with("%total%", Formats.money(sold.stream().mapToDouble(n -> n.number("price")).sum()))))
            .click("sold", "/market claim");
}

// any thread
notices.send(seller, "sold", "%prefix% {letters}Sold {highlight}%item% {letters}for {success}%price%",
        Map.of("item", "Diamond x16", "price", 4500));
```

## The surface

| Method | What it does |
| --- | --- |
| `Notices.of(plugin)` | the plugin's notices, once in `onEnable`; same instance on every call |
| `send(UUID, category, message)` | leaves a message |
| `send(UUID, category, message, Map<String, ?> values)` | the same with `%key%` values, kept apart so a summary can add them up |
| `summary(category, NoticeSummary)` | collapses a category's waiting notices into a few lines on join |
| `click(category, command)` | every join line of the category runs the command when clicked (hover: *Click to open*) |
| `channel(category, Broadcasts.Channel)` | a player who muted the channel in `/settings` is not shown that category, live or on join |
| `live(category, BiConsumer<Player, Notice>)` | runs on the player's thread after each notice of the category shown live (not on join): a sound, a toast. *Since 1.279.0* |
| `footer(Function<UUID, List<String>>)` | lines after the join summary, or alone, computed off the main thread — "you have 3 items to claim" |

`Notice` is what a summary reads: `category()`, `message()`, `values()`,
`sentAt()`, `text(plugin)` (the message filled), `fill(Text)` (another template
filled with the same values) and `number(key)` (a value as a number, `0` when it
is not one). `NoticeSummary.render(player, notices)` returns `List<Text>` and
runs on the player's thread: it reads only what it is handed.

## Where a notice goes

- **Online on this server** — shown at once, as its own message.
- **Online on another server** sharing the plugin's database — written, then
  announced on the plugin's `notices` channel ([redis.md](redis.md)); the server
  they are on takes it and shows it as its own message. Without Redis it waits
  for their next join.
- **Offline** — written to the plugin's database, table `exylia_notices`, and
  shown on their next join, three seconds in so it is not buried under the join
  messages.

A player who leaves between the send and the message gets the notice written
back rather than lost.

## The join summary

Waiting notices are grouped by category, in the order each first appeared, oldest
first inside each. A category with a `summary` shows whatever lines it returns.
One without shows a lone notice as its own message, and two or more as:

```
EXYLIA • While you were away (7)
 ▎ Sold Diamond x16 for $4,500
 ▎ ...                          (five at most)
 ▎ and 2 more
```

A leading `%prefix%` is dropped from list lines. A summary that throws is
reported and the category is listed instead. The `footer` lines follow.

## Exactly once

A notice is taken by deleting its row, and only the call whose delete removed it
shows it. Two servers — or a join and a channel message on the same server —
racing for one player split the rows; they never both show one.

## Limits

- 500 waiting notices per player and plugin. Past that a new one is refused and
  the console says so: a queue that grows without end is a loop somewhere.
- A notice nobody read is deleted after `notices.keep-days` days, on the next
  claim or by a sweep every six hours:

  ```yaml
  notices:
    # Days an undelivered notice is kept before it is deleted unread. At least 1.
    keep-days: 30
  ```
- A category is 1 to 64 characters; anything else throws.
- Messages are stored raw and rendered by the server that shows them, with its
  palette and the plugin's prefix there.

## Threads and lifecycle

Every method is safe from any thread. The database is only touched in
asynchronous tasks; nothing is read in `onEnable`, so players online during a
reload are greeted a few seconds later rather than blocking the enable. Released
with the plugin: its join checks, sweep timer and channel stop. Nothing is
cached from the palette; reload needs nothing.

## Where the code lives

`notice/Notices`, `Notice`, `NoticeSummary` (public); `notice/internal/NoticeRow`
(the table). Tests: `notice/NoticesTest`.
