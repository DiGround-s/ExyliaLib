# Screens

See also: [Markup reference](screens-markup.md) (every tag, attribute and property), [Cookbook](screens-cookbook.md) (working recipes), [Performance](screens-performance.md) (budgets and wire numbers).

Full-screen 2D menus drawn in front of the player, with a mouse cursor. No resource pack: every box is a
packet-only text display, so the screen exists only on the viewer's client.

Code: `net.exylia.lib.screen` (`Screens`, `Screen`, `Node`, `ScreenSession`, `ScreenEvent`, `ScreenMarkup`);
internals in `screen/internal` (`LiveScreen`, `Layout`, `ScreenPackets`, `ScreenRuntime`, `ScreenMath`,
`Markup`, `Css`, `MarkupRender`). The base theme is `screens/base.css` in the library jar. Needs PacketEvents.

A screen is built in Java with `Node` (below) or written as markup and CSS with `ScreenMarkup`
(since 1.485.0, see *Markup*).

## Opening one

```java
Screen hub = Screen.of(session -> {
    String tab = session.state("tab", "queue");
    return Node.column(
            Node.text("{letters}Practice").scale(1.4),
            Node.row(
                    Node.text("{letters}Queue").padding(5, 12).background("#2a2240").hover("{primary}")
                            .onClick(event -> event.session().set("tab", "queue")),
                    Node.text("{letters}Duels").padding(5, 12).background("#2a2240").hover("{primary}")
                            .onClick(event -> event.session().set("tab", "duels")))
                    .gap(4),
            Node.text("{letters_black}Open: " + tab))
        .padding(12).gap(8).background("#17131f")
        .align(Node.Align.CENTER).justify(Node.Justify.CENTER);
}).onClose(session -> plugin.getLogger().info("closed"));

Screens.of(plugin).open(player, hub);          // on the player's thread
```

## The render function

- Called on open, after every click or scroll handler, after `session.set(...)`, and once a second. Read a
  moving value (a countdown, a queue size) inside it and it stays current; there is nothing to refresh.
- Redraws are coalesced (1.493.0): any number of `set(...)` and `redraw()` calls within a tick, and the ones a
  handler makes, render once. A render whose tree comes out the same as the last one (same text, pixels, sizes,
  colours, ids) skips layout and diff and sends nothing; its handlers still replace the old ones.
- Runs on the viewer's thread. Read memory, never a database.
- Returns a fresh `Node` tree each time. Only what changed since the last render is sent: a box keeps its
  display while its key stays the same. The key is the node's `id(...)` when it has one, or its position in the
  tree; give ids to rows of a list whose length changes.
- At most 400 displays per screen (each box with a background is one, each text another). Past it the rest
  is left out and the plugin logs it once.

## Layout

A small flexbox in screen pixels. The layout area is `Screen.WIDTH` × `Screen.HEIGHT` (400 × 225), the part
of the view every common window shape shows at the default field of view; `Screen#size` changes it. The
backdrop (`Screen#backdrop`) reaches past it to the edges of the view.

A screen's field of view is left to the player's FOV slider alone (since 1.521.0): FOV Effects, a sprint, a jump and
speed effects change nothing. At FOV 70 the layout area fills three quarters of a 16:9 window; a wider slider draws it
smaller (about half at FOV 110). The server cannot see the slider, so each player fits their view once (see "Fit to
your window" below) and every screen is drawn for it. `screen-size` in `screens.yml` (percent, 50 to 250, 100 by
default) is the size for everyone, read with `Screens.size(player)`. Players chose their own size until 1.587.0; the fit
made it unneeded, so the choice was removed and what was stored is no longer read (`Screens.size(player, percent)` does
nothing).

### Where preferences are kept (1.556.0)

Cursor speed, cursor mode, cursor assist, reduced motion and menu style are library settings
([settings](settings.md)): table `exylia_player_settings` in the library's own database, `plugins/ExyliaLib/database.yml`.
They are network-wide. Point that file of every server at one MySQL, MariaDB or PostgreSQL database and a player's
choices follow them from server to server; with `redis.enabled` a change reaches the other servers at once. With the
default H2 file each server keeps its own. Reads are memory, so the cursor never waits for the database; until a
joining player's row is read (a moment) they see the server's defaults.

A player who never chose follows `screens.yml`, reloads included: only a value that differs from the server's is
stored. Before 1.556.0 these lived in each server's player data (`exylialib:screen_*`, `exylialib:menu_style`); a
joining player's old values are moved into the database once, only where it holds nothing for them yet, then removed
from their data. `/exylialib resetplayer` and wipes cover them like every other setting.

Screen state a screen keeps with `persist="player"` is not a preference and stays in the player's data on each server.

## The settings button

Every screen draws a small button at its bottom right, in the gutter outside the layout, which the cursor reaches:
it moves 24 pixels past the layout's sides and 6 past its top and bottom. Under a dialog the button is gone. It opens an
in-screen panel:

- **Fit to window**, the FOV the screen is fitted for: a click fits it again (see "Fit to your window").
- **Cursor speed**, 0.1 to 10 (3 by default), as `Screens.sensitivity`.
- **Cursor**, how the arrow follows the mouse (1.546.0): Normal, Raw or Prediction. See "The cursor modes".
- **Cursor assist**, Off or On (1.547.0): makes buttons easier to hit. See "Cursor assist".
- **Reduced motion**, as `Screens.reducedMotion`.
- **Menu style**, while `player-choice` is on: the server's choice, chest windows or screens, for menus opened after.
- **Reset** forgets all of them.

Sprint and jump together (Ctrl + Space) open and close it from anywhere, for a size so large the button is out of view.
Those two keys never reach a screen's own `keys`, and clients older than 1.21.2 do not send jump, so they have the
button only. The same choices are in `/settings` → Interface.

**Fit to your window (1.584.0).** The client never sends its FOV or window shape, so the player shows them once.
The first time a player meets a camera-view screen or their HUD, whichever comes first, lines are drawn where their
window's edges would be at FOV 70; they move them onto the real edges (step 1 the height, which is their FOV, step 2 the
width, with 16:9, 16:10, 21:9 and 4:3 one click away), and the screen behind grows or shrinks as they do. The result is
`view-fov`, `view-aspect` and `view-fitted` in `/settings` → Interface, network-wide like every preference: every
screen is drawn larger by `tan(fov/2) / tan(35°)` so it fills the same share of any window (narrower than 16:9, it
shrinks to fit), and the HUD's corners land on the window's corners. The card says that a changed FOV or window needs a
new fit; the settings panel's *Fit to window* row starts it again. A player who chose *Later* is not asked again on
that server. A HUD asks only at a quiet moment (on the ground, not hurt, not falling, no vehicle, no screen open), with
a screen of its own. Wheel screens never show it: they keep the player's FOV effects, which would spoil the measure.
Since 1.586.0 no camera-view screen is drawn wider or taller than 96% of the fitted window, whatever `screen-size` says. `first-screen-setup: false` in `screens.yml` turns the asking off; the settings row stays.


