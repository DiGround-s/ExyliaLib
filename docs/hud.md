# HUD

Text on a player's screen that never takes their controls: widgets that stay
(money, health, a quest tracker, a combat timer), notices that pop in and out
("+120 coins", a kill), and big moments over the crosshair ("DOUBLE KILL").
*Since 1.566.0.*

```java
Huds huds = Huds.of(this);

HudHandle money = huds.show(player, HudWidget.of("money", HudAnchor.TOP_LEFT,
        p -> Text.of("{success}♦ {letters}" + Formats.money(balance(p))).build()));
money.refresh();   // read the text again now, instead of on its next period
money.remove();

huds.notify(player, HudNotice.of(Text.of("{success}+120 {letters}coins").build()).group("coins"));
huds.notify(player, HudNotice.moment(Text.of("{error}&lDOUBLE KILL").build()));
```

Every method is safe from any thread. Without PacketEvents nothing is drawn and
the handles still work, so a caller never branches on support.

## Widgets

`HudWidget.of(id, anchor, text)` stays until it is removed. Showing another with
the same id replaces it in place (no pop).

| Field | Copy | Default |
| --- | --- | --- |
| `text` (`Function<Player, Component>`) | | read every `every` ticks |
| `when` (`Predicate<Player>`) | `when(condition)` | always |
| `order` (its place in the anchor's stack, lowest at the edge) | `order(n)` | 0 |
| `every` (ticks between reads, at least 2) | `every(ticks)` | 10; a fixed `Component` 1200 |
| `background` (ARGB, `0` none) | `background(argb)` | `0`; `HudWidget.PANEL` is the palette's panel |
| `scale` (against the default text size) | `scale(size)` | 1 |

- `text` and `when` run on the player's own thread, so they read memory, never
  a database. A text equal to the one on screen sends nothing.
- A `null` or blank text, or a `false` condition, shrinks the widget away; it
  pops back in when it has something to say. That is a conditional element:
  "in combat", "low health", "inside a region", "holding a map".
- Lines break only where the text has `\n`. Keep them short: a corner is about a
  fifth of the screen wide before it reaches the middle.

## Designed widgets

*Since 1.582.0.* A widget can be designed as a screen is: boxes with backgrounds, gradients and outlines, rows and
columns, words, `progress` bars and `img` pictures, laid out by the screens' flexbox
([markup](screens-markup.md)).

```java
ScreenMarkup vitals = ScreenMarkup.load(folder.resolve("vitals.html"), folder.resolve("vitals.css"));
huds.show(player, HudWidget.markup("vitals", HudAnchor.TOP_LEFT, vitals).every(4));

// or a tree built in Java, null to hide it
huds.show(player, HudWidget.layout("boss", HudAnchor.TOP, p -> Node.column(...)));
```

```html
<column class="hud-card">
  <row class="line"><text>{error}❤ {letters}Health</text><text>{error}${round(health())}</text></row>
  <progress value="${health()}" max="20"/>
</column>
```

- The tree is read again every `every` ticks on the player's thread (`${...}`, `%placeholders%`, `if`, `<for>` all
  work; state reads its defaults). A layout equal to the one on screen sends nothing; a changed value is one
  metadata packet for the displays it touches.
- `hud-card` is in every sheet's base theme: the dark see-through panel and thin outline that read over any sky.
- **HUD kit** (1.585.0), also in the base theme: `hud-row` and `hud-stack` (centred rows and columns, `hud-start`
  to align left), `hud-chip` (a small pill: icon and value), `hud-banner` (an event line), `hud-objective`, `hud-alert` (a warning line, error-edged),
  `hud-level` with `hud-bar` (a level bar), `hud-profile` (face, name and a line under it), `hud-panel` with
  `hud-panel-title` (a sidebar-like card), `hud-map`. ExyliaScreens ships one design for each.
- **Rounded corners**: `border-radius: 2px` cuts each corner as pixel steps; the rows never overlap, so a
  see-through card shows no seam. Only a HUD draws them.
- **Minimap**: `<img map="40" zoom="1" round ring="{primary}"/>` is the ground around the player, turned so the
  way they face is up, with an arrow in the middle: the map colour of each column's top block, shaded by height as
  a map is (the first solid block under the head in a world with a roof). Four displays whatever its size (8 to
  64); it reads only loaded chunks the player's region owns, and a player who neither moves nor turns (6° steps)
  is sampled again every two seconds. Give it `every: 10` or more.
- Clicks, hovers, `transition`, `animation` and `enter` do nothing on a HUD; `item`, `item3d`, `window` and turning
  bodies are left out. Text shadows are never drawn (see below).
- **Cost.** One display per rect, per text and four per picture; an outline is four. A card of two bars is about
  ten. An element draws at most 64 and every display is resent on each pitch step, so keep designs small.
- **How layers stay in order.** The displays are see-through, and a client draws see-through displays in the order
  they were spawned, with no depth between them; a 1.21.9+ client also draws every see-through *background* after
  every see-through glyph. So every rect is a full block glyph (never a display background) and the layers are
  spawned back to front. A change of shape (a bar that empties, a line that appears) spawns the widget's displays
  again in order. Measured on real 1.21.4 and 26.2 clients.

## Notices

`HudNotice.of(text)` pops in at the top right, stays three seconds and shrinks
away. Newer ones stand nearest the edge; past five at one anchor the oldest
leaves early.

