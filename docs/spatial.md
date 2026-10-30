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
        .action("clear", ctx -> { clear(ctx); ctx.refresh(); })
        .permission("trims.wardrobe")                                   // since 1.408.0
        .canOpen(player -> inCombat(player) ? lines.notNow() : null));  // null opens; a line refuses

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

`register(id, definition)` reads the jar through `plugin.getResource`, which
sees nothing under the Lukittu loader. A plugin loaded that way (or any plugin,
it costs nothing) passes a class packaged with the layout:
`menus.register("trims", MyPlugin.class, menu -> ...)` (since 1.408.0). The file
is then installed and kept up to date through [BundledFiles](config.md), the
way `Languages` reads lang files: written when missing, new keys added, changed
defaults offered in `/exylialib updates`.

Since 1.413.0 the anchored `register` looks for the jar's layout through
`getResourceAsStream`, the only call Lukittu's in-memory loader answers.
1.408.0 to 1.412.0 asked `getResource`, got nothing under Lukittu, and wrote the
library's wardrobe (`WARDROBE`, `CATEGORIES`, `OPTIONS`, empty lists, the focus
lines' raw `%choice%` / `%detail%`) to the plugin's layout path, where it then
counted as the server's own. A server file that is still that generic wardrobe
(the default's panel names reading `categories`, `options` and carousel `items`,
none of which the menu fills; positions are not compared) is set aside as
`spatial/<id>.yml.library-default` and replaced by the jar's on the next start,
with a line in the log. Nothing else is ever replaced.

`permission(node)` and `canOpen(player -> refusal)` guard every open but edit
mode, from a command or a placed spot alike, before the player is walked
anywhere. A refusal plays the theme's `error-sound`; `canOpen`'s line (unless
blank) is sent to them, so it comes from the plugin's lang files. `null` lets
them in.

`SpatialEntry` is an immutable record: `id`, `icon` (an `ItemStack`) or `model`
(any `DisplayModel`), `name`, `subtitle`, `state` and an optional `price`, with
`of(id, icon, name)` and `with*` withers. Sources run on the viewer's thread on
every redraw: read memory, never a database. Equal entries are not redrawn.

`SpatialContext` is what every callback is handed: `viewer()`, `menu()`,
`get/set(key)` (the menu's own state, gone when it closes; every key is also a
`%key%` placeholder in TEXT lines), `page(source)` / `page(source, n)`,
`selected(source)` / `select(source, id)`, `refresh()`, `replay()` (since
1.408.0: plays the preview again from the start, now) and `close()`.
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
  header:    {at: [0, 1.7, 0], size: [4.6, 0.34], title: '{primary}&lARMOR TRIMS', hint: '{muted}Sneak to leave'}
  pieces:    {at: [-2.05, 0.3, 0.45], turn: 24, size: [1.35, 1.55], title: '{secondary}&lPIECES',
              content: {type: LIST, source: pieces, rows: 4}, footer: {label: '{error}✘ CLEAR', action: clear}}
  materials: {at: [2.05, 0.3, 0.45], turn: -24, size: [1.35, 1.55], title: '{secondary}&lMATERIAL',
              content: {type: GRID, source: materials, columns: 3, rows: 4},
              footer: {label: '{highlight}&lAPPLY TO SET', action: apply-set}}
  focus:     {at: [0, 1.05, 0.2], size: [1.6, 0.42],
              content: {type: TEXT, lines: ['{letters}&l%pattern%', '{info}Editing %piece%']}}
preview:  {at: [0, -0.8, 0.2], turn-speed: 12, pedestal: true, scale: 0.8, vanish-on-play: true, effect-scale: 0.35,
           skins: [others, defaults]}
