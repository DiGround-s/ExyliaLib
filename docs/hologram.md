# Hologram module

Floating text, items and blocks made of packets — never real entities.
Since 1.6.0.

Entry point: `net.exylia.lib.hologram.Holograms`.

## Declared in config, not in Java

```java
Holograms.show(this, "trophy", location, config.trophy());
```

`HologramConfig` nests in the plugin's config record:

| Field | Meaning |
| --- | --- |
| `enabled` | show at all |
| `lines` | the text lines (Kind TEXT) |
| `item` / `block` | what floats for Kind ITEM / BLOCK |
| `perPlayer` | each viewer sees their own placeholder values |
| `viewDistance` | how far it is visible |
| `offsetX/Y/Z` | position adjustment |
| `kind` | `TEXT`, `ITEM` or `BLOCK` |
| `properties` | `billboard`, `alignment`, `scaleX/Y/Z`, and more display properties |
| `click` | `commands`, `width`, `cooldown`, `hover-scale`: what clicking it does (since 1.287.0; `hover-scale` since 1.288.0) |

The YML keys match what ExyliaCommons' `HologramTemplateSerializer` wrote,
minus the ones that mean nothing here (chunks, disk persistence — a hologram
is packets, not a file). The interval is in ticks, like the scoreboard (and,
like it, also reads `1s`):
another scoped deviation so commons files keep working.

## Line tags

Tags at the very start of a text line style that line alone. Look and models
since 1.285.0; models with blocks, glow, motion, visibility and clicks since
1.287.0.

