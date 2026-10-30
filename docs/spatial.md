# Spatial

In-world 3D menus, and the pointer every in-world screen shares. Panels of tinted
glass stand round the player, lists and grids of icons on them, a carousel of
plinths along the bottom and a live preview turning in the middle: a wardrobe for
cosmetics. Looked at to aim, clicked to choose, scrolled to page, sneaked out of.
Packets only (nothing is placed in the world), no resource pack. Since 1.406.0.

Entry points: `net.exylia.lib.spatial.SpatialMenus` (menus) and
`net.exylia.lib.spatial.SpatialInput` (input, for any screen built on
[Stage](stage.md)).

## Using

```java
SpatialMenus menus = SpatialMenus.of(this);
menus.register("trims", menu -> menu
        .source("pieces", ctx -> pieces(ctx.viewer()))                  // List<SpatialEntry>, read on every redraw
        .source("materials", ctx -> materials(ctx.viewer()))
        .source("patterns", ctx -> patterns(ctx.viewer(), ctx.selected("pieces")))
        .preview(ctx -> SpatialPreview.npc(ctx.viewer(), trying(ctx))) // the player wearing what they try
        .onSelect("patterns", (ctx, entry) -> ctx.set("pattern", entry.name()))
        .action("apply-set", ctx -> { apply(ctx); ctx.refresh(); })
        .action("clear", ctx -> { clear(ctx); ctx.refresh(); }));

menus.open(player, "trims");                 // in front of the player
menus.open(player, "trims", "trims-1");      // at a spot an admin placed
menus.edit(admin, "trims");                  // move its pieces with the mouse wheel
menus.close(player);
```

The plugin ships `src/main/resources/spatial/trims.yml`. On `register` it is
written to `plugins/<Plugin>/spatial/trims.yml` when the server has none, then
read as a `SpatialLayout` record: keys the server's copy lacks are added with the
library's defaults, its values are never overwritten, and the web editor finds
the file by itself. A plugin that ships no file gets the library's wardrobe.
`Configs.reloadAll(plugin)` reloads layouts with the rest; the next open uses
them.

`SpatialEntry` is an immutable record: `id`, `icon` (an `ItemStack`) or `model`
(any `DisplayModel`), `name`, `subtitle`, `state` and an optional `price`, with
`of(id, icon, name)` and `with*` withers. Sources run on the viewer's thread on
every redraw: read memory, never a database. Equal entries are not redrawn.

`SpatialContext` is what every callback is handed: `viewer()`, `menu()`,
`get/set(key)` (the menu's own state, gone when it closes; every key is also a
`%key%` placeholder in TEXT lines), `page(source)` / `page(source, n)`,
`selected(source)` / `select(source, id)`, `refresh()` and `close()`.
`menus.context(player)` finds the open one from outside (a purchase that
finished).

## The layout

```yaml
title: '{primary}&lARMOR TRIMS'   # what a placed spot's hologram reads as %title%
anchor:
  mode: FRONT             # FRONT | PLACED (nearest spot within 8 blocks, else FRONT)
  distance: 3.4           # the board's distance from the eyes
  lift: 0.0               # its middle above eye height
hide-others: true
freeze: true
theme: {}                 # SpatialTheme; every key defaults to the library's look
panels:
  header:    {at: [0, 1.55, 0], size: [4.6, 0.34], title: '{primary}&lARMOR TRIMS', hint: '{muted}Sneak to leave'}
  pieces:    {at: [-2.05, 0.45, 0.45], turn: 24, size: [1.35, 1.55], title: '{secondary}&lPIECES',
              content: {type: LIST, source: pieces, rows: 4}, footer: {label: '{error}✘ CLEAR', action: clear}}
  materials: {at: [2.05, 0.45, 0.45], turn: -24, size: [1.35, 1.55], title: '{secondary}&lMATERIAL',
              content: {type: GRID, source: materials, columns: 3, rows: 4},
              footer: {label: '{highlight}&lAPPLY TO SET', action: apply-set}}
  focus:     {at: [0, 0.95, 0.2], size: [1.6, 0.5],
              content: {type: TEXT, lines: ['{letters}&l%pattern%', '{info}Editing %piece%']}}
preview:  {at: [0, -0.55, 0.6], turn-speed: 12, pedestal: true, scale: 1.0}
carousel: {source: patterns, at: [0, -1.05, 0.8], slots: 6, spacing: 0.62, centre-gap: 1.3, curve: 0.15}
```