carousel: {source: patterns, at: [0, -1.05, 0.8], slots: 6, spacing: 0.62, centre-gap: 1.3, curve: 0.15}
```

Coordinates are blocks on the board, a plane `distance` ahead of the eyes:
`across` (right +), `up`, `forward` (towards the viewer +); points are
`[across, up, forward]`, sizes `[width, height]`. `turn` is how many degrees a
panel is turned towards the viewer round its own vertical axis: positive on the
left, negative on the right. The defaults keep everything inside a 70° vertical
view at 3.4 blocks, and nothing in front of anything else as the eyes see it
(nearer pieces look larger): the side panels' outer corners stay under the
header, the body on the preview (0.8 scale, level with the focus text, not
nearer) keeps its head under the focus, and the pedestal clears the plinths.
Since 1.408.0; a layout written before keeps its own values.

## Components

| Where | What it draws |
| --- | --- |
| Panel | Glass, a darker backing sheet, the accent bar along its top, its `title` (bold, shadowed, fitted) and `hint`. A panel with `type: NONE` is a header: title in the middle, hint at its right end |
| `TEXT` | `lines`, centred; `%key%` from the menu's state and `values(...)`; drawn again, fitted, when they read something else |
| `LIST` | `rows` rows of icon, name and state line; the row under the crosshair lights with a bar, the chosen one carries a `{highlight}` marker that glides between rows |
| `GRID` | `columns × rows` icons; an underline glides to the chosen one; the name of the hovered (else chosen) one reads under the grid, followed by its price or `locked-line` when it is `LOCKED` |
| `BUTTON` | `label` on a plate that lights under the crosshair; runs `action` |
| `footer` | A button along any panel's bottom |
| `carousel` | `slots` plinths (a block display and a dark inlaid plate) each turned to the eyes, the item over it, its name and state line under and in front of it, bare like a hologram; `← PAGE x / y →` under the middle |
| `preview` | A plinth in the middle, and what `preview(ctx)` returns on it, turning `turn-speed` degrees a second |

Hovering grows an item ×`hover-grow` with a 120 ms glide and outlines it in
`hover-glow` (panels).

**The carousel, since 1.414.0.** Every icon is drawn in the GUI display
context, so a trident, a cake or a totem look like their inventory icon: one
size (0.32), centred 0.2 over its plinth's top, swaying ±16° and bobbing
±0.012 on a 3.6 s cycle, each slot out of step. The chosen plinth is outlined in
`plate-selected` (a glow ring), its item lifted 0.07, grown ×1.1 and turning a
full circle every 2.4 s, and a thin `plate-selected` bar glides (180 ms) to the gap between its plinth and its
label. Hover is the lighter version: a `plate-hover` ring, the item lifted 0.035
and grown ×`hover-grow`, its label grown ×1.1 and brought 0.03 nearer, its top
kept where it was. No plate is ever drawn as a flat bright sheet. The label is
one text display (name, then state line) with no backing at all since 1.415.0
(`#00000000`, like the library's holograms; its text shadow keeps it readable
over anything; 1.414.0 drew it on a dark solid backing), its top
0.04 under the plinth's bottom and its face 0.09 nearer the eyes than the
plinth's front face, so seen from the eyes no part of any plinth is before it.
A page turn slides the old page off sideways (200 ms, shrinking) and brings the
new one in from the side the wheel turned to, slot by slot.

**The panels' icons** are GUI-context too, bobbing 0.008 on a 4 s cycle. `LOCKED` entries are drawn dimmer and read the theme's
`locked-line` (or their `price`); `EQUIPPED` reads `equipped-line`; `OWNED` reads
its subtitle or `owned-line`. `SELECTED` marks the entry chosen when the menu opens.
A grid cell has no line of its own, so its state is a marker in its icon's
corner: `equipped-marker` when worn, and since 1.415.0 `locked-marker` (`🔒`) and
the dimmed icon when locked; the caption under the grid then names it with its
price or `locked-line` (`Iron » $500.00`). Before 1.415.0 a locked cell looked
like an owned one but for a slightly darker icon.

**Every text display is explicit (since 1.415.0).** Text the menu draws has a
transparent background, sent explicitly; only the glass, plates and bars (empty
sheets) are coloured. `DisplayModel#background()` never answers `null`, so no
display anywhere falls back to the client's grey panel; before, a
`background(0xFFFFFFFF)` (opaque white) was mistaken for "unset" and drawn grey.

Every word the menu draws is live: TEXT lines, panel titles and hints, BUTTON
and footer labels are filled again on every `refresh()`, and only a label that
now reads something else is drawn again (since 1.408.0; before, all but TEXT
lines kept what they read when drawn), popping back from nine tenths of its size
in 170 ms (since 1.414.0) rather than appearing at once.

**Readable everywhere (since 1.414.0).** Every line is drawn at least as large
as the theme's `min-glyph-degrees` (0.95°) reads from the eyes where it stands:
a vanilla capital is 7 px, a small capital 5 px, a pixel 1/40 block at scale 1,
so a line 3.6 blocks away is never under scale 0.34, or 0.48 in small capitals.
A line too long for its room at that size is cut with an ellipsis, its colours
kept, rather than shrunk. Titles and buttons follow the server's `small-text`;
body lines (TEXT lines, hints, entry names and state lines, the page line, the
empty line, captions) are drawn as written unless `small-caps-body: true`.

## Words and translation

