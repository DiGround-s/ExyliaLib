# Stage

Per-player screens standing in the world, built from packet displays: a board in
front of a seat, buttons picked by looking at them, a dark room round the
player, a countdown with boss bars, and a kit of cards and dice. Nothing is an
entity and nothing is a real block. Since 1.356.0.

Entry point: `net.exylia.lib.stage.Stage`.

## Using

```java
PluginDisplays displays = Displays.of(this);
PluginPackets packets = Packets.of(this);
Enclosure rooms = new Enclosure(displays, packets.fakeBlocks(), packets.visibility());

// On the player's thread, where they stand.
Location seat = player.getLocation();
rooms.open(player, seat, config.get().room());                 // an EnclosureTheme

StagePlane plane = StagePlane.inFrontOf(seat, 1.62, 2.5, 0, 3, 3, 0.5, 0.45);
Stage stage = new Stage(player, plane, displays, 120_000L, config.get().stage());   // a StageTheme
stage.table(0, 0, 1.8, 1.8);                                   // first: it also sizes the board
stage.heading(StageHud.line("{primary}&lPICK ONE", null), StageHud.line("{letters}Look and click", null), 1.05);
StageButton ok = stage.button(StageHud.line("{success}&lOK", null), 0, -1.1, 0.7, 0.22, 200);

// Every couple of ticks, on the player's thread.
ok.hover(ok.under(player));
int cell = plane.pick(player.getEyeLocation());               // -1 when on no cell

// On a swing.
if (ok.under(player)) ok.press();

// Done.
stage.close();
rooms.close(player.getUniqueId());
```

Not only for games: a tutorial panel, a 3D selector or a confirmation screen is
a stage with a table, a heading and a few buttons.

## The pieces