**From a message file, no code** (*since 1.597.0*): a message that begins with
`[hud]` or `[hud:<group>]` ([effect tag](text.md#effects-inside-a-message))
is sent as a notice instead of a chat line, prefix dropped, and falls back to
chat for anyone who cannot see the HUD. This is how plugins put their passive
feedback on the HUD: `earned: "[hud:earned]{success}+%amount% {letters}coins"`.

| Field | Copy | Default |
| --- | --- | --- |
| `anchor` | `anchor(where)` | `TOP_RIGHT` |
| `seconds` | `seconds(time)` | 3 |
| `group` | `group(key)` | none |
| `scale` | `scale(size)` | 1 |

A notice whose `group` matches one still up replaces it, counts (`×3` in
`{muted}`), pops and starts its time again: three kills in a row are one line.

`HudNotice.moment(text)` is a big moment: twice the size, no panel, popping in
just above the crosshair for two seconds. A new moment replaces the one on
screen.

## Anchors

| Anchor | Where | Stacks |
| --- | --- | --- |
| `TOP_LEFT` | the top left corner, free in vanilla | down |
| `TOP` | the top centre, under one boss bar | down |
| `TOP_RIGHT` | the top right, under the potion icons; notices | down |
| `LEFT` | the left edge, above the chat | down |
| `CENTER` | just above the crosshair; moments | down |
| `RIGHT` | the right edge, where the sidebar goes | down |
| `ABOVE_HOTBAR` | over the hotbar, hearts and experience bar | up |
| `BOTTOM_RIGHT` | the bottom right corner | up |

`HudAnchor.parse("top-left")` reads the names a file writes.

- **The held item covers `BOTTOM_RIGHT`.** The client draws the hand after
  everything else, so an item in the main hand hides that corner (an empty hand
  does not). Keep it for things that can wait.
- Positions are measured on a 16:9 window at the default 70° field of view. A
  wider field of view draws everything a little nearer the centre, never off
  screen; a 4:3 window can cut the side anchors.

## How it stays put

Each element is one text display, seated on the player for their client alone
with a camera-facing billboard, sixteen blocks ahead and see-through, like a
[guide view text](guide.md#view-text). The client carries it with the view every
frame, so walking, jumping and turning cost no packet and nothing trails.

- **Pitch.** The seat is above the eyes, so every element's offset is sent again
  when the pitch moves 3° (one metadata packet each).
- **Fitted to the window (1.584.0).** Corners are placed for the player's own
  FOV and window shape, the ones they fit on their first screen or HUD
  ([screens](screens.md), "Fit to your window"), and every element is drawn
  larger by the same share as their FOV, so it looks the same size at FOV 50
  or 110. Until they fit, FOV 70 at 16:9.
- **Sprinting (1.596.0).** Sprinting, speed and flying widen the client's
  view by a known factor, which would pull the HUD towards the middle. The
  server sees the same speed and draws everything larger by that factor, so on
  screen the HUD stays where it is. The factor follows the player's *FOV
  Effects* slider, which they tell once on the fit's width step (or in
  `/settings`, Interface, *FOV effects*); vanilla's 100% until they do.
- **Walls.** No shadow, no panel by default (`background(HudWidget.PANEL)` or
  any ARGB). When something stands between the eye and an element (a block,
  leaves, glass, water's surface, a portal, a mob), the element is drawn just in
  front of it, 0.4 blocks nearer and just as large on screen, so nothing covers
  it and nothing translucent (clouds, portals, glass) is drawn over it. Rays 1.5
  blocks of the view apart over the element and a 0.75 margin around it look for
  blocks, its corners and middle for mobs; it moves back out 30 ticks after there
  is room. Only a wall nearer than 1.9 blocks turns it see-through instead, since
  view bobbing would shake something that close (*since 1.597.0*).
- **One stack per anchor across plugins.** Two plugins showing at the same
  anchor stack one after the other (the first to show starts at the edge).
- **Steps aside** while a screen is open, while a camera shot plays, while dead
  and in spectator mode, and when the player turned *Show HUD* off in
  `/settings` (kept on the player, across restarts; `Huds.hidden(player, hide)`
  from code). Everything comes back on the next draw.
- **Cost.** One display per element and nothing layered: a HUD of ten widgets is
  ten entities. Only a changed text, a condition flipping, a reflowed stack or a
  pitch step sends anything.
- Only translation and scale animate on a client: elements grow from their own
  middle, glide when their stack reflows, and shrink back into it (1.588.0;
  they slid in from the screen's edge before, which a client cuts). Colours and opacity
  do not fade.
- **Who sees it** (*since 1.591.0*): `Huds.supports(player)` is a Java client of
  1.20.2 or newer with PacketEvents on the server (the floor screens share; Bedrock
  through Geyser and older clients through ViaBackwards fail it), and
  `Huds.visible(player)` adds "and did not turn it off in /settings". A widget shown
  to a player who is not `visible` is simply not drawn, so a plugin never branches to
  avoid showing it; it branches only to show something else instead (a sidebar, a
  boss bar, chat). `%player_hud%` is the same answer for other plugins' files.
  ExyliaScreens' `scoreboards.yml` and `bossbars.yml` are that pattern: a HUD design,
  and the vanilla sidebar or boss bar for everyone else.

Try it in a client with `/exylialib hudtest show|notice [group]|moment|hide`.

## From a file

Any interaction list (menu clicks, screen actions, open items) can write
`[HUD] {success}+120 {letters}coins;group:coins;seconds:4` and
`[MOMENT] {error}&lDOUBLE KILL` ([interactions](interactions.md)). Options are
the trailing `;key:value` parts (`anchor`, `seconds`, `group`); the rest is the
text, with the player's placeholders.
