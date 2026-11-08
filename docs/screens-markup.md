# Screen markup reference

Every tag, attribute, CSS property, expression function and built-in action the screen markup accepts. The API
and the long explanations stay in [Screens](screens.md); recipes are in [Cookbook](screens-cookbook.md), numbers
in [Performance](screens-performance.md).

The lists below come from `Compile.TAGS`, `Compile.ATTRIBUTES` and `Css.PROPERTIES`. Anything else is a problem
(`unknown tag`, `unknown attribute`, `unknown property`) reported once with its `file:line:col`; `data-*`
attributes and `--custom` properties are always allowed.

**Cost** is what one element costs at render time. A "display" is one packet text/item display entity on the
viewer's client; a screen may draw 400 ([budget](screens-performance.md#budget-and-detail)). Layout-only
elements cost nothing themselves, but a box with a `background` or an `outline` is a display of its own.

## Tags

| Tag | What it does | Example | Since | Cost |
| --- | --- | --- | --- | --- |
| `screen` | The root: size, backdrop, wheel, keys, scene, parallax, routes | `<screen size="400x225">` | 1.485.0 | free (layout only); `backdrop`/`scene` see attributes |
| `panel` | Themed column | `<panel>...</panel>` | 1.485.0 | background: ~1 display |
| `column`, `row`, `box` | Plain boxes down, across, and a spacer / block of colour | `<row><box class="spacer"/></row>` | 1.485.0 | free (layout only); ~1 display if it has a background or outline |
| `text`, `h1`, `h2`, `b`, `small`, `p`, `span`, `label` | Text; an element with only text is one text box. `p` wraps (base theme `white-space: normal; max-width: 220px`, 1.496.0) | `<h1>Practice</h1>` | 1.485.0 | 1 text display per box (a wrapped line is part of it) |
| `button` | Themed box with an `action` | `<button action="close">Close</button>` | 1.485.0 | ~1 display (background) + 1 text |
| `tabs`, `tab` | Option group; a `tab value="x"` sets the group's `state` on click and gets `[selected]` | `<tabs state="tab" default="play">` | 1.485.0 | ~1 text per tab, plus its background |
| `chips`, `chip` | Same as tabs, styled as chips | `<chips state="cat" default="all">` | 1.485.0 | ~1 text per chip, plus its background |
| `grid` | Repeats its children over `source`; `columns`/`rows` make a window the wheel and a `pager` move | `<grid source="kits" columns="4" rows="2">` | 1.485.0 | children x rows in the window only (not the whole source) |
| `list` | Same, one column | `<list source="players">` | 1.485.0 | children x rows in the window only |
| `card` | Themed box, usually a grid cell | `<card action="queue %kit_id%">` | 1.485.0 | ~1 display + its children |
| `empty` | Shown by a repeating parent when the source has no rows (several may carry `if`); outside a repeat a centred note | `<empty>No kits</empty>` | 1.485.0 | 1 text display, only while shown |
| `toggle`, `checkbox` | On/off: `state="key"` flips local state, or `checked="%f%"` + `action`; `on`/`off` set the words | `<toggle checked="%open%" action="party_open" on="Open" off="Private"/>` | 1.485.0 | 1 text display |
| `pager` | `for="<source>"`: previous, `page / pages`, next. `keys="a d"` binds movement keys; `previous`/`next` change the arrows | `<pager for="kits" keys="a d"/>` | 1.485.0 (`keys` 1.492.0) | 3 text displays; a missing grid is a problem |
| `icon` | Item sprite by `material=` or text; `glyph=` stands in below 1.21.9 | `<icon>diamond_sword</icon>` | 1.485.0 | 1 display on 1.21.11+ (sprite glyph), otherwise 4 (pixel picture) |
| `item` | Real item model, flat, 16 px: `material=`, `head=`, `texture=` or `url=` | `<item head="%player_name%"/>` | 1.488.0 | like `icon`: 1 sprite glyph, else 4 pixel copies; heads/chests fall back to the 3D item (1) |
| `item3d` | Lit 3D model with glint; `spin` turns it (36 degrees a second, or `spin="72"`) | `<item3d material="diamond_sword" spin/>` | 1.499.0 | 1 item display; a spin is 1 transform a second (client interpolation) |
| `img` | Picture: `src="img/x.png"` (markup folder), `skin="<player>"` (body 16x32), `face="<player>"` (8x8, 1.490.0); `turn` (1.492.0), `wearing` (1.498.0) | `<img face="%player_name%"/>` | 1.489.0 | `src`/`face`: 4 displays whatever its size; flat `skin`: 4; `skin turn`: 96 displays (stands still at detail level 2) |
| `canvas` | Sprite layer the plugin moves from Java (`session.scene("id")`); size from CSS `width`/`height` | `<canvas id="table"/>` | 1.500.0 | the sprites on it: item 1, picture 4, rect 1-2 |
| `progress` | Bar: `value` of `max` (default 100), filled with CSS `color` | `<progress value="%xp%" max="100"/>` | 1.488.0 | 1-2 displays (filled part, rest) |
| `modal` | Dialog over everything while its `if` holds; `dismiss="<action>"` | `<modal if="confirm" dismiss="clear confirm">` | 1.488.0 | dimmer + its content; drawn first, so it survives a tight budget |
| `search` | Field showing `⌕ value`; click asks for text (screen stepped aside); `state`, `prompt`, `hint`, `max` | `<search state="q" prompt="Search a kit" max="16"/>` | 1.496.0 | ~2 text displays |
| `fragment` | Groups children without a box of its own | `<fragment if="x">...</fragment>` | 1.494.0 | free (layout only) |
| `for` | Repeats children per row of a source or a range, in place (no window/pager); `each="kit in kits"`, `each="i in 1..5"` (up to 256) | `<for each="i in 1..5"><box class="star"/></for>` | 1.494.0 | children x iterations |
| `switch`, `case`, `default` | `on="expr"`; `case value="a\|b"`; only the match is drawn | `<switch on="tab"><case value="play">...</case></switch>` | 1.494.0 | free (only the chosen branch costs) |
| `else` | Element form of `else`; must follow an element with `if` | `<else>...</else>` | 1.494.0 | free (layout only) |

