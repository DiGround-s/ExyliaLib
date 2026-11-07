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

When the children of a row or column need more room than there is, the ones that grow give it back in
proportion to their size, as `flex-shrink` does; fixed boxes and text keep theirs (1.488.0).

**Motion (1.488.0).** The screen zooms in as it opens, a dialog pops out from the centre, and a box that
appears after a click slides up into place. A clickable box under the cursor rises a pixel, and a box that
moves or resizes glides there. All of it is client-side interpolation, no extra packets per frame.
`ScreenSession#toast(text)` shows a short note at the top for three seconds.

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
- **Speed:** `Screens.sensitivity(player, value)` from 0.2 to 3 (1 by default), kept until they quit and
  applied at once.
- **Clicks:** left and right, through an invisible interaction box around the eyes, so a click never has the
  vanilla miss cooldown and never reaches the world.
- **Wheel:** off by default. The client is then shown as a spectator: no hand, no crosshair, no hotbar, but a
  spectator's wheel sends nothing. `Screen#wheel(true)` keeps the real mode so the wheel scrolls, at the cost
  of the hand and crosshair. Page lists with buttons when the wheel is off.
- **Shift** closes. So does damage, death, a world change, a teleport of more than a block (1.485.1), quitting, the opening plugin being disabled, and
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
| `screen` | The root. `size="WxH"`, `backdrop="{token}"`, `wheel="true"` |
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
| `img` (1.489.0) | A picture with no resource pack: `src="img/badge.png"`, read from the markup's folder (never outside it), or `skin="<player>"`, the player's whole body from the front (16 × 32, drawn once their skin has downloaded); `face="<player>"` (1.490.0) is their face with its outer layer, 8 × 8, sharper than an `<item head>` in a list. Placeholders work in both. `turn` on a `skin` picture (1.492.0) stands the body in the round, every part's four sides from the real skin, turning slowly; it is the flat picture until the skin is known. Clear pixels of a picture are see-through (1.492.0) |
| `progress` (1.488.0) | `value` and `max`: a bar, filled with the CSS `color` |
| `modal` (1.488.0) | A dialog over everything while its `if` holds; `dismiss="<action>"` runs on a click outside or Shift, `clear <key>` by default when the `if` is a plain name |

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

Selectors: tag, `#id`, `.class`, `[selected]`/`[disabled]`/`[checked]`, `:hover`, compounds, descendants and
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

## Trying it

`/exylialib screentest open [spectator|wheel]` (permission `exylialib.admin`) opens a demo built with
`Node`; `/exylialib screentest markup [spectator|wheel]` opens the same kind of screen from
`screens/demo.html` and `screens/demo.css` in the library jar.