Coordinates are blocks on the board, a plane `distance` ahead of the eyes:
`across` (right +), `up`, `forward` (towards the viewer +); points are
`[across, up, forward]`, sizes `[width, height]`. `turn` is how many degrees a
panel is turned towards the viewer round its own vertical axis: positive on the
left, negative on the right. The defaults keep everything inside a 70° vertical
view at 3.4 blocks.

## Components

| Where | What it draws |
| --- | --- |
| Panel | Glass, a darker backing sheet, the accent bar along its top, its `title` (bold, shadowed, fitted) and `hint`. A panel with `type: NONE` is a header: title in the middle, hint at its right end |
| `TEXT` | `lines`, centred; `%key%` from the menu's state and `values(...)`; rewritten in place when they read something else |
| `LIST` | `rows` rows of icon, name and state line; the row under the crosshair lights with a bar, the chosen one carries a `{highlight}` marker that glides between rows |
| `GRID` | `columns × rows` icons; an underline glides to the chosen one; the name of the hovered (else chosen) one reads under the grid |
| `BUTTON` | `label` on a plate that lights under the crosshair; runs `action` |
| `footer` | A button along any panel's bottom |
| `carousel` | `slots` plinths (a block display and a flat lit plate) each turned to the eyes, the item over it, its name and state line under it; `← PAGE x / y →` under the middle |
| `preview` | A plinth in the middle, and what `preview(ctx)` returns on it, turning `turn-speed` degrees a second |

Hovering grows an item ×`hover-grow` with a 120 ms glide and outlines it in
`hover-glow`; a plinth's plate lights in `plate-hover`, the chosen one's in
`plate-selected`. `LOCKED` entries are drawn dimmer and read the theme's
`locked-line` (or their `price`); `EQUIPPED` reads `equipped-line` and a grid
cell gets `equipped-marker`; `OWNED` reads its subtitle or `owned-line`.
`SELECTED` marks the entry chosen when the menu opens.

The mouse wheel pages the list or grid under the crosshair, or the carousel
anywhere else; the carousel's arrows page it too. Pages wrap round.

Previews: `SpatialPreview.npc(player)` (their skin, wearing what they wear),
`npc(player, equipment)` (re-dressed in place when only the equipment changes),
`item(stack)`, `sequence(sequence, skin)` (a mannequin with the effect played on
it again and again, for the viewer alone: a kill effect seen for real), `none()`.
An NPC lives two minutes at most; the menu brings a fresh one before that.

## Opening and closing

Panels pop in first, staggered by 70 ms, their content 180 ms after, the
preview at 300 ms and the carousel's plinths from 420 ms one by one. Closing
deflates everything in 170 ms. A `refresh()` pops nothing: only the slots whose
entries changed are drawn again.