| Type | What it is |
| --- | --- |
| `StagePlane` | Pure geometry: a grid upright in front of a seat. `inFrontOf(seat, eyeHeight, distance, lift, columns, rows, pitch, cell)`, `point(across, up, forward)`, `cell(index, forward)`, `grid(...)` for a second grid on the same plane, `hit(eyes)` → `Hit(across, up)`, `pick(hit)` (a cell or `-1`; the gap between cells misses), `column(hit)`, `Hit.inside(...)`, `fit(...)` and `zoom(...)` |
| `Stage` | What one player sees. Primitives: `show(model, motion, at)`, `block`, `item`, `text`, `remove`, `close`; poses: `still`, `pop`, `pose(millis, across, up, forward, x, y, z)`; screen parts: `table`, `header`/`status`, `heading`/`figures`, `verdict(text, Outcome, delay)`, `button`, `pulse`, `celebrate`, `deflate`. Static `feet(player)` and `retire(player[, keep])` |
| `StageButton` | A plate with a fitted label: `contains(hit)`, `under(player)`, `hover(on)`, `press()`, `label(text)`, `remove()` |
| `StageTheme` | The look a plugin passes in: table, frame, buttons, verdict, win effects, button sounds, zoom. A record with `@Comment`s and a no-argument default, so it embeds in the plugin's config |
| `Enclosure` / `EnclosureTheme` | The room: six inward-facing slabs and faint stars, the blocks inside sent as air and kept so, the entities inside hidden, the player hidden from everyone and everyone from them (see [The room](#the-room-1420)) |
| `PlaySpot` | Where an admin set a plugin's rooms up, and the way back for each player sent there (1.420.0) |
| `StageHud` | One deadline and one boss bar per seat: `clock`, `expired`, `secondsLeft`, `ticked`, `bar`, `stop`, `stopAll`; static `line(text, values)`, `lines`, `row` |
| `StageGuide` | Two panels at a seated player's sides, a quarter turn away from the board: a title over lines on each, rows optionally with an item. `new StageGuide(viewer, seat, displays, theme, look, leftTitle, leftRows, rightTitle, rightRows)`, `rows(lines, values)`, `close()`; `Look(distance, lift, scale)` embeds in a plugin's config (since 1.362.0) |
| `StageLook` | Helpers: `rgb(token, fallback)`, `block(name, fallback)`, `item`, `play(player, "NAME\|vol\|pitch"[, factor])`, `pitch`, `facing(plane)`, `still`, `pop` (staggered entrances), `pulse`, `fit`, `width`, `hex` |
| `kit/PlayingCard` | The 52-card deck: `shuffledDeck()`, `decks(n[, random])`, `label()`, `Suit`; the 40-card Spanish deck: `spanishDeck()`, `spanishLabel()` (since 1.361.0) |
| `kit/CardSprite` | A card dealt with a glide, face up or down: `reveal`, `glow`, `lift`, `muck`, `remove`. Draws on a `CardSprite.Canvas`, which `Stage` is. `Style.with(Deck.SPANISH)` draws Spanish faces: the number over a sword, club, coin or cup in the suit's colour (since 1.361.0) |
| `kit/DieSprite` | A cube with pips on the face towards the seat: `thrown(...)`, `roll(value, millis)`, `throwFrom`, `show`, `hide`, `toss`, `glow` |
| `kit/StageFx` | `stamp` (a label slammed onto free table), `fling` (items thrown on an arc), `burst`, `play` (pitch climbing, clamped), `Drumroll` |

## A theme per plugin

The library reads no file for the look. Each plugin ships its own default by
building a `StageTheme`/`EnclosureTheme` (or embedding the records in its
config, where the YAML is generated from their comments), and passes it in. Two
plugins on one server keep their own look. `new Stage(viewer, plane, displays,
life)` uses `StageTheme.defaults()`: a dark table in the palette's primary
colour, no zoom.

`StageButton` plays the theme's `hoverSound` when it lights and `pressSound`
when pressed. A plugin that plays its own passes a theme with blank sounds
(`withSounds("", "")`).

## The first table sizes the board

`Stage.table(...)` brings the plane nearer by the theme's `zoom`, as far as the
table (with room for a heading) still spans no more than `maxWide` by `maxTall`
degrees and stands no nearer than `nearest` blocks. It only moves a plane
nothing was placed on yet, so **call `table` before anything reads the plane**:
a point taken before it is where the board was before the zoom.

## Tables in the world (1.367.0)

A screen is its viewer's alone until others are let in. `Stage.watchers(viewer, others)`
shows every stage the viewer has open, in the pose it is in, and every stage drawn
for them later, to those players too; `Stage.watchedWithin(viewer, radius)` lets in
whoever stands near (not one another plugin holds in a session, who may be in a
room of their own) and is called again every half second as people come and go,
from the viewer's thread. An empty list gives the screens back to the viewer alone:
call it when their game ends. A bystander near several players shown this way (a table of eight) watches only the
nearest one's screens (1.374.0), so they see one board, not every board crossed over the
table, and a crowded table costs its crowd once rather than once per seat. Only the viewer's clicks, hovers and sounds count; a
win's burst of stars and a loss's smoke are the viewer's alone (1.371.0). Built on
`DisplayHandle#viewers(list)`, which changes who sees one display mid-motion.

`PlacedTables` keeps the places an admin set down for a game: a spot where the player
stands facing the board, a hologram `ahead` blocks in front of it that reads free or
in use and takes the click, and who plays there. Kept in a YAML file in the plugin's
folder.

```java
tables = new PlacedTables(plugin, "tables.yml", 2.5, hooks);   // look(table), lines(table, occupant), clicked(player, table)
tables.place("blackjack-1", "blackjack", admin.getLocation());
tables.claim(id, player.getUniqueId());                        // false: someone else is at it
tables.release(id, player.getUniqueId());
tables.remove(id); tables.all(); tables.of(player); tables.refresh(); tables.close();
```

Its holograms are `table:<id>`. A second set of places in the same plugin passes
its own namespace, `new PlacedTables(plugin, file, ahead, hooks, "spot")`, so ids
never collide (since 1.408.0; spatial menus' spots use `spatial-spot`).

A table game skips the `Enclosure` (the point is to be seen) and confines the player
to a small square at the spot with `Movement#confine`.

### Setting a table down by clicking it (1.371.0)

`tables.setup(admin, tables.nextId(game), game, setupText, placed -> ...)` turns the
admin's hotbar into tools for a while (drawn over it, nothing moves): left-click a
chair (stair, slab, block) to make it a seat or take it back, right-click a block to
put the board there, DONE (slot 8) sets the table down, the barrier (slot 9) cancels.
Seats are numbered over the chairs for the admin as they go and face the board (the
right-clicked block, else the middle of the seats; one seat with no board clicked
faces the way the admin looks). `SetupText` is the plugin's wording; `setup(admin, id, game, done)` (1.373.0) uses the library's own (`tables` in its messages, every language), as the seat lines do by default. `place(id, game,
board, seats)` is the same without the clicking; `nearest(location, radius)` finds the
table an admin stands at, `nextId(game)` names a new one. `Hooks.seatLines(table, seat,
occupant)` floats a small clickable line over each seat (where to sit); the table's
own look (`look(height)`) turns round the vertical axis only and never grows on hover.

### A stage for everyone near (1.377.0)

A board that belongs to a place rather than a player (a live roulette everybody near bets on) is a
public stage: `new Stage(plane, displays, life, theme, radius, magnify)` (a subclass passes the same to
`super`). Every piece is a display shown by radius, so it is drawn once whoever comes and goes, and a
crowd costs the server what one viewer costs. It has no viewer (`viewer()` is `null`, `isPublic()` is
`true`): no clicks, hovers or buttons; sounds, smoke and `StageFx.burst` go to
`spectators()` (a win draws no burst of stars: nobody owns the moment), whoever is within the radius now (read from the world: call it on the region thread of
where it stands). A screen written for a player (a game's view) is drawn publicly unchanged once it
takes this constructor.

`magnify` draws everything that many times larger about the plane's middle, so a screen laid out for
a seat two blocks away reads from across a room. Pieces, poses and glides stay in the stage's own units:
a handle's `then`, `moveTo` and `pose()` are magnified on the way in and read back unmagnified
(`DisplayMotion#magnified` and `DisplayKeyframe#magnified` do the arithmetic). Build the plane with
`StagePlane`'s constructor: it is not brought nearer by `table`'s zoom.

### What watchers must not see (1.370.0)

A game with hidden information (a hand, dice under a cup, a fleet) is watched too:
draw the secret inside `stage.secretly(() -> ...)`, seen by the viewer alone whoever
watches, and what watchers see in its place inside `stage.toOthers(() -> ...)` (drawn
even before anyone watches, so latecomers get it). A `CardSprite` dealt with
`held = true` does both by itself: its face for its holder, its back for watchers,
until `uncover()` (a showdown) shows it to all. Pieces keep their scope when people
start or stop watching. `PluginDisplays#showKept` is the display drawn for nobody
yet that `toOthers` stands on.

### Seats and chairs (1.369.0)

A table for several players is placed at its centre and given seats one by one:
`addSeat(id, location)` where each player sits facing their board, `clearSeats(id)`
to start over. Players take the first free seat with `sit(id, uuid)` (its index, or
-1 when full), read with `seated(id)` / `seatOf(id, uuid)`, and leave it with
`release`. A table without seats is still one seat at its spot, as before. Its
hologram stands over the centre; `Hooks.lines(table, seated)` reads every seat,
`Hooks.lift(table, seated)` raises it clear of the boards while played, and
`update(id)` redraws it when the lines read something else (a stake). `look(height)`
is the shared hologram look, `icon(icon)` the line tag that floats a game's icon.

`Chairs.of(plugin).sit(player, at, stoodUp)` sits a player down (on their thread, at
the seat): they ride an invisible block display, hips on `at` (feet 0.6 below), so put
seats on stairs or slabs. Sneaking stands an unlocked player up and runs `stoodUp`;
`lock(player, true)` keeps them seated (while a match is played); `stand(player)` is
the plugin standing them up. Quitting, dying and the plugin's disable take the seat
away; it is never saved.

## The room (1.420.0)

Everything here is the viewer's alone; everyone else sees the real world.

- **Blocks.** Every block that reaches into the room (its square grown by the
  block's half diagonal, from the floor layer to the ceiling) is sent as air and
  kept so with `FakeBlocks#keep`: walls, plants tall or short, doors and beds
  (each half on its own), signs, banners, fences, walls, leaves. A block placed,
  broken or updated there by anyone while it is open, or the chunk sent again,
  stays air to them.
- **The floor.** The player keeps standing on the real block under their feet:
  the floor layer is read half a block down (`floor(feetY - 0.5)`), and a block
  whose collision tops out at or under the feet is the floor and stays real (a
  slab, a carpet, farmland, soul sand, a bed). Where the floor layer has nothing
  to stand on (the void, the edge of a one-block platform, water) or pokes up past
  the feet, it is a barrier only this player has, under the walkable square only.
  The drawn floor sits on top. Under the floor layer the world stays as it is.
  The server's half is `Movement#confine`: back to the centre on a fall or a
  flight, no fall damage, no flying kick.
- **Entities.** Every few ticks, on the player's thread, whatever stands in the
  room (mobs, items, item frames, paintings, armour stands, projectiles, display
  entities, other plugins' entity holograms and NPCs) is hidden from the player
  with `Player#hideEntity` and shown again once it leaves or the room closes.
  Entities other plugins draw in packets alone are kept out with
  `Visibility#seal` when they spawn inside; displays and hitboxes are not, so this
  plugin's screens stay. Players: with the walls drawn every other player is hidden
  (their names would show through the walls); with `enabled: false` only those
  inside the room are, and they come back when they step out.
- **Limits.** A packet-only entity another plugin had already drawn in the room
  before it opened stays until that plugin draws it again, and one that walks in
  by relative moves is not caught.

### A play spot

```java
spot = new PlaySpot(plugin, "play-spot.yml", uuid -> games.isPlaying(uuid));
spot.set(admin.getLocation());            // <plugin>admin spot set; spot.clear()
spot.send(player).thenAccept(at -> ...);  // to the spot (Teleports, no warmup), or where they stand when none
spot.giveBack(player);                    // the game ended or they left
spot.close();                             // onDisable
```

Every player goes to the same spot: a room is drawn for its player alone and
every other player is hidden from them, so any number play there at once, each
in their own room, with no offsets to fit into the terrain. Where each came from
is written to the plugin's file before the move and taken out once they are back:
one who quit or was there when the server stopped hard is sent back on their next
join (unless `playing` says the plugin still has them), one who died respawns
there, and `close()` moves everyone still away synchronously at shutdown (on
Folia they go back on their next join).

## Building on it

Hover, clicks, the wheel and sneaking are not a stage's: attach a
[`SpatialPointer`](spatial.md#input-for-games) and register the board's cells and
buttons as `Hotspot`s (`Hotspot.cells(plane)`, `Hotspot.of(button)`) instead of
keeping a hovered index, polling `plane.hit` and listening for swings. A menu
made of panels, lists and a carousel is a [spatial menu](spatial.md).
`StageButton.plane()` (1.406.0) is the plane a button measures hits on.

Everything a richer screen needs is public, so a plugin draws its own pieces on
top without forking the module:

- a decoration on every table: subclass `Stage` and override
  `framed(across, up, width, height)`; override `celebrate` to add to a win;
- a string of lights, a reel, a dial: `show(model, motion, at)` with
  `pose(...)`, `facing()` and `life()`, taken down with `remove` or `close`;
- a panel that follows a finished screen (play again / leave): a second `Stage`
  placed under `Stage.feet(player)`, with `StageButton`s; `Stage.retire(player,
  keep)` takes the finished screen's buttons down first;
- a side panel: another `Stage` on a plane turned to the side; for how a game is
  played and won, `StageGuide` draws both.

## Contracts

- **Threads.** A stage, its plane, its buttons and a `StageHud` are used from one
  thread at a time: the one that runs the screen. Drawing sends packets and
  touches no world state. `StageButton.under` and `StagePlane.pick(Location)`
  read the player's eyes, so they run on the player's thread.
  `Enclosure.open` reads blocks round the player and runs on the player's
  thread (their region on Folia); `Enclosure.close` is safe from any thread.
- **Lifetime.** Every piece lives at most the stage's `life`; `close` takes
  everything down at once. The room lives until `close`. Everything a plugin
  drew, faked or hid is undone when it is disabled (the display and packet
  modules), and the library forgets its open stages then.
- **Visibility.** `new Enclosure(...)` installs the plugin's visibility rule
  (one per plugin). A plugin with a rule of its own composes the two:
  `visibility.rule((v, t) -> rooms.allows(v, t) && mine(v, t))`.
- **Inventory.** `rooms.overlay(Overlays.of(this), () -> definition)` dresses
  every player in a room in an [overlay](overlays.md) until the room closes.
  With `hideRest()` and one leave button, all they see is the screen and the
  way out (1.358.0+).
- **Sneaking.** A frozen player (`Movement.freeze`) may still sneak on foot,
  so the eyes `StagePlane.pick` reads match the client's camera (1.358.0+).
- **Button labels.** A label of one or two glyphs (`+`, `-`, an arrow) is an
  icon and is drawn as tall as its plate holds (1.358.0+).
- **No PacketEvents, nothing drawn.** Pieces come back `null`; every method
  accepts a `null` piece. Picking still works: it is server-side maths.
- **Reload.** Nothing is cached from the palette: colours are read when a piece
  is drawn, so a reload restyles the next screen. Not hooked into
  `ExyliaLib.loadPalette`.

## Source and tests

- Public: `stage/` — `Stage`, `StagePlane`, `StageButton`, `StageTheme`,
  `StageHud`, `StageLook`, `Enclosure`, `EnclosureTheme`; `stage/kit/` —
  `PlayingCard`, `CardSprite`, `DieSprite`, `StageFx`.
- Lifecycle: `Stage.release(plugin)` in `ExyliaLib.onPluginDisable` (before
  `Displays.release`), `Stage.releaseAll()` in `onDisable`.
- Tests: `stage/EnclosureTest` (the floor layer and what each block is sent as),
  `packet/VolumeTest` (the turned square, heights, boxes touching it),
  `packet/internal/PenTest` (falls, flights and far strays go back to the centre),
  `stage/StagePlaneTest` (ray-to-cell picking on every seat facing, the
  gap, columns, zoom limits, fit only before use), `stage/StageHudTest` (the
  countdown on a fake clock, sound pitch, text width), `stage/kit/KitTest`
  (decks, a throw landing square to the seat, bounces).