`include`, `template` and `slot` are handled while compiling and are not drawn: see
[Components](screens.md#components).

## Attributes

Attributes any tag may carry (a template's own props are accepted on its uses too).

| Attribute | What it does | Example | Since | Cost |
| --- | --- | --- | --- | --- |
| `id`, `class` | For CSS; an `id` also keeps a box's display between renders. `class` may hold `${...}` | `class="card ${owned ? 'owned' : 'locked'}"` | 1.485.0 (dynamic class 1.503.0) | free; a stable `id`/`track` avoids resending |
| `if`, `else` | Expression gate; `else` (bare or with an `if`) follows an element with `if` | `if="tab == 'play'"` | 1.485.0 (expressions 1.494.0) | free; a hidden element draws nothing |
| `t`, `t-<attr>` | Words per language; `t` element holds only text | `t="play.title"`, `t-on="party.open"` | 1.494.0 | free (read at compile) |
| `track` | On `grid`/`list`/`chips`: row identity, so reordering glides instead of redrawing | `track="%kit_id%"` | 1.494.0 | cheaper updates: moved rows send transforms only |
| `key`, `keybind` | Movement key (`w a s d space`) clicks the box | `key="space"` | 1.492.0 (`keybind` 1.494.0) | free |
| `action` | Click. Steps split by `;` (see [Actions](#built-in-actions)) | `action="set tab duels"` | 1.485.0 | free |
| `action-right`, `action-shift`, `action-hover` | Right click; click with sprint held; once each time the cursor enters | `action-hover="set wear %kit_wearing%"` | 1.494.0 / 1.496.0 / 1.498.0 | free; `action-hover` re-renders on each pass |
| `require`, `deny` (+ `-right`, `-shift`) | Expression gating each click; `deny` runs when false (else a quiet refusal sound) | `require="balance() >= 250"` | 1.501.0 | free |
| `selected`, `disabled`, `checked` | CSS flags (bare or after placeholders); `disabled` refuses clicks | `disabled="${balance() < 250}"` | 1.485.0 (bare `disabled` 1.494.0) | free |
| `state`, `default` | What an option group remembers / shows before a pick (`tabs`, `chips`, `toggle`, `search`) | `state="tab" default="play"` | 1.485.0 | free |
| `persist` | `persist="player"` next to `state`: kept across sessions and restarts | `<tabs state="tab" default="play" persist="player">` | 1.503.0 | one player-data write per change |
| `source` | Repeat over a registered source (or the menu's rows of a routed screen) | `source="kits"` | 1.485.0 | rows in the window |
| `columns`, `rows` | Window of a `grid` (`columns x rows` cells) | `columns="4" rows="2"` | 1.485.0 | bounds the cells drawn |
| `each` | On `for`: `name in source` or `i in 1..10` | `each="kit in kits"` | 1.494.0 | iterations x children |
| `for` | On `pager`: the grid's `source` or `id` | `for="kits"` | 1.485.0 | - |
| `on`, `off` | Words of a `toggle` (on `switch`, `on` is the expression) | `on="Open" off="Private"` | 1.485.0 | - |
| `material`, `glyph`, `head`, `texture`, `url` | Item choice for `icon`/`item`/`item3d`; `glyph` is the sub-1.21.9 stand-in | `head="%player_name%"` | 1.488.0 | a skin lookup runs on an async task the first time |
| `row` | On `item`/`item3d` inside a repeat over `UiEntry` rows: the row's own item, as the chest menu draws it (template filled, banner patterns, dyes, a stored stack); `material=` etc. are the fallback | `<item row material="shield"/>` | 1.506.0 | the chest's item render, once a render per row shown |
| `src`, `skin`, `face`, `turn`, `wearing`, `spin` | Pictures and bodies, see tags `img` and `item3d` | `wearing="diamond_helmet diamond_sword"` | see tags | see tags |
| `max`, `value` | `progress` (`max` default 100); `max` on `search` is the longest text (default 32); `value` on options/`case` | `max="16"` | 1.485.0 | - |
| `prompt`, `hint` | Texts of a `search` | `prompt="Search a kit"` | 1.496.0 | - |
| `dismiss` | On `modal`: action on a click outside or Shift | `dismiss="clear confirm"` | 1.488.0 | - |
| `priority` | `priority="low"`: decoration, the first thing left out when over budget | `priority="low"` | 1.496.0 | frees displays when the screen is full |
| `refresh` | Redraw this often while shown (2 ticks at least; default every second) | `refresh="5t"` | 1.496.0 | re-renders every N ticks for that viewer: use sparingly |
| `tooltip` | Panel beside the box while hovered; lines separated by `<nl>` | `tooltip="%kit_name%<nl>{letters_black}%mode%"` | 1.495.0 | 3 displays, only while hovered |
| `routes` | On `screen`: chest menu ids this screen stands for, with state | `routes="player/queue_kit:tab=play"` | 1.495.0 | free |
| `command`, `aliases`, `permission` | On `screen`: read by the plugin that opens screens by command (ExyliaScreens) | `command="hub" aliases="lobby"` | 1.502.0 | free |

Attributes on `<screen>` only:

| Attribute | What it does | Example | Since | Cost |
| --- | --- | --- | --- | --- |
| `size` | Layout area `WxH` (default 400x225) | `size="400x225"` | 1.485.0 | free |
| `backdrop` | `{token}` or `linear-gradient(...)` behind everything, to the view edges | `backdrop="{primary}0e"` | 1.485.0 (gradient 1.497.0) | 1 display; a gradient is 1 per band (see [Backgrounds](screens.md#backgrounds-14970)) |
| `wheel` | `"true"` keeps the real game mode so the wheel scrolls (hand and crosshair show) | `wheel="true"` | 1.485.0 | free |
| `keys` | `"navigate"`: W A S D move the cursor between clickable boxes, Space clicks | `keys="navigate"` | 1.496.0 | free (keys are read only while held across a tick) |
| `scene`, `parallax` | Picture behind everything (`img/x.png`); drift against the cursor, 0 to 16 px | `scene="img/stars.png" parallax="2"` | 1.497.0 | scene: 4 displays, sent once; parallax: 4 transforms per cursor move |

`version` is in the attribute allow-list but nothing in the screen code reads it.

## CSS properties

Selectors: tag, `#id`, `.class`, `[selected]`/`[disabled]`/`[checked]`, `:hover` (`:focus` is the same), `:active`
(1.496.0), `:root`, `:first-child`, `:last-child`, `:only-child`, `:nth-child(odd|even|n)`, `:not(...)`,
`:selected`/`:disabled`/`:checked`, descendants, `a > b`, comma lists, `*`. Specificity then order; the base theme
first, then each sheet. `var(--x, fallback)` and `--x: value` inherit. `@keyframes name { 0% {...} to {...} }`
for `animation`.

| Property | What it does | Example | Since | Cost |
| --- | --- | --- | --- | --- |
| `flex-direction` | `row`, `column` (default) | `flex-direction: row;` | 1.485.0 | free |
| `display` | `none` hides the element | `display: none;` | 1.485.0 | removes its displays |
| `gap`, `width`, `height` | Pixels (`6px` or `6`) | `gap: 6px;` | 1.485.0 | free |
| `padding` | One to four pixel values | `padding: 4px 10px;` | 1.485.0 | free |
| `flex-grow` | Share of the room left | `flex-grow: 1;` | 1.485.0 | free |
| `align-items` | `start`, `center`, `end`, `stretch` | `align-items: center;` | 1.485.0 | free |
| `justify-content` | `start`, `center`, `end`, `space-between` | `justify-content: space-between;` | 1.485.0 | free |
| `background` | `{token}`, `{token}40`, `#rrggbb`, `#aarrggbb`, `none`, `linear-gradient(...)` | `background: {primary}20;` | 1.485.0 (gradient 1.497.0) | 1 display; gradient: 1 per 5 px band (2 to 12) |
| `background-image` | `url(img/x.png)`: cover-fit picture over the background colour | `background-image: url(img/banner.png);` | 1.497.0 | 4 displays |
| `outline`, `border` | `1px {primary}`: a line outside the box | `outline: 1px {success};` | 1.485.0 | ~1 display; a hover outline adds it only while hovered |
| `color` | Text colour (inherited); the fill of `progress` | `color: {letters};` | 1.485.0 | free |
| `font-size` | `14px` is 1.4 lines (inherited) | `font-size: 14px;` | 1.485.0 | free |
| `font-weight` | `bold` (inherited) | `font-weight: bold;` | 1.485.0 | free |
| `text-align` | `left`, `center`, `right` (inherited) | `text-align: center;` | 1.488.0 | free |
| `margin` | One to four pixel values, outside the box | `margin: 4px 0;` | 1.496.0 | free |
| `position`, `top`, `right`, `bottom`, `left` | `position: absolute` pins to the parent's edges, out of the flow | `position: absolute; top: 4px; right: 4px;` | 1.496.0 | free |
| `z-index` | -4 to 8 | `z-index: 2;` | 1.496.0 | free |
| `max-width` | On text: wraps onto lines at most that wide | `max-width: 220px;` | 1.496.0 | text stays 1 display |
| `white-space` | `normal` wraps, `nowrap` keeps one line (inherited) | `white-space: normal;` | 1.496.0 | free |
| `text-shadow` | `none` or anything else for the vanilla shadow (inherited) | `text-shadow: 1px;` | 1.496.0 | free |
| `transform` | `translate(x, y)`, `translateX`, `translateY`, `scale(s)`; also in `:hover`/`:active` | `transform: translateY(-2px);` | 1.496.0 | client interpolation, no packets per frame |
| `transition` | `[transform\|background\|all] <duration> [easing]` | `transition: transform 3t ease-out;` | 1.496.0 | client interpolation: 2 to 4 packets per glide |
| `enter`, `leave` | Arrival / departure tween; `none` turns the built-in off | `enter: scale(0.9) 5t back;` | 1.496.0 | a few frames per arrival (test: under 60 packets) |
| `animation` | `<keyframes> <duration> [easing] [infinite]`; transforms only | `animation: pulse 40t ease-in-out infinite;` | 1.496.0 | re-sampled every 2 to 10 ticks while shown; capped by `motion-ceiling` |

Durations: `4t` or `4` ticks, `200ms`, `0.3s`. Easings: `linear`, `ease-in`, `ease-out` (`ease`), `ease-in-out`,
`back`. Palette tokens (`{primary}`) go inside values, never hex in shipped files.

## Expressions and functions

Detail in [Expressions](screens.md#expressions-14940). Functions: `min`, `max`, `abs`, `round`, `floor`, `ceil`,
`len`, `upper`, `lower`, `plural(n, 'one', 'many')`, `t('key', 'fallback')`, `fmt.number`, `fmt.percent`,
`fmt.time`; about the viewer (1.501.0): `perm('node')`, `balance()`, `balance('points')`, `has_item('diamond', 16)`,
`level()`, `world()`. Cost: free; evaluated on the viewer's thread each render, so keep them to memory.

## Built-in actions

Detail in [`action`](screens.md#attributes-on-any-tag) and
[Commands, messages and requirements](screens.md#commands-messages-and-requirements-15010).

| Action | What it does | Since |
| --- | --- | --- |
| `close` | Closes the screen | 1.485.0 |
| `set <key> <value>`, `toggle <key>`, `clear <key> ...` | Session state | 1.485.0 |
| `toast <text>` / `toast <success\|error\|info\|warning> <text>` | Short note at the top | 1.488.0 / 1.496.0 |
| `back` | As Shift: the dialog, then the screen before; closes at the root | 1.496.0 |
| `sound <key> [volume] [pitch]` | A UI sound to the viewer | 1.496.0 |
| `confirm '<question>' [dangerous] [yes=..] [no=..] then <actions>` | In-screen dialog; what follows `then` runs only on yes | 1.496.0 |
| `input text <key> '<prompt>' [hint=..] [max=n] then <actions>` | Asks for a line of text into state `<key>` | 1.496.0 |
| `[console]`, `[player]`, `[MONEY]`, `[MESSAGE]`, `[TITLE]`, `[SERVER]`, `[TELEPORT]`, `[ACTION]`, `[DELAY]` | [Interaction lines](interactions.md) | 1.501.0 |
| `name args` | The markup's own `action(...)`, then a namespaced ExyliaLib action (`practice:join_queue boxing`) | 1.485.0 |

Actions cost no displays; a handler redraws the screen once (coalesced).
