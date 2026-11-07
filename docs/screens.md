# Screens

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
| `background`, `hover` | `{token}`, `#rrggbb` or `#aarrggbb`; `hover` also makes the box pointable |
| `onClick`, `onScroll` | handlers, on the viewer's thread; the screen redraws after them |
| `item(ItemStack)` (1.488.0) | the item's own model, flat, 16 px by default; a hovered one grows a little |
| `overlay(true)`, `onDismiss` (1.488.0) | a dialog: left out of the flow, centred over a dimmer; only it takes the pointer, and a click outside it or Shift runs `onDismiss` |
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
`ScreenSession#toast(text)` shows a short note at the top for three seconds. Since 1.496.0 every one of these can
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

**Other players** are hidden from the viewer while a screen is open (only by this plugin, so other plugins'
visibility rules stay), so a crowded spawn never stands in front of it.

A click goes to the topmost box under the cursor that has `onClick`; a wheel step to the topmost that has
`onScroll`, so a list's rows can take clicks while the list itself scrolls.

## Input

- **Cursor:** the player's mouse. Their view does not move; the turn between rotation packets moves the
  cursor, gliding one tick so it reads smooth. The client sends rotation at most 20 times a second, so the
  cursor trails the real mouse by about 50 ms; that is the floor for any server-drawn cursor.
- **Speed:** `Screens.sensitivity(player, value)` from 0.2 to 3 (1 by default), applied at once. Since 1.496.0
  it is kept in the player's data across restarts, and players set it themselves in `/settings` → Interface
  ("Cursor speed"). `cursor-acceleration` in `screens.yml` (0 by default, 1 is mild) carries a fast mouse
  further while a slow one stays precise.
- **Hover** (1.496.0): the box under the cursor keeps its hover 3 pixels past its edges, unless the cursor is
  inside a box nested in it, so a hover never flickers on a border.
- **Clicks:** left and right, through an invisible interaction box around the eyes, so a click never has the
  vanilla miss cooldown and never reaches the world.
- **Wheel:** off by default. The client is then shown as a spectator: no hand, no crosshair, no hotbar, but a
  spectator's wheel sends nothing. `Screen#wheel(true)` keeps the real mode so the wheel scrolls, at the cost
  of the hand and crosshair. Page lists with buttons when the wheel is off.
- **Shift** steps back (1.496.0): it closes the open in-screen confirm or dialog, then goes back to the screen
  or menu this one was opened from (`Screen#onBack`), and closes only at the root. Held for 12 ticks it closes
  from anywhere. Before 1.496.0 Shift always closed.
- **Shift-click** (1.496.0): Shift itself steps back, so a click with **sprint held** (Ctrl, or the sprint key)
  is the shift-click: `action-shift` in markup, `Node#onShiftClick` in Java, and `ScreenEvent#shift()` on any
  click; a chest menu drawn as a screen gets `SHIFT_LEFT`/`SHIFT_RIGHT`. Tell players in a key hint
  (`<key-hint key="Ctrl">Shift-click</key-hint>`).
- **Focus navigation** (1.496.0): `<screen keys="navigate">` (`Screen#navigate(true)`) makes W, A, S, D move the
  cursor to the nearest clickable box that way and Space click it, so a screen works without the mouse.
  `:focus` styles the focused box (it is the hovered one). Bindings with `key=` are ignored while navigating.
  Since 1.497.0 a key only moves to boxes that lie that way (within about 60 degrees), never to a far box barely
  past the cursor. The client sends a key only when it is held across a tick: a tap shorter than that is missed.
- Damage closes it, as do death, a world change, a teleport of more than a block (1.485.1), quitting, the opening plugin being disabled, and
  another screen opening for the same player (one per player across all plugins).

- **Keys** (1.492.0): W, A, S, D and Space click the box bound to them (`key=`, `pager keys=`). Show the bindings in
  the screen itself: a short row of key hints under the content is the house style.

While a screen is open the player is frozen in place (`Packets#movement`), and everything else in the world is
out of their sight (1.492.0, `Visibility#seal` around the screen): other players, their name tags, mobs and other
plugins' holograms never show through or at its edges. It all comes back on close or while an input is aside.

