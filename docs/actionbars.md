# Action bars

One action bar per player, shared by every plugin that has something to say
there. *Since 1.323.0.*

```java
ActionBars.register(this, "cooldowns", 100, player -> {
    Duration left = ItemCooldowns.remaining(player, "pearl");
    return left.isZero() ? null : Text.component("{info}Pearl " + TimeFormats.render(left) + " ⌚");
});

// something changed and should not wait for the next refresh
ActionBars.refresh(player);
```

## What this replaces

The action bar holds one line, and the last plugin to write it wins. A combat
tag drawn by one plugin and item cooldowns drawn by another, each on its own
timer, overwrite each other every few ticks and the line flickers between them.
Here every plugin hands over a **segment** and the library draws all of them on
one line, in order, separated by `{muted}|`.

## The API

| Call | Does |
| --- | --- |
| `register(plugin, id, order, segment)` | adds a segment; the same id again replaces it in place |
| `unregister(plugin, id)` | removes it |
| `refresh(player)` | redraws that player's line now instead of on the next refresh |

Lower `order` values are drawn first. Exylia plugins use `0` for a combat tag
and `100` for item cooldowns, so a tag reads first.

## Segments

- Asked every **two ticks**, on the player's own thread (entity scheduler on
  Folia). Read memory only: a map, a cached field, `ItemCooldowns`.
- `null` or an empty component means nothing to show. A plugin with several
  entries joins them itself, with its own separator.
- A segment that throws is skipped for that refresh and logged once.

## Sending

- A changed line is sent at once; an unchanged one is sent again once a second,
  because the client fades an action bar after about two.
- A line with nothing left is cleared **once** and then left alone, so a plugin
  outside this module can still use the action bar while nobody here has
  anything to show.

## Lifecycle

Nothing runs while no segment is registered. The first registration starts one
timer per online player; a player who joins gets theirs a second later and loses
it on quit. A disabled plugin's segments are dropped, and the last one going
clears every line. Keeps nothing derived from the palette between refreshes, so
there is nothing to invalidate on reload.

## Where the code is

| | |
| --- | --- |
| API | `net.exylia.lib.actionbar.ActionBars` |
| Internal | `net.exylia.lib.actionbar.internal` — `ActionBarRuntime`, `ActionBarLine` |
| Tests | `src/test/java/net/exylia/lib/actionbar/internal/ActionBarLineTest.java` |
