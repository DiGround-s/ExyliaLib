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
| `Enclosure` / `EnclosureTheme` | The room: six inward-facing slabs and faint stars, the blocks inside sent as air, the player hidden from everyone and everyone from them |
| `StageHud` | One deadline and one boss bar per seat: `clock`, `expired`, `secondsLeft`, `ticked`, `bar`, `stop`, `stopAll`; static `line(text, values)`, `lines`, `row` |
| `StageLook` | Helpers: `rgb(token, fallback)`, `block(name, fallback)`, `item`, `play(player, "NAME\|vol\|pitch"[, factor])`, `pitch`, `facing(plane)`, `still`, `pop` (staggered entrances), `pulse`, `fit`, `width`, `hex` |
| `kit/PlayingCard` | The 52-card deck: `shuffledDeck()`, `decks(n[, random])`, `label()`, `Suit` |
| `kit/CardSprite` | A card dealt with a glide, face up or down: `reveal`, `glow`, `lift`, `muck`, `remove`. Draws on a `CardSprite.Canvas`, which `Stage` is |
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

## Building on it

Everything a richer screen needs is public, so a plugin draws its own pieces on
top without forking the module:

- a decoration on every table: subclass `Stage` and override
  `framed(across, up, width, height)`; override `celebrate` to add to a win;
- a string of lights, a reel, a dial: `show(model, motion, at)` with
  `pose(...)`, `facing()` and `life()`, taken down with `remove` or `close`;
- a panel that follows a finished screen (play again / leave): a second `Stage`
  placed under `Stage.feet(player)`, with `StageButton`s; `Stage.retire(player,
  keep)` takes the finished screen's buttons down first;
- a side panel: another `Stage` on a plane turned to the side.

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
- Tests: `stage/StagePlaneTest` (ray-to-cell picking on every seat facing, the
  gap, columns, zoom limits, fit only before use), `stage/StageHudTest` (the
  countdown on a fake clock, sound pitch, text width), `stage/kit/KitTest`
  (decks, a throw landing square to the seat, bounces).
