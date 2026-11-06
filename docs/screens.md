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

While a screen is open the player is frozen in place (`Packets#movement`).

## Threads and lifecycle

`ScreenSession` is safe from any thread. Handlers and `onClose` run on the viewer's thread; `onClose` only
while the player is online and the plugin enabled. Nothing survives the plugin: its screens close on disable.
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
an action or source that is not registered), each once, with its file and line. Only markup that is not
well-formed throws, when it is read.

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
| `empty` | Shown by its repeating parent when the source has no rows |
| `toggle`, `checkbox` | On/off: `state="key"` flips local state, or `checked="%field%"` with an `action`; `on`/`off` change the toggle's words |
| `pager` | `for="<source>"`: previous, `page / pages`, next |
| `icon` | An item sprite by material (`<icon>diamond_sword</icon>` or `material=`), `glyph=` below 1.21.9 |

### Attributes on any tag

- `id`, `class`: for CSS; an `id` also keeps a box's display between renders.
- `if="tab == 'play'"`: `name`, `!name`, `name == 'x'`, `name != 'x'`. Names are row fields, values, then state.
- `action="name arg ..."`: built-ins `close`, `set <key> <value>`, `toggle <key>`; then the markup's own
  `action(...)`; then a namespaced ExyliaLib action (`practice:join_queue boxing`).
- `selected`, `disabled`, `checked`: present (or true after placeholders) adds that CSS flag. A
  `disabled` box refuses clicks with a quiet villager sound.
- `state` + `default`: what an option group remembers, and what it shows before the player picks.
- `%name%` anywhere: a row field inside a repeat, a registered `value`, or session state; left as written when
  none of them knows it.

### CSS subset

Selectors: tag, `#id`, `.class`, `[selected]`/`[disabled]`/`[checked]`, `:hover`, compounds, descendants and
comma lists, resolved by specificity then order. The base theme comes first, then each sheet given.

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

Shipped files use palette tokens, never hex, so `colors.yml` restyles every screen.

## Trying it

`/exylialib screentest open [spectator|wheel]` (permission `exylialib.admin`) opens a demo built with
`Node`; `/exylialib screentest markup [spectator|wheel]` opens the same kind of screen from
`screens/demo.html` and `screens/demo.css` in the library jar.
