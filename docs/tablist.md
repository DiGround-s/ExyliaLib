# Tab list module

The player list (Tab): header, footer, how each player is listed and in what
order. Resolved per player, sent only when the rendered text changes.
Since 1.633.0.

Entry point: `net.exylia.lib.tablist.TabLists`.

```java
TabLists.of(this)
        .header(p -> "{primary}&lMY SERVER<nl>{letters}Welcome, " + p.getName())
        .footer(p -> "{letters_black}Ping {info}" + p.getPing() + "ms")
        .name(p -> "{highlight}[R" + rank(p) + "] {letters}" + p.getName())
        .order(p -> rank(p))
        .every(Duration.ofSeconds(1));
```

## API

`TabLists`: `of(plugin)` returns the plugin's `PluginTabList`;
`release(plugin)` drops it (done by ExyliaLib when the plugin is disabled).

`PluginTabList` (every setter returns the handle):

| Method | Contract |
| --- | --- |
| `header(Function<Player, String>)` / `footer(...)` | MiniMessage + palette text; `<nl>` or a newline breaks a line; per viewer |
| `name(Function<Player, String>)` | how a player is listed; one per listed player, not per viewer |
| `order(ToIntFunction<Player>)` | rank in the list, **higher first** |
| `every(Duration)` | re-resolve period, default 1s, minimum one tick |
| `remove(Player)` | stop managing that player; back to vanilla or another plugin's values |

## Behavior

- **Folia-safe.** Every update runs on the player's entity thread, so a
  function may read the player freely. One global timer fires per interval;
  there is no per-tick work.
- **Only changes are sent.** The rendered text is compared with what the player
  already has. An unchanged tab list costs the resolve and a string compare and
  zero packets. Header and footer go in one packet.
- **Several plugins: the last one registered wins each field**, so one plugin
  can own the header and another the names. The fastest `every` among plugins
  sets the timer. When a plugin is disabled its fields fall back to the next
  plugin's, or to vanilla, for players who are still online.
- **A function that throws** is logged once per plugin and the field keeps the
  value it last showed.
- **Cleanup**: quit, `remove(player)` and plugin disable.
- Text is parsed as the server's own, like a scoreboard line: escape what a
  player typed (see [text](text.md)) before putting it in the template.

## Order and server versions

`order` uses `Player#setPlayerListOrder`, which exists on Paper 1.21.2+, behind
`Features.playerListOrder()`. **On 1.21.1 the order is ignored.** The only
fallback would be sorting through scoreboard teams, which collides with the
[nametags](nametags.md) module and with any other plugin owning the teams, so
it is not attempted. Header, footer and names work on every supported version.
