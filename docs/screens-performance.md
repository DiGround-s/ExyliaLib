# Screen performance

What a screen costs the server and the viewer, and how to keep it low. API in [Screens](screens.md), element costs
in the [markup reference](screens-markup.md), recipes in the [cookbook](screens-cookbook.md).

A screen is a set of packet-only displays seen by one viewer. The server cost is the render (markup walk, CSS,
layout, diff) on the viewer's thread; the network cost is what the diff sends; the client cost is how many displays
it lays out.

## Budget and detail

- **Flat budget: 400 displays per screen** (`LiveScreen.BUDGET`). Each box with a background, each text, each
  picture copy is one. The cursor (4 copies) is part of the screen. There is no weighted budget: a turning body
  (96) and a text (1) are counted by the displays they actually produce.
- **Detail ladder.** When a layout wants more than 400, the screen re-computes it at a lower detail, up to three
  times, and stops at the first level that fits:

| Level | Drops |
| --- | --- |
| 1 | `priority="low"` boxes are left out |
| 2 | turning bodies (`<img skin turn>`) stand still as the flat 4-display picture |
| 3 | item sprites drawn as pixel pictures (4 displays) become the 3D item (1 display) |

- **Past level 3** the tail of the draw order is cut and one warning is logged
  (`A screen drew more than 400 displays at its lowest detail`). A dialog is drawn first, so it survives and the
  page loses its last boxes.
- **Motion ceiling.** `screens.yml` `motion-ceiling` (300) caps the motion packets one screen sends a second; past
  it eased glides go straight and animations wait where they are. `motion: REDUCED` (server or per player) removes
  arrivals, departures and animations and makes every glide two ticks.
- 1.21.11+ client and server (`sprite-objects: true`, default): an item or `<icon>` is one sprite glyph instead of
  four pixel copies.

## Diffed or resent

| Thing | What goes out |
| --- | --- |
| Unchanged tree (same text, pixels, sizes, colours, ids) | nothing: layout and diff are skipped, only handlers are replaced |
| A changed field of a display | that field only; the wire remembers the last value of every field |
| Cursor move | a transform per arrow copy (about 25 bytes a display) |
| Hover | the box that changed, never the cursor |
| Same `id` / `track` | the display is kept and glides to its new place |
| New or removed box | spawn with full metadata, or destroy (a leaving box after its `leave`) |
| Whole frame | one bundle, one flush: the client never draws half of it |
| Picture, scene, gradient | sent once on open; later only transforms |

## Coalescing

`set(...)` and `redraw()` mark the screen dirty; a render runs at most once a tick, and the ones a handler makes
render once. The poll is one second by default; `refresh="5t"` (2 ticks at least) on an element, or
`Screens.of(plugin).redrawAll(...)` for a queue that changed, are the only reasons to go faster.

## Caches

| Cache | Bound |
| --- | --- |
| Computed CSS styles | 4096 entries |
| Expression/text parse (`Interp`) | 20,000 texts |
| Picture files | 256, 30 s after write |
| Bodies / dressed bodies / skins | 512 / 512 / 256, 10 to 30 min after last use |
| Pixels turned into glyph components | 262,144 pixels of weight, shared by every screen |
| Items as packet items | 512 |
| Item sprites | read once from the client jar into `plugins/ExyliaLib/cache/sprites-<version>-2`, on an async task |

A sprite, skin or picture seen for the first time is loaded off the viewer's thread; the slot is empty (or a
placeholder) for that moment and the screen redraws on arrival.

## Wire numbers

Asserted in `ScreenWireTest` (bytes are estimated per field, enough to compare frames):

| Event | Asserted | Test method |
| --- | --- | --- |
| Open (panel, h1, two buttons, text) | more than 10 packets | `framesSendOnlyWhatChanged` |
| 40 ticks with nothing changed | 0 packets, 0 raw packets (diff skipped) | `framesSendOnlyWhatChanged` |
| Cursor onto a button | under 200 bytes | `framesSendOnlyWhatChanged` |
| Hover drawn | at least 1 packet, under 1024 bytes | `framesSendOnlyWhatChanged` |
| Cursor move | 4 packets (one per arrow copy), metadata indices 8, 9, 11 only (translation) | `framesSendOnlyWhatChanged` |
| Move inside the same box | 0 packets | `framesSendOnlyWhatChanged` |
| `set` twice + `redraw` in one tick | 1 render | `framesSendOnlyWhatChanged` |
| One second of a pulsing badge | more than 0, at most 30 packets | `motionStaysWithinBudget` |
| Eased hover lift of one card | more than 0 packets, under 3000 bytes | `motionStaysWithinBudget` |
| A dialog arriving | more than 0, under 60 packets | `motionStaysWithinBudget` |
| A toast | more than 0, under 20 packets | `motionStaysWithinBudget` |
| Painted screen open (gradients, scene, picture) | under 120 packets, under 100,000 bytes | `backgroundsStayWithinBudget` |
| Painted screen idle | 0 packets | `backgroundsStayWithinBudget` |
| Cursor move with `parallax` | 8 packets (arrow 4 + scene 4), indices 8, 9, 11 | `backgroundsStayWithinBudget` |

Other figures stated in [Screens](screens.md) (not asserted in this test): a sparse 96 x 54 `scene` is about
40 KB on open and nothing after; a spinning `item3d` is one transform a second; a dressed body costs the same as
a bare one (96 displays turning).

## What each thing costs

| Thing | Displays |
| --- | --- |
| Text | 1 |
| Box with background or outline | ~1 |
| Picture (`img src/face/skin`, `background-image`, `scene`) | 4, whatever its size |
| Turning body | 96 |
| Item sprite | 1 (sprite glyph, 1.21.11+) or 4; 3D item 1 |
| Gradient | 1 per band: 5 px each, 2 to 12 |
| Tooltip | 2 + 1 per line, only while hovered |
| Canvas sprite | item 1, picture 4, rect 1-2 |

## Tips

Avoid:

- `refresh="1t"` or a render that reads a database: the render runs on the viewer's thread, so read memory.
- Pictures larger than needed (the limit is 96 a side; badges 16, logos up to 64): every pixel is a glyph the
  client lays out.
- Long lists with no window: a `grid` without `columns`/`rows` draws every row. Window it and add a `pager`.
- More than one `turn` body, or bodies in a list of players: use `<img face>` (4 displays) or `<item head>`.
- Hover actions that do work: `action-hover` runs on every pass of the cursor; set state only.
- Animations on many boxes at once: each is re-sampled every 2 to 10 ticks.
- Rows without a stable `id`/`track` in a list that reorders: every cell redraws.

Prefer:

| Instead of | Use |
| --- | --- |
| `<item3d>` in a list | `<item>` (flat sprite, never shaded) |
| Pixel pictures for icons | `<icon>` or `<item>` on 1.21.11+ (one sprite glyph) |
| `refresh="2t"` on a countdown | the default one-second poll, or `5t` |
| A big `scene` that drifts | a smaller scene, or no `parallax` (4 transforms per cursor move) |
| Many decorative boxes | `priority="low"` so they are dropped first when the screen is full |
| Looping animation everywhere | one `transition` (client interpolation) or a single `animation` on the focal box |
| Redrawing from a timer | `redrawAll(...)` when the data actually changes |