| Tag | Effect |
| --- | --- |
| `<size:2.2>` | scales the line on top of `properties.scale-*`; the lines above it move up to make room, so a title never overlaps |
| `<opacity:160>` | the line's text opacity, 0 to 255 |
| `<background:#66000000>` | the line's own background (`#AARRGGBB`), e.g. a pill behind a call to action |
| `<shadow:false>` | turns the shadow off (or `<shadow>` on) for this line |
| `<item:DIAMOND>` | the line is a floating item, centred in the stack and facing the player |
| `<block:BEACON>` | the line is a floating block, turning around its own centre when it spins |
| `<glow>` / `<glow:#8a51c4>` | glow outline on an item or block line, in the team colour or the one given |
| `<spin>` / `<spin:180>` | turns the line, in degrees a second (default 90, negative turns the other way) |
| `<bob>` / `<bob:0.15>` | floats the line up and down, in blocks (default 0.08) |
| `<pulse>` / `<pulse:0.1>` | the line breathes, growing and shrinking by that fraction (default 0.06) |
| `<shine>` / `<shine:highlight>` / `<shine:#ffd700>` | a band of light sweeps over the line every few seconds (default colour: the palette's `letters`) |
| `<flow>` / `<flow:8>` | the line's own colours flow along it, in characters a second (default 4); a gradient flows without a seam, a one-colour line gets a soft wave of its lighter shade |
| `<typewriter>` / `<typewriter:40>` | types the line in, in characters a second (default 24), when a player first sees it or when it appears for them |
| `<range:8>` | the line only shows within 8 blocks; walking up reveals it, and the stack re-lays itself out for that player |
| `<perm:observatory.key>` / `<perm:!observatory.key>` | only shown to players with (or without) the permission |
| `<if:"%player_level% >= 10">` | only shown while the condition holds (`==`, `!=`, `>`, `<`, `>=`, `<=`, placeholders resolved per viewer) |

An arena entrance: a block turning above a shining title, a line each viewer
reads for themselves, and a hint that types itself in only up close.

```yaml
lines:
  - '<block:NETHERITE_BLOCK><spin:45><glow:#ff6b9d>'
  - '<size:2.4><shine>{accent}&lTHE ARENA'
  - '<flow>{secondary}Season II · open now'
  - '<size:0.5> '
  - '<if:"%player_level% >= 10">{success}✔ You qualify'
  - '<if:"%player_level% < 10">{error}Reach level 10 to enter'
  - '<range:5><typewriter><background:#55000000>{letters}Right-click to join the queue'
click:
  commands:
    - 'arena join'
  width: 1.5
  cooldown: 500ms
```

A members' door, one line for each side of a permission:

```yaml
lines:
  - '<item:AMETHYST_SHARD><bob><glow:#b48fd9>'
  - '<size:1.6><pulse:0.04>{secondary}&lTHE OBSERVATORY'
  - '<perm:observatory.key>{success}Welcome in'
  - '<perm:!observatory.key><opacity:180>{letters_black}Find the key in the library'
```

- Only these tags are read, and only at the start of the line:
  `<gradient:…>` and every other text tag is left for the text parser.
- A tag whose value does not parse (`<size:big>`) stays in the text, so the
  typo shows on screen.
- A value that holds `>` goes in double quotes.
- A blank `<size:0.5> ` line is a half-height gap.

**Motion costs almost nothing.** An animated line is sent one transformation
every 500 ms aimed at the next keyframe, and the client glides there on its
own. Every viewer sees the same frame, because frames come from the clock.

**Visibility per line** (`range`, `perm`, `if`) is checked on the visibility
pass (four times a second) for the viewers in range. When a viewer's set of
lines changes, the lines they lost are destroyed, new ones spawn, and the ones
that stay are moved, so nothing flickers. Placeholders in conditions run off
the main thread, like every placeholder in a hologram line.

**Shine and flow** recolour the parsed line one character at a time, keeping
every other style, so they work over gradients, palette colours and
placeholder values alike. A flowing line is re-sent four times a second; a
shining one only while its band crosses it (about a second and a half every
four), plus once to restore the plain line.

**Typewriter** sends the growing text to one player only while it is typing
for them. Changes to that line wait until it is fully typed, and colours and
styles are never cut.

## Reading and writing line tags in code

`HologramLine` (since 1.289.1) is the reading a hologram does, public, for an
editor that shows a line's tags as fields and writes them back:

```java
HologramLine line = HologramLine.parse(raw);          // tags into fields, the rest into text()
String edited = line.toBuilder().size(2.0).spin(null).build().raw();
```

A tag the line does not write is `null`, so a round trip adds nothing nobody
asked for. A bare tag (`<spin>`) reads as its default (`DEFAULT_SPIN` and the
rest) and is written back with it. Values are kept as written: the hologram
clamps them when it draws.

## Clicks

`click.commands` (the `net.exylia.lib.command` syntax: `console:`,
`player:`, a bare line runs as the player) give the hologram an invisible
interaction box as tall as the lines the viewer sees and `click.width` wide.
Left or right click both count, once per `click.cooldown`. While a player
looks at the box, within about six blocks, the hologram grows by
`click.hover-scale` (default 1.08, 1 turns it off) and glides back when they
look away, so it reads as something to click. In code:

```java
hologram.onClick(player -> arenas.queue(player)); // on the player's thread
```

A handler makes the hologram clickable even with no commands. Without either
it has no box at all.

## Riding an entity

`attachTo(entity)` makes every display a passenger. The client draws all the
passengers of one vehicle at the same point, so a ridden hologram is stacked
with each display's translation instead of its position. `offset-x/y/z` still
applies, measured from where the vehicle carries its passengers. The click box
rides along and reaches up over the offset. Hover does not apply while
riding. Since 1.288.0; before, the lines of a ridden hologram overlapped.

## API

`Holograms`:

| Method | Contract |
| --- | --- |
| `show(plugin, id, location, config)` / with `data` | create and show |
| `show(plugin, id, location, config, visibleIf)` | create and show only to the players the filter lets through, from the first frame (since 1.188.0); `visibleIf` on a shown hologram leaves a moment in which it can be drawn for everybody in range |
| `get(plugin, id)` / `all(plugin)` | queries |
| `remove(plugin, id)` / `removeAll(plugin)` | cleanup |
| `isSupported()` | false when PacketEvents is absent — nothing draws, everything still works |
| `isViewing(player, hologram)` | whether that player currently sees it |

`Hologram`: `id()`, `location()`, `moveTo(location)` (teleport, not respawn —
no flicker), `attachTo(entity)` (rides the entity; the client moves it, zero
packets while moving), `lines(List)`, `refresh()`, `updateData(Map)`,
`onClick(Consumer<Player>)` (since 1.287.0), `visibleIf(Predicate<Player>)`, `isViewing(player)`, `viewerCount()`,
`remove()`, `removed()`.

## Behavior

- **Packets or nothing.** No PacketEvents → `isSupported()` is false; there
  is no real-entity fallback ticking on the server.
- **Visibility is checked four times a second** with squared distance per
  player per hologram; packets only go out when someone crosses the edge.
- **Only what changed is sent.** A line with no placeholders never refreshes;
  one that does diffs and re-sends just that line.
- Nothing outlives its owner: plugin disable, player quit and palette reload
  all clean up (palette reload re-sends everything).

## Source and tests

- Public: `hologram/Holograms.java`, `Hologram.java`, `HologramConfig.java`,
  `HologramLine.java`.
- Internal: `hologram/internal/` (`HologramImpl`, `HologramRuntime`,
  `DisplayPackets`, `DisplaySink`, `DisplayState`, `LineStyle`, `Typewriter`,
  `Shimmer`,
  `NoopHologram`).
- Tests: `src/test/java/net/exylia/lib/hologram/`.