Since 1.408.0 every theme line is filled like a title: the plugin's `values`,
then the menu's state as `%key%`. That covers `locked-line`, `owned-line`,
`equipped-line`, `equipped-marker` and `locked-marker` (which also read the entry's `%name%`,
`%subtitle%` and `%price%`), `page-line`, the arrows, `empty-line`, and the leave
item's `leave-name` and `leave-lore`. To translate them per viewer, write a
placeholder in the layout and give it from the plugin's lang files:

```yaml
theme:
  locked-line: '%wardrobe_locked%'
  leave-name: '%wardrobe_leave%'
```

```java
.values(ctx -> Values.of().put("wardrobe_locked", lines.locked()).put("wardrobe_leave", lines.leave()))
```

No second hook: `values` already runs per viewer on every redraw. An entry's
own `price` still wins over `locked-line`.

A placed spot's hologram (`spot-lines`, `%title%` the layout's `title`) has no
open menu to read `values` from: each viewer gets their own copy, so a
placeholder registered with [Placeholders](placeholders.md)
(`%myplugin_wardrobe_title%`) resolves for whoever looks at it.

The mouse wheel pages the list or grid under the crosshair, or the carousel
anywhere else; the carousel's arrows page it too. Pages wrap round.

Previews: `SpatialPreview.npc(player)` (their skin, wearing what they wear),
`npc(player, equipment)` (re-dressed in place when only the equipment changes),
`item(stack)`, `sequence(sequence, skin)` (a mannequin with the effect played on
it again and again, for the viewer alone: a kill effect seen for real), `none()`.

An effect is played `by(viewer)` with no target, as the previews'
[mannequin mode](previews.md) does, so a `[POTION]` or a victim's title never
lands on anyone. The mannequin, every `[RAGDOLL]` line and every
`[NPC] {victim}` line wear the same skin, so the body that falls is the one that
stood there: `skin`'s when one is given, otherwise the one `preview.skins` picks
(since 1.416.0), afresh each time the mannequin stands.