| Node call | Effect |
| --- | --- |
| `row(...)`, `column(...)` | children across or down |
| `text(String)` | one line through `Text` (palette tokens, MiniMessage, `&` codes, small text) |
| `box()` | an empty box: a spacer with `grow`, or a block of colour with `size` |
| `width`, `height`, `size` | fixed size; otherwise a box takes what its children need |
| `grow(share)` | a share of the room left in the parent's main axis |
| `padding`, `gap` | room inside the edges, room between children |
| `align(START/CENTER/END/STRETCH)` | children across the main axis; `STRETCH` by default |
| `justify(START/CENTER/END/SPACE_BETWEEN)` | spare room along the main axis |
| `scale` | text size: 1 is one font pixel per screen pixel, ten pixels a line |
| `background`, `hover` | `#rrggbb`, `#rrggbbaa` (alpha last, 1.592.0), `var(--primary)` or `{token}`; `hover` also makes the box pointable |
| `onClick`, `onScroll` | handlers, on the viewer's thread; the screen redraws after them |
| `item(ItemStack)` (1.488.0) | the item's own model, flat, 16 px by default; a hovered one grows a little |
| `overlay(true)`, `onDismiss` (1.488.0) | a dialog: left out of the flow, centred over a dimmer; only it takes the pointer, and a click outside it runs `onDismiss` (Shift closes the whole screen) |
| `textAlign(START/CENTER/END)` (1.488.0) | where text sits in a box wider than it |
| `image(w, h, argb)`, `image(BufferedImage)` (1.489.0) | a small picture (up to 96 a side), one layout pixel a picture pixel unless sized; pixels stay square and the picture is centred in its box |
| `transform`, `hoverTransform`, `active(background, transform)` (1.496.0) | a `Motion.Transform` (translate in pixels, scale) after layout; while hovered; for a moment after a click |
| `transition(ticks, ease)`, `enter(tween)`, `leave(tween)`, `animation(keyframes)` (1.496.0) | how changes glide, how the box arrives and departs, a looping or one-shot animation ([Motion](#motion-14960)) |
| `margin(t, r, b, l)`, `absolute(top, right, bottom, left)` (1.496.0) | room outside the box; out of the flow, pinned to its parent's edges (`Double.NaN` for a side left free) |
| `layer(n)`, `wrap(width)`, `shadow(true)` (1.496.0) | drawn above its siblings (-4 to 8); text broken onto lines at most `width` pixels wide; text with the vanilla shadow |
| `decoration(true)`, `refresh(ticks)`, `onShiftClick` (1.496.0) | left out first when a screen is over its display budget; redraw this often while shown; runs on a click with sprint held |

When the children of a row or column need more room than there is, the ones that grow give it back in
proportion to their size, as `flex-shrink` does; fixed boxes and text keep theirs (1.488.0).

**Motion (1.488.0).** The screen zooms in as it opens, a dialog pops out from the centre, and a box that
appears after a click slides up into place. A clickable box under the cursor rises a pixel, and a box that
moves or resizes glides there. All of it is client-side interpolation, no extra packets per frame.
`ScreenSession#toast(text)` shows a short note at the bottom for three seconds. Since 1.496.0 every one of these can
be styled, eased and turned off; see [Motion](#motion-14960).

**Pictures (1.489.0).** Each pixel is the vanilla font's full block (`█`) in its colour, one run of a colour
one component, so no resource pack is needed. The glyph is 8 font pixels in a 9 by 10 cell, so a picture is
four text displays, each nudged by the gap, which together cover every cell edge to edge: four displays a
picture whatever its size. A pixel more than half see-through takes the colour of the box behind it. Keep
pictures small (badges 16, logos up to 64): every pixel is a glyph the client lays out. `ScreenMarkup#images(dir)`
sets the folder for markup given as a string; `load` uses the markup file's own folder.

**Inputs from a screen (1.490.0).** `session.aside(() -> Inputs.of(plugin).text(...).open())` steps the screen
away while the input runs (a chat prompt below 1.21.6, a dialog from 1.21.6 on, both ends) and brings it back,
state and page kept, when it ends. Never open an input from a screen without it: the prompt would sit behind it.

A list of players that follows `viewer.canSee(target)` also asks `Screens.hides(viewer, target)` (1.518.5): a player the screen itself hid is `canSee` false until it closes.

**Other players** are hidden from the viewer while a screen is open (only by this plugin, so other plugins'
visibility rules stay), so a crowded spawn never stands in front of it.

A click goes to the topmost box under the cursor that has `onClick`; a wheel step to the topmost that has
`onScroll`, so a list's rows can take clicks while the list itself scrolls.

## What steps out of the way

`hide-sidebar` in `screens.yml` (default on, since 1.522.0) hides the player's sidebar while a screen is open, whichever
plugin or vanilla board draws it, team colour slots included. Only the display slot is held: the board's own packets flow
as usual, so on close it comes back as it is by then, or not at all if it was removed. Hopping between screens does not
flicker it, and it is back while a screen stepped aside for an input.

`quiet-chat` (default on, since 1.523.0) keeps the chat quiet while a screen is open. Other players' chat is not sent to
that player while it is up, since a signed chat packet cannot be dropped once the server made it: the line is rendered for
them and delivered as a system message on close (since 1.524.0). Those lines, system messages and `/say` wait together, up to the
newest 200, and arrive when the screen closes; one that comes within a second of a click also shows as a toast, unless a game frame's result is up (it says the same, and would be covered). If chat
was on view when the screen opened (anything in the last 10 seconds), blank lines push it out and those lines come back
on close. An input that steps the screen aside (a text prompt) gives the chat back at once.

Chat cannot be clicked while a screen is up, so links get two ways through (1.554.0). A toast made from a line with
links (`<click:open_url>`, `copy_to_clipboard`, `run_command`, `suggest_command`) stays eight seconds and can be clicked:
it opens a native dialog with the line and one button per link, and an address goes on to Minecraft's own "open this
page?" screen. A button that should lead straight to an address uses `[URL] https://dc.exylia.net;Discord`
([interactions](interactions.md)). Both need a 1.21.6+ client and PacketEvents; older clients still get the line in chat
on close. From code: `net.exylia.lib.input.Links.open(player, url, title)` and `Links.show(player, line)`.

## Input

- **Cursor:** the player's mouse. Their view does not move; the turn between rotation packets moves the
  cursor, gliding one tick so it reads smooth. The arrow is the client's rendering of a server packet, so it can not be
  closer to the hand than the client's own ticks allow. See "The cursor modes" below.
- **Speed:** `Screens.sensitivity(player, value)` from 0.1 to 10 (3 by default), applied at once. Since 1.496.0
  it is kept across restarts (on every server since 1.556.0), and players set it themselves in `/settings` → Interface
  ("Cursor speed"). `cursor-acceleration` in `screens.yml` (0 by default, 1 is mild) carries a fast mouse
  further while a slow one stays precise.
- **Hover** (1.496.0): the box under the cursor keeps its hover 3 pixels past its edges, unless the cursor is
  inside a box nested in it, so a hover never flickers on a border. A click lands on the box that is lit (1.546.0). It
  fades in over one tick unless a transition says otherwise (1.546.0, two before).
- **Clicks:** left and right, through an invisible interaction box around the eyes, so a click never has the
  vanilla miss cooldown and never reaches the world.
- **Wheel:** off by default. The client is then shown as a spectator: no hand, no crosshair, no hotbar, but a
  spectator's wheel sends nothing. `Screen#wheel(true)` keeps the real mode so the wheel scrolls, at the cost
  of the hand and crosshair. Page lists with buttons when the wheel is off.
- **Shift** closes the screen, from anywhere, a dialog included (1.514.0). Going back is the Back button's job
  (the `back` action, `ScreenSession#back()`). From 1.496.0 to 1.513.0 Shift stepped back instead.
- **Shift-click** (1.496.0): Shift itself closes the screen, so a click with **sprint held** (Ctrl, or the sprint key)
  is the shift-click: `action-shift` in markup, `Node#onShiftClick` in Java, and `ScreenEvent#shift()` on any
  click; a chest menu drawn as a screen gets `SHIFT_LEFT`/`SHIFT_RIGHT`. Tell players in a key hint
  (`<key-hint key="Ctrl">Shift-click</key-hint>`).
- **Focus navigation** (1.496.0): `<screen keys="navigate">` (`Screen#navigate(true)`) makes W, A, S, D move the
  cursor to the nearest clickable box that way and Space click it, so a screen works without the mouse.
  `:focus` styles the focused box (it is the hovered one). Bindings with `key=` are ignored while navigating.
  Since 1.497.0 a key only moves to boxes that lie that way (within about 60 degrees), never to a far box barely
  past the cursor. The client sends a key only when it is held across a tick: a tap shorter than that is missed.
- A screen stands at the player's eyes, so it cannot be seen or clicked from a camera (a cinematic, a replay, a spectator's target). A camera that
  takes the view closes it, and while one holds the player opens are refused with a line in chat: `Screens.open` returns `null`, a routed or skinned
  menu opens no window, and an input goes through its Dialog or chat instead (1.518.4). It opens again once the view is back.
- Damage closes it, as do death, a world change, a teleport of more than a block (1.485.1), quitting, the opening plugin being disabled, and
  another screen opening for the same player (one per player across all plugins).

- **Keys** (1.492.0): W, A, S, D and Space click the box bound to them (`key=`, `pager keys=`). Show the bindings in
  the screen itself: a short row of key hints under the content is the house style.

While a screen is open the player is frozen in place (`Packets#movement`), and everything else in the world is
out of their sight (1.492.0, `Visibility#seal` around the screen): other players, their name tags, mobs and other
plugins' holograms never show through or at its edges. It all comes back on close or while an input is aside.

### The cursor modes (1.546.0)

Each player picks one in the settings panel ("Cursor", kept with their preferences); `cursor-mode` in `screens.yml` is the choice
of everyone who did not. Only the drawing differs: clicks and hover read the real pointer in every mode.

| Mode | What it does | Lag on the test client | Trade-off |
| --- | --- | --- | --- |
| `NORMAL` (default) | glides one tick to each new place | 136 to 154 ms | smoothest, no overshoot |
| `RAW` | lands at once on each new place | 112 to 132 ms | visible steps, 20 a second |
| `PREDICTION` | glides to where the mouse is heading, at most 10 layout pixels ahead | 108 to 110 ms | passes the place the mouse stopped at on a fast stop, then settles back |

Where the time goes, on 1.21.4 with no ping: a client sends its rotation once per tick (50 ms apart), the server handles
it and flushes in under 1 ms without waiting for its own tick, and the client applies a display's new position only at
its next tick. A glide then takes another tick. So `NORMAL` shows the hand as it was 100 ms before, `RAW` 50 to 100 ms,
plus the ping rounded up to whole ticks. The figures above also carry the capture's own delay, about 35 ms, so compare
them with each other. The default stays `NORMAL` because it is the only mode free of steps and overshoot; feel is the
player's to judge, which is why they choose.

The lit box waits for the server's next tick on top of that, up to about 50 ms, since the viewer's thread owns the frame.


### Cursor assist (1.547.0)

An optional help each player turns on in the settings panel ("Cursor assist", kept with their preferences); `cursor-assist` in
`screens.yml` is the choice of everyone who did not, off by default. The cursor modes only change what is drawn; assist
moves the real pointer, so it works under any mode and the arrow always shows where a click lands.

- **Friction:** a slow move over a clickable box travels about 70% as far; a fast one travels in full, so leaving on
  purpose is not slowed.
- **Magnet:** a slow move within 12 pixels of a clickable box, heading for it, bends slightly toward it. It is a part of
  the player's own move and only toward where they already head: a fast move gets none, moving away is never held back,
  and it never steps from button to button.
- **Click tolerance:** a click up to 8 pixels past the box that was just lit, within 200 ms of leaving it and with no
  other clickable box under the pointer, goes to that box. A click on another box always goes to that box.

Where it never acts: anything inside `<game-frame>` (every Casino and Tabletop game on the frame, the stake and the Cash
out included), every scene (`<canvas>`), the Yes of a dangerous confirm, and any box with `action="confirm ... dangerous"`.
Elsewhere a screen says so itself with `assist="false"` on any box (`Node#assist(false)` from Java), which also covers
what is drawn inside it and any dialog over it. A screen whose clicks place a bet outside the frame needs it on its root
box. A clickable box larger than a quarter of the screen counts as a background and gets no help.

The strengths are constants in `screen/internal/CursorAssist.java`, in one place, to tune by feel.

## How things are drawn (1.492.0)

- **The panel stands 0.09 blocks from the eyes** (1.545.0). A player's eyes are at least 0.3 from a wall and 0.18 under a
  ceiling, and every corner of the view at that depth is closer, so a wall, a sign, a ceiling or a tunnel never cuts it
  and a particle draws behind it. What has no collision and sits within those 9 cm of the eyes (a player's head inside a
  sign or a torch's block) can still show.
- **Items** are drawn as their own inventory sprite, in pixels: a 3D item display is shaded by the world's fixed
  lights, so it brightened and darkened as the viewer looked around. The sprites are read once from the client jar
  Mojang serves for the server's version into `plugins/ExyliaLib/cache/sprites-<version>`, in the background; until
  then, and for items with no flat sprite (heads, chests, shields), the 3D item is drawn.
- **Words wider than their box** shrink to fit it rather than run over the next box: a long kit name in an
  equal-width chip or card stays inside it. Rows give way in this order: boxes that grow, then words.
- **The cursor** is a pixel arrow with a dark outline, one picture moved as a whole.
- **Only changed fields go out** (1.493.0). The wire remembers the last value of every field of every display and
  sends only what changed, so a cursor move, a hover, a glide or a turning body costs a transform (about 25 bytes a
  display) rather than the whole picture again. Each frame goes out as one bundle with one flush, so the client
  never draws half of it. Pictures turned into glyphs are cached once for every screen.

## Threads and lifecycle

`ScreenSession` is safe from any thread. Handlers and `onClose` run on the viewer's thread; `onClose` only
while the player is online and the plugin enabled. Since 1.493.0 the netty thread only moves the cursor and reads
the last laid-out frame to find what is under it; rendering, hover and every sprite, skin or picture lookup happen
on the viewer's thread, and an item sprite seen for the first time is read from disk on an async task (the slot
stays empty for that moment) rather than on any server thread. Another player's position is read on their own
thread on Folia. Nothing survives the plugin: its screens close on disable.
The module keeps nothing derived from the palette between renders (each render re-reads the tokens), so it
has no `invalidateAll`.

## Screens in the world (1.606.0)

`WorldScreen.place(plugin, centre, screen, widthBlocks)` stands any screen (Java or markup) in the world as a flat
panel: a leaderboard on a wall, a menu by a portal. The location is the panel's middle; its yaw is the way the
front faces (0 south, 90 west, 180 north, -90 east). The height follows the screen's `size`.

```java
WorldScreen board = WorldScreen.place(plugin, location, screen, 3.0).range(32).reach(12);
board.remove(); // on disable: it is sent as packets, nothing is saved in the world
```

- Every player within `range` gets their own session: `session.state("page", 1)` is per viewer.
- The crosshair is the pointer (an observing `SpatialInput` pointer): a hovered box takes its `hover` and
  `hoverOutline`, a swing or a use on a box with `onClick` presses it (`shift` is sneaking), a click anywhere
  else reaches the world. Nobody is frozen. A right-click with an empty hand in the air is never sent by the
  client, so left-click is the one that always works.
- It redraws every second (placeholders), on hover, on click and on `set`; each look keeps its display, so only
  what changed is sent and a box that goes is destroyed.
- Drawn: boxes, outlines, text (a gradient takes its middle colour). Not drawn: items, images, motion, tooltips.

## Markup (since 1.485.0)

`ScreenMarkup` reads a screen from a well-formed XHTML file and CSS sheets, so a server owner restyles it
without touching Java. The JDK's SAX parser reads it; doctypes and external entities are refused.

```java
ScreenMarkup hub = ScreenMarkup.load(folder.resolve("practice_hub.html"), folder.resolve("theme.css"))
        .source("kits", session -> kits.rows(session.viewer(), session.state("category", "all")))
        .value("queued", session -> queues.size(session.viewer()))
        .action("queue", (event, args) -> queues.toggle(event.viewer(), args.get(0)));
hub.problems().forEach(plugin.getLogger()::warning);
Screens.of(plugin).open(player, hub.screen(Map.of("tab", "play")));
```

```html
<screen size="400x225">
  <panel>
    <tabs state="tab" default="play">
      <tab value="play">Play</tab>
      <tab value="settings">Settings</tab>
    </tabs>
    <column if="tab == 'play'">
      <chips state="category" default="all">
        <chip value="all">All</chip>
        <chip value="sword">Sword</chip>
      </chips>
      <grid source="kits" columns="4" rows="2">
        <card action="queue %kit_id%" selected="%kit_queued%">
          <icon>%kit_icon%</icon>
          <b>%kit_name%</b>
          <small>%kit_playing% playing</small>
        </card>
        <empty>No kits in this category</empty>
      </grid>
      <pager for="kits"/>
    </column>
  </panel>
</screen>
```

```css
.cards { gap: 6px; }
card:hover { outline: 1px {primary}; }
card[selected] { outline: 1px {success}; background: {success}18; }
```

`problems()` lists what is outside the subset (an unknown tag, an unknown CSS property, a pager with no grid,
an action or source that is not registered), each once, with its `file:line:col` (since 1.494.0). Only markup
that is not well-formed throws, when it is read.

### Tags

| Tag | Does |
| --- | --- |
| `screen` | The root. `size="WxH"`, `backdrop="{token}"` (or a `linear-gradient(...)`, 1.497.0), `wheel="true"`, `scene="img/x.png"` and `parallax="3"` (1.497.0, see [Backgrounds](#backgrounds-14970)) |
| `panel`, `column`, `row`, `box` | Boxes; `panel` is a themed column, `row` runs across |
| `text`, `h1`, `h2`, `b`, `small`, `p`, `span`, `label` | Text; an element with only text is one text box |
| `button` | A themed box with an `action` |
| `tabs`/`tab`, `chips`/`chip` | Options: an element with `value` inside one with `state` sets that state on click and gets `[selected]` while it holds |
| `grid`, `list` | Repeat their children over `source`; `columns` and `rows` make a window the wheel and a `pager` move |
| `card` | A themed box, usually a grid cell |
| `empty` | Shown by its repeating parent when the source has no rows; several may each carry an `if`. Outside a repeat it is a centred note |
| `toggle`, `checkbox` | On/off: `state="key"` flips local state, or `checked="%field%"` with an `action`; `on`/`off` change the toggle's words |
| `pager` | `for="<source>"`: previous, `page / pages`, next; `keys="a d"` (1.492.0) turns the page with those movement keys |
| `icon` | An item sprite by material (`<icon>diamond_sword</icon>` or `material=`), `glyph=` below 1.21.9 |
| `item` (1.488.0) | A real item model, no resource pack: `material="diamond_sword"`, `head="<player>"`, `texture="<base64>"` or `url="<skin url>"`. Since 1.518.0 `material=` takes whatever a chest template's `material:` does: a `bytes:` snapshot, `basehead-...`, `playerhead-...`, a custom item (`nexo:ruby`) |
| `window` (1.538.0) | A hole in the screen looking at a preview played for real behind it: `preview="<value>"` or `wearing="<outfit>"`; its children stand in when there is nothing to show. See [Windows](#windows-a-preview-in-the-screen-15380) |
| `canvas` (1.500.0) | `id="table"`: a sprite layer the plugin moves from Java (`session.scene("table")`), sized by CSS `width`/`height`. See [Scenes and games](#scenes-and-games-15000) |
| `item3d` (1.499.0) | The item's real 3D model, lit, with enchantment glint: `material=`/`head=`/`texture=`/`url=` as `<item>`; `spin` turns it slowly about its upright (36° a second, or `spin="72"`). See [3D items](#3d-items-14990) |
| `img` (1.489.0) | A picture with no resource pack: `src="img/badge.png"`, read from the markup's folder (never outside it), or `skin="<player>"`, the player's whole body from the front (16 × 32, drawn once their skin has downloaded); `face="<player>"` (1.490.0) is their face with its outer layer, 8 × 8, sharper than an `<item head>` in a list. Placeholders work in both. `turn` on a `skin` picture (1.492.0) stands the body in the round, every part's four sides from the real skin, turning slowly; it is the flat picture until the skin is known. Clear pixels of a picture are see-through (1.492.0) ; `wearing="..."` (1.498.0) dresses the body, flat or turning: see [Bodies wearing a kit](#bodies-wearing-a-kit-14980) |
| `progress` (1.488.0) | `value` and `max`: a bar, filled with the CSS `color` |
| `modal` (1.488.0) | A dialog over everything while its `if` holds; `dismiss="<action>"` runs on a click outside, `clear <key>` by default when the `if` is a plain name |
| `search` (1.496.0) | `state="q" prompt="Search a kit" hint="..." max="16"`: a field showing what was searched (the search sprite, then the value) that asks for a new search on click, the screen stepped aside meanwhile, with a ✘ to clear it while set; filter a source with `%q%` |
| `p` (1.496.0) | Wraps onto lines (base theme: `white-space: normal; max-width: 220px`) |

A `<nl>` (or `<br>`) inside text or a value, as lore values write their line breaks, starts a new line and is measured as one (1.507.0).

### Attributes on any tag

- `id`, `class`: for CSS; an `id` also keeps a box's display between renders.
- `if="tab == 'play'"`: an [expression](#expressions-14940) (1.494.0; before, only `name`, `!name`, `==`, `!=`,
  `&&`, `||`). `&&` and `<` may be written bare. An `else` right after it is shown when it is not
  (see [Choosing what is drawn](#choosing-what-is-drawn-14940)).
- `action="name arg ..."`: built-ins `close`, `set <key> <value>`, `toggle <key>`, `clear <key> ...`,
  `toast <text>` (1.488.0); several run in order when split by `;`; then the markup's own
  `action(...)`; then a namespaced ExyliaLib action (`practice:join_queue boxing`). Since 1.494.0 each word is
  one argument after its placeholders are filled, so a value with spaces never splits; quote a word to keep
  spaces in it (`toast 'Saved, see you'`).
- `action-right="..."` (1.494.0): runs instead of `action` on a right click.
- `action-shift="..."` (1.496.0): runs instead of `action` on a click with sprint held (see [Input](#input)).
- `assist="false"` (1.547.0): cursor assist stays off this box and everything over it. See "Cursor assist".
- `action-hover="..."` (1.498.0): runs once each time the cursor comes onto the box (not again until it
  leaves): `action-hover="set wear %kit_wearing%"` shows what a card is about elsewhere on the screen. Any action
  works, but keep it to state: it runs on every pass of the cursor. `Node#onHover` in Java.
- `action-leave="..."` (1.600.0): runs once each time the cursor leaves the box (onto another box, a gap or off the
  screen), before the next box's `action-hover`. Pair it with a hover that lights something up, so the light goes
  out with the cursor: `action-hover="set hover %id%" action-leave="clear hover"`. `Node#onLeave` in Java.
- More built-in actions (1.496.0): `back` (the dialog, then the screen before; closes at the root),
  `sound <key> [volume] [pitch]`, `toast <success|error|info|warning> <text>` (the tone colours its edge),
  `confirm '<question>' [dangerous] [yes=Leave] [no=Stay] then <actions>` (an in-screen dialog; what follows
  `then` and every later action run only on yes), and `input text <key> '<prompt>' [hint=...] [max=n] then
  <actions>` (asks for a line of text, stores it in state `<key>`, then runs the rest).
- `priority="low"` (1.496.0): decoration, the first thing left out when the screen is over its display budget.
- `refresh="5t"` (1.496.0): redraws the screen this often (2 ticks at least) while the element is shown, instead
  of every second: a countdown.
- `key="w|a|s|d|space"` (1.492.0; `keybind=` is the same since 1.494.0): pressing that movement key clicks the box while it is on screen and reachable
  (inside the open dialog when one is up). `Node#key` in Java.
- `selected`, `disabled`, `checked`: present (a bare `disabled` works since 1.494.0, or true after
  placeholders) adds that CSS flag. A
  `disabled` box refuses clicks with a quiet villager sound.
- `state` + `default`: what an option group remembers, and what it shows before the player picks.
- `persist="player"` next to `state` (1.503.0): the player keeps that choice across sessions and restarts,
  stored with their player data (`exylialib:screen/<screen>/<key>`, the screen being the root `id` or the
  file name without `.html`). A state handed in by a route or `screen(Map)` still wins.
  `<tabs state="tab" default="play" persist="player">`. Java: `Screen#persist(scope, keys)`.
- `class="${...}"` / `class="tile %tone%"` (1.503.0): classes filled per render take part in CSS matching, so
  `class="card ${owned ? 'owned' : 'locked'}"` picks `.card.owned` or `.card.locked`.
- `%name%` anywhere: a row field inside a repeat, a registered `value`/`text`, or session state; then (1.494.0)
  any placeholder the server knows (`%exyliaffa_total_players%`); left as written when none of them knows it.
- `${expression}` anywhere (1.494.0): the [expression](#expressions-14940)'s result, in text and attributes.
- `track="%kit_id%"` on a `grid`, `list` or `chips` (1.494.0): what makes a row the same row when the list
  changes. Its boxes keep their identity (`kits@k2`) and glide to their new place when the order changes,
  instead of every cell redrawing.
- `t="key"` and `t-<attribute>="key"` (1.494.0): [words](#words-14940).

### CSS subset

Selectors: tag, `#id`, `.class`, `[selected]`/`[disabled]`/`[checked]`, `:hover`, `:focus` (the same as
`:hover`) and `:active` (for a moment after a click; 1.496.0), compounds, descendants and
comma lists, resolved by specificity then order. The base theme comes first, then each sheet given.

Since 1.494.0 also: the child combinator `a > b`; `:root` (the `screen`); `:first-child`, `:last-child`,
`:only-child`, `:nth-child(odd|even|3)` (rows of a repeat count as children); `:not(<simple selector>)`;
`:selected`, `:disabled`, `:checked` as the attribute flags. Custom properties: `--name: value` on any rule,
inherited, read with `var(--name)` or `var(--name, fallback)` in any value.

```css
:root { --accent: {primary}; }
.danger-zone { --accent: {error}; }
button { background: var(--accent); }
list > row:nth-child(even) { background: {letters_black}10; }
card:not(.mini):hover { outline: 1px var(--accent, {secondary}); }
```

| Property | Values |
| --- | --- |
| `flex-direction` | `row`, `column` (default) |
| `display` | `none` hides the element |
| `gap`, `width`, `height` | pixels (`6px` or `6`) |
| `padding` | one to four pixel values, as in CSS |
| `flex-grow` | a share of the room left |
| `align-items` | `start`, `center`, `end`, `stretch` |
| `justify-content` | `start`, `center`, `end`, `space-between` |
| `background` | `#rrggbb`, `#rrggbbaa` (alpha last, 1.592.0), `var(--primary)`, `var(--primary)40`, `{token}`, `{token}40`, `none`; a see-through colour is mixed over its container's and sent opaque (1.485.2) |
| `outline`, `border` | `1px {primary}`: a line outside the box |
| `color`, `font-size`, `font-weight` | inherited; `font-size: 14px` is 1.4 lines, `font-weight: bold` |
| `text-align` (1.488.0) | `left`, `center`, `right`; inherited |
| `margin` (1.496.0) | one to four pixel values, outside the box |
| `position: absolute` + `top`/`right`/`bottom`/`left` (1.496.0) | out of the flow, pinned to the parent's edges; with both sides of an axis and no size it stretches between them, with neither it sits at the start |
| `z-index` (1.496.0) | -4 to 8: drawn above (or below) its siblings |
| `max-width` (1.496.0) | on text: wraps it onto lines at most that wide |
| `white-space` (1.496.0) | `normal` wraps text to its box, `nowrap` (default) keeps one line; inherited |
| `text-shadow` (1.496.0) | `none` (default) or anything else for the vanilla shadow; inherited |
| `transform`, `transition`, `enter`, `leave`, `animation` (1.496.0) | see [Motion](#motion-14960) |
| `text-gradient` (1.610.0) | colours and an optional duration, or `rainbow`; inherited. See [Motion](#motion-14960) |
| `background: linear-gradient(...)`, `background-image: url(img/x.png)` (1.497.0) | see [Backgrounds](#backgrounds-14970) |

Shipped files use palette tokens, never hex, so `colors.yml` restyles every screen.

## Authoring (1.494.0)

Everything below works with `ScreenMarkup.parse`/`load` too, but a plugin ships screens through a
`ScreenSet`, which owns the files, the words and the reloads.

### Screen files

```java
ScreenSet screens = Screens.of(plugin).files(MyPlugin.class, "screens");
ScreenMarkup hub = screens.markup("practice_hub")          // screens/practice_hub.html
        .rows("players", session -> players(session))     // List<UiEntry>
        .text("invite_query", session -> session.state("invite_query", ""))
        .action("duel_pick", (event, args) -> pick(event, args.get(0)));
hub.problems().forEach(plugin.getLogger()::warning);
// on the plugin's reload:
screens.reload();
```

- The jar's `screens/` folder is installed into the data folder and kept up to date by `BundledFiles`: a file
  the owner never edited is replaced with each new jar; an edited one stays, with the new version written next
  to it as `<name>.new` and listed in `/exylialib updates` (apply swaps them and keeps yours as `.old`). This
  holds for HTML, CSS and pictures, so shipped screens improve on servers without losing anyone's edits.
- Sheets, later ones winning: the library's base theme, `plugins/ExyliaLib/screens/theme.css` (one network
  look for every plugin), `theme.css`, `<id>.css`, then `custom.css`. The plugin never ships `custom.css`:
  it is where an owner puts their own rules, and it is never touched by an update.
- `components/*.html` next to the screen is read for [templates](#components), `<include>` reads from the
  same folder (never outside it), and `img src=` too.
- `ids()` (1.501.0) lists every `<id>.html` at the top of the folder, read or not, for a plugin whose screens are
  whatever the owner puts there.
- `markup(id)` returns the same object every time; sources, values and actions registered on it survive every
  reload. `reload()` installs what the jar brought and reads every screen again; a screen that no longer reads
  keeps its last good version and says why in `problems()`.
- **Hot reload**: edits to any of those files, or to the words, show on open screens within two seconds (the
  dates are checked off the main thread). `hotReload(false)` turns it off.
- `/exylialib screens check [plugin]` lists every problem with its `file:line:col`; `/exylialib screens reload
  [plugin]` reads them again (permission `exylialib.admin`).
- `BundledFiles.retire(plugin, "lang/en/screens/old.html")` deletes a file a plugin stopped shipping unless the
  owner edited it (it returns `false` then, so the plugin can say so).

### Words (1.494.0)

One HTML serves every language. An element with `t="key"` shows the word `key` of
`lang/<code>/screens/<id>.yml` (the server's language, then English), and the text written in the HTML when
neither has it. `t-<attribute>="key"` does the same for an attribute (`t-on`, `t-off`, `t-title`). Words may
hold colour tokens, `%placeholders%` and `${...}`; `t('key', 'fallback')` reads one inside an expression.

```html
<h1 t="play.title">Play</h1>
<toggle checked="%party_open%" action="party_open" on="Open" off="Private" t-on="party.open" t-off="party.private"/>
```

```yaml
# lang/es/screens/practice_hub.yml
play:
  title: 'Jugar'
party:
  open: 'Abierta'
  private: 'Privada'
```

An element with `t` holds only text (words replace its children).

**Icons in words (1.568.0).** Text on a screen takes `{icon:...}` as lore does: `{icon:search} Search`,
`{icon:up} Newest first`. A 1.21.9+ client sees the game's own sprite; an older one sees the glyph after `|` (or
the named icon's), in the colour around it. Write a sprite, never a bare Unicode symbol (`⌕`, `⇅`, `▤`): those are
not in the game's pixel font and are drawn thin and mismatched from the fallback font.

### Expressions (1.494.0)

`${...}` in any text or attribute, and the whole of `if`, `else if` and `switch on`, is an expression:

| | |
| --- | --- |
| Values | `'text'` or `"text"`, numbers, `true`, `false`, `null`, `%placeholder%`, names (`kills`, `kit.kit_name`, `loop.index`) |
| Operators | `?:`, `or`/`\|\|`, `and`/`&&`, `not`/`!`, `== != < <= > >=`, `in` (a word of a text, an item of a list), `+ - * / %`, unary `-`, parentheses |
| Functions | `min`, `max`, `abs`, `round`, `floor`, `ceil`, `len`, `upper`, `lower`, `plural(n, 'one', 'many')`, `t('key', 'fallback')`, `fmt.number(1234)` → `1,234`, `fmt.percent(0.53)` → `53%`, `fmt.time(seconds)` → `1h 1m 5s` |

Names are looked up as a `for` alias, `loop.*`, a row field, a value, a text, session state, then the defaults;
a `%placeholder%` the screen does not know is asked of the server's placeholders. `+` adds numbers and joins
anything else; comparisons are numeric when both sides are numbers. Empty, `0`, `false`, `no`, null and a
placeholder left as written are false. An expression that does not parse is a problem at its `file:line:col`.

```html
<small>${kills + deaths} ${plural(kills, 'kill', 'kills')} · ${ratio > 1.5 ? 'good' : 'keep going'}</small>
<text if="queued != 0 and tab in 'play duels'">${fmt.time(wait)}</text>
```

### Commands, messages and requirements (1.501.0)

Any action step that is an [interaction line](interactions.md) runs as one: a command as the player or the
console, a `[MESSAGE]`, a `[MONEY]` charge, a `[SERVER]` move, any effect token. Consecutive lines form one list,
so `[DELAY]` waits before the ones after it and a line that stops (a refused command, a charge the player cannot
afford) stops everything after it, the screen's own steps included.

```html
<card action="[MONEY] take 250; [console] give %player_name% diamond 16; [MESSAGE] {success}Bought 16 diamonds.; toast success Bought"
      require="balance() >= 250" deny="toast error 'You need 250 coins'; sound entity.villager.no">
```

| Step | Is |
| --- | --- |
| `[console] <command>`, `console: <command>` | the console runs it |
| `[player] <command>`, `player: <command>`, `player-proxy:`, `console-proxy:` | the player (or the proxy) runs it |
| `[MONEY] take\|give <amount> [currency]` | the lib economy; a take that cannot be paid stops the list |
| `[MESSAGE] ...`, `[TITLE] ...`, `[SERVER] lobby`, `[TELEPORT] world;x;y;z`, `[ACTION] ns:id`, `[DELAY] 1s` | as in [interactions](interactions.md) |

A line holds everything up to the next `;`, so a step with a `;` of its own (`[TELEPORT] world;0;64;0`) is written
with commas (`world,0,64,0`); a sound is the screen's `sound` step. Values are filled before the line runs and a
value never becomes another step.

`<screen command="hub" aliases="lobby menu" permission="myserver.hub">` (1.502.0): the lib only keeps these for
the plugin that opens screens by command (ExyliaScreens) and reads them with `ScreenMarkup#attribute(name)`.
`ScreenMarkup.test(expression, player)` (1.502.0) evaluates an expression outside any screen, as a chest button
that must agree with a screen's `require` does.

**Requirements.** `require="<expression>"` gates every click of the element, `require-right` and `require-shift`
only that click. When it is false the click runs `deny` (`deny-right`, `deny-shift`) instead, or a quiet refusal
sound when there is none. Show the state too: `disabled="${balance() < 250}"` greys the card.

Expression functions about the viewer, for `if`, `require` and `${...}`:

| Function | Answers |
| --- | --- |
| `perm('node')` | whether they have the permission |
| `balance()`, `balance('points')` | their money in the default (or named) currency |
| `has_item('diamond', 16)` | whether they carry at least that many (1 when left out) |
| `level()` | their experience level |
| `world()` | the name of their world |
| `contains(text, 'part')` | whether the text holds that part, case kept (1.552.0) |
| `matches(text, 'regex')` | whether the whole text matches the pattern; a pattern that does not read matches nothing (1.552.0) |
| `chance(30)` | true 30% of the time, drawn again on each evaluation (1.552.0) |

A plugin adds its own with `ExpressionFunctions.register(plugin, "data", (viewer, args) -> ...)` (1.552.0), from
`onEnable` before the files that call it are read. The name is lower-case words (dots allowed), never a built-in one;
it is released when the plugin disables, and a call that outlives it answers nothing.

### Choosing what is drawn (1.494.0)

```html
<text if="match_win">W</text>
<text else if="match_loss">L</text>
<text else>D</text>

<switch on="tab">
  <case value="play">...</case>
  <case value="duels|party">...</case>   <!-- | lists several -->
  <default>...</default>
</switch>

<for each="kit in kits"><text>${loop.number}. ${kit.kit_name}</text></for>   <!-- a source -->
<for each="i in 1..5"><box class="star"/></for>                              <!-- a range, up to 256 -->
```

`else` is an attribute (with an optional `if`, for else-if) or an `<else>` element, and must follow an element
with `if`. Inside a `for`, `loop.index` (from 0), `loop.number` (from 1), `loop.count`, `loop.first` and
`loop.last` are known; a `for` draws its children once per row, in place, without a grid's window or pager.
`<fragment>` groups elements without a box of its own.

### Components

```html
<!-- components/hub.html -->
<template name="stat" props="label value tone=info">
  <row class="stat"><small t="${label}"><slot/></small><box class="spacer"/><b>{${tone}}${value}</b></row>
</template>
```

```html
<stat label="stats.kills" value="%me_kills%" tone="success">Kills</stat>
<include src="parts/dialogs.html"/>
```

- `<template name props>` declares a tag; `props` lists its attributes, `name=default` for optional ones.
  Templates in `components/*.html` are known to every screen of the folder; one in a screen is known there.
- A use writes each prop into the template where it says `${prop}`, puts its own children where `<slot/>` is,
  keeps its `if`/`else`, and adds any other attribute (`id`, `class`, `action`) to the template's root.
- `<include src="...">` inlines another file of the folder (any number of roots), up to 16 deep.

### Values that cannot restyle a screen (1.494.0)

A value a player chose (a name, a typed search, a layout name) must never inject colours or markup:

- `text(name, session -> ...)` registers a value drawn exactly as written.
- `rows(name, session -> List<UiEntry>)` registers a source whose rows are `UiEntry`s: fields set with
  `with(...)` are drawn as written, `withFormatted(...)` keep their colours (a kit's display name). The row's
  `value` object comes back on a click as `event.row()`.
- Session state (what `set`, chips, tabs and inputs store) is drawn as written.
- `value(...)` and `source(...)` with maps stay as they were: formatted, for text the plugin controls.

```java
hub.rows("layouts", session -> slots(session).stream()
        .map(slot -> UiEntry.of(slot)
                .with("slot_name", slot.name())                 // typed by the player
                .withFormatted("kit_name", slot.kit().displayName())
                .build())
        .toList())
   .action("layout_delete", (event, args) -> event.row(KitSlot.class).ifPresent(slots::delete));
```

`ScreenEvent#row()` is the row the clicked box was drawn for (`null` outside a repeat), `row(Type.class)` the
same as an `Optional`.

## Motion (1.496.0)

Every movement is the client gliding a display from one transform to the next, so motion costs packets per
change, never per frame. An eased glide is split into two to four straight glides that follow the curve.

```css
card { transition: transform 3t ease-out; }            /* how changes glide */
card:hover { transform: translateY(-2px); }             /* lift while hovered */
card:active { transform: scale(0.96); }                 /* pressed for a moment */
modal { enter: scale(0.9) 5t back; leave: scale(0.94) 3t ease-in; }
#page-play, #page-duels { enter: translate(0, 6px) 4t ease-out; }
.live { animation: pulse 40t ease-in-out infinite; }
@keyframes pulse { 0% { transform: scale(1); } 50% { transform: scale(1.06); } 100% { transform: scale(1); } }
```

- `transform`: `translate(x, y)`, `translateX`, `translateY` (pixels, y down), `scale(s)`, after layout and about
  the box's centre; a parent's transform carries its children.
- Durations: `4t` or `4` ticks, `200ms`, `0.3s`. Easings: `linear`, `ease-in`, `ease-out` (`ease`),
  `ease-in-out`, `back` (overshoots a little).
- `transition: [transform|background|all] <duration> [easing]`, several by commas, the longest wins; inherited
  by children. With `background` or `all` the colour steps along the glide too; otherwise it changes at once.
- `enter` and `leave`: how a box arrives and departs, `none` to turn the built-in arrival off. A box that is new
  arrives with its own `enter` or the nearest enclosing one's, about the outermost new box, so a whole page
  arrives as one; a box that is gone glides away with its `leave` and is removed after it (the dimmer stays while
  its dialog leaves). Give each tab's page an `id` so a tab switch is a new page arriving, not the old one
  changing.
- `animation: <keyframes> <duration> [easing] [delay] [count|infinite] [alternate]`: transforms only, sampled every
  2 to 10 ticks. Since 1.565.0 a second duration is a delay, a number the plays, `alternate` runs every second play
  backwards; the longhands `animation-delay`, `animation-iteration-count`, `animation-direction` win over it.
- `stagger: 2t` (1.565.0) on a container: its children arrive one after another, each waiting shrunk to nothing for
  its turn, and their animations start as far apart, so a grid fills in card by card and a `pulse` runs across it
  as a wave. Plain rows between (a grid's lines) are seen through; the wait stops growing at 40 ticks.
- `text-reveal: 1t` (1.565.0): words type themselves out, a character every tick, after the box has arrived and
  again whenever they change. Inherited.
- `animate [#id] <keyframes> [duration] [easing]` (1.565.0), an action, and `session.animate(id, keyframes)` in
  Java: one play on a box, over its own transform. `deny="animate shake"` is the house refusal.
- Easings `bounce` and `elastic` (1.565.0) spring past the end; a glide along them takes up to eight steps.
- The base theme ships keyframes `shake`, `pop`, `pulse`, `float`, `nudge`, `jump`, `flip`, `drop-in`.
- `text-gradient: <colour> <colour>... [duration]` (1.610.0) paints the words with a gradient, first colour to last;
  with a duration the gradient slides along them once in that time (a shimmer), looping without a seam.
  `text-gradient: rainbow [duration]` uses every hue, softened to stay readable. Colours are `var(--primary)`,
  `{token}` or `#rrggbb`; `none` keeps the words' own colours. Inherited, so it can sit on a heading or a button and
  reach its words. A CSS property rather than MiniMessage `<gradient>`, so a sheet (and `colors.yml`) restyles it
  without touching the words, and the engine can hold it still. `Node#textGradient(Motion.TextGradient)` in Java.
  Cost: the words' display is re-sent at most every 4 ticks (`Motion.TextGradient.STEP`), five times a second for a
  60t slide, each counted against `motion-ceiling`; at most six texts a screen slide, the rest hold their gradient
  still, as they do under reduced motion, lighter screens and a screen lowered in detail over its budget.
- Opt-in classes (1.610.0) for any element, no keyframes to write: `.lib-pulse` (a gentle beat for the main call to
  action), `.lib-float`, `.lib-nudge`, `.lib-shimmer` (the house `{primary}` to `{secondary_light}` sliding over 60t),
  `.lib-rainbow` (rainbow over 80t) and `.lib-wave` on a container (its children arrive 2t apart and float in a wave).
  The older `.anim-pulse`, `.anim-float`, `.anim-nudge` are the same loops. Nothing in the base theme uses them, so no
  screen changes until it names one.
- An element's own `style="..."` (1.565.0) wins over every rule and may set `--custom` properties for its children.
- Not drawn (Minecraft cannot): rotation, opacity and colour glides, rounded corners, blur. A fade is a scale from
  nearly nothing; a colour change steps.
- The base theme has the house motion: cards lift, buttons, tabs and chips press, dialogs pop and fade out.

**Budget.** A screen may draw 400 displays. Over that it lowers its detail in order: `priority="low"` boxes are
left out, turning bodies stand still, item sprites become 3D items (one display rather than four); only then is
the tail cut, with one warning. `screens.yml` `motion-ceiling` (300) caps motion packets a viewer gets each
second: past it glides go straight and animations wait.

**Reduced motion.** `screens.yml` `motion: REDUCED` for everyone, or a player's own toggle in `/settings` →
Interface ("Reduced motion"), `Screens.reducedMotion(player, true)` in Java: no arrivals, departures or
animations, and every glide is two ticks and straight.

**Lighter screens** (1.565.0). A player's toggle in `/settings` → Interface draws every screen at the lowest detail
for them. `Screens.of(plugin).cost(name)` reports a screen's heaviest frame (`Screen#name`); see
[Performance](screens-performance.md#budget-and-detail).

**Toasts and confirms.** `session.toast("success", "Joined the queue")`: one small note at the bottom centre, a newer one replacing it (since
1.540.0), sliding in and out; success and error play a quiet sound. `session.confirm("Leave the party?",
true, "Leave", null, s -> parties.leave(s.viewer()))` draws the question in a dialog on the screen itself; Cancel,
or a click outside does nothing (Shift closes the screen). `session.ask(prompt, hint, max, current)` asks for a line of text and
completes with it, or `null` when cancelled.

**Refresh.** `refresh=` on an element, `Node#refresh(ticks)` in Java, and
`Screens.of(plugin).redrawAll(session -> ...)` to redraw every open screen of the plugin that matches now (a
queue that changed).

**Sprites (1.21.11+).** With `sprite-objects: true` (default) a 1.21.11+ client on a 1.21.11+ server gets an
item as one sprite glyph rather than four pixel copies; others keep the pixel picture.

## Backgrounds (1.497.0)

```html
<screen backdrop="linear-gradient(to bottom, {primary}1c, {info}06)" scene="img/stars.png" parallax="2">
```

```css
.app { background: linear-gradient(to bottom, {primary}0e, {primary}05); }
.banner { background: {primary}20; background-image: url(img/banner.png); }
```

- `linear-gradient([to bottom|to top|to right|to left|<n>deg,] colour, colour...)` works wherever a background
  colour does: `background` in CSS, `Node#background` and `Screen#backdrop` in Java. Colours spread evenly; an
  angle turns to the nearest side. It is drawn as stepped bands, one each five pixels (2 to 12), neighbours of the
  same colour merged: one display a band. What a gradient box holds is mixed over its middle colour. A backdrop
  gradient spans the view, its outer bands stretched to the edges.
- `background-image: url(path)` (`Node#backgroundImage(Node.image(...))`): a picture from the screen's folder,
  cut to cover the box with square pixels and centred, over the background colour (clear pixels show it), under
  the box's content. Four displays.
- `<screen scene="img/x.png">` (`Screen#scene(Node.image(...))`): a picture behind everything that covers the
  whole view of a 16:9 window and some; clear pixels show the backdrop. Four displays, sent once: a sparse 96 by 54
  starfield is about 40 KB on open, nothing after. `parallax="3"` (`Screen#parallax`, 0 to 16 pixels) drifts it
  against the cursor, so it reads as far behind: four transforms more per cursor move.
- Pictures follow the 96-a-side limit of `<img>`. Use palette tokens for every colour in the CSS; a picture's own
  colours are its file's.

## Bodies wearing a kit (1.498.0)

`<img skin="%me_name%" turn wearing="...">` stands the player's body in the round wearing armour and holding an
item, with no resource pack: the armour layers, leather dyes and trims are the game's own textures, read once from
the client jar into the sprite cache (`plugins/ExyliaLib/cache/sprites-<version>-2`, which since 1.498.0 also
keeps `entity/equipment/humanoid*`, the pre-1.21.2 `models/armor` layers and `trims/`), and laid over the skin's
own boxes in the order the game draws them: leggings, chestplate, boots, helmet. The held item is its inventory
sprite, ten skin pixels tall, side on in the right fist as the game holds it. A flat `<img skin wearing>` shows
the front of the same dressed body.

`wearing` takes one word per item, space-separated, in any order:

| Word | Means |
| --- | --- |
| `diamond_helmet`, `iron_chestplate`, `chainmail_leggings`, `golden_boots`, `turtle_helmet` | armour, its slot from its name |
| `leather_chestplate#a06540` | leather in a dye colour (undyed leather without one) |
| `netherite_leggings@silence:gold` | a trim: pattern and material, as their keys |
| any other item (`diamond_sword`, `bow`) | held in the right hand; only the first one |

Words that do not read are left out. From Java, `Screens.outfit(ItemStack...)` writes the words for real items
(colours and trims included), and `Node#wearing(ItemStack...)` / `Node#wearing(String)` dress a `turntable`.
The first time an outfit is seen its textures are read on an async task; the body is drawn bare for that moment.

The PracticeCore hub dresses its side bar's body in the kit under the cursor:

```html
<img skin="%me_name%" class="me-body" turn wearing="${tab == 'play' ? wear : ''}"/>
...
<card action="queue %kit_id%" action-hover="set wear %kit_wearing%">...</card>
```

```java
row.put("kit_wearing", Screens.outfit(helmet, chestplate, leggings, boots, firstHotbarItem));
```

Armour is laid flat on the skin's boxes rather than as the game's slightly larger second box, so a helmet sits on
the head's outer layer; a slim arm wears the first three columns of the classic armour arm. It costs nothing more
on the wire than the bare body (the same 96 displays turning).

## 3D items (1.499.0)

`<item3d material="diamond_sword" spin/>` (Java: `Node.item3d(stack, 36)`) draws the item as the game models
it: a block with its sides, a sword with its edge, glint on enchanted items. It leans 25° towards the viewer so a
block shows its top, and a spinning one glides a step every second, interpolated by the client (one transform a
second on the wire). Every model fits its box as it turns (1.507.1): a block cube at half the box, so its corners
reach the edge, and a flat item (a sword, and block items drawn flat such as cake, saplings and doors) the box
across. Being 3D, an item off the screen's centre leans a little past its box towards the edge it is near.

A 3D item is shaded by the world's two fixed lights, and a screen faces the eye wherever it looks: left alone,
the item would brighten and darken as the player moves the cursor. So while one is on screen the camera is held
within 8° of where it faced when the screen opened: whenever yaw or pitch drifts further, the server turns it
back with a relative teleport (the pitch recentre the screen always does past 40°, generalised). The screen
itself never moves, so the snap is not seen, and the cursor keeps every bit of the turn. Verified in a 1.21.4
client: sweeping the cursor across the whole screen left the camera within 6° of home and the items' shading
steady; clicks land as before. Shading still depends on the compass direction the player faced when the screen
opened (north and east light a block differently), and a very fast flick can show one frame of drift before the
turn back arrives.

Use `<item>` (a flat sprite, never shaded) for lists and icons; keep `<item3d>` for a showcase: the item a
crate gives, a kit's weapon, a shop's featured item.

## Scenes and games (1.500.0)

Solo and house games (a coin, dice, a wheel) are drawn on a screen with a **scene**: the markup holds a
`<canvas id="table"/>` where the game goes (its size from CSS), and the plugin moves sprites on it from Java. The
score, the stake and the buttons stay markup, so an owner restyles everything but the motion.

```java
ScreenScene table = session.scene("table");
table.sprite("coin").item(new ItemStack(Material.SUNFLOWER)).size(36, 36).at(92, 76);
table.sprite("coin").move(92, 10, 8, Motion.Ease.OUT);          // up; the client glides it
table.sprite("coin").scale(0.2, 2, Motion.Ease.LINEAR);          // to its edge, mid-turn
table.sprite("shadow").rect(28, 3, "{letters_black}").at(96, 114);
table.remove("coin");
```

| `Sprite` | Does |
| --- | --- |
| `item(stack)`, `item3d(stack, spin)` | an item's flat sprite, or its lit 3D model (see [3D items](#3d-items-14990)) |
| `text("{highlight}x2")`, `image(w, h, argb)`, `rect(w, h, colour)` | words, a picture, a plain box |
| `content(() -> node)` | any node, built again on each redraw |
| `size(w, h)` | its box in pixels (`-1` for its content's own) |
| `at(x, y)`, `move(x, y, ticks, ease)` | its top left in canvas pixels, at once or gliding |
| `scale(s, ticks, ease)` | about its centre; 0 hides it |
| `layer(z)`, `remove()` | above or below its siblings (-4 to 8); off the scene |
| `to(x, y, s, ticks, ease)` | place and scale in one glide (1.531.0) |
| `squeeze(s, ticks, ease)` | narrows it across only, its height untouched (1.531.0) |
| `flip(ticks, face)` | squeezes to its edge, runs `face` (change what it shows there) and opens again (1.531.0) |
| `pop(peak, ticks)` | swells to `peak` and settles at 1; from scale 0, it appears (1.531.0) |
| `shake(pixels, ticks)` | jolts about its place, dying away (1.531.0) |

- Sprites persist: they stay put until moved, unlike a render function's nodes. Every change redraws the screen
  once (changes in the same tick are drawn together) and only changed sprites go out, one transform each.
- Motion is the client's interpolation, so it is smooth at any TPS. One sprite has one glide at a time: a `move`
  and a `scale` set together share the last call's ticks and easing. For a curve (a toss), move it a step every
  two ticks along the curve; an ease is split into linear segments as `transition` does.
- A screen has one viewer, so hidden information (the next card, the result before it lands) never leaves the
  server early. Decide the outcome first; the scene only shows it.
- Cost: what each sprite draws (an item 1 display, a picture 4, a rect 1-2), within the screen's display budget.
- A screen built in Java places a scene with `session.scene("table").node(220, 120)`. `ScreenScene` is safe from
  any thread; a session that cannot draw (a test double) answers a scene nothing shows.

ExyliaCasino's coinflip is the reference: for a player whose menu style is SCREEN the toss is a sprite on
`screens/coinflip.html`'s canvas (`modules/coinflip/CoinScreen.java`), and table games with seats and watchers
keep drawing in the world.

### Motion kit (1.531.0)

On a `ScreenScene`, for what several sprites do together. Every step runs on the viewer's thread and a closed screen
runs no more of them. Reduced motion (`reduced-motion`) jumps to the end: a flip just changes the face, a count shows
its target, a burst and a shake do nothing.

| `ScreenScene` | Does |
| --- | --- |
| `timeline().at(tick, step).after(ticks, step).play()` | steps in order, some ticks after `play`; `cancel()` runs no more. Tick 0 runs inside `play`; what one step does in a tick is drawn together |
| `burst(x, y, count, radius, ticks, colours...)` | squares flying out and shrinking away, one display each while they fly. Decoration: left out first over the display budget, and nothing at all past the motion ceiling |
| `shake(pixels, ticks)` | the whole scene jolts. Every display of the scene takes a transform each step, so past the motion ceiling it stops early, back in its place |
| `countUp(from, to, ticks, show)` | hands `show` a rising number every two ticks and exactly `to` at the end. It draws nothing itself: `show` sets a value the markup reads |

```java
ScreenScene board = session.scene("board");
ScreenScene.Sprite tile = board.sprite("tile7");
tile.flip(5, () -> {
    tile.rect(36, 36, "{success}38");
    board.sprite("gem7").item(gem).size(24, 24).at(40, 6).scale(0, 0, Motion.Ease.LINEAR);
    board.timeline().at(1, () -> board.sprite("gem7").pop(1.3, 5)).play();   // a tick later: it glides from what the client has
    board.burst(58, 18, 8, 22, 8, "{success}", "{success_light}");
});
board.countUp(1.10, 1.24, 8, value -> session.set("prize", money(value)));
```

A display only glides from a transform the client already has: a new sprite shown at its start and moved in the same
tick jumps. Show it first, then move it from a timeline step at least one tick later, as above.

**Rotation.** A sprite does not rotate, and a flip is a squeeze rather than a turn about the upright: a display turned
out of the screen's plane cuts through whatever is behind it. Turning *within* the plane, which a wheel needs, works on a
client but is not built: see [What the kit does not cover yet](#what-the-kit-does-not-cover-yet).

## Windows: a preview in the screen (1.538.0)

A cosmetic is made of what the world draws: bodies, displays, particles. A screen is an opaque panel in front of the
camera, so none of that shows through, and redrawing hundreds of effects as sprites does not scale. A `<window>` is a
hole cut in the panel that looks at the real thing: the same lines, played for real on a stage in the world behind it,
sent to this viewer alone. No effect needs any code or any change to be seen.

```html
<window preview="effect_preview" class="window"><item3d material="%effect_material%" spin class="hero"/></window>
<window wearing="${wear ? wear : outfit}" class="window"/>
```

```java
screens.markup("effects").value("effect_preview", session -> {
    EffectDefinition effect = registry.resolve(session.state("focus", ""));
    return effect == null ? SpatialPreview.none() : SpatialPreview.sequence(registry.sequenceOf(effect.id()), null);
});
```

```css
.window { width: 122px; height: 104px; background: {accent}0c; outline: 1px {accent}40; }
```

- `preview="name"` reads the value of that name, a `SpatialPreview`, the same object a spatial menu's pedestal plays:
  `sequence` (an effect on a mannequin), `performance` and `duet` (an emote), `flight` (an arrow and its trail), `npc`,
  `item`. A plugin with a spatial menu already has it. Set the focus with `action-hover="set focus %id%"`.
- `wearing="<outfit>"` is the viewer's own body in those pieces, in the words `<img wearing>` takes: a hover swaps what
  it wears in place, with real armour, trims and glint.
- **Nothing to show:** a `none()` or missing value makes it a plain box drawing what is inside it, so what cannot be
  previewed (no permission, nothing chosen) keeps its icon.
- `vanish` makes the mannequin disappear while the effect plays, so a kill reads as a death (a spatial layout's
  `vanish-on-play`). Without it the mannequin stands and takes the effect.
- Java: `Node.window(preview).vanish(true)`. One a screen; a second is an ordinary box. A `scene=` background is not cut.

How it stays honest:

- **Hover.** A new hover stops what is on stage at once, and the next one starts after the cursor rested 180 ms, so
  sweeping down a list draws and plays nothing. Only a change of what a body wears skips the wait.
- **Private.** Everything goes through `onlyTo(viewer)` and packet-only entities; a player beside the viewer is sent
  nothing about it. Nothing is placed in the world.
- **The world is kept out.** Every block any part of which the window's view reaches is kept as air for the viewer
  (`FakeBlocks#keep`), corners included and grown a quarter block for the camera's yaw steps (1.553.0: tested by its
  middle, a wall beside the eyes still showed at the edge of a window far aside), up to a back wall, a display coloured by the window's `background`, and two fake light blocks light the stage at night or
  underground. The floor is a block display.
- **Closing leaves nothing.** Hover change, leaving the page, an input stepping the screen aside and closing all cancel
  the run, destroy every piece and put the real blocks back. Particles the client already drew are the client's and
  fade by themselves within a second or so; the panel hides them on a hover change, on close they may finish in the
  open.
- **The camera holds still.** While a screen is open the client looks out of a still entity at its eyes, facing level, so
  the mouse moves only the cursor and the world in the hole never moves with it (1.545.0, client 1.21.2+). The rotation
  still reaches the server because the client also rides a second entity, which the server never hears of; a client
  that rides something sends its rotation every tick whatever it looks through. Closing, an input stepping the screen
  aside, a death, a world change and a cinematic each give the view back. A player really riding something, and a client
  before 1.21.2, whose sprint and sneak stop with the position, keep their own camera: there it stays within a degree of
  its place, goes back the moment the mouse rests, and the picture can shake by that degree.
- **The stage is seen level.** A pitched camera leans every upright away from the middle of the view, so it does not
  pitch: a body stays upright wherever the window is, as a shift lens keeps a building straight. The stage is turned with
  the camera, square to the view as the panel is: the floor's front edge stays level however far aside the window is,
  and the body, its bodies and its scenery face out of the window in that one frame. The floor is level and thick (1.553.0;
  before, it was tipped towards the camera, so a body stepping back sank into it): a window low on the screen shows its
  top, one high on the screen its front, and seen from below it reaches only as far in front of the feet as keeps its
  near edge off the legs. Far from the middle a body is drawn wider, as
  anything is near the edge of a wide view: keep a tall window near the middle where it can go.
- **Glow is off for the viewer while a window is up.** A glowing piece's outline is drawn over everything, panels
  included, so one past the window's edge would show on them.
- **Display budget.** A screen and the stage in its window share 560 displays (a screen alone keeps its 400). An effect
  that wants more than the screen leaves plays with its later pieces missing, and the console says so once per plugin;
  every effect ExyliaKillEffect ships fits. On the first play of an effect a rehearsal learns how to turn and size it;
  it is never sent, so it costs the client nothing. See [Performance](screens-performance.md).

**What cannot preview:** anything that acts on a player rather than the world around them is left out of a preview as it
is on a spatial menu (a title, a potion, a command). A pure sound has nothing to see. Words in chat need no window: draw
the line itself (ExyliaChatCosmetics' rows).

## Gallery and preview (1.497.0)

`/exylialib screens gallery` opens every element, the component library, the motion, backgrounds and the keys
on one screen, from `plugins/ExyliaLib/gallery/` (installed and updated like any plugin's screens, hot-reloaded,
so authors can try edits there). `/exylialib screens preview <plugin> <id>` opens any plugin's screen as its files
are now, with no state (permission `exylialib.admin`).

## Words from Java (1.497.0)

`ScreenMarkup#word(key, fallback)` reads a word of the screen's `lang/<code>/screens/<id>.yml`, for text Java puts
on the screen:

```java
session.confirm(question, true, hub.word("dialogs.kit.delete", "Delete"), hub.word("dialogs.cancel", "Cancel"), yes);
```

The in-screen confirm's own default labels ("Confirm", "Cancel") follow the library's language since 1.497.0.

## Component library (1.496.0)

Every screen knows these templates; a plugin's own template of the same name wins.

| Template | Use |
| --- | --- |
| `<page-head title="Kits" subtitle="Pick one"/>` | a centred title and dark subtitle |
| `<stat label="Wins" value="%wins%" tone="success"/>` | a label and a coloured value on one line |
| `<badge tone="warning">New</badge>` | a small tag; tones `primary`, `success`, `error`, `warning`, `info` |
| `<key-hint key="Shift">Close</key-hint>` | a key and what it does |
| `<empty-state title="No kits" hint="Ask staff to add one"/>` | a list's empty note (inside a `grid`/`list` since 1.497.0: before, it was repeated as a row) |
| `<dialog title="Sure?" when="confirm" dismiss="clear confirm">...</dialog>` | a themed `modal` |
| `<game-frame title="...">`, `<game-play>`, `<game-board>`, `<game-side>`, `<game-figure value tone>`, `<game-action action disabled>`, `<game-result/>`, `<game-page tab="how">` (1.531.0) | the frame of a game: see [Giving a game its screen form](#giving-a-game-its-screen-form) |

| `<table-head title subtitle rules>`, `<table-rules title rules>`, `<table-panel title>`, `<kicker label tone>`, `<results-strip source empty>`, `<chip-rack source state>`, `<table-action action tone>` (1.598.0) | a shared table game on one screen: see [A game table on one screen](#a-game-table-on-one-screen) |

**Shared bindings.** `screens.bindings().value(...).action(...)` registers for every screen of a `ScreenSet`
(`ScreenBindings`, same methods as `ScreenMarkup`); a screen's own registration of a name wins.

## Giving a game its screen form

A game can be played in the world or on a screen, the rules and the money the same in both. Mines in ExyliaCasino is
the reference: `modules/mines/`, `screens/mines.html`, `lang/<code>/screens/mines.yml`.

**1. Which form.** Ask once, as the game starts:

```java
GameForm form = MenuStyles.of(plugin).gameForm(player);                 // the player's menu style
GameForm form = MenuStyles.of(plugin).gameForm(player, config.form());  // SCREEN or WORLD force one; AUTO or null follow
```

A chest-window style plays in the world, and so does a client that cannot draw screens. `GameForm` is its own enum:
`MenuStyle` is switched on exhaustively by consumers, and "where is a game played" is not a menu style. Staff try
either with `/exylialib style game screen|3d|default [player] [plugin]`, cleared on quit.

**2. Split the round from its drawing.** The rules, the odds, the money and the order (decide first, then animate)
stay in the round. Write the small interface it talks to, what happened and never how it looks (`MinesTable`: `show`,
`safe`, `boom`, `cashed`, `tension`), and make the existing view implement it. The round imports no stage, screen,
display, sound or text class. Move sounds and particles to the view with the rest of the drawing. Test the round
against a recording table, with a pinned script for one seed.

**3. The markup.** `screens/<game>.html`, one file for every language, words in `lang/<code>/screens/<game>.yml`
(`t="key"`; see [Words](#words-14940)). The frame is 400 by 225 and the components come with the library:

```html
<screen>
  <game-frame title="{primary}%game%">                <!-- title, and tabs Play / How to play / Payouts -->
    <game-play>
      <game-board><canvas id="board"/></game-board>   <!-- size the canvas in the game's css -->
      <game-side>
        <game-figure value="%stake%"><small t="side.stake">Stake</small></game-figure>
        <game-figure value="%multiplier%" tone="success"><small t="side.now">Multiplier</small></game-figure>
        <box class="spacer"/>
        <game-action if="!game_result" action="my_cash" disabled="${!can_cash}">
          <h2 t="side.cash">Cash out</h2>             <!-- the main action, large, muted while it cannot be used -->
        </game-action>
        <game-result/>                                <!-- what happened, and Again / Change stake / Leave -->
      </game-side>
    </game-play>
    <game-page tab="how">...</game-page>              <!-- fills the frame; only while its tab is open -->
    <game-page tab="payouts">...</game-page>
  </game-frame>
</screen>
```

Tabs and buttons speak the library's words, in the language of the plugin the screen belongs to (1.543.0+; before, the server's). A screen's own words replace them under `frame.play`,
`frame.how`, `frame.payouts`, `frame.again`, `frame.change`, `frame.auto` and `frame.leave`.

**4. Bind the frame.** `GameFrame.install(markup)` once; `GameFrame.of(session, markup)` for each screen:

```java
frame.result("success", "{success}&lCASHED OUT", "{letters}You take {success}$241");
frame.offer(new GameFrame.Offer("$100", refused, this::again, this::change, this::leave));
frame.withdraw();   // the choice was made
frame.clear();      // the next round, on the same screen
```

`refused` is the reason Again cannot be pressed now (the stake cannot be paid), shown under the muted button, or
`null`. The markup object is shared by every player: actions read the table from session state, never from a field
captured when the markup was taken.

**5. The form-specific view.** A scene of sprites plus `session.set` for what the markup reads:

- Nothing hidden leaves the server early. A screen has one viewer, but the mines still reach the view only when the
  round is over. A view can only ever show what the round told it.
- Keep the view's own record of what is shown, enough to draw the scene from nothing: the player can close the
  screen and come back.
- Hover reacts, click decides, the result stays in place with the board in view. Quiet, short sounds.
- A number that changes goes through `countUp`. A rich streak spends more shards, not longer ones.
- `Screen#onInput` is told of every click, for an idle clock.

**6. Closing mid-round.** Shift always closes a screen. Treat it as walking away by whatever rule the game already has:
keeps what was earned, leaves at once; loses the stake, asks first (a dangerous confirm), and brings the screen back on
no. Ask with `.transports(TransportKind.SCREEN, ...)` and the question is drawn on a screen whatever the player's menus
are, so it never meets a chest window. Nothing is decided by a closed screen on its own.

**7. Check it.** Budget and motion from [Screen performance](screens-performance.md#games-on-a-screen), every tab in
every state on a real client, in two languages: a win, a loss, a stake that cannot be paid, closing mid-round.
Two checks catch what cost the pilot most, and belong in the plugin's tests from the first commit (Casino's
`ScreenFilesTest` and `GameScreenFit`, a new game is one more line):

- **Clashes.** `theme.css` styles a whole plugin, so a class a game names for itself must not be one of its selectors
  (`.pays` collapsed every payout cell to no width), and it defines no `.game-*`, which are the kit's.
- **Overflow.** Lay every tab in every state out, in every language, with the widest values the game can show: nothing
  may leave the 400 by 225 frame or its parent, and no text may end up in a box with no width.

### The game kit (1.538.0)

`net.exylia.lib.screen.kit`, built from sprites and timelines, shared by every game. Each returns or holds the timeline it
plays, to cancel with the round. What is hidden stays on the server until it is shown: a face is sent at the flip's edge, a
reel's landing only as it comes in, a die's value last.

| Class | Does | Costs |
| --- | --- | --- |
| `ScreenCards` | a card as one sprite: `up`, `down`, `dealt` (slides in face down), `turn` (flips to a face), `hide`; `Look.standard()`, `.sized(w, h)`, `.with(deck)` for the Spanish deck | 2 to 3 displays |
| `ScreenDice` | a die with real pips: `show`, `roll` (tumbles on decoys and lands on the value) | 1 + its pips |
| `ScreenReel<T>` | a column running down its window, shrinking away at both ends since a scene has no clip: `rest`, `spin(ticks, decoy, landing, landed)` | `rows + 2` sprites, one transform each per step |
| `ScreenOrbit` | sprites going round a centre upright, slowing to an angle: `add`, `landing(offset, turns)`, `spin(to, ticks, stepTicks, shrinkTo, eachStep, landed)`, or `spin(angle, radius, ticks, stepTicks, eachStep, landed)` for a path of its own (1.543.0+). A wheel's segments, or one ball round a fixed ring of numbers | one transform per display per step: a ball when there are many numbers |
| `ScreenTrail` | a curve as dots gliding to where they are asked: `draw(points, colour, ticks)`, `colour`, `clear` | one display a dot, one transform per dot per call |

```java
ScreenCards cards = new ScreenCards(ScreenCards.Look.standard());
cards.dealt(board.sprite("d0"), board, 170, -50, 20, 10, 6);
cards.turn(board.sprite("d0"), card, 6);
new ScreenDice(ScreenDice.Look.standard()).roll(board.sprite("die"), board, 20, 40, 3, 16);
```

**The frame's other two.** `GameFrame#notice(text)` puts a line in the frame's head on every tab, for a warning that time
is running out. An `Offer` with an `auto` puts an Auto button with Change stake and Leave (`frame.auto`).

**Rotation.** Sprites stay upright. A wheel keeps its words readable that way, and a turned rect pivots on its bottom
centre rather than its centre, so `Sprite#rotate` is left unbuilt until a game cannot do without it. Tried on a 1.21.4 client
with a throwaway build: rects stay crisp at 30 and 60 degrees and turn smoothly; text and pictures were not tried.

**Watchers.** A screen has one viewer. A table standing in the world, the point of which is to be seen, stays in 3D.

## Shared matches (1.533.0)

A game played by several players, each on their own screen. `SharedMatch` is what they share, and it is deliberately
little: a screen has one viewer, so the way to keep a hand, a ship or a secret word off a client is never to hand it to
that viewer's seat. ExyliaTabletop is the reference (`game/TableScreen`, `match/MatchService`, `modules/twentyone`).

```java
SharedMatch match = SharedMatch.of(plugin, markup, (seat, stay) -> askToLeave(seat.viewer(), stay));
SharedMatch.Seat seat = match.seat(player, Map.of("game", name));   // on the player's thread
if (seat == null) playInTheWorld(player);                           // a camera holds them, no PacketEvents
seat.drawing(this::drawFromMyRecord);                               // the scene, after they closed it and came back
match.everyone("pot", "$200");                                      // what anyone at the table may see, by name
seat.set("total", "17");                                            // this viewer's alone; survives closing
seat.scene("board").sprite("hole").content(() -> cardBack());       // sprites go to this client only
match.end();                                                        // closes every screen; none of it counts as closing
```

| Case | What happens |
| --- | --- |
| **Hidden information** | Values, scenes and records are per seat. The round tells each seat's table only what that player may see: a rival's card is told as "a card, face down", and its face comes in the call that turns it. A table cannot leak what it was never told, so check the contract, not the drawing: one recording table per seat, assert the secret is not in the rival's script before the reveal (see Tabletop's `TwentyOneFormsTest`). |
| **Mixed forms** | Only players on a screen have a seat. Their rivals play the same round in the world through the game's other table. The round holds one table per player and never asks which form it is. |
| **Closing** | Shift, damage or a camera closes one screen and tells `Closed`. It decides nothing by itself: run the game's rule for walking out, and call `stay` to bring it back. The match keeps running meanwhile: the table keeps its record while `seat.open()` is false. |
| **Disconnect** | The plugin's quit rule. `seat.close()` closes the screen without counting as a player closing it. |
| **The end** | `match.end()`. Late clicks and closes do nothing. |
| **More players** | Any number of seats. A seat that leaves closes alone, the rest play on, by the round's rule. |
| **Watchers** | A screen cannot be watched. A table that stands in the world to be seen plays in the world for everyone at it. |

**Threads.** `seat()` opens a screen, so it runs on that player's thread; on Folia seats live on different threads and
a round lives on one of them. Everything else on a seat is safe from any thread. A click arrives on the viewer's thread:
hop to the round's before it touches the game.

**Shared words.** `lang/<code>/screens/shared.yml` (1.533.0) is read by every screen of the folder, under its own file:
the words twenty screens of one kit have in common.

## Chest windows or screens: menu style (1.495.0)

Every plugin's chest menus can open as screens with no code of its own. Which one a player gets is a
`MenuStyle` (`net.exylia.lib.ui`): `CLASSIC` (chest windows, the default) or `SCREEN`. `Menus.of(plugin).open*`
asks `MenuStyles` on every open; in `SCREEN` the chest menu still runs as always (rows, context, actions, pages,
redraws, `UiKeys.SESSION`), but its window is never opened and a screen draws it instead:

- **A screen routed to that menu id** (`<screen routes="...">`, below) when the plugin has one;
- otherwise **the chest skin**: the chest's title as a header, then only its items as cards (icon, the name
  without its `Context ▸` part, and the first lore line when there is room), kept at their slot positions; glass
  panes and empty slots are not drawn, and rows or columns with nothing in them are left out (1.513.0). When the
  last row holds buttons (back, page arrows, the main action) it becomes a bar under a divider, its buttons kept
  left, centre and right as in the chest. Since 1.562.0 that bar never moves: the chest's column 4 (the back
  button in every Exylia layout) sits in a fixed cell at the exact middle, the page arrows (columns 3 and 5) in fixed
  cells either side of it, the buttons of columns 0-2 from the left edge and 6-8 from the right, both sides the same
  width; nothing wraps (a side whose labels do not fit shows its icons, the whole name in each tooltip). A back
  button (its subject in the error tone) says its context, `Back`, not the screen it returns to. The body keeps its
  columns symmetric about the chest's centre, so what the chest centres stays centred. A list with no items shows what its empty slots say. Each item's name
  and lore are its tooltip while hovered. Cards that do something light up under the cursor; the others only show
  their tooltip. A left or right click on a card is that click on the slot of the chest menu, so every action
  works as in the window, and a menu opened from a click replaces the cards in place. Shift closes it (1.514.0); the
  chest's own back button goes back; a click with sprint held is a shift-click on the slot, or the
  plain click when the button binds no shift-click, so a client with toggle sprint never meets a dead button
  (1.513.0).

**Admin menus stay chest windows** (1.562.0) unless `screens.yml` says `admin-style: FOLLOW`: a menu is an
admin menu when a part of its id's path is `admin` or starts or ends with `admin_`/`_admin` (`menus/admin/kits`,
`admin_hub`), and the library's settings screens. Admin work is dense lists, editors and typed values, which a
chest does best. A staff member who runs `/exylialib style screen` sees admin menus as screens too (1.567.0),
for checking how they look; everyone else keeps the windows.

Menus that stay windows whatever the style: those with input slots (the player puts items in them), those
that are not chests (hopper, dispenser...) and the kit room (it hands items into the inventory the player is
building in, so that has to show). A client that cannot draw screens always gets windows.

### Who decides

The first rule that answers wins:

| # | Rule | Where |
|---|---|---|
| 1 | A client that cannot draw screens (Bedrock, older than 1.20.2, no PacketEvents) gets `CLASSIC` | `MenuStyles.canShowScreens(player)` |
| 2 | A staff override, always (even with `player-choice: false`) | `/exylialib style`, below; in memory, cleared on quit or restart |
| 3 | The player's own choice, when the server lets players choose (optional, off by default: `/settings` is disabled unless enabled) | `/settings` → Interface → Menu style, kept with their screen preferences (see "Where preferences are kept") |
| 4 | The plugin's own setting; `DEFAULT` passes | `MenuStyles.of(plugin).defaultStyle(() -> settings.menuStyle())` |
| 5 | The server's choice for that plugin | `plugins/ExyliaLib/screens.yml` `plugins: {ExyliaJobs: SCREEN}` |
| 6 | The server's default, `CLASSIC` as shipped | `screens.yml` `default-style` |

### Trying a style: `/exylialib style`

`/exylialib style game screen|3d|default [player] [plugin]` (1.531.0) tries which form a game is played in, on top of
the style, until it is cleared or the player leaves: see [Giving a game its screen form](#giving-a-game-its-screen-form).

`/exylialib style <classic|screen|default> [player] [plugin]` (permission `exylialib.admin`) switches a player's
menu style instantly, with no config edit: without a player it is you, without a plugin it covers every plugin,
with one only that plugin's menus (names tab-complete from the plugins that use `MenuStyles`). `default` clears
the player's overrides (all of them, or just that plugin's when one is named). It applies to the next menu opened.
A client that cannot draw screens still gets `CLASSIC`, and the command says so.

`screens.yml` (`ScreenSettings`, editable in game with `/exylialib config`):

```yaml
default-style: CLASSIC     # CLASSIC or SCREEN, for every plugin that does not choose
player-choice: true        # players may pick their own in /settings
plugins: {}                # ExyliaJobs: SCREEN
chest-skin: true           # off: menus without a screen of their own stay windows in SCREEN style
cursor-mode: NORMAL        # NORMAL, RAW or PREDICTION, for players who did not choose (1.546.0)
cursor-assist: false       # makes buttons easier to hit, for players who did not choose (1.547.0)
admin-style: CLASSIC       # admin menus as chest windows, or FOLLOW the style (1.562.0)
```

A plugin with a `menu-style` key of its own hands it over once, on enable:

```java
MenuStyles.of(this).defaultStyle(() -> settings.get().ui().menuStyle());   // "CLASSIC", "SCREEN" or "DEFAULT"
```

| Call | Answers |
|---|---|
| `MenuStyles.of(plugin).resolve(player)` / `.screens(player)` | the style that player gets for that plugin now |
| `MenuStyles.canShowScreens(player)` | rule 1 |
| `MenuStyles.choice(player)` / `MenuStyles.choose(player, style)` | the player's own pick (`null` = the server's); kept across restarts, any thread |
| `MenuStyles.choosable()` | whether `player-choice` is on |

### Routing a menu id to a screen

A screen of a `ScreenSet` names the menu ids it stands for. In `SCREEN` style, opening any of them opens this
screen with the state written after the id, `%name%` filled from the menu's context; a key whose placeholder the
context does not answer is left to the screen's own default. A screen already open is reused (its state set), so
moving between routed menus never reopens it.

```html
<screen routes="player/queue_category:tab=play
                player/queue_kit:tab=play,category=%category_id%
                player/settings:tab=settings">
```

Routes are separated by spaces; in each, the parts after a `:` that hold `=` are the state (pairs separated by
`,`), the rest is the menu id, with or without its namespace (`practice:kits`). The plugin's own checks (permission,
"already in a game") run before the menu opens, so they apply to the screen too.

### The data contract: a screen for a chest menu reads the menu

A routed screen is drawn from the same data as the chest menu it stands for, so it needs no Java of its own:

- `%name%` reads, after the screen's own values and state, **the menu's context** (`menus.open(player, id, context)`,
  suppliers included). Then the library placeholders and PlaceholderAPI, as everywhere.
- `source="<section>"` / `<for each="x in <section>">` (the `<for>` since 1.507.0) reads **the menu's rows** of that section (`main`, or the
  section ids of a `sections:` menu) when no binding of the screen has that name, refreshed as the plugin refreshes
  them (`entries(...)`, live suppliers).
- Each of those rows also carries `row_template` (1.504.0): the template the chest would draw it with (`empty`,
  `locked`, `shop`... as the plugin set it with `UiEntry.Builder#template`), `''` for the default one. A screen
  uses it to style a locked or rotating row: `class="shop ${row_template}"`. A row that has a `row_template`
  value of its own keeps it. A row that carries nothing (no object, values or item: a chest's placeholder that only
  keeps a slot empty, `UiEntry.row().template("empty")`) is left out, so the screen's grid has no holes. `screens check` does not report a source of a routed screen that no
  binding registers, since the menu's sections answer it.
- `<item row/>` (1.506.0) draws **the item the chest draws for that row**: its template filled with the row's
  values and the menu's context, so what no material names (a shield's banner patterns, a dyed piece, a player's
  stored stack) shows as it does in the chest. `material=`/`head=`... are the fallback while it draws nothing. A row of
  a screen's own `rows(...)` binding draws the item it brought with `UiEntry.Builder#item`. Java:
  `UiSession#render(section, row)`.

  ```html
  <grid source="main" columns="7" rows="2"><card action="shield:open_editor"><item row material="shield"/></card></grid>
  ```
- `ns:id args` actions run with `UiKeys.SESSION` (the chest menu behind the screen), `UiKeys.MENU` and
  `UiKeys.ENTRY` (the clicked row's object), exactly as from a chest, so the YAML's action strings work unchanged.
- Bindings registered on the screen (`value`, `text`, `rows`, `action`) are read **once a render** however many rows
  ask for them.

### Tooltips

`Node#tooltip(lines...)` (Java) and `tooltip="..."` (markup, lines separated by `<nl>`) show a small panel beside a
box while the cursor is over it, the first line as its title, as an item's lore shows over a slot. It is drawn
over everything, a dialog included, to the right of the box or to its left near the edge, kept on screen. Since
1.513.0 it reads as an item tooltip: the title at 0.8 scale, the rest one left-aligned block at 0.7 (so every line
starts at the same x on every client), at most 150 pixels wide, sized to its content, blank lines at its ends left
out. Leading spaces become a small indent (at most two spaces), never a run pushing the line right. A box with a
tooltip is hoverable even without a hover style. Cost: three displays, only while hovered; moving between boxes
sends only what changed.

```html
<card action="practice:select %kit_id%" tooltip="%kit_name%<nl>{letters_black}%kit_mode%">...</card>
```

A row of a list can show its own item instead (1.569.0): `tooltip-item` on a box inside a repeat over `UiEntry`
rows makes its tooltip what a chest shows over that item, name, enchantments and lore, with any `tooltip` lines after
it. Chest menus drawn as screens show enchantments the same way. `ItemTexts.tooltip(item)` gives those lines in Java.

```html
<row class="entry" tooltip-item action="market:buy %listing_id%"><item row material="%listing_material%"/>...</row>
```

### Windows and inputs over a screen

- An input (`Inputs`: chat prompt, dialog, sign, anvil, confirm window) started while a screen is open steps the
  screen aside until it is answered, then brings it back as it was. If the answer opened a window, the screen
  closes instead.
- Any other window opened over a screen (a chest menu that stays a window, another plugin's inventory) closes it.
- `player.closeInventory()` closes the screen as it would the chest (1.509.0): an action that closes the menu before
  a game, a teleport or a prompt takes over needs no change for the SCREEN style. Only a close with no window open
  counts (a real window closing is not the screen), and never while the screen stepped aside for an input. Since
  1.514.0 only a `closeInventory()` made by a click written for a chest closes it: a chest-skin card handed to its
  menu, or a plugin action (`plugin:action`) a screen calls. One from anywhere else (a screen's own Java binding,
  a party update refreshing lobby items, a state change) leaves the screen up. Since 1.517.0 that close happens when the click ends, and not at all when the click went on to ask something
  (`closeInventory()` then `inputs().confirm(...)`, as chest code does): the screen steps aside for the question and
  comes back after it. A confirm or choice for a player on screens is drawn as a screen dialog, and a number or amount as a keypad (1.607.0); a choice of more than
  twelve answers pages with A/D. Before 1.509.0 such an action left the screen up; `UiSession#close` closes
  either style in every version.

## Anticheats (1.564.0)

A screen reads like a cheat to an anticheat: the cursor is the player's own
rotation (fast, precise turns), clicks land on a packet-only interaction entity
at the eyes with the arm swing dropped, and the camera is turned back with
small teleports. Kill aura, aim and bad-packet checks flag that.

The library cancels every flag of **GrimAC, Vulcan, Spartan and Matrix** on a
player while a screen is open, while a camera holds them (cinematics,
replays), and for 3 seconds after either ends. Nothing to configure. Nobody
can fight from behind a screen (the world is sealed and other players
hidden), so no real flag is lost. The same four are listed in
`docs/replays.md`, *Anticheat flags*; a cancelled flag is not recorded there
either.

Any other anticheat (Polar, Intave, NoCheatPlus...) is not covered yet: exempt
the checks that flag in its own config.

## Trying it

`/exylialib screentest open [spectator|wheel]` (permission `exylialib.admin`) opens a demo built with
`Node`; `/exylialib screentest markup [spectator|wheel]` opens the same kind of screen from
`screens/demo.html` and `screens/demo.css` in the library jar.

## A game table on one screen

Since 1.598.0 a shared table game (roulette first, ExyliaCasino `screens/roulette_table.html`) is one screen with
everything on show: no tabs, the rules in a modal. The kit in `components/library.html`:

| Part | What it draws |
| --- | --- |
| `<table-head title="..." subtitle="..." rules="table_rules"/>` | the big centred title and subtitle, a RULES button at the right that toggles the state `rules`, a divider |
| `<table-rules title="..." rules="table_rules">...</table-rules>` | the rules modal; clicking outside closes it |
| `<table-panel title="...">...</table-panel>` | a framed block with a small label |
| `<kicker label="SINGLE ZERO">...</kicker>` | a small label over a big value |
| `<results-strip source="last" empty="No spins yet"/>` | the last results as coloured tiles; rows give `result_label` and `result_tone` (`red`, `black`, `green`) |
| `<chip-rack source="chips" state="chip"/>` | round chips; rows give `chip_id`, `chip_colour` (a palette colour) and `chip_label`; a click sets `state` to the chip's id and the picked one is ringed in `{highlight}`; `custom="<action>"` (1.601.0) adds a CUSTOM chip at the end whose click runs that action (ask the amount with `session.aside(() -> inputs.amount(...).defaultValue(last).open())`, then `session.set(state, "custom")`), and `custom_label` is what it reads once set |
| `<table-action action="leave" tone="error">...</table-action>` | the big footer button |

A chip is a picture: `<img chip="{error}" size="18" ring="{highlight}">` (4 displays, any size up to 96).
`net.exylia.lib.screen.kit.ScreenDiscs` makes the same chip, and a wheel of coloured segments, as pixels for a scene
sprite (`sprite.image(size, size, ScreenDiscs.wheel(size, colours, "{highlight}", "#1a1424", 0.55))`).

**A hand of cards (1.602.0).** `net.exylia.lib.screen.kit.ScreenHand` lays a hand across a strip of a scene: side by
side while it fits, overlapping more as it grows, so one card or thirty span the same strip and each keeps a part
the cursor can hit. `new ScreenHand(left, width, cardWidth)`, then `x(index, count)` for each card's left edge (give
each card `layer(index)` so the later one lies on top). `ScreenHand.lifting(node, 6)` makes the hovered card rise
without moving where it is clicked. A rival's hand is `ScreenHand.backs(count, () -> back, w, h, 5)`: a small fan
of backs, so only the count reaches that client. ExyliaTabletop's cards and poker are the reference.