While open: the player is held still (`freeze`, `Movement.freeze`; they can
look round and sneak), wears a hotbar of nothing but the leave item
([overlay](overlays.md), `hideRest`), and with `hide-others` sees nobody and is
seen by nobody. Sneaking or right-clicking the leave item closes it (there is
no ESC in the world, so the header's hint says so). One spatial menu per
player across every plugin: opening another closes the first.
`SpatialMenus.inMenu(player)` asks across plugins.

FRONT: the board is set square to the world (yaw in 45° steps) where the player
stands, the player turned to face it (through [Teleports](teleport.md), no
countdown), and brought nearer (never under 2 blocks) when a block stands in the
way.

## Theme

`SpatialTheme`, nested in every layout as `theme`, no-argument default the
library's look:

| Keys | Default |
| --- | --- |
| `glass-tint`, `glass-tint-strength`, `glass-alpha` | `{primary}` mixed into black by 0.16, alpha 208 |
| `backing-reach`, `backing-alpha` | 0.035 blocks larger each side, half as bright, alpha 236 |
| `accent`, `accent-height` | `{primary}`, 0.022 |
| `title-scale`, `text-scale`, `small-scale` | 0.5, 0.36, 0.27 (about 21-26 px at 3.4 blocks, 1080p) |
| `pedestal-block`, `plate-idle`/`-hover`/`-selected` | `POLISHED_BLACKSTONE`; `{primary}` darkened, `{secondary}`, `{highlight}` |
| `hover-glow`, `hover-grow`, `edit-outline` | `{highlight}`, 1.12, `{info}` |
| `locked-line`, `owned-line`, `equipped-line`, `equipped-marker` | `{error}✘ Locked`, `{muted}Owned`, `{success}✔ Equipped`, `{success}✔` |
| `page-line`, `previous-arrow`, `next-arrow`, `empty-line` | `{muted}PAGE {letters}%page% {letters_black}/ {letters}%pages%`, `←`, `→`, `{muted}Nothing here yet` |
| `spot-lines` | `%title%`, `{warning}➥ Click to open` |
| `leave-material`, `leave-name`, `leave-lore` | `BARRIER`, `{error}&lLEAVE`, the Exylia lore pattern |
| `hover-sound`, `select-sound`, `page-sound`, `open-sound`, `close-sound`, `error-sound` | note-block hat (soft), the menus' click, `item.book.page_turn`, the menus' open, `item.bundle.remove_one\|1.0\|0.5`, `entity.villager.no` |

Every colour is a palette token, read when a piece is drawn: a palette reload
restyles the next menu opened.

Glass, plates and bars are text-display backgrounds (any ARGB, palette-exact);
text in front of them has none. Only the glass is translucent, and nothing but
the world stands behind it: a translucent sheet in front of another display hides
it on clients up to 1.21.11 (what makes `Stage` bare its text). Plates, row bars,
buttons and markers are opaque for the same reason.

## Placement

```java
menus.spots().place("trims-1", "trims", admin.getLocation());   // where the player stands, facing the board
menus.spots().nearest(admin.getLocation(), 4).ifPresent(spot -> menus.spots().remove(spot.id()));
menus.spots().all(); menus.spots().get(id); menus.spots().nextId("trims");
```

Spots live in `plugins/<Plugin>/spatial/spots.yml` (built on
`PlacedTables`). Each shows a small clickable hologram 1.6 blocks ahead of it
(`spot-lines`); clicking it walks the player onto the spot (no countdown) and
opens the menu there, facing the way the spot faces. They stay where they are
when it closes. The holograms go up when a menu is registered and a spots file
exists, or on the first `spots()` call.

## Edit mode

`menus.edit(admin, id)` opens the menu with nothing clickable but its pieces:
each panel, the carousel and the preview is outlined in `edit-outline` while
aimed at. Clicking one hands the mouse wheel to the library's
[Tuner](tuner.md): the wheel moves it (0.05 a notch, sneak + wheel changes the
step), F changes the axis (ACROSS, UP, FORWARD, TURN, WIDTH, HEIGHT for a panel;
ACROSS, UP, FORWARD, SPACING, CURVE for the carousel; ACROSS, UP, FORWARD for the
preview), the boss bar reads `PIECE » axis » value`, and a click saves it to the
layout file (comments kept: the file is written from the record) and redraws.
Every notch redraws the menu as it would look. Sneak to leave.

## Input for games

A game view that keeps its own `hovered` index, its own button list and its own
click listener moves onto `SpatialPointer`:

```java
// Before: the view and its listener.
int hovered = -1;                                 // in the view
void tick() {                                     // every 2 ticks, in the round
    int target = view.target(view.plane().hit(viewer.getEyeLocation()));
    if (view.hover(target)) StageLook.play(viewer, sounds.hover());
}
void click(Player player) {                       // from HouseListener: swing + interact, deduplicated
    int target = view.target(view.plane().hit(player.getEyeLocation()));
    if (target >= 0) play(moves.get(target));
}

// After: nothing to poll, nothing to listen to.
SpatialPointer pointer = SpatialInput.of(plugin).attach(player)
        .sounds(sounds.hover(), "")                // the game plays its own press sounds
        .onEnd(end -> leave(player));              // quit, death, world change, disable
Overlays.of(plugin).show(player, SpatialInput.of(plugin).hotbar(leaveItem));
pointer.onLeave(() -> askToLeave(player));

void buttons(List<Move> moves) {                  // a new set of buttons for this move
    pointer.clear();
    for (Move move : moves) pointer.add(Hotspot.of(stage.button(label(move), ...)).onPress(click -> play(move)));
}
pointer.add(Hotspot.cells(boardPlane).onCell(view::highlight).onPress(click -> place(click.cell())));
pointer.enabled(false);                            // while the dealer plays: no hover, no clicks
pointer.detach();                                  // the round is over
```

- `SpatialInput.of(plugin).attach(player)` → `SpatialPointer`, one per player per
  plugin (a second replaces the first, `End.REPLACED`). Polled every 2 ticks on
  the player's thread. The newest pointer a player has takes their input; when it
  is detached the one under it gets it back.
- `Hotspot.rect(plane, across, up, width, height)`, `Hotspot.cells(plane)`
  (`StagePlane.pick`), `Hotspot.of(stageButton)` (lights and squashes by itself,
  plays its own sounds). Each takes `onHover(boolean)`, `onCell(int)`,
  `onPress(SpatialClick)`, `onScroll(int)`, `priority`, `enabled`, `silent`.
  Overlapping: higher priority, then nearer the eyes. Several planes per pointer.
- The pointer plays its hover sound once per change (entering a hotspot or a new
  cell) and its press sound on a press, unless the hotspot is silent.
- `SpatialClick(kind, sneaking, cell)`: LEFT is a swing (air, block or entity);
  RIGHT a use on a block or entity, or a right-click with the hotbar's leave item.
  A swing and the interaction it causes are one click (120 ms). A click on no
  hotspot goes to `onClick`.
- The wheel: `onScroll` of the hotspot under the crosshair, else the pointer's;
  -1 / +1 a notch. The slot follows the wheel (no notch lost at any ping) and is
  put back 450 ms after the last notch.
- `onSneak`, `onLeave` (the hotbar's leave item), `onTick` (every poll), `onEnd`
  (`QUIT`, `DEATH`, `WORLD`, `DISABLED`, `REPLACED`; not run for `detach()`),
  `mute(millis)`.
- While attached, using blocks, breaking and placing them and hitting entities is
  cancelled. Protection against damage, hunger, drops and commands stays the
  game's.
- `hotbar(item)` is the overlay to show with it: `hideRest`, no pickup, the item in
  slot 8; the overlay swallows swings over its slots and hands them back through
  the plugin's `<plugin>_spatial:click` / `:leave` actions, so clicks arrive the
  same with or without it.

## Contracts

- **Threads.** A pointer, its hotspots and a menu are used from the player's thread
  (their region on Folia); every callback runs there. `SpatialMenus`' public
  methods hop to it from anywhere.
- **Lifetime.** Every piece lives at most 30 minutes; closing takes everything down.
  Quit, death and world change close at once; the plugin's disable closes its
  menus and ends its pointers before its displays, NPCs and tuner sessions are
  released.
- **Visibility.** `hide-others` uses a rule installed under the library's own name,
  so a plugin's own `Visibility` rule is not replaced; every plugin's rules must
  still agree.
- **One per player.** One spatial menu per player, whichever plugin; pointers stack.
- **No PacketEvents.** Nothing is drawn (pieces come back `null`, every call accepts
  them); picking still works, it is server-side maths.
- **Reload.** Nothing derived from the palette is kept; layouts are snapshots read on
  open.

## Performance

A default wardrobe is about 120 displays for one viewer, sent once. A poll is a
ray against each hotspot (a few dozen dot products) every 2 ticks. A `refresh()`
re-reads the sources and sends only the slots whose entries changed; a hover
sends one pose and one glow. Edit mode redraws the whole menu per notch, for the
admin alone.

## Source and tests

- Public: `spatial/` — `SpatialMenus`, `SpatialMenu`, `SpatialContext`,
  `SpatialEntry`, `SpatialPreview`, `SpatialLayout`, `SpatialTheme`,
  `SpatialSpots`, `SpatialInput`, `SpatialPointer`, `Hotspot`, `SpatialClick`.
- Package-private: `MenuSession` (drawing, diffing, preview), `EditMode`.
- Internal: `spatial/internal/Geometry` (turned panels, carousel slots, pages,
  diffs, glass sizing, colour mixing).
- Lifecycle: `SpatialMenus.init` in `ExyliaLib.onEnable` (the input listener and the
  hiding rule), `SpatialMenus.release(plugin)` first in `onPluginDisable`,
  `releaseAll` in `onDisable`.
- Tests: `spatial/internal/GeometryTest` (turned panels facing the eyes, hits on a
  turned panel, the carousel's symmetry, gap and curve, pagination, diffs, glass
  sizing), `spatial/SpatialInputTest` (nearest and priority, disabled hotspots,
  cell picking and the gap, hover reported once, wheel wrap),
  `spatial/SpatialLayoutTest` (the default round-trips, a shipped layout keeps its
  values and gains the missing keys).
