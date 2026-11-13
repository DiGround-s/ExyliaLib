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
- **Lighter screens** (1.565.0). A player's own toggle in `/settings` → Interface ("Lighter screens") starts every
  screen at level 3 for them: fewer displays and picture glyphs for a weak graphics card, the same screen.
- **What a screen costs** (1.565.0). `Screens.of(plugin).cost(name)` gives the heaviest frame a screen named
  `name` (`Screen#name`; a markup screen is its root `id` or file name) drew for anyone since the start, as a
  `ScreenCost(displays, pictureGlyphs, lightened)`. `heavy()` is true past 300 displays or `ScreenCost.GLYPH_GUIDE`
  (16,000) picture glyphs; `lightened` says the ladder had to drop detail for a viewer who did not ask for it.
  ExyliaScreens lists heavy screens in `/screens check`. A picture's glyphs are its pixels times its four copies:
  a 64×64 `<img>` is 16,384, so displays alone undercount what the client lays out.
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
| One second of a shimmering title (60t) / ten rainbow lines (1.610.0) | at most 5 / at most 30 packets | `shimmerIsBounded` |
| Eased hover lift of one card | more than 0 packets, under 3000 bytes | `motionStaysWithinBudget` |
| A dialog arriving | more than 0, under 60 packets | `motionStaysWithinBudget` |
| A toast | more than 0, under 20 packets | `motionStaysWithinBudget` |
| Painted screen open (gradients, scene, picture) | under 120 packets, under 100,000 bytes | `backgroundsStayWithinBudget` |
| Painted screen idle | 0 packets | `backgroundsStayWithinBudget` |
| Cursor move with `parallax` | 8 packets (arrow 4 + scene 4), indices 8, 9, 11 | `backgroundsStayWithinBudget` |

Other figures stated in [Screens](screens.md) (not asserted in this test): a sparse 96 x 54 `scene` is about
40 KB on open and nothing after; a spinning `item3d` is one transform a second; a dressed body costs the same as
a bare one (96 displays turning).

## Games on a screen

`ScreenWireTest#aFullGameBoardStaysWithinBudget`: the game frame, 25 tiles, 24 gem pictures (the worst case: an item
sprite's four copies), on a real clock since the ceiling is per second, on the test harness.

| Moment | Displays | Packets | Bytes |
| --- | --- | --- | --- |
| Frame open | 53 | 106 | 18 KB |
| 25 tiles appear | 78 | 50 | 10 KB |
| One reveal (flip, gem, burst of 12) | 163 at most | 91 | 13 KB |
| Every gem shown | 151 | | |
| The dearest second: a blast shakes the board and 10 bursts of 10 run across it | 181 at most | 637 | 27 KB |

Within the budget of 400. The slowest tick took 10 to 16 ms on a cold JVM with no network. These are harness numbers,
not a client's: frame rate and stutter are not measured here. A burst is decoration, so 24 at once (288 displays on top
of the board) lose their shards to the detail ladder rather than the board. The shake is the dear part, every display
of the scene a transform per step, and it stops early past the ceiling. A shard counts against the ceiling (3 for its
spawn, first look and removal, 1 for its flight), so a burst asked for past it is left out; what already started
finishes, and the test's window runs past one wall second, which is why that row reads above 300 (1001 before shards
counted).

## What each thing costs

| Thing | Displays |
| --- | --- |
| Text | 1 |
| Box with background or outline | ~1 |
| Picture (`img src/face/skin`, `background-image`, `scene`) | 4, whatever its size |
| Turning body | 96 |
| Item sprite | 1 (sprite glyph, 1.21.11+) or 4; 3D item 1 |
| Gradient | 1 per band: 5 px each, 2 to 12 |
| Tooltip | 3 (panel, title, lore block), only while hovered |
| Canvas sprite | item 1, picture 4, rect 1-2 |

## Measured on a real client

ExyliaScreens' shipped screens, Paper 1.21.4, one viewer, vanilla 1.21.4 client, RTX 3050 at 1080p, frame rate
capped at 120 (it held 116-118 on every screen), so the GPU load is the figure that compares them. The world alone
was 7%.

| Screen | GPU | With Lighter screens |
| --- | --- | --- |
| `/play` (3 cards, 3 floating items) | 47% | 40% |
| `/rewards` (7 cards, 7 items, a pulse) | 64% | 40% |
| `/profile` (a turning body) | 56% | 40% |
| `/welcome` | 21% | |
| `/hub` | 28% | |
| `/shop` | 33% | |

Repeat it with `bot/screencam.py` on a test server: show the HUD (F1), open F3 with a held key, open each screen and
read the second F3 line after four seconds.

## Tips

Avoid:

- `refresh="1t"` or a render that reads a database: the render runs on the viewer's thread, so read memory.
- Pictures larger than needed (the limit is 96 a side; badges 16, logos up to 64): every pixel is a glyph the
  client lays out.
- An effect in a `<window>` heavier than the screen leaves room for: a screen and its stage share 560 displays (the
  screen alone keeps its 400); past that the effect plays with pieces missing, the screen stays whole, and the console
  says so once per plugin. Measured on ExyliaKillEffect's effects screen (Paper 1.21.4, one viewer, RTX 3050, 1080p):
  the screen draws about 205, leaving 355; the heaviest shipped effects peak at 316 (Ejected), 299 (Runway) and 269
  (To Be Continued), and the median at 74. About 100 fps with the screen alone, 71-83 at Ejected's peak of 520 in all.
  The rehearsal that fits an effect's first play is never sent. Particles are not displays and are not counted.
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
