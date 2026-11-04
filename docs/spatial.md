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
menus.open(player, "editor", Map.of("slot", 2)); // its state holding what it is about (since 1.423.0)
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
defaults offered in `/exylialib updates`. Before 1.441.0 those offers never showed for a layout: the file
is also read as a record, whose own (empty) review replaced them, and its record-written `0.0`
counted as the owner's change of a shipped `0`.

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

**Minimum client 1.21.4 (since 1.431.0).** A player whose client is older (joining
through ViaBackwards; the version PacketEvents saw at login, through
[`ClientVersions`](packets.md#clientversions)) never gets a 3D menu. Every `open`,
a command, the catalogue, a placed spot's hologram or a PLACED layout alike, runs
the menu's fallback instead, before the permission and `canOpen`, with nothing
built and nobody walked onto a spot:

```java
menus.register("trims", Main.class, menu -> menu
        .fallback(player -> classicMenu.open(player))   // the plugin's inventory screen
        ...);
catalog.menu(menu -> menu.fallback(player -> classicMenu.open(player)));  // a catalogue
```

Without a fallback such a player hears the theme's `error-sound` and nothing
opens (edit mode is refused the same way). `SpatialMenus.supports(player)` is the
same check, for a plugin choosing a screen up front. An unknown version counts as
the server's, so a server without ViaVersion behaves as before. The crate scene
follows the same rule ([crate scene](crate-scene.md)).

`SpatialEntry` is an immutable record: `id`, `icon` (an `ItemStack`) or `model`
(any `DisplayModel`), `name`, `subtitle`, `state` and an optional `price`, with
`of(id, icon, name)` and `with*` withers. Sources run on the viewer's thread on
every redraw: read memory, never a database. Equal entries are not redrawn.

`SpatialContext` is what every callback is handed: `viewer()`, `menu()`,
`get/set(key)` (the menu's own state, gone when it closes; every key is also a
`%key%` placeholder in TEXT lines), `page(source)` / `page(source, n)`,
`selected(source)` / `select(source, id)`, `refresh()`, `refreshPreview()`
(since 1.428.0: the preview alone, cheap enough for every few ticks), `replay()`
(since 1.408.0: plays the preview again from the start, now) and `close()`.
`open(player, id, state)` (since 1.423.0) opens it with `state` already set,
so `onOpen` and every source read what the menu is about (`ctx.get("slot")`); a
placed spot opens without it, so a menu reading it keeps its own default.
`menus.context(player)` finds the open one from outside (a purchase that
finished). `menus.refresh(player)` redraws it from any thread, and
`menus.refreshAll()` every open menu of the plugin (call it from the plugin's
reload step, so an open menu shows the new catalogue at once; since 1.416.0).

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
| Panel | Glass, a darker backing sheet, the accent bar along its top, its `title` (bold, shadowed, fitted) and `hint`. A panel with `type: NONE` is a header: title in the middle, hint at its right end (since 1.441.0 in all the room the title leaves); one 0.36 or taller (since 1.441.0) stacks the hint centred under the title, so a header narrowed to the gap between upper side panels keeps both whole |
| `TEXT` | `lines`, centred; `%key%` from the menu's state and `values(...)`; drawn again, fitted, when they read something else |
| `LIST` | `rows` rows of icon, name and state line; the row under the crosshair lights with a bar, the chosen one carries a `{highlight}` marker that glides between rows |
| `GRID` | `columns × rows` icons; an underline glides to the chosen one; the name of the hovered (else chosen) one reads under the grid, followed by its price or `locked-line` when it is `LOCKED` |
| `BUTTON` | `label` on a plate that lights under the crosshair; runs `action` |
| `BUTTONS` | A column of `buttons` (`{label, action}` each), stacked in the content area; one whose label reads blank once filled is left out and the rest close up (since 1.416.0) |
| `TABS`, `ACTION` | A row of tabs, the one primary button (since 1.452.0; see *Centre stage*) |
| `footer` | A button along any panel's bottom |
| `footers` | Several buttons side by side along a panel's bottom, `{label, action}` each, equal widths with a small gap; after `footer` when both are given (since 1.423.0) |
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
A page turn (the arrows) slides the old page off sideways (200 ms, shrinking) and
brings the new one in from the side it turned to, slot by slot. A wheel step
(since 1.463.0) is the same slide by one place: every item leaves towards its
neighbour's place and comes in from the other one, all at once.

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

**Readable over the backdrop (since 1.467.0).** On a board with a `backdrop`, a colour in any word that reads under
3:1 over the sky and over deepslate seen through it (`{neutral}`, `{muted}`, `{error}`, `&8`, a dark hex) gives way
to the line's body colour, the first readable one it is written in, else `{letters}`
(`spatial/internal/Legible`). A catalogue's description written for an inventory
(`{letters}... {highlight}gets deleted{neutral}.`) then reads whole in 3D with no server's file edited. Boards
without a backdrop draw colours as written.

**Only grey words give way (since 1.470.1).** A hue carries meaning and is kept as written (`{primary}`, `{error}`, a
rarity's blue): only a grey (HSV saturation under a quarter: `&8`, `{neutral}`, `{muted}`) gives way. A colour written
on symbols alone (a dye's `■` swatch, an icon's `↶`, a `✔` marker) is content, not words, and is never touched; nor is
a dark swatch taken for the line's body colour. Before 1.470.1 every dark dye swatch, `{primary}` title and `{error}`
line read lavender.

## Words and translation

Since 1.408.0 every theme line is filled like a title: the plugin's `values`,
then the menu's state as `%key%`. That covers `locked-line`, `owned-line`,
`equipped-line`, `equipped-marker` and `locked-marker` (which also read the entry's `%name%`,
`%subtitle%` and `%price%`), `page-line`, the arrows and `empty-line`. To
translate them per viewer, write a placeholder in the layout and give it from the
plugin's lang files:

```yaml
theme:
  locked-line: '%wardrobe_locked%'
```

```java
.values(ctx -> Values.of().put("wardrobe_locked", lines.locked()))
```

No second hook: `values` already runs per viewer on every redraw. An entry's
own `price` still wins over `locked-line`.

Prose spread over several TEXT lines (a description in `%description_1%` ..
`%description_3%`) is broken with `ctx.wrap(panel, text, lines)` (since
1.441.0): word by word, at the width a body line of that panel really has from
the eyes (90 % of the panel, the theme's `text-scale`, never under
`min-glyph-degrees`). Each line then fits whole instead of being cut with an
ellipsis; only what overflows the last line is cut.

```java
List<String> lines = ctx.wrap("info", effect.description(), 3);
for (int i = 0; i < 3; i++) values.put("description_" + (i + 1), i < lines.size() ? lines.get(i) : "");
```

A placed spot's hologram (`spot-lines`, `%title%` the layout's `title`) has no
open menu to read `values` from: each viewer gets their own copy, so a
placeholder registered with [Placeholders](placeholders.md)
(`%myplugin_wardrobe_title%`) resolves for whoever looks at it.

The mouse wheel moves one item a notch (since 1.463.0; a page before): the list or
grid under the crosshair, or the carousel anywhere else, the tab row included
(tabs change only by click). Over the preview's body with `turn-controls` it turns
the body instead (its `↺` `↻` arrows say so). Notches that come faster than a step
plays wait their turn and play a beat apart (one tick while more wait, two for the
last, at most 12 waiting), so a flick of five slides five items, never a page. The
ends wrap round: past the last item comes the first. The arrows `‹ ›` still turn a
whole page, from the page the first item shown is on to the next page's start.
The page line reads the page of the first item shown. Edit mode and the Tuner keep
their own wheel.

Previews: `SpatialPreview.npc(player)` (their skin, wearing what they wear),
`npc(player, equipment)` (re-dressed in place when only the equipment changes),
`item(stack)`, `sequence(sequence, skin)` (a mannequin with the effect played on
it again and again, for the viewer alone: a kill effect seen for real), `none()`.
A re-dress sends only the slots whose item changed (since 1.428.0).

`npc(player, () -> equipment)` (since 1.428.0) is read again every poll (two
ticks) while the menu is open, so an animated skin or trim moves with no timer
and no `refresh()`; only changed slots are sent. Two with the same skin are the
same preview, so `preview` may build a fresh supplier on every redraw. The
supplier runs on the viewer's thread: read memory, and cache what is costly to
build.

`performance(sequence, skin)` (since 1.428.0) is a body performing: an emote or
a dance. There is no mannequin: the body the sequence draws (`[RAGDOLL]`,
wearing `skin`, or `preview.skins` for `null`) is the preview, so only one ever
stands there. An endless sequence keeps going; a finite one starts again 0.6 s
after its last piece is gone. `duet(sequence, skin, gap)` stands two, `gap`
blocks apart (times `preview.effect-scale`) and turned towards each other, as a
duet emote plays between two players: the first in `skin`, the partner in a body
`preview.skins` picks. Both wait up to 3 s for their skins' pictures. A crate
scene shows a performance's first body alone.

`flight(projectile, launch, trail, trailEveryTicks, impact)` (since 1.422.0) is a
projectile seen in flight, for the viewer alone: the item (an arrow, a snowball,
a trident; a display, never an entity) sets off low over the left of the stage,
rises a little and comes down in a gentle arc into a small target block standing
on the pedestal, pointing the way it flies, stuck there a moment, and again every
2.6 s (longer when the impact is). `launch` plays where it sets off, `trail`
where it is every `trailEveryTicks` ticks (at most one step every two ticks, the
menu's poll), `impact` where it lands, facing along the stage; any may be
`null`. Each plays at `preview.effect-scale`, about where it plays, and the arc
is sized by `preview.scale`, so at the defaults the whole flight stays between
the side panels and under the focus text. A crate scene shows a flight's
projectile as an `item` prize. Plays its personal lines (`[MESSAGE]`,
`[TITLE]`, `[COMMAND]`...) on the viewer, who sets it off: hand it sequences
without them.

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
true) the mannequin is taken down while the effect plays, as the previews'
`vanish-on-hit`, so a kill reads as a death. It stands again only once every piece
of the effect is gone (since 1.428.0; before, after the effect's length or 3 s, so
a long ragdoll was seen beside a second body), never while an endless one plays,
and the next play waits a second after that. Set it to
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

**An item on the pedestal (since 1.470.1).** `SpatialPreview.item(...)` is drawn in the item frame's display
context, so a shield or a banner shows its front, centred, never its edge or back. At `turn-speed: 0` it faces the
viewer (`facing`), swaying a few degrees; any other speed turns it round, and `turn-controls` turn it as a body.
On shelves, arcs, plinths and the crate reel a shield or a banner is drawn the same way and never spins round when
chosen (it sways), nor as a crate scene's prize whatever its `turn-speed`; every other item keeps its inventory icon
(`spatial/internal/ItemFacing`). Before 1.470.1 an
item preview always span full circle and a shield or banner opened edge-on or from behind.

**Facing and turning the body (since 1.442.0).** `preview.facing` is the side a
body (`npc(...)`, the mannequin) shows when it stands: `FRONT` (towards the
viewer, the default), `SIDE`, `BACK`, `OTHER_SIDE`, or degrees turned from the
front. `turn-speed: 0` stands it still; otherwise it turns from there on its own.
With `preview.turn-controls: true` the viewer turns it: a click on the body turns it
to the next side (front, side, back, other side; sneaking, the other way), the
wheel over it turns it 30° a notch, and two arrows beside the pedestal (`↺` `↻`,
lit in `hover-glow` under the crosshair) turn it either way. Each turn glides there
in 0.45 s and holds that side, its own turning stopped, for `turn-pause` seconds
(8), then it turns on its own again from where it was left. `turn-hint` is the
short line under the pedestal saying so (`{muted}Click the body to turn it`; blank
for none; a `%placeholder%` from the plugin's lang files translates it).

```yaml
preview: {at: [0, -0.8, 0.2], turn-speed: 12, pedestal: true, scale: 0.8, facing: FRONT,
          turn-controls: true, turn-hint: '%wardrobe_turn_hint%', turn-pause: 8}
```

ExyliaArmorTrims' and ExyliaArmorSkin's wardrobes ship with it on; a server's own
layout gains the keys through `/exylialib updates`. Item, effect, flight and
performance previews are unchanged.

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
- Only sources named as saved keys are re-selected by REVERT. A menu whose markers
  follow its drafts some other way (a basket of parts, the piece being edited)
  moves them in `onRevert(ctx -> ...)` (since 1.428.0), run after the drafts are
  dropped and before the redraw.
- The SAVE plate is warmed towards `plate-selected` while `dirty()`, calm otherwise;
  the label is the plugin's (`SAVE` / `Saved ✔` from its lang files, picked with
  `ctx.dirty()` in `values`).
- Closing with `dirty()` runs `onDiscard` (after which drafts are gone); nothing was
  ever applied, so there is nothing to undo.
- `ctx.hovered(source)` is the entry under the crosshair: an information panel reads
  it (falling back to the selection) to describe what the player is looking at
  before they click. A hover redraws the panels' words at once; leaving an entry
  waits one poll, so a sweep along the plinths does not flash back in between.

## Undo (since 1.423.0)

An editor (a design built layer by layer) wants one click back, not REVERT's
everything. Every menu with drafts keeps the steps itself:

```yaml
  layers:
    content: {type: LIST, source: layers, rows: 5}
    footers:
      - {label: '%undo_label%', action: undo}
      - {label: '%clear_label%', action: clear}
```

```java
.values(ctx -> Values.of().put("undo_label", ctx.canUndo() ? lines.undo() : lines.undoNone()))
```

- Each click that changes the drafts (an entry chosen, a button pressed) is one
  step, however many `ctx.draft(...)` calls it made; a click that changed nothing
  is none. A step is every plain key's draft and the selection of every source the
  `saved` map names. What the plugin drafts in `onOpen` is where the history starts.
- The layout action `undo` (or `ctx.undo()`) puts the newest step back and
  redraws; with nothing to undo it plays the theme's `error-sound`. Answered only
  when the plugin registers no action of that name.
- REVERT is a step too, so UNDO brings back what it dropped. SAVE clears the
  history: what is saved now is where it starts again.
- `ctx.canUndo()` says whether there is a step, for the label. At most 64 steps
  are kept, the oldest going first.

## Confirm in place (since 1.416.0)

An inventory confirmation opened over a menu standing in the world would hide
it, so a click that must be asked twice asks in place:

```java
.action("buy", ctx -> {
    if (!ctx.confirm("buy:" + id)) { ctx.refresh(); return; }   // first click: arms it
    pay(ctx);                                                   // second click, within 6 s
})
```

- `ctx.confirm(key)` arms `key` and answers `false`; the same key again within six
  seconds answers `true` and disarms. A different subject under the same action
  (`buy:a` then `buy:b`) asks again, so a price that changed is never paid blind.
- `ctx.confirming(key)` reads it for the label (`CONFIRM` while armed); `key` is
  `"<action>"` or `"<action>:<subject>"`, and the button whose action is the part
  before the colon is warmed like a dirty SAVE while armed.
- Choosing an entry, turning a page, pressing another button, reverting, or six
  seconds passing disarms it (the words are drawn again on their own).

## Cosmetic catalogues (since 1.416.0)

`SpatialCatalog` is a cosmetic shop and wardrobe in one menu, on top of drafts:
the plugin says what it sells and how it previews; the catalogue answers the
layout's actions, draws every state, buys in place and saves.

```java
SpatialMenus.of(plugin).registerCatalog("effects", Main.class, catalog -> catalog
        .offers("effects", this::offers)                         // List<Offer>, every redraw, memory only
        .ownedFirst()
        .equips(this::worn, (player, id) -> { wear(player, id); return null; })
        .unequip(player -> { takeOff(player); return null; })
        .preview((ctx, id) -> SpatialPreview.sequence(sequenceOf(id), null))
        .categories(ctx -> categories(), true)                    // true: "every offer" is a step
        .favourites(this::starred, this::toggleStar)
        .crate(() -> crates)
        .words(ctx -> lines.catalog())                            // SpatialCatalog.Words, from the lang files
        .values(this::values)                                     // the plugin's own %placeholders%
        .menu(menu -> menu
                .canOpen(this::refusal)
                .onDiscard(ctx -> lines.discarded().send(ctx.viewer()))
                .action("replay", SpatialContext::replay)));

List<SpatialCatalog.Offer> offers(SpatialContext ctx) {
    Player player = ctx.viewer();
    return catalogue.stream().map(item -> {
        SpatialEntry entry = SpatialEntry.of(item.id(), icon(item), item.name()).withSubtitle(item.rarity());
        SpatialCatalog.Offer offer = owns(player, item)
                ? SpatialCatalog.Offer.owned(entry).withEquipped(item.id().equals(worn(player)))
                : SpatialCatalog.Offer.locked(entry, price(item)
                        .map(p -> SpatialCatalog.Purchase.crate(crates, player, item.id(), item.name(), p))
                        .orElse(null));                         // null: not for sale, drawn locked
        return offer.withCategory(item.category());
    }).toList();
}
```

**What the plugin declares.**

| Call | What it is |
| --- | --- |
| `offers(source, ctx -> List<Offer>)` | A list the layout panel or carousel with that `source` draws. The first is the main list: filtered, favoured, equipped and previewed. More than one for a look made of parts (a pattern carousel, a material grid) |
| `Offer` | `entry` (how it is drawn), `owned`, `equipped`, `category`, `purchase`. `Offer.owned(entry)`, `Offer.locked(entry, purchase)`, `withEquipped`, `withCategory` |
| `Purchase(id, name, price, owned, grant)` | How a part is bought, as `CosmeticPurchases.purchase` takes it; `Purchase.crate(crates, player, id, name, price)` for a crate reward |
| `equips(worn, equip)` | One worn at a time: the saved state is `worn(player)`, the menu opens on it (on its page; nothing chosen when nothing is worn), SAVE calls `equip(player, id)` for an owned offer (a refusal line, `""` silent, `null` done) |
| `unequip(player -> line)` / `unequip(wearing, ctx -> line)` | UNEQUIP: shown while something is worn, takes it off at once |
| `preview((ctx, id) -> SpatialPreview)` | The try-on of the main list's chosen offer (see below) |
| `categories(ctx -> List<Category>, all)` | The `filter` action's steps, each offer under its `category`; `all` puts every offer first, else the first category opens |
| `favourites(starred, toggle)` | The `favourite` action stars the offer looked at (else the chosen one); the filter gains a favourites step, in catalogue order, which shows starred offers even where a plugin hides locked ones (read `SpatialCatalog.filter(ctx)`) |
| `basket(ctx -> List<Offer>)` | The parts the draft needs and the player lacks. Default: each list whose selection is drafted, its chosen offer when not owned. A look of several parts gives its own (each part once) |
| `crate(() -> crates)` | The `crate` button. A player who can see the [crate scene](crate-scene.md) opens one crate straight in it, whatever `opening` says (no "how many" question; OPEN ANOTHER opens more), and DONE brings the menu back on the same filter and page (since 1.462.0). No key: the menu stays, with the theme's error sound and the crate's `no-keys` line. Anyone else: the menu closes and the inventory question opens, as before |
| `words(ctx -> Words)` | Every word below; `new Words()` is the English. A record a lang file can nest (`@Key("catalog") SpatialCatalog.Words catalog`) |
| `values(ctx -> Values)` | The plugin's placeholders (use this, not `menu.values`); filled first, so one may read `%catalog_save%` (an old layout's name for it) |
| `alias(legacy, action)` | An action name an older layout wrote: `alias("primary", SpatialCatalog.SAVE)` |
| `menu(m -> ...)` / `menu()` | The `SpatialMenu` for anything else: extra sources, `saved`/`onSave` for a multi-part draft, guards, actions |

**SAVE.** The draft needs nothing bought: saved at once (`onSave`). It needs
parts the player lacks, all for sale: SAVE reads `buy` with the total
(`%catalog_total%`; prices in different currencies are listed `$500 + 3 gems`),
the first click checks the player can pay (`CosmeticPurchases.check`, which
says why not in the shop's words: no money, no economy, already owned) and arms
CONFIRM, the second buys every part one after another through
`CosmeticPurchases.purchase` (each charged, granted, refunded on a failed grant
and told, as the inventory shop does) and then saves. A part that fails stops
the rest and redraws with what the player owns now. A part nobody sells (no
`purchase`) blocks SAVE: it reads `locked` and the status line names it.

**Layout actions** it answers, unless the plugin registers the same name:
`save`, `unequip`, `favourite`, `filter`, `crate`; `revert` is the library's.

**Words**, filled in every TEXT line, title, hint and button:

| Placeholder | Reads |
| --- | --- |
| `%catalog_save%` | `saved` / `save` / `buy` / `confirm` / `locked` |
| `%catalog_status%` | `status-saved` / `status-unsaved` / `status-buy` / `status-confirm` / `status-locked`, with `%name%` (the offer tried), `%price%` (the total), `%count%` (parts to buy), `%part%` (the part not for sale) |
| `%catalog_revert%`, `%catalog_unequip%` | Their label while there is something to undo or take off; blank otherwise, so a `BUTTONS` column closes up |
| `%catalog_favourite%` | `favourite` / `unfavourite` for the offer looked at; blank when none |
| `%catalog_filter%` | `filter` with `%category%` (`filter-all`, `filter-favourites` or the category's name). It is a footer button: since 1.441.0 the default is the name alone (`{info}%category%`), so it fits |
| `%catalog_crate%` | `crate` with `%keys%`; blank without crates |
| `%catalog_total%`, `%catalog_name%` | The basket's total; the chosen offer's name |

An entry for sale reads `price` under it (`%price%`), and the theme's
`locked-line` when nothing sells it.

**Try-on previews.** `SpatialPreview.npc(viewer, equipment)` for armour, trims
and skins (re-dressed in place as the draft changes); `sequence(sequence, null)`
for a kill or hit effect on a mannequin (`preview.vanish-on-play: false` for a
hit effect, which must not read as a death); an emote is
`performance(emote, viewer)` (since 1.428.0; `sequence` before), the viewer's own
skin performing it with nothing beside it, and a duet emote `duet(emote, viewer,
gap)`; an animated skin is `npc(viewer, () -> equipment)`; a trail or a projectile effect is a
`flight(projectile, launch, trail, every, impact)` (since 1.422.0; a `sequence`
before); `item(stack)` for a shield, a banner or anything held.

**A layout for it.** Since 1.452.0 a catalogue's layout is the **centre stage**
(next section): ExyliaKillEffect's `spatial/effects.yml` is the reference, and
the library's own `spatial/centre-stage.yml` is written for a catalogue whose
plugin ships no layout. The panels-and-carousel layouts from before keep
working unchanged; they are what a plugin has until it moves.

A look of several parts (ExyliaArmorTrims' wardrobe: pieces, a pattern carousel
that filters to presets, a material grid) declares two `offers` lists, a
`basket` of the halves each changed piece lacks, its own `saved`/`onSave` on
`menu()`, and `unequip(wearing, ...)`.

### Adopting it

1. Ship `src/main/resources/spatial/<id>.yml` (copy KillEffect's, see *Centre
   stage*) and call `registerCatalog(id, Main.class, ...)` on enable.
2. `offers`: one `Offer` per thing sold, built from memory (cache icons).
   Owned ones `Offer.owned(...)`, the worn one `.withEquipped(true)`, the rest
   `Offer.locked(entry, purchase-or-null)` with the plugin's own shop price;
   `.withUnlock(line)` says how one nobody sells is had.
3. `equips` + `unequip` for one worn at a time; `preview` for the try-on.
4. `categories`, `favourites`, `crate` where the classic menu has them.
5. `words` from the lang files (en plus every translation), `values` for the
   card; `menu(m -> m.canOpen(...))` with the same checks the command
   makes (a placed spot opens straight from the library).
6. Call `menus.refresh(player)` when ownership changes outside the menu (crate
   `onChange`, the store's listener) and `menus.refreshAll()` in the reload step.

## Centre stage (since 1.452.0; redrawn in 1.453.0)

The catalogue layout new players read at once, and nothing like an inventory pasted in the sky: no panels,
words floating with a shadow, one filled button, the items floating round the preview at eye level.

```
           All   Meme   Void   Cataclysm   Lovestruck   Cosmic   ★          tabs: plain words
                        THE FLOOR IS LAVA                                   the name looked at
                      Epic · ✔ Equipped                                     one quiet line
             (a short muted description once the crosshair rests)
   ‹   ◆  ◆  ◆  ◆        ( preview )        ◆  ◆  ◆  ◆   ›  1 / 14         the arc, at eye level
                           [ EQUIP ]                                        the one primary button
               ☆   ✘   ↺   ⟳   ▶   ✦   ?   ✕                                icons, words on hover
               Look at an effect to see it · click to try it                the hint
```

The board stands **4.5 blocks** away and spans about 78° across and 42° top to bottom since 1.454.0 (measured by the bot
from the packets; its words drawn at `min-glyph-degrees: 1.06`, a little over the library's floor): about half the view of a 16:9 window at the default 70° FOV. 1.452.0 stood it 6 blocks
away with glass cards, plates and tiles: on a real client it read as an old inventory screen, small and
crammed in the middle. Every line is still drawn at the theme's `min-glyph-degrees` where it stands.

| Piece | YAML | What it does |
| --- | --- | --- |
| Tabs | `frame: false`, `content: {type: TABS, source: catalog_tabs}` | The filter steps as plain words (`tab` muted, `tab-active` light), a thin accent line under the open one, a faint one under the one looked at. Never cut; a window round the open one when they do not fit |
| Title | `frame: false`, no content, `title: '%catalog_card_title%'`, `hint: '%catalog_card_meta%'` | The name of what is looked at, else chosen, in one light colour (its own colours dropped), and one quiet line: its subtitle (a rarity, in its colour) `·` where the player stands (`card-owned`, `card-equipped`, the price, or `🔒 ` and its unlock line) |
| Description | `frame: false`, a `TEXT` panel of up to three lines | The plugin's, through `SpatialCatalog.described(ctx, source)`: the entry the crosshair has **rested on** (`SpatialContext.dwelled`, 0.7 s), else the chosen one while the crosshair is on nothing; nothing during a quick look, so a sweep along the arc stays calm |
| Arc | `carousel: {style: ARC, slots: 8, spacing, centre-gap, curve}` | The page's items either side of the preview, at eye level, bending towards the viewer like an orbit, each turned to the eyes over a soft disc of light, no tiles, no names, bobbing gently. Under the crosshair an item comes 0.28 forward, grows ×1.3 and glows in its own colour (the first colour of its subtitle: a rarity); the chosen one's disc takes the accent and it turns slowly. `‹ ›` past the ends (muted, light under the crosshair) and `1 / 14` under the right one; the wheel moves it an item a notch, sliding (a page before 1.463.0) |
| Primary action | `frame: false`, `content: {type: ACTION}` | The only filled thing: a translucent accent plate while there is something to do (`EQUIP`, `BUY · $500`, `CONFIRM · $500`), no fill and muted words while not (`EQUIPPED ✔`, `LOCKED`). Its words are drawn bold in one colour whatever colours the words carry |
| Icons | `frame: false`, `content: {type: ICONS, buttons: [...]}` | A row of muted glyphs, light under the crosshair. A button's label is `'<glyph>\|<words>'` or words starting with their glyph (`☆ FAVOURITE`); the words show only under the crosshair, in the hint line (the menu's `%icon_label%`). Blank words leave it out |
| Hint | `frame: false`, a `TEXT` line `%catalog_hint%` | One short muted line: what to do next, or what the icon under the crosshair does |
| Tour | `intro.steps` | Coach marks; see below |

**Since 1.454.0** (after the second client test):

- **Readable on any sky or ground.** `backdrop: {at, size, tint, alpha, feather, rings}` draws one soft dark
  translucent sheet behind the whole board, its edge fading out in `rings` steps over `feather` blocks (each ring a
  little further back and fainter; no border). The words use the palette with meaning again, chosen for at least
  3:1 contrast over the backdrop on sky (`#8ab4ff`) and deepslate: `{letters}` for words, `{letters_black}` for the
  quieter ones, `{secondary_light}` for the open tab and a lit icon, `{success}` / `{warning}` / `{accent}` for owned,
  price and locked. Not `{muted}` or `{error}`: under 3:1 there (`CentreStageLayoutTest` measures it). The title is the
  item's own name, gradient and all (`%catalog_card_name%`).
- **Never under the floor.** `SpatialLayout.lowest()` is how far below the board's middle its lowest piece reaches
  (an icon row's hover words included), and the board is drawn at `liftOverFloor(1.62, 0.15)`: its own lift, or
  more when its lowest piece would stand less than 0.15 above the floor the viewer stands on (clear-view keeps the
  ground, which hid the button, icons and hint in 1.453.0). Every layout gets this; the library's old wardrobe moves
  up a few centimetres at most.
- **Icons** are larger (`title-scale` × 1.25), light, accent under the crosshair, and their words show right under
  the icon looked at.
- **Description lines are balanced** (`ctx.wrap` uses `Geometry.balanced`): as many lines as needed at the narrowest
  width that holds them, so no lone word ends a paragraph; the author's own breaks stay when they already balance.
- **Tour arrow** stands outside its caption's measured box, on the side facing the piece, with room for its nudge; it
  points at an inner item of the arc (the outermost has the page arrow beside it) or the near end of a panel.
  Caption, dots and the continue line are one evenly spaced block. While the tour shows, the menu's state holds
  `touring` and a catalogue's hint line is blank.
- **The effect preview is smaller** in the shipped layout (`effect-scale: 0.44`, about 60% of 1.453.0's body), and
  the zone keeps every effect off the words.
- **Effects seen from the front or the side, sized to fit.** An effect now plays facing the viewer (before, it played
  at the world's yaw 0 whatever way the board faced, so a victim could show its back). With
  `preview.effect-facing: AUTO` (the default) the first play of each effect is watched (every display it shows the
  viewer within 12 blocks, from `DisplayRuntime.sample`), and later plays are turned within ±90° so its motion runs
  across the view (`EffectView`: the turn that maximises the spread across against the spread in depth; a still
  effect stays facing you). `preview.zone: [width, height]` is the room above the pedestal it must stay inside: an
  effect that spills out plays smaller, never larger than `effect-scale`, the mannequin with it. Per effect,
  `SpatialPreview.sequence(sequence, skin, facing)` (`AUTO`, `FRONT`, `SIDE`, degrees) wins over the layout's; in
  ExyliaKillEffect it is `preview-facing:` on the effect in `effects.yml`. What was learned is kept in memory per
  sequence, facing, zone and scale.

**Since 1.461.0** (the wardrobes and designers move to the centre stage):

- **`PALETTE`**, a compact picker for a look made of parts: `content: {type: PALETTE, source: dyes, columns: 8, rows: 2}`
  draws a source's entries as plain words or glyphs in a small grid, no plates. Name an entry `'<#b02e26>■'` for a
  colour swatch, or a word for a piece or a layer (`columns: 1` stacks them). The chosen one (marked `SELECTED`,
  else the source's selection) has a thin accent line under it; the one under the crosshair is drawn larger and
  bold, its subtitle (else its name) and price written once under the grid; a locked one is drawn smaller. A click
  chooses it, also when it is chosen already, so `onSelect` can toggle (edit a layer, stop editing it). Its words
  under it count for `lowest()` and the tour's free room (`Content.wordsUnder`).
  Since 1.470.1 an entry whose name starts with a swatch (a colour on `■`) is drawn as a tile of exactly that colour
  in a thin `{letters_black}` rim (lit in `hover-glow` under the crosshair, the tile growing a little), so black and
  navy read on the dark backdrop; words after the `■` are drawn beside it (`'<#3c44aa>■ {letters}Stripe Left'`).
  `columns: 1` is a stack with a fixed pitch (`height / rows`, from the top): each row its tile at the left and its
  words after it, the chosen row on a soft accent plate, the row under the crosshair in `hover-glow`.
- **A plain-word selector by the preview** is a `TABS` panel with the plugin's own source (the piece edited, the
  armour tried on, the step of a look): any source works, not only `catalog_tabs`. The catalogue's own tabs stay
  hidden while it has no categories or favourites, so a wardrobe can put its steps on top
  (ExyliaArmorTrims: `Pattern  Material`).
- **The crate scene** stands on the same soft vignette (`backdrop` in `spatial/crate.yml`, added to every file);
  with it the reel has no glass box, only its marker. OPEN ANOTHER (DONE when no key is left) is the one filled
  button, the other its words alone; the header reads in the centre stage's colours. `backdrop.alpha: 0` brings
  the glass back.

**Since 1.459.0** (a preview opened over the void):

- **A preview's floor is its pedestal.** A scaled play (`SequenceTarget.scale`) stands on where it is played,
  whatever is under it: every `[RAGDOLL]` piece and every display a shape draws rests on that height, kept up by
  half its own height as it turns (`DisplayMotion.resting`), so nothing falls through the menu, choreography
  that sinks into the ground included. A play at scale 1 (a real kill) keeps the world's ground.
- **Particles at a preview's size.** A shrunk play also sends fewer of each particle (the count times the scale,
  at least one), and the big dark ones come as their small kin (`SQUID_INK`, `LARGE_SMOKE` and the campfire
  smokes as `SMOKE`, `GLOW_SQUID_INK` as `GLOW`, explosions and `SONIC_BOOM` as `POOF`), so ink does not cover
  the board in black squares. Their spread and speed were already scaled.

**Since 1.457.0** (after the fourth client test):

- **Every selection plays at once.** An effect not measured yet plays right away at the layout's `effect-scale`
  while its rehearsal runs out of sight beside it; the plays after that are turned and sized from what it saw.
  A rehearsal cut short (another selection, the menu closed) is simply started again next time; it never holds
  the stage. A body waits at most a second for its skin.
- **A switch leaves nothing behind.** The displays a sequence's shapes draw belong to its run
  (`ShapeStep.DRAWING`), so `SequenceRun.cancel()` takes them down at once with its bodies and scheduled
  frames, in a preview and in real kills alike; a run is finished once its last display has gone. Particles
  already sent stay, but no frame runs after the cancel.
- **The backdrop fades through at least 20 rings** (`MenuSession.MIN_RINGS`, up to 32), its alpha easing out
  with the square of what is left: the outer steps are a few alpha apart and the last ring all but clear.
- **The tour's arrow stops short of a row of icons** rather than landing on one of them.

**Since 1.455.0** (after the third client test):

- **Effects keep one size, from the first play on.** Before an effect is shown the first time, it is *rehearsed*:
  played once as a miniature (×0.05) 48 blocks over the board, out of sight and earshot, while its pieces are
  watched (`DisplayRuntime.sample`, which now marks a body's pieces). Its turn and its size are chosen from that,
  so every visible play, the first included, looks the same. It is measured by its bodies (a ragdoll is built of
  player heads) when it has them, with the farthest tenth left out: a crowd, a thrown part or a floating word does
  not set its size. Only height fits it to `preview.zone`, at the 90th percentile, and never under 60 % of
  `effect-scale` (`EffectView.MIN_SCALE`); wider than the zone, it spills out sideways rather than shrink. 1.454.0
  learned from the first visible play and shrank down to 15 %, so a crowd made the next plays a miniature.
  Particles cannot be scaled by the client: with the 60 % floor they stay in proportion.
- **No body is ever built of blocks for want of a skin.** An extra with nobody to wear (a `[RAGDOLL] {crowd}` seat
  with no spectator, a placed NPC with no skin, an account that cannot be read) wears one of vanilla's default
  skins (`PreviewSkins.extra(seat)`), never `SkinCache.fallback()`'s block colours; every default skin's picture
  is read when the library starts, so the first death already wears it. This holds for real kills too.
- **The backdrop is a soft vignette:** 10 rings over 0.9 blocks by default (alpha steps of about 14).
- **Icons in the colour of what they do:** an `ICONS` label `'{colour}|words'` (or `'{colour}glyph|words'`) draws its
  glyph in that colour, bolder and larger under the crosshair. The shipped row: favourite `{highlight}`, unequip and
  leave `{accent}`, revert and replay `{info}`, random `{secondary_light}`, crate `{success}`, help `{info_light}`.
  The arc's page arrows are `{secondary_light}` and larger, the page line `{letters}`.
- **The tour finds free room.** Each caption is measured, then set down at the nearest spot round its piece (the
  layout's `at` only says which side first) where it, and its arrow, touch no piece of the board as the eyes see
  it: tabs, title, description, every arc item at its hovered size, the button, the icons with their words, the
  preview's zone. The arrow points at the piece's middle (an inner arc item, the body on the pedestal, a panel). The
  continue line is one short line (`%dots%  Click: next · sneak: skip`), and captions are plain sentences.

`theme.open-motion: RISE` (since 1.453.0; `POP` is the default and the old look) brings pieces in rising a
little and settling, quickly. Bare panels (`frame: false`) draw their titles, buttons and words as written:
one font on the board. The shipped layout sets `title-scale: 1.0`, quiet arrows (`‹ ›`), a page line of
`%page% / %pages%` and a muted lock marker.

**The tour as coach marks (since 1.453.0).** No box: each step writes a short caption beside its piece (its
title, then its words broken to a narrow column, shadowed, nothing behind them) with the step dots under it
(`theme.intro-line`, `%dots%`: `● ○ ○ ○`, the current one in `intro-outline`), and a `➜` between caption and
piece pointing at it and nudging towards it. The piece is lit softly rather than framed: the arc's items glow,
a pool of light on the pedestal for the preview, a thin line under a panel. Moving on, the caption and the
arrow glide from where the last step stood. Clicking moves on, sneaking skips, `? ` (action `intro`) shows it
again. The shipped layout has four steps: the arc, the preview, the primary button, the icons and tabs
(`%catalog_intro_more%`, a new word).

**New words** (`SpatialCatalog.Words`, after the 1.416.0 ones; the 19-word and 49-word
constructors still work and give the later ones in English; `intro-more` since 1.453.0): `action-equip`,
`action-equipped`, `action-pick`, `action-buy`, `action-confirm`,
`action-locked`; `card-none`, `card-equipped`, `card-owned`, `card-price`,
`card-locked`, `unlock`; the ten `hint-*` above; `tab` and `tab-active`
(`%tab%` the step's name, its own colours dropped), `tab-favourites` (`★`);
`leave`, `help`, `intro-shelf`, `intro-preview`, `intro-action`. The tab of
every offer is `filter-all`. Keep them short: each is one button or one line,
never cut, so a long one is not shrunk but cut at the readability floor.
ExyliaKillEffect's `SpatialWordsFitTest` measures every language against its
room; copy it.

**API.** `SpatialMenu.ready(ctx -> boolean)` (what lights the `ACTION` button;
a catalogue sets it: a draft that can be saved or bought, or CONFIRM armed),
`SpatialCatalog.TABS` (the tabs source), `Offer.withUnlock(line)`,
`SpatialMenus.showIntro(player)`, `SpatialMenus.resetIntro(player, id)`,
`SpatialMenus.CENTRE_STAGE` (the bundled layout's path), `SpatialLayout.Intro`,
`SpatialLayout.Step`, `SpatialLayout.Carousel.Style` / `rows` / `rowGap` /
`perPage()`, `SpatialLayout.Panel.frame`, `SpatialLayout.menuVersion`,
`SpatialTheme.introLine` / `introOutline`; since 1.453.0 `SpatialLayout.Carousel.Style.ARC`,
`Content.Type.ICONS`, `SpatialTheme.openMotion` / `rises()`, `SpatialContext.dwelled(source)`,
`SpatialCatalog.described(ctx, source)`, `%catalog_card_title%`, `%catalog_card_meta%`,
`%catalog_intro_more%`, `%icon_label%`. Every record keeps its earlier constructors.

**Shipping it to servers.** A plugin that moves to the centre stage raises its
layout's `menu-version` (KillEffect: 2 since the 1.453.0 redraw; a file without one is 0). A server file
nobody edited is replaced on the next start (the old one kept as
`<id>.yml.v<n>`); an edited one keeps its layout and the new one waits in
`/exylialib updates` as one change. Reading a layout as a record fills in keys
the jar never shipped, drops unknown ones and stamps a `config-version`, which
used to make every layout on every server look edited; `SpatialMenus.register`
now calls `BundledFiles.recordWrote` first, so a file that is exactly the
reviewed defaults as the record writes them (older library defaults included)
counts as untouched (1.453.0 also counts 1.452.0's `theme.intro-line` default as the library's). `menu-version` is a record key since 1.452.0, so the
record's own write keeps it.

**Room in front.** A FRONT menu with `clear-view` opens towards the nearest
square heading (45° steps from where the player looks) with room for the whole
board at its distance, checked across the middle and both sides of the view, and
turns the player to it; with no such heading, the one with clearly the most room.
The view is still cleared, so a cramped spot opens; it only clears less. The
space kept empty (`seal`) and the blocks cleared follow the board's distance and
size, so a larger board covers the larger volume by itself.

## Opening and closing

Panels pop in first, staggered by 70 ms, their content 180 ms after, the
preview at 300 ms and the carousel's plinths from 420 ms one by one. Closing
deflates everything in 170 ms. A `refresh()` pops nothing: only the slots whose
entries changed are drawn again.

While open: the player is held still (`freeze`, `Movement.freeze`; they can
look round and sneak), wears an empty hotbar ([overlay](overlays.md), `hideRest`:
their items hidden, nothing in any slot; 1.462.x and before put a leave item in the
last slot, which the menu's wheel never let anyone reach), and with `hide-others`
sees nobody and is seen by nobody. The `✕` icon or sneaking closes it (there is no
ESC in the world, so the hint line says so). One spatial menu per
player across every plugin: opening another closes the first.
`SpatialMenus.inMenu(player)` asks across plugins.

FRONT: the board is set square to the world (yaw in 45° steps) where the player
stands, the player turned to face it (through [Teleports](teleport.md), no
countdown). With `anchor.clear-view: false` it is brought nearer (never under 2
blocks) when a block stands in the way.

**The space it stands in is kept empty (since 1.442.0).** Before anything is
drawn, the menu seals the space it stands in for its viewer
([`Visibility#seal`](packets.md#visibility)), from just behind them to just
behind the board, as wide as its widest piece and as high as its highest: every
entity in it that is not the menu's own is out of sight, whoever drew it (mobs,
other players, other plugins' packet-only NPCs and holograms, already there or
walking in later), and back where it really is when the menu closes. With
`hide-others: true` that is every entity anywhere, not only players.

With `anchor.clear-view: true` (the default) every block in front of them up to
just behind the board and up to its top (walls, trunks, leaves, a slab, water) is
sent to them as air and kept so while it is open, also one placed there
meanwhile; the board keeps its full distance. The floor stays: the layer they stand
on (a slab, a carpet, farmland count as theirs) and everything under it is never
touched, so nothing opens under them or the pedestals, and nothing behind them is
cleared. Closing, quitting, a teleport (the menu closes) or the plugin's disable
gives every block back; a world change forgets them. With `freeze: false` a viewer
who walks into a cleared block is stopped by the real one. A layout written before
gains `clear-view: true`. The crate scene does the same (`clear-view` in
`spatial/crate.yml`).

**Opened in mid-air (since 1.442.0).** A FRONT menu (and the crate scene) asked for
while the player is falling or jumping waits for them to land, up to five seconds,
and opens there ([`Footing`](stage.md#a-play-spot)); still in the air after that
(the void, an elytra) or killed by the fall, nothing opens and the theme's
`error-sound` plays. A menu at a placed spot walks them there as before. Before,
it opened where they hung in the air: the board floated there, `freeze` held the
falling client up with a position correction every tick (and cancelled the
server's "floating too long" kick each time), and leaving dropped them the rest
of the way.

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
| `leave-material`, `leave-name`, `leave-lore` | Unused since 1.463.0 (the hotbar is empty); still read so older files load |
| `intro-line`, `intro-outline` | `{muted}Click to continue · Sneak to skip » %step%/%steps%`, `{highlight}` (since 1.452.0) |
| `hover-sound`, `select-sound`, `page-sound`, `open-sound`, `close-sound`, `error-sound` | note-block hat at 0.35, note-block hat at 0.7, `item.book.page_turn`, `block.ender_chest.open\|0.6\|1.4`, `item.bundle.remove_one\|1.0\|0.5`, `entity.villager.no\|0.6` (select and open at 1.0 before 1.428.0) |

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

## Web editor (since 1.464.0)

Every registered menu's layout is also a [web editor](web-editor.md) subject of kind `spatial/layout`, with no
plugin code: `SpatialMenus.register` (and `registerCatalog`) registers `spatial-<id>` (title `<Id> (3D layout)`)
for the plugin's `spatial/<id>.yml`. `/exylialib editor` uploads it; on utils.exylia.net it opens on a board drawn as
the player sees it (the game's font, the palette's real colours, sizes from the eyes at the layout's distance), where
pieces are dragged, resized, nudged with the arrow keys, snapped to each other and lined up, every value sits in an
inspector, and the checks below run as it is edited. `/exylialib editor apply <code>` writes it.

- **What the subject carries** besides the layout's values (`Subject.details`): `defaults` (the layout the plugin
  ships, read as the record reads it, for per-piece and whole-menu "reset to default"; the library's wardrobe when
  the plugin ships none, the centre stage for a catalogue without one), `samples` (the catalogue's words in English
  with a made-up offer, so `%catalog_*%` lines preview as players read them), `tabs`/`tabFormat`/`tabActiveFormat`,
  `sources` and `actions` (what the menu answers, so a button only offers those), `catalog`, `ranges` (bounds per
  dotted path) and `rules` (the numbers below).
- **Sampled through the admin's eyes** (since 1.467.0). The board's words are what the admin who opened the session
  (else anyone online) would read on opening the menu: the menu's own `values`, the catalogue's card, words, tabs
  and page count for its first real offer, read on that player's thread through a context with nothing open
  (`SampleContext`). A catalogue is sampled in each state its offers can show (`SpatialCatalog.STATES`: `saved`
  with the worn one, `unsaved` with a draft of one owned, `buy` and `confirm` with one for sale), and the editor
  offers a state picker, so the board never shows two things the game never shows together (a SAVE pill and an
  EQUIP button). The library's English samples (Lightning Strike, Epic) are only the fallback: nobody online, or
  an empty catalogue. `Subject.details(sender -> Map)` is how a subject reads its details for the session's sender.
- **The plugin's own words** (since 1.465.0). What `values` fills (`%effect_about_1%`, `%replay_label%`) has no
  player to be filled for on the web, so a menu hands samples: `menu.samples(layout -> Map)` (or
  `catalog.samples(...)`), keys with or without the percent signs, read off the main thread at each upload from
  memory; `samples(state, layout -> Map)` (since 1.467.0) for one state only. They are the fallback under what the
  menu's own `values` read for the viewer. `layout.wrap(panel, text, lines)` breaks prose as the open menu breaks it (`ctx.wrap`), so a sample
  description wraps as it will in game. A placeholder with no sample is drawn as a muted chip with its name.
- **The checks** are `CentreStageLayoutTest`'s and `ReadabilityTest`'s, with their numbers: no two pieces in front of
  each other as the eyes see them (an icon row with its words, the pedestal and the body, each arc item over its
  disc), the board within 80° × 47° and its lowest piece 0.15 over the floor (eyes 1.62 above the feet; otherwise
  the library raises it, `liftOverFloor`), every line at least 1.06° tall from the eyes and whole at the floor (not
  cut with an ellipsis), every colour at 3:1 over sky (`#8ab4ff`) and deepslate (`#4d4d50`) seen through the
  backdrop, and every `%catalog_*%` word, action and source one the menu answers.
- **Hiding a piece** (since 1.465.0): `visible: false` on a panel, the `preview` or the `carousel` keeps it in the
  file and leaves it out in game (nothing drawn, nothing to click, not counted for the floor or the edit mode).
  Every piece is shown unless its file says so; the editor's eye writes it, and its checks leave hidden pieces out.
- **Applying** binds the browser's values through the record (the real reader), writes the file from it with its
  comments kept (an owner's comments inside `panels` too, since 1.464.0), and redraws every open copy of the menu
  on its viewer's thread: in place when it still stands where it stood (the same `anchor`, `hide-others` and
  `freeze`), closed otherwise, since the space cleared and sealed for it was measured for the old board. An admin in
  edit mode is closed out, their copy of the layout being stale. The next open reads the new file either way.

## Crate openings (since 1.418.0)

A crate opening in the world, built on these pieces (the pointer, the hiding rule, the theme, the
previews): see [crate-scene.md](crate-scene.md). `SpatialMenus.hideOthers(player, hidden)` applies the
menus' `hide-others` rule to a screen built outside `SpatialMenus`; opening a spatial menu ends a crate
scene, handing its prizes over.

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
Overlays.of(plugin).show(player, SpatialInput.of(plugin).hotbar());
pointer.onSneak(() -> askToLeave(player));

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
  RIGHT a use on a block or entity, or a right-click on the overlay hotbar.
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
  slot is put back on the player's when the pointer ends. `deliverOn(null)` brings it back; the same
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
  always -1 / +1, one call per notch. Several notches the client sends in one
  tick (a flick) arrive as that many calls (1.463.0; one before). The held slot
  follows the wheel and is put back only when the pointer ends (1.463.0; 450 ms
  after the last notch before, which showed as the hotbar jumping back). The
  client moves its slot before the server hears of it and reports only where it
  is now, so a slot put back while it keeps scrolling races it: notches sent
  meanwhile count from the wrong slot, and a scroll that lands on the slot it
  last reported sends nothing at all. Leaving the slot where the wheel left it is
  the only way that loses no notch at any ping; the hotbar shows nothing, so
  only its highlight moves, as in vanilla. The number keys arrive as notches too
  (key 5 from slot 1 is +4).
- `onSneak`, `onLeave` (a `hotbar(item)`'s leave item), `onTick` (every poll), `onEnd`
  (`QUIT`, `DEATH`, `WORLD`, `DISABLED`, `REPLACED`; not run for `detach()`),
  `mute(millis)`.
- While attached, using blocks, breaking and placing them and hitting entities is
  cancelled. Protection against damage, hunger, drops and commands stays the
  game's.
- `hotbar()` (since 1.463.0) is the overlay to show with it: `hideRest`, no
  pickup, nothing in any slot; the overlay swallows swings over its slots and
  hands them back through the plugin's `<plugin>_spatial:click` action, so clicks
  arrive the same with or without it. `hotbar(item)` adds a leave item in slot 8
  (right-click runs `onLeave`; `null` for none). It is for a screen that does not
  use the wheel (ExyliaCasino's tables, ExyliaTabletop's boards): the held slot
  follows the wheel and the number keys, so the player reaches slot 8 and leaves
  with it. A screen whose wheel moves something (a 3D menu's arc) takes `hotbar()`
  and leaves through itself, since reaching slot 8 would scroll it. Deprecated in
  1.463.0 and 1.464.0, undeprecated in 1.465.0.

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
something else. The plugin's `values` are read once per redraw, every label filling
from that one reading (since 1.416.0; before, once per label). Idle icons are client-interpolated loops: a carousel icon sends
a pose every 0.6 s, a panel icon one every 2 s (about 10 + 10 packets a second
for a default wardrobe's 6 plinths and 20 panel icons). Edit mode redraws the whole menu per notch, for the
admin alone.

## Source and tests

- Public: `spatial/` — `SpatialMenus`, `SpatialMenu`, `SpatialContext`, `SpatialCatalog`,
  `SpatialEntry`, `SpatialPreview`, `SpatialLayout`, `SpatialTheme`,
  `SpatialSpots`, `SpatialInput`, `SpatialPointer`, `Hotspot`, `SpatialClick`.
- Package-private: `MenuSession` (drawing, diffing, preview), `EditMode`, `LayoutEditor` (the web editor subject
  and its details).
- Internal: `spatial/internal/Geometry` (turned panels, carousel slots, pages,
  diffs, glass sizing, colour mixing, the readability floor and ellipsis cuts),
  `spatial/internal/Drafts` (drafts against the saved state),
  `spatial/internal/Confirm` (confirm in place), `spatial/internal/Catalogs`
  (basket totals, owned-first order, the filter cycle), `spatial/internal/UndoHistory`
  (the undo steps), `spatial/internal/WheelSteps` (wheel notches queued as item steps).
- Lifecycle: `SpatialMenus.init` in `ExyliaLib.onEnable` (the input listener and the
  hiding rule), `SpatialMenus.release(plugin)` first in `onPluginDisable`,
  `releaseAll` in `onDisable`.
- Tests: `spatial/internal/GeometryTest` (turned panels facing the eyes, hits on a
  turned panel, the carousel's symmetry, gap and curve, pagination, diffs, glass
  sizing, footers side by side, prose wrapped into a panel's lines, the item ring), `spatial/internal/WheelStepsTest`
  (a notch is one item, a flick plays in turn, reversing, the cap), `spatial/SpatialInputTest` (nearest and priority, disabled hotspots,
  cell picking and the gap, hover reported once, wheel wrap, custom pickers from
  state, refused cells falling through, hotspots that do not hover, press guards),
  `spatial/internal/ReadabilityTest` (the floor's angle, the default theme readable
  at 3.6 blocks, ellipsis cuts keeping markup, drafts against the saved state),
  `spatial/SpatialLayoutTest` (the default round-trips, a shipped layout keeps its
  values and gains the missing keys, a `BUTTONS` column reads and a single `BUTTON`
  still does; `footers` read as a row, `footer` first), `spatial/internal/UndoHistoryTest`
  (no step for a click that changed nothing, newest first, the cap, draft snapshots),
  `spatial/internal/CatalogTest` (totals in one currency only, owned
  first, the filter cycle, confirm arming, re-arming on a new subject, running out
  and disarming, button columns centred), `spatial/DefaultLayoutOverlapTest` (the
  default layout seen front-on from the eyes: no two pieces' outlines overlap,
  all inside a 70° vertical view).