## How things are drawn (1.492.0)

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
| `item` (1.488.0) | A real item model, no resource pack: `material="diamond_sword"`, `head="<player>"`, `texture="<base64>"` or `url="<skin url>"` |
| `canvas` (1.500.0) | `id="table"`: a sprite layer the plugin moves from Java (`session.scene("table")`), sized by CSS `width`/`height`. See [Scenes and games](#scenes-and-games-15000) |
| `item3d` (1.499.0) | The item's real 3D model, lit, with enchantment glint: `material=`/`head=`/`texture=`/`url=` as `<item>`; `spin` turns it slowly about its upright (36° a second, or `spin="72"`). See [3D items](#3d-items-14990) |
| `img` (1.489.0) | A picture with no resource pack: `src="img/badge.png"`, read from the markup's folder (never outside it), or `skin="<player>"`, the player's whole body from the front (16 × 32, drawn once their skin has downloaded); `face="<player>"` (1.490.0) is their face with its outer layer, 8 × 8, sharper than an `<item head>` in a list. Placeholders work in both. `turn` on a `skin` picture (1.492.0) stands the body in the round, every part's four sides from the real skin, turning slowly; it is the flat picture until the skin is known. Clear pixels of a picture are see-through (1.492.0) ; `wearing="..."` (1.498.0) dresses the body, flat or turning: see [Bodies wearing a kit](#bodies-wearing-a-kit-14980) |
| `progress` (1.488.0) | `value` and `max`: a bar, filled with the CSS `color` |
| `modal` (1.488.0) | A dialog over everything while its `if` holds; `dismiss="<action>"` runs on a click outside or Shift, `clear <key>` by default when the `if` is a plain name |
| `search` (1.496.0) | `state="q" prompt="Search a kit" hint="..." max="16"`: a field showing what was searched (`⌕ value`) that asks for a new search on click, the screen stepped aside meanwhile, with a ✘ to clear it while set; filter a source with `%q%` |
| `p` (1.496.0) | Wraps onto lines (base theme: `white-space: normal; max-width: 220px`) |

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
- `action-hover="..."` (1.498.0): runs once each time the cursor comes onto the box (not again until it
  leaves): `action-hover="set wear %kit_wearing%"` shows what a card is about elsewhere on the screen. Any action
  works, but keep it to state: it runs on every pass of the cursor. `Node#onHover` in Java.
- More built-in actions (1.496.0): `back` (as Shift: the dialog, then the screen before; closes at the root),
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
| `background` | `{token}`, `{token}40` (hex alpha), `#rrggbb`, `#aarrggbb`, `none`; a see-through colour is mixed over its container's and sent opaque (1.485.2) |
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
- `animation: <keyframes> <duration> [easing] [infinite]`: transforms only, sampled every 2 to 10 ticks.
- The base theme has the house motion: cards lift, buttons, tabs and chips press, dialogs pop and fade out.

**Budget.** A screen may draw 400 displays. Over that it lowers its detail in order: `priority="low"` boxes are
left out, turning bodies stand still, item sprites become 3D items (one display rather than four); only then is
the tail cut, with one warning. `screens.yml` `motion-ceiling` (300) caps motion packets a viewer gets each
second: past it glides go straight and animations wait.

**Reduced motion.** `screens.yml` `motion: REDUCED` for everyone, or a player's own toggle in `/settings` →
Interface ("Reduced motion"), `Screens.reducedMotion(player, true)` in Java: no arrivals, departures or
animations, and every glide is two ticks and straight.

**Toasts and confirms.** `session.toast("success", "Joined the queue")`: up to three stack at the top, newest at
the bottom, each sliding in and out; success and error play a quiet sound. `session.confirm("Leave the party?",
true, "Leave", null, s -> parties.leave(s.viewer()))` draws the question in a dialog on the screen itself; Cancel,
a click outside or Shift does nothing. `session.ask(prompt, hint, max, current)` asks for a line of text and
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
second on the wire). Blocks are drawn a quarter larger so blocks and items fill their box alike.

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
| `<key-hint key="Shift">Back</key-hint>` | a key and what it does |
| `<empty-state title="No kits" hint="Ask staff to add one"/>` | a list's empty note (inside a `grid`/`list` since 1.497.0: before, it was repeated as a row) |
| `<dialog title="Sure?" when="confirm" dismiss="clear confirm">...</dialog>` | a themed `modal` |

