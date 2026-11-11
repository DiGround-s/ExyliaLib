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
| `properties` | `billboard`, `alignment`, `scaleX/Y/Z`, and more display properties; `distance-scale` grows it with the viewer's distance (since 1.301.0, see [Size by distance](#size-by-distance)) |
| `click` | `commands`, `width`, `cooldown`, `hover-scale`: what clicking it does (since 1.287.0; `hover-scale` since 1.288.0); `style`, `color`, `hover-color`, `sound`, `buttons` (since 1.301.0, see [Buttons](#buttons)); `range` (since 1.303.0, see [Reach](#reach)); `permission`, `denied-message`, `cooldown-message`, `button-permissions` (since 1.563.0, see [Who may click](#who-may-click)) |
| `pages` | more pages after `lines`, each with its own `lines` and `click` lines (since 1.297.0) |
| `page-interval` | ticks before each viewer is turned to the next page on their own; 0 is off (since 1.297.0) |
| `view` | `condition`, `permission`, `regions`, `worlds`: who sees the hologram at all, checked per player; left out, everyone in range (since 1.563.0, see [Where the viewer stands](#where-the-viewer-stands)) |

The YML keys match what ExyliaCommons' `HologramTemplateSerializer` wrote,
minus the ones that mean nothing here (chunks, disk persistence — a hologram
is packets, not a file). The interval is in ticks, like the scoreboard (and,
like it, also reads `1s`):
another scoped deviation so commons files keep working.

## Which way it faces

Every line is spawned, and moved, with the yaw and pitch of the location the
hologram was shown at (since 1.291.1). That is what a `FIXED` billboard is read
from: a text display at yaw 0 faces south, so a hologram meant to be read by a
player looking at it takes that player's yaw plus 180. `CENTER` turns to every
viewer anyway; `VERTICAL` and `HORIZONTAL` keep the axis they do not turn.

## Line tags

Tags at the very start of a text line style that line alone. Look and models
since 1.285.0; models with blocks, glow, motion, visibility and clicks since
1.287.0.

The colour and text effects (`shine`, `flow`, `rainbow`, `frames`, `marquee`,
`blink`) are shared with every other text since 1.579.0: the same tags move a
sidebar title or a boss bar (see [Moving text](text.md#moving-text-since-15790)).

| Tag | Effect |
| --- | --- |
| `<size:2.2>` | scales the line on top of `properties.scale-*`; the lines above it move up to make room, so a title never overlaps |
| `<opacity:160>` | the line's text opacity, 0 to 255 |
| `<background:#66000000>` | the line's own background (`#AARRGGBB`), e.g. a pill behind a call to action |
| `<shadow:false>` | turns the shadow off (or `<shadow>` on) for this line |
| `<item:DIAMOND>` | the line is a floating item, centred in the stack and facing the player; `<item:nexo:ruby>` and the other resource-pack ids work too (1.328.0), and so does a stored `<item:bytes:...>`, enchantments and all (1.537.0) |
| `<block:BEACON>` | the line is a floating block, turning around its own centre when it spins |
| `<head:Notch>` | the line is a player head, drawn like an item line: a player name, a UUID, a base64 texture, a `textures.minecraft.net` URL or a bare texture hash (`urlhead-`/`basehead-`/`playerhead-` values read too). `<size:0.5>` makes it small, `<size:2>` large (since 1.297.0). A placeholder is read per viewer: `<head:%player_name%>` shows each player their own head (1.537.0) |
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

Since 1.301.0:

| Tag | Effect |
| --- | --- |
| `<gap:0.3>` | extra blocks above the line, so the lines above it move up; negative pulls them closer (clamped to -5..5) |
| `<align:left>` / `<align:center>` / `<align:right>` | the line's own alignment, over `properties.alignment` |
| `<width:120>` | where the line wraps, in pixels, over `properties.line-width` |
| `<billboard:fixed>` / `center` / `vertical` / `horizontal` | how the line turns towards the player, over `properties.billboard` (a block line is `fixed` unless it says otherwise) |
| `<seethrough>` / `<seethrough:false>` | whether the line shows through blocks |
| `<brightness:15>` | the line's fixed light level, 0 to 15, -1 for the light where it stands |
| `<autoscale>` / `<autoscale:4,24,3>` | the line grows with the viewer's distance: on the hologram's `distance-scale` curve (enabled or not), or its own `start,end,max` (blocks, blocks, size; it is its own size up to `start`). A line's own curve replaces the hologram's for that line |
| `<sway>` / `<sway:20>` / `<sway:20,period=6>` | turns the line back and forth by that many degrees (default 15) over `period` seconds (default 4); for item and block lines, adds to `<spin>` |
| `<bob:0.1,period=3>` / `<pulse:0.06,period=4>` | the bob or pulse with its own period in seconds (defaults 3 and 2; at least 2, as keyframes are half a second apart) |
| `<shine:highlight,speed=15,pause=1,width=4>` | the shine with its own speed (characters a second, default 10), `pause` between sweeps (seconds, default 2.5) and half `width` of the band (characters, default 3). Every option is optional, in any order; `<shine:speed=20>` keeps the default colour |
| `<rainbow>` / `<rainbow:10>` | the line's colours become a soft rainbow running along it, in characters a second (default 6; negative runs the other way) |
| `<frames>` / `<frames:1.5>` | the text is split by <code>&#124;&#124;</code> into frames that take turns, each shown that many seconds (default 2; `1s` and `500ms` read too) |
| `<marquee>` / `<marquee:20>` / `<marquee:20,speed=3>` | shows 20 characters (default 24) of a longer line at a time, scrolling through them at `speed` characters a second (default 6); a line that fits stays still |
| `<fade>` / `<fade:2>` | the text fades out and back in, once every that many seconds (default 3); the background stays |
| `<blink>` / `<blink:0.5>` | the text shows and hides, once every that many seconds (default 1, shown two thirds of it) |
| `<scramble>` / `<scramble:30>` | when a player first sees the line, random glyphs settle into it from the left, in characters a second (default 20); spaces stay spaces |
| `<button:join>` | the line is a button of its own: clicking it runs `click.buttons.join` instead of `click.commands`, and only it lights up and grows (see [Buttons](#buttons)) |

Since 1.563.0:

| Tag | Effect |
| --- | --- |
| `{countdown:2026-12-01 18:00}` | anywhere in the text: the time left, `24d 6h 26m 12s`, leading zero units left out; the date is `yyyy-MM-dd HH:mm:ss`, `yyyy-MM-dd HH:mm` or `yyyy-MM-dd`, with an optional zone after a space (`2026-12-01 18:00 Europe/Madrid`; default the server's). Passed, it reads `0s`, or the text after a bar: `{countdown:2026-12-01 18:00\|{success}Open now!}` (see [Time in a line](#time-in-a-line)) |
| `{date:HH:mm}` / `{date:dd MMM yyyy}` | anywhere in the text: now, in a Java date pattern, English names, same optional zone |
| `<hover:"{success}▶ Click to join">` | while a player aims at the hologram, or at this line when it is a `<button>`, the line says this instead; its other tags still apply (see [Hover text](#hover-text)) |
| `<region:spawn>` / `<region:!spawn>` / `<region:world:spawn>` | only shown to players standing in (or outside) that WorldGuard region; `world:` names its world too. Without WorldGuard nobody stands in a region |
| `<world:world_nether>` / `<world:!world>` | only shown to players in (or not in) that world |
| `<entity:ZOMBIE>` | the line is a mob, any living entity type, drawn in the stack like an item line and never ticked by the server; takes `<size>`, `<spin>`, `<sway>`, `<bob>`, `<glow>` / `<glow:#hex>` (see [Mob lines](#mob-lines)) |
| `<slide>` / `<slide:left>` / `<slide:down,time=0.4>` | when the line first appears for a player it glides in from that side (`up`, the default, `down`, `left` or `right`) over `time` seconds (default 0.6), per player like typewriter |
| `<image:logo.png>` / `<image:logo.png,width=24>` | the line is a picture from the plugin's `images/` folder, drawn as coloured pixel glyphs, `width` pixels across (default its own, at most 96) (see [Images](#images)) |

Options are written `key=value` after the tag's value, separated by commas. An
unknown option, or one that is not a number, keeps the whole tag in the text,
as any broken tag does. Durations and periods are seconds.

```yaml
lines:
  - '<item:NETHER_STAR><sway:25,period=5><bob:0.1,period=4>'
  - '<size:2><shine:highlight,speed=18,pause=1>{primary}&lSEASON II'
  - '<gap:0.15><frames:3>{letters}Open now||{letters}Rewards doubled||{letters}Talk to the guide'
  - '<marquee:28,speed=5>{letters_black}Top players this week: %top_1% · %top_2% · %top_3%'
  - '<align:left><width:150><range:6><scramble>{letters}Hit the panel below to queue up.'
  - '<autoscale:6,30,2.5><fade:4>{muted}/warp arena'
```

- **Frames** split the parsed line, so a colour set before `||` carries into
  the next frame, as it would on one line, and placeholders resolve in every
  frame. A frame is only re-sent when it turns.
- **Marquee, rainbow, flow and shine** keep every other style and run at ten
  frames a second; a resting shine and a frame that did not turn send nothing.
- **Fade** sends the text opacity alone, in steps of 8, so a fading line costs
  a few small packets a second. **Blink** sends an empty line while hidden.
- **Scramble** is per viewer, like typewriter, and runs at ten frames a second.
  On a line with both, scramble wins.
- **Sway** is keyframed like spin: one transformation every 500 ms, glided by
  the client.

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

**Slide** spawns the line half a block off to its side for that player and,
a tick later, sends it one transformation gliding home over `time`. It costs
one packet per line as it appears, and nothing after. A line that also moves
(`<spin>`, `<bob>`) takes up its motion on the next keyframe.

## Time in a line

Since 1.563.0 a line can count down to a moment and tell the time, with no
placeholder plugin:

```yaml
lines:
  - '<size:1.6>{primary}&lSEASON III'
  - '{letters}Opens in {highlight}{countdown:2026-12-01 18:00 Europe/Madrid|{success}now!}'
  - '{muted}Server time {date:HH:mm}'
```

- **A line holding a token is drawn again once a second, on its own,**
  whatever the hologram's `refresh` interval, and re-sent only when its text
  changed. The other lines keep their interval; a hologram without tokens
  ticks nothing.
- A countdown rounds up, so it never reads `0s` while the moment is still
  ahead, and drops only its leading zero units (`5m 0s`, `12s`).
- The date can be a placeholder: `{countdown:%event_start%}` reads the date
  the placeholder resolves to for that viewer.
- A token that does not read (`{countdown:soon}`) stays in the text as written.
- The resolver is public, `net.exylia.lib.text.TimeTokens`
  (`resolve(text)`, `resolve(text, nowMillis)`, `contains(text)`,
  `countdown(seconds)`), for scoreboards and other text that wants the same
  tokens.

## Hover text

Since 1.563.0 `<hover:"...">` gives a line a second text for while a player
aims at the hologram:

```yaml
lines:
  - '<item:DIAMOND_SWORD><spin>'
  - '{primary}&lTHE ARENA'
  - '<hover:"{success}▶ Click to join">{letters_black}Ranked · 1v1'
  - '<button:spectate><hover:"{info}▶ Watch a match">{info}Spectate'
```

- On a plain line it shows while the player's line of sight crosses the
  hologram (the click box's shape, `click.width` wide and `click.range` far);
  on a `<button>` line, only while that button is aimed at.
- **A hologram that cannot be clicked still tracks aim for its hover lines,**
  on the visibility pass (four times a second), with no click box and no
  extra entity; it does not grow, since there is nothing to click.
- The hover text takes the line's other tags (size, shine, background...),
  and is drawn when the aim starts; looking away sends the line's own text
  back, whole. A line aimed at while it is being typed in shows its hover
  text at once.

## Where the viewer stands

`<world>` and `<region>` (since 1.563.0) hide a line from players elsewhere,
on the same per-viewer pass as `<range>`, `<perm>` and `<if>`. The whole
hologram can ask the same with a `view` section:

```yaml
view:
  condition: '%player_level% >= 10'
  permission: 'arena.see'      # '!arena.see' for players without it
  regions: [spawn, '!pvp']     # in spawn, and not in pvp
  worlds: [world, world_nether]
```

- **One rule for lists.** When any entry is written plain, the player must
  match one of them; and they must match none of the `!` entries. A line tag
  is a list of one. `world:spawn` only counts in that world.
- **Failing `view` hides everything,** click box included, exactly like
  walking out of range; passing it again sends the hologram whole. A missing
  section is everyone in range, as before.
- **Which thread.** Both run on the hologram driver, ExyliaLib's async timer,
  never on a region thread. The world and position are read from the
  player's location (their camera's, while one films for them), which Paper
  and Folia let any thread read, and WorldGuard's region index is safe from
  any thread (see [WorldGuard](worldguard.md)); WorldGuard is asked once per
  viewer per pass, and only by holograms that name a region. The condition's
  placeholders run off the main thread, like every placeholder in a line.
- Without WorldGuard nobody stands in any region: `<region:spawn>` shows to
  nobody, `<region:!spawn>` to everybody.

## Mob lines

`<entity:ZOMBIE>` (since 1.563.0) draws a mob in the stack: a living entity
sent only to the viewer, so the server never ticks, saves or collides with
it. It stands on its place like a block line and takes the room its type is
tall (read once per type from the server), times `<size>`.

```yaml
lines:
  - '<entity:ALLAY><size:0.6><bob:0.1><spin:40><glow:#b48fd9>'
  - '{accent}&lCOMPANIONS'
```

- `<size>` is the scale attribute (clients from 1.20.5); `<spin>`, `<sway>`
  and `<bob>` turn and move it; `<glow>` outlines it, `<glow:#hex>` in the
  nearest chat colour (a mob's outline takes its team colour, which has
  sixteen). `<pulse>` does not apply to a mob.
- A mob has no transformation to glide, so one that moves is re-posed ten
  times a second (a teleport and a head turn) and the client smooths the steps
  between; a still mob is sent once. Only moving mob lines pay for it.
- Each mob gets a team of its own on the viewer's client: no nametag, no
  pushing, and its glow colour. The team goes when the mob does.
- A click on the mob is a click on the hologram, as if on its box.
- A name that is not a living entity type (`<entity:DIAMOND>`, or `PLAYER`,
  which needs a profile) is reported once and drawn as stone.
- Riding an entity (`attachTo`), a mob line sits on the seat and does not move.

## Images

`<image:logo.png>` (since 1.563.0) draws a PNG from the owning plugin's
`images/` folder (`plugins/<Plugin>/images/logo.png`) as a text line of
coloured full-block glyphs, the screen module's pixel pictures: one glyph a
pixel, one row of text a row.

- `width=24` scales it to 24 pixels across, keeping its shape (each new pixel
  the average of the ones it covers); left out, it is drawn at its own width.
  At most 96 pixels a side.
- A pixel is about a sixteenth of a block at `<size:1>`; `<size>` scales it.
  The line takes the room its rows take, has no shadow, and never wraps.
- A pixel more than half see-through is left out; the glyphs sit a pixel
  apart, as the font draws them.
- The file is read once and kept for half a minute, so an edit shows on the
  next reload; one picture is shared by every hologram that shows it. A
  missing or unreadable file is reported once and the line shows its text.

## Size by distance

A hologram can grow with each viewer's distance, so it stays readable from far
away (since 1.301.0). Off unless enabled:

```yaml
properties:
  distance-scale:
    enabled: true
    start: 8.0      # blocks away where it starts growing
    end: 32.0       # blocks away where it reaches max
    min: 1.0        # its size up to start
    max: 2.5        # its size from end on
```

The size grows in a straight line from `start` to `end`, which keeps its
apparent size steady. `min` and `max` are clamped to 0.1..10, and an `end`
before `start` is moved past it. A single line can follow a curve with
`<autoscale>` (the hologram's curve, enabled or not) or `<autoscale:start,end,max>`.

- **Per viewer, in steps.** Each viewer is shown their own size, quantised to
  steps of 0.1 and re-sent only when their step changes, on the visibility
  pass (four times a second), gliding over the 5 ticks to the next pass. Up
  close, or standing still, it costs nothing; walking away costs at most four
  transformations a second per line.
- **The stack spreads with it.** Grown lines take room in proportion, so lines
  never overlap; a line that grows alone pushes the ones above it up for that
  viewer only. Hover and press growth multiply on top.
- The click box keeps its size: nobody clicks a hologram from 20 blocks away.

In code: `new HologramConfig.Properties().withDistanceScale(new HologramConfig.DistanceScale(true, 8, 32, 1, 2.5))`,
and `DistanceScale.scaleAt(distance)` is the curve.

## Reading and writing line tags in code

`HologramLine` (since 1.289.1) is the reading a hologram does, public, for an
editor that shows a line's tags as fields and writes them back:

```java
HologramLine line = HologramLine.parse(raw);          // tags into fields, the rest into text()
String edited = line.toBuilder().size(2.0).spin(null).build().raw();
```

A tag the line does not write is `null`, so a round trip adds nothing nobody
asked for. Since 1.301.0 the record also carries `gap`, `align`, `width`,
`seeThrough`, `brightness`, `billboard`, `autoscale`, `shineSpeed`,
`shinePause`, `shineWidth`, `bobPeriod`, `pulsePeriod`, `sway`, `swayPeriod`,
`frames`, `rainbow`, `fade`, `blink`, `marquee`, `marqueeSpeed`, `scramble` and
`button`, each with its builder method. An option is kept only with its tag:
`shine(null)` drops `shineSpeed` too. `align` and `billboard` are kept in lower
case; a duration such as `<frames:1s>` is written back in seconds (`<frames:1>`).
Since 1.563.0 it also carries `hover`, `region`, `world`, `entity`, `slide`,
`slideTime`, `image` and `imageWidth`; `slideTime` and `imageWidth` are kept
only with their tag, and `entity` excludes `item`, `block` and `head` as they
exclude each other. The 18- and 40-component constructors are deprecated;
build lines with `builder()`. A bare tag (`<spin>`) reads as its default (`DEFAULT_SPIN` and the
rest) and is written back with it. Values are kept as written: the hologram
clamps them when it draws.

## Pages

A hologram can have several pages; every viewer sees one at a time. `lines`
is page 1, so a file written before pages existed reads exactly as it did,
and `pages` adds the rest (since 1.297.0):

```yaml
lines:
  - '<head:Notch><size:0.8>'
  - '{primary}&lWELCOME'
  - '{warning}➥ Click for the rules'
click:
  commands:
    - '[PAGE] next'
pages:
  - lines:
      - '{primary}&lRULES'
      - '{letters}No griefing, no cheating.'
    click:
      - 'left: [PAGE] previous'
      - 'right: [PAGE] 1'
page-interval: 10s
```

- **`click.commands` run on page 1, a page's own `click` on that page.** A
  page with no click lines does nothing when clicked there (a code handler
  still runs).
- **`[PAGE] next`, `[PAGE] previous`, `[PAGE] first`, `[PAGE] last` and
  `[PAGE] 2`** (pages count from 1 there) turn the clicking player's page.
  It is a token only hologram click lines know
  (`Interactions.compile(plugin, lines, name, tokens)`), so it never clashes
  with a plugin's own.
- **`page-interval`** turns every viewer on to the next page on their own,
  counted from when they last turned or came into range. 0 turns it off.
- **A page turn is a per-viewer re-layout.** Pages are one stack shown through
  the same per-viewer mask as `<range>` and `<if>`: the old page's lines are
  destroyed, the new ones spawned, the click box re-sized to the new page and
  typewriter lines typed in again. Nothing else is re-sent.
- The page a player is on is kept until they leave the server.

In code (pages count from 0):

```java
hologram.nextPage(player);
hologram.page(player, 2);
int at = hologram.page(player);
hologram.pages(List.of(firstLines, secondLines));   // keeps each viewer's page
```

## Clicks

`click.commands` takes [interaction lines](interactions.md) (since 1.291.0; a
superset of the command syntax it took before, so every existing line keeps its
meaning) and gives the hologram an invisible interaction box as tall as the
lines the viewer sees and `click.width` wide. A line may start with a click
filter, so left and right (and either while sneaking) can do different things:

```yaml
click:
  commands:
    - '[SOUND] UI_BUTTON_CLICK;1;1.2'
    - 'left: [MESSAGE] {letters}Right-click to join.'
    - 'right: arena join'
    - 'shift_right: [SERVER] lobby'
  cooldown: 500ms
```

A click counts once per `click.cooldown`, whichever button it was. While a
player looks at the box, within about six blocks, the hologram grows by
`click.hover-scale` (default 1.08, 1 turns it off) and glides back when they
look away, so it reads as something to click. A line that cannot be read is
reported once when the hologram is shown and skipped. In code:

```java
hologram.onClick((player, click) -> {            // on the player's thread
    if (click.isShift()) editor.open(player); else arenas.queue(player);
});
hologram.onClick(player -> arenas.queue(player)); // any click, since 1.287.0
```

A handler makes the hologram clickable even with no lines. Without either it
has no box at all.

### Who may click

Since 1.563.0 the click section can ask for a permission and say why a click
did nothing:

```yaml
click:
  commands:
    - 'arena join'
  cooldown: 3s
  permission: 'arena.join'                    # '!arena.join' for players without it
  denied-message: '%prefix% {error}You cannot join yet.'
  cooldown-message: '%prefix% {warning}Wait {highlight}%time%{warning}.'
  button-permissions:
    spectate: 'arena.spectate'                # over 'permission', for that button
```

- A refused click runs nothing: no lines, no `sound`, no press, no code
  handler. Empty `permission` lets everybody click.
- The messages go to the clicker, with `%prefix%` and their placeholders;
  `%time%` is the cooldown left (`2.5s`). Empty (the default) says nothing, as
  before. A double-fired press is told once.
- `button-permissions` gives a `<button>` line its own permission; a button
  without one takes `permission`.

## Buttons

Since 1.301.0 a clickable hologram looks like a button, and single lines can be
buttons of their own.

```yaml
lines:
  - '<head:Notch><size:0.8>'
  - '{primary}&lTHE ARENA'
  - '<gap:0.1><button:join>{success}&l▶ JOIN'
  - '<button:spectate>{info}Spectate'
click:
  commands:
    - '[MESSAGE] {letters}Pick a button below.'
  style: BUTTON              # BUTTON (default) or NONE
  color: '{primary}'         # at rest; '#RRGGBB' or '#AARRGGBB' too
  hover-color: '{secondary}' # while aimed at
  range: 8                   # how far away it can be aimed at and clicked
  sound: 'UI_BUTTON_CLICK;0.6;1.4'   # every click; empty (default) for none
  hover-scale: 1.08
  buttons:
    join:
      - 'arena join'
    spectate:
      - 'left: arena spectate'
      - '[PAGE] next'
```

- **The look** (since 1.303.0). With `style: BUTTON`, what can be clicked is
  drawn as a button:
  - A **button line** is its own button: its text display's background,
    which the client sizes to the label, so it lines up with its text at
    every size, hover, press and tilt. The label gets two spaces of room
    either side, and a column of buttons is padded to the widest one so they
    share a width. A palette colour or `#RRGGBB` rests at 35% alpha and
    lights to 80% (`hover-color`) while aimed at; `#AARRGGBB` keeps its own.
    A `<background:...>` tag on the line replaces the colour at rest.
  - A hologram **without button lines** whose whole body is clickable gets
    one slim panel, fitted to the widest line and the top of the highest
    (measured with the font's widths, wrapping included) plus 0.07 blocks.
    Its lines lose their own background, so there are never two, and turn
    upright (`CENTER` becomes `VERTICAL`, unless the line has a
    `<billboard>` tag) as the panel does, which lets it stand a hair behind
    them with no parallax. 25% alpha at rest, 45% aimed at.
  - A hologram **with** button lines draws nothing behind its other lines.
  - Aimed at, the target grows with `hover-scale`; clicked, it squashes to
    92% for 120 ms and flashes more opaque before springing back. Fully lit
    (`brightness` 15) day and night.

  Before 1.303.0 every button and the whole hologram had a separate panel,
  sized from the line spacing and `width`, which did not match the text.
- **Who gets it.** Only holograms with click lines (`click.commands`, a page's
  `click`) or buttons. A hologram made clickable from code alone
  (`onClick`) gets no panel and no press, and a hologram nobody can click is
  untouched. `style: NONE` turns the look off and keeps the hover growth.
  Sections written before 1.301.0 read as `BUTTON`.
- **Buttons.** A line starting with `<button:name>` runs `click.buttons.name`
  when it is clicked, instead of `click.commands`; every other spot of the
  hologram still runs `click.commands`. Button lines take every interaction
  line, click filters and `[PAGE]` included, and share the click cooldown.
  Only the button looked at grows and lights up. A `<button>` whose name has
  no lines is a plain line. Code handlers (`onClick`) run for clicks on the
  hologram, not on a button.
- **Which line was clicked** is worked out from the height the player's line
  of sight crosses the hologram's axis at: the one click box catches every
  click, so a button costs no extra entity.
- **`sound`** is played to the clicker on every click that has lines to run,
  as a `[SOUND]` line put first.
- **Riding** an entity (`attachTo`), a hologram draws no panel and its buttons
  do not light up: passengers cannot be placed apart from their seat.

## Reach

Since 1.303.0, `click.range` (default 8 blocks, at most 32) is how far away a
player can aim at a clickable hologram. Hover and clicks share one ray from the
eyes against the click box, so whatever lights up is what a click hits.

- **Left-click** reaches the full range: an arm swing aimed at the hologram is
  a click, wherever the client sent it.
- **Right-click holding an item**, or on a block, reaches the full range too:
  the client sends the use of the item or block.
- **Right-click with an empty hand** at nothing sends no packet at all, so it
  reaches only as far as the client lets a hand touch an entity, the click box
  (3 blocks in survival, 5 in creative). Templates that should work from afar
  say "click" or "left-click".
- A click that lands on the box is followed by its swing; the second arrives
  inside the 200 ms click gap and is dropped, so one click never runs twice.
  The ray click goes to the hologram nearest along the line of sight. None of
  these packets is kept from the server: the swing still swings, the item is
  still used. Riding holograms keep box clicks only.

## Riding an entity

`attachTo(entity)` makes every display a passenger. The client draws all the
passengers of one vehicle at the same point, so a ridden hologram is stacked
with each display's translation instead of its position. `offset-x/y/z` still
applies, measured from where the vehicle carries its passengers. The click box
rides along and reaches up over the offset. Hover does not apply while
riding. Since 1.288.0; before, the lines of a ridden hologram overlapped.

While it rides, the entity is the hologram's place: who is in range, and
where its displays are spawned, follow the entity's current location, not
the one it was placed at (`location()` still returns the placed one, and
detaching puts it back there). A client only seats passengers on an entity
it already knows, so the mount is sent again whenever a viewer is sent the
entity anew (they start tracking it, it respawns, it comes back from another
world), and a viewer who respawns or changes world is sent the hologram whole
again. A plugin no longer has to keep moving the hologram to the entity. Since
1.298.1.

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
`onClick(Consumer<Player>)` (since 1.287.0), `onClick(BiConsumer<Player, Click>)` (since 1.291.0), `visibleIf(Predicate<Player>)`, `isViewing(player)`, `viewerCount()`,
`remove()`, `removed()`; pages since 1.297.0: `pages(List<List<String>>)`,
`pageCount()`, `page(player)`, `page(player, n)`, `nextPage(player)`,
`previousPage(player)`.

## Behavior

- **Packets or nothing.** No PacketEvents → `isSupported()` is false; there
  is no real-entity fallback ticking on the server.
- **Visibility is checked four times a second** with squared distance per
  player per hologram; packets only go out when someone crosses the edge.
- **Only what changed is sent.** A line with no placeholders never refreshes;
  one that does diffs and re-sends just that line. A value in the data map that
  is a `Supplier` is read on every refresh (since 1.458.0), so a countdown is
  handed over once — `Map.of("left", (Supplier<Long>) drop::secondsLeft)` — and
  moves on the hologram's own `refresh` interval, re-sending only the lines
  whose text changed. Suppliers run off the main thread, like every placeholder
  in a hologram line: read memory, never the world. One that throws reads as no
  value. A per-player hologram
  diffs per viewer: a line that resolves the same for them is not re-sent.
- Nothing outlives its owner: plugin disable, player quit and palette reload
  all clean up (palette reload re-sends everything).

## Source and tests

- Public: `hologram/Holograms.java`, `Hologram.java`, `HologramConfig.java`,
  `HologramLine.java`.
- Internal: `hologram/internal/` (`HologramImpl`, `HologramRuntime`,
  `DisplayPackets`, `DisplaySink`, `DisplayState`, `LineStyle`, `Typewriter`,
  `Places`, `Mobs`, `ImageLines`,
  `NoopHologram`). Time tokens: `text/TimeTokens.java`; text effects:
  `text/internal/TextEffects.java`.
- Tests: `src/test/java/net/exylia/lib/hologram/`, `text/TimeTokensTest.java`.