`preview.skins` is an ordered list; the first source that has a body wins
([`PreviewSkins`](npcs.md#preview-bodies-since-1416)):

| Source | Body |
|---|---|
| `others` | Another online player, at random each time (who the "victim" is) |
| `viewer` | The player looking |
| `defaults` | Vanilla's default skins (Steve, Alex, Ari, Efe, Kai, Makena, Noor, Sunny, Zuri, slim and wide), one after another |

The default is `[others, defaults]`: a kill effect reads best on somebody else,
and alone on the server you see the real vanilla skins rather than yourself.
`viewer` and `defaults` always have a body, so anything after them is never
reached. A fresh body's first play waits up to 3 s for its skin's picture, so a
ragdoll never falls in placeholder colours. With `preview.vanish-on-play` (default
true) the mannequin is taken down while the effect plays (its length, or 3 s for
an endless or very long one), as the previews' `vanish-on-hit`, so a kill reads
as a death; it stands again at least a second before the next play. Set it to
false for hit effects. `ctx.replay()` plays it again at once.
An NPC lives two minutes at most; the menu brings a fresh one before that.

The effect plays at the pedestal's top, where the mannequin's feet are, as a
real kill plays at the victim's feet, and as a miniature of itself:
`preview.effect-scale` (since 1.413.0, default 0.35) is handed to
[`SequenceTarget.scale`](sequences.md#played-as-a-miniature-since-1413), so
every offset, movement, body, display and particle spread is that many times its
written size, about the pedestal's top, and nothing it drops falls below it. The
mannequin stands at the same scale, so the body that dies is the one that stood
there (`scale` sizes `npc(...)` and `item(...)` previews). 0.35 keeps a typical
kill effect (a burst body, a 2-block ring, debris thrown 3 blocks) between the
side panels and under the header; an effect that flies 20 blocks up does not
fit at any readable size. Before 1.413.0 the effect played full size from 0.8
above the pedestal, so a body towered over the header and floor rings drew at
eye height, edge-on, as a thin bar across the middle.

Preview NPCs (`npc(...)` and the mannequin) are [nameless](npcs.md): announced
under a generated name in a team that hides the tag, so no name, and no
scoreboard-team prefix of the viewer's (a rank), hangs over them.

## Drafts and SAVE (since 1.414.0)

What a player picks in a cosmetic menu is a draft: shown at once (the preview
wears or plays it, owned or not), stored only when they press SAVE, dropped when
they leave.

```java
menus.register("effects", menu -> menu
        .source("effects", this::effects)
        .preview(ctx -> SpatialPreview.sequence(sequenceOf(ctx.selected("effects")), null))
        .saved(ctx -> Collections.singletonMap("effects", worn(ctx.viewer())))  // what is stored now
        .onSave(ctx -> equip(ctx))       // null: done; a line: refused, told with the error sound; "" refuses silently
        .onDiscard(ctx -> lines.discarded().send(ctx.viewer())));
```

- `saved(ctx -> Map)` is what a draft is compared with, read from memory on every
  redraw. A key that names a source is that source's selection; any other key holds
  what `ctx.draft(key, value)` was given (compared with `equals`; drafting the saved
  value, or `null`, is no draft).
- `ctx.draft(key)` reads the draft, else the saved value; `ctx.changed()` lists the
  keys that differ, `ctx.dirty()` says whether any does.
- A BUTTON or footer with `action: save` runs `onSave` (also `ctx.save()`); with
  `action: revert`, `ctx.revert()` drops every draft and re-selects what is saved.
  Both are answered only when the plugin registers no action of that name itself.
- The SAVE plate is warmed towards `plate-selected` while `dirty()`, calm otherwise;
  the label is the plugin's (`SAVE` / `Saved ✔` from its lang files, picked with
  `ctx.dirty()` in `values`).
- Closing with `dirty()` runs `onDiscard` (after which drafts are gone); nothing was
  ever applied, so there is nothing to undo.
- `ctx.hovered(source)` is the entry under the crosshair: an information panel reads
  it (falling back to the selection) to describe what the player is looking at
  before they click. A hover redraws the panels' words at once; leaving an entry
  waits one poll, so a sweep along the plinths does not flash back in between.

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
| `title-scale`, `text-scale`, `small-scale` | 0.6, 0.42, 0.35 (0.5, 0.36, 0.27 before 1.414.0) |
| `min-glyph-degrees`, `small-caps-body` | 0.95, false (since 1.414.0; see *Readable everywhere*) |
| `pedestal-block`, `plate-idle`/`-hover`/`-selected` | `POLISHED_BLACKSTONE`; `{primary}` darkened, `{secondary}` (hover ring), `{highlight}` (chosen ring, label tint, lit SAVE) |
| `hover-glow`, `hover-grow`, `edit-outline` | `{highlight}`, 1.12, `{info}` |
| `locked-line`, `owned-line`, `equipped-line`, `equipped-marker`, `locked-marker` | `{error}✘ Locked`, `{muted}Owned`, `{success}✔ Equipped`, `{success}✔`, `{error}🔒` (since 1.415.0) |
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
`PlacedTables`). Their holograms are `spatial-spot:<id>` (since 1.408.0; before,
`table:<id>`, which a plugin's own table of the same id took over). Each shows a small clickable hologram 1.6 blocks ahead of it
(`spot-lines`); clicking it walks the player onto the spot (no countdown) and
opens the menu there, facing the way the spot faces, once the menu's
`permission` and `canOpen` let them. They stay where they are
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
  plays its own sounds), `Hotspot.custom(plane, hit -> index)` (since 1.410.0).
  Each takes `onHover(boolean)`, `onCell(int)`, `onPress(SpatialClick)`,
  `onScroll(int)`, `priority`, `enabled`, `silent`. Overlapping: higher priority,
  then nearer the eyes. Several planes per pointer.
- The pointer plays its hover sound once per change (entering a hotspot or a new
  cell) and its press sound on a press, unless the hotspot is silent.
- `SpatialClick(kind, sneaking, cell)`: LEFT is a swing (air, block or entity);
  RIGHT a use on a block or entity, or a right-click with the hotbar's leave item.
  A swing and the interaction it causes are one click (120 ms). A click on no
  hotspot goes to `onClick`; `onAnyClick` (since 1.410.0) hears every click first,
  on a hotspot or not, a refused press included: an idle clock, stopping AUTO.

### Shapes, state and threads (since 1.410.0)

```java
// A game's own shapes, answered from its state on every poll: -1 is a miss.
pointer.add(Hotspot.custom(plane, hit -> view.target(hit))
        .accepts(cell -> board.isEmpty(cell))   // a refused cell neither hovers nor sounds
        .hoverable(seat == turn)                // waiting: the press arrives ("not your turn"), nothing lights
        .onCell(view::hover)
        .onPress(click -> play(seat, click.cell())));

pointer.add(Hotspot.of(rollButton).squash(false)          // the press redraws the button anyway
        .pressWhen(() -> state == State.READY)            // refused: no sound, no squash, no onPress
        .onPress(click -> roll()));

pointer.onAnyClick(click -> session.touched());           // every click, before routing
pointer.deliverOn(match.spot(0));                         // the round's region: everything runs there
```

- `Hotspot.custom(plane, picker)`: `picker` turns a `StagePlane.Hit` into a cell
  index or `-1`; it runs on every poll and every click, so it may read the game's
  state (edges in reach, the card on top of a fanned hand, round arrows, nothing
  while the dice roll). The index arrives like a cell picker's.
- `accepts(IntPredicate)`: which cells answer, asked on every poll. A refused cell
  is a miss: no hover, no sound, and its click goes to the hotspot behind or to
  `onClick`. A rectangle and a button are cell 0.
- `hoverable(false)`: the hotspot still takes the crosshair and its presses, with
  no hover callback, glow, squash or sound. Flipping it back under the crosshair
  lights it on the next poll.
- `squash(false)` on `Hotspot.of(button)`: the button lights but is not squashed
  on a press. `pressWhen(BooleanSupplier)`: a press refused by it does nothing
  visible (it still hovers); `onAnyClick` still hears it.
- `deliverOn(location)`: the pointer moves onto the thread that owns `location`
  (on Folia, its region): it polls there, and pickers, `accepts`, button visuals
  and every callback run there, so a round that lives on one region needs no hop
  of its own. Clicks are heard on the player's thread and handed over; the hotbar
  slot is put back on the player's. `deliverOn(null)` brings it back; the same
  place again does nothing. Hotspots may be added and removed from any thread.

### Observing pointers (since 1.410.0)

A public board (a live station passers-by bet at, each with their own hover)
uses `SpatialInput.of(plugin).observe(player)` instead of `attach`:

```java
SpatialPointer pointer = input.observe(player).sounds("", "");
pointer.add(Hotspot.custom(board.plane(), board::buttonAt).onCell(cell -> hovers.put(id, cell))
        .onPress(click -> press(player, click.cell())));
pointer.detach();                             // they walked off
```

- Only clicks that land on one of its hotspots are consumed (the interaction,
  break, place or hit is cancelled); every other click passes to the world. The
  click on nothing still reaches `onClick`.
- No hotbar takeover: the slot is never moved back, the wheel reaches only a
  hovered hotspot that scrolls. Nothing is frozen.
- A player may have several, one per board near; they never replace or take input
  from an attached pointer: while one is attached (a game, a menu) or a tuner
  session runs, observers lose the hover and hear nothing.
- Callable from any thread; it polls on the player's. Ends on `detach()`, quit,
  death, world change and the plugin's disable.
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
  (their region on Folia), or from the region a pointer was moved to with
  `deliverOn`; every callback runs there. `onEnd` runs on the thread that ended it. `SpatialMenus`' public
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
sends a few poses and restyles, and redraws only the panel lines that now read
something else. Idle icons are client-interpolated loops: a carousel icon sends
a pose every 0.6 s, a panel icon one every 2 s (about 10 + 10 packets a second
for a default wardrobe's 6 plinths and 20 panel icons). Edit mode redraws the whole menu per notch, for the
admin alone.

## Source and tests

- Public: `spatial/` — `SpatialMenus`, `SpatialMenu`, `SpatialContext`,
  `SpatialEntry`, `SpatialPreview`, `SpatialLayout`, `SpatialTheme`,
  `SpatialSpots`, `SpatialInput`, `SpatialPointer`, `Hotspot`, `SpatialClick`.
- Package-private: `MenuSession` (drawing, diffing, preview), `EditMode`.
- Internal: `spatial/internal/Geometry` (turned panels, carousel slots, pages,
  diffs, glass sizing, colour mixing, the readability floor and ellipsis cuts),
  `spatial/internal/Drafts` (drafts against the saved state).
- Lifecycle: `SpatialMenus.init` in `ExyliaLib.onEnable` (the input listener and the
  hiding rule), `SpatialMenus.release(plugin)` first in `onPluginDisable`,
  `releaseAll` in `onDisable`.
- Tests: `spatial/internal/GeometryTest` (turned panels facing the eyes, hits on a
  turned panel, the carousel's symmetry, gap and curve, pagination, diffs, glass
  sizing), `spatial/SpatialInputTest` (nearest and priority, disabled hotspots,
  cell picking and the gap, hover reported once, wheel wrap, custom pickers from
  state, refused cells falling through, hotspots that do not hover, press guards),
  `spatial/internal/ReadabilityTest` (the floor's angle, the default theme readable
  at 3.6 blocks, ellipsis cuts keeping markup, drafts against the saved state),
  `spatial/SpatialLayoutTest` (the default round-trips, a shipped layout keeps its
  values and gains the missing keys), `spatial/DefaultLayoutOverlapTest` (the
  default layout seen front-on from the eyes: no two pieces' outlines overlap,
  all inside a 70° vertical view).