**Shared bindings.** `screens.bindings().value(...).action(...)` registers for every screen of a `ScreenSet`
(`ScreenBindings`, same methods as `ScreenMarkup`); a screen's own registration of a name wins.

## Chest windows or screens: menu style (1.495.0)

Every plugin's chest menus can open as screens with no code of its own. Which one a player gets is a
`MenuStyle` (`net.exylia.lib.ui`): `CLASSIC` (chest windows, the default) or `SCREEN`. `Menus.of(plugin).open*`
asks `MenuStyles` on every open; in `SCREEN` the chest menu still runs as always (rows, context, actions, pages,
redraws, `UiKeys.SESSION`), but its window is never opened and a screen draws it instead:

- **A screen routed to that menu id** (`<screen routes="...">`, below) when the plugin has one;
- otherwise **the chest skin**: the slots as cards in the chest's own layout, each item's name and lore as a
  tooltip while hovered, the title on top, glass panes drawn as the empty part of the card. A left or right click
  on a card is that click on the slot of the chest menu, so every action works as in the window, and a menu
  opened from a click replaces the cards in place. Shift goes back to the menu before, and closes at the first
  one (1.496.0); a click with sprint held is a shift-click on the slot.

Menus that stay windows whatever the style: those with input slots (the player puts items in them) and those
that are not chests (hopper, dispenser...). A client that cannot draw screens always gets windows.

### Who decides

The first rule that answers wins:

| # | Rule | Where |
|---|---|---|
| 1 | A client that cannot draw screens (Bedrock, older than 1.20.2, no PacketEvents) gets `CLASSIC` | `MenuStyles.canShowScreens(player)` |
| 2 | The player's own choice, when the server lets players choose | `/settings` → Interface → Menu style, kept in the player's data (no database) |
| 3 | The plugin's own setting; `DEFAULT` passes | `MenuStyles.of(plugin).defaultStyle(() -> settings.menuStyle())` |
| 4 | The server's choice for that plugin | `plugins/ExyliaLib/screens.yml` `plugins: {ExyliaJobs: SCREEN}` |
| 5 | The server's default, `CLASSIC` as shipped | `screens.yml` `default-style` |

`screens.yml` (`ScreenSettings`, editable in game with `/exylialib config`):

```yaml
default-style: CLASSIC     # CLASSIC or SCREEN, for every plugin that does not choose
player-choice: true        # players may pick their own in /settings
plugins: {}                # ExyliaJobs: SCREEN
chest-skin: true           # off: menus without a screen of their own stay windows in SCREEN style
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
- `source="<section>"` / `<for each="x in <section>">` reads **the menu's rows** of that section (`main`, or the
  section ids of a `sections:` menu) when no binding of the screen has that name, refreshed as the plugin refreshes
  them (`entries(...)`, live suppliers).
- `ns:id args` actions run with `UiKeys.SESSION` (the chest menu behind the screen), `UiKeys.MENU` and
  `UiKeys.ENTRY` (the clicked row's object), exactly as from a chest, so the YAML's action strings work unchanged.
- Bindings registered on the screen (`value`, `text`, `rows`, `action`) are read **once a render** however many rows
  ask for them.

### Tooltips

`Node#tooltip(lines...)` (Java) and `tooltip="..."` (markup, lines separated by `<nl>`) show a small panel beside a
box while the cursor is over it, the first line as its title, as an item's lore shows over a slot. It is drawn
over everything, a dialog included, to the right of the box or to its left near the edge. Cost: two displays plus
one per line, only while hovered; moving between boxes sends only what changed.

```html
<card action="practice:select %kit_id%" tooltip="%kit_name%<nl>{letters_black}%kit_mode%">...</card>
```

### Windows and inputs over a screen

- An input (`Inputs`: chat prompt, dialog, sign, anvil, confirm window) started while a screen is open steps the
  screen aside until it is answered, then brings it back as it was. If the answer opened a window, the screen
  closes instead.
- Any other window opened over a screen (a chest menu that stays a window, another plugin's inventory) closes it.

## Trying it

`/exylialib screentest open [spectator|wheel]` (permission `exylialib.admin`) opens a demo built with
`Node`; `/exylialib screentest markup [spectator|wheel]` opens the same kind of screen from
`screens/demo.html` and `screens/demo.css` in the library jar.
