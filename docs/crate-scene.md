# Crate scene

The in-world crate opening. With `crate.opening: SPATIAL` a plugin's [crate](crates.md) no longer
opens `crate_open` in the inventory: a crate built from displays stands in front of the player, a reel
of rewards slides past a marker and slows onto the prize, the lid opens, the prize's icon grows into
the middle with its name, rarity and what became of it (new, or a duplicate and the keys it gave back),
a burst and a short sound scaled to its rarity, and the prize is tried on where the crate stood. After
the last crate: OPEN ANOTHER (while the player can pay for one) and DONE. Packets only, no resource
pack. Since 1.418.0.

Entry point: `net.exylia.lib.spatial.crate.CrateScene`.

## Turning it on

Nothing to code for the opening itself: a plugin passes its crate section's `opening` to
`CrateSettings` (the 14-argument constructor, or `withOpening`), and the server owner writes

```yaml
crate:
  opening: SPATIAL     # INVENTORY (default): the reels in menus/crate_open.yml
```

The crate's question (how many) is still `menus/crate.yml`; the choice opens the scene. Without
PacketEvents, or while another plugin's spatial menu is open, the inventory reels play instead.

The plugin says how a prize is tried on, on its `CrateCatalogue`:

```java
// A kill effect: a miniature on a mannequin that falls as the kill lands (previewVanishes() defaults true).
public SpatialPreview preview(EffectDefinition effect, Player viewer) {
    return SpatialPreview.sequence(registry.sequenceOf(effect.id()), null);
}
// A hit effect lands on a body that stays.
public boolean previewVanishes() { return false; }

// Armour: the player wearing it. A shield: the item turning. null: the prize's icon alone, crate left open.
return SpatialPreview.npc(viewer, worn);
return SpatialPreview.item(shield);
```

`preview` is asked once, as the prize is revealed, on the viewer's thread: read memory only.

## What it never changes

- **The prize.** Drawn and paid for (keys spent) before anything is drawn, exactly as the inventory
  opening does. The reel is built round it afterwards: random faces, the prize where the reel stops and,
  with `near-miss`, a face of the best rarity right after it. Presentation only.
- **Granting.** Each prize is handed over once, through the same payout as the inventory reels: as its
  reel lands, or at once when the player skips. Sneaking, the SKIP item, death, a world change, a menu
  opened over it and the plugin being disabled all hand every prize not yet revealed over at once. A
  quit is paid by the crate's quit path (unlocked on the row, tokens kept for the next join).
- **One at a time.** The player cannot open another crate until the scene has handed everything over.

While it plays the player is held still (`freeze`), sees nobody and is seen by nobody (`hide-others`),
and holds only the SKIP item, as in a [spatial menu](spatial.md). One scene per player; opening it
closes this plugin's open spatial menu (the wardrobe it was opened from).

## The layout

`plugins/<Plugin>/spatial/crate.yml` (`CrateSceneLayout`), written with the library's look the first
time and given the keys a newer library adds, its values never overwritten.

```yaml
distance: 3.2             # brought nearer (never under 2) when a wall is close
hide-others: true
freeze: true
theme: {}                 # SpatialTheme: glass, plates, text sizes, readability floor, sounds
header: {up: 1.45, title: '{primary}&lCRATE OPENING', counter: '{muted}Crate {letters}%current% {letters_black}/ {letters}%total%', hint: '{muted}Sneak to skip'}
reel: {up: 0.72, slots: 7, spacing: 0.46, icon: 0.34, seconds: 4.6, faces: 32, pause-seconds: 0.6,
       near-miss: true, marker: '{highlight}', tick-sound: 'minecraft:block.note_block.hat|0.4|1.6'}
crate: {up: -1.5, width: 0.9, height: 0.5, body: POLISHED_BLACKSTONE, lid: POLISHED_BLACKSTONE_BRICKS,
        trim: GILDED_BLACKSTONE, open-sound: 'minecraft:block.ender_chest.open|0.5|1.2'}
reveal: {up: 0.2, name: '%name%', rarity: '%rarity%', won: '{success}✔ New to your collection',
         duplicate: '{warning}Already owned {letters_black}» {highlight}+%refund% {letters}keys back',
         hold-seconds: 3.5, burst: true, sounds: [orb pickup, note-block chime, bell, amethyst chime]}
preview: {scale: 0.7, turn-speed: 14, effect-scale: 0.35}
buttons: {up: -0.75, spread: 1.35, width: 1.05, again: '{success}&lOPEN ANOTHER', done: '{highlight}&lDONE'}
skip-item: {material: CLOCK, name: '{warning}&lSKIP', lore: [...]}
```

Coordinates are blocks on the board, a plane `distance` ahead of the eyes: `up` from eye height,
`across` to the right. Every line left as the library wrote it is shown in the server's language
(ExyliaLib's `language`, through its phrase table); a line you change is shown as you wrote it.
`%current%`/`%total%` count the crates of one opening (the counter hides for one), `%name%`,
`%rarity%` and `%refund%` describe the prize. Every line is drawn at least `theme.min-glyph-degrees`
tall from the eyes and cut with an ellipsis rather than shrunk.

## How it plays

| Moment | What is drawn |
| --- | --- |
| Open | The header, the reel's glass with its accent bar and marker, the crate (body, gilded bands and corners, a lock, a lid hinged on the back edge) popping in |
| Spin | `faces` + `slots` icons (GUI context, glowing their rarity's colour) sliding leftwards, largest and nearest at the marker, shrinking off the edges; fast then easing to a stop (cubic) over `seconds`. A tick sound as each face crosses the marker, rising a little as it slows; the crate's outline takes the colour of the face on the marker |
| Land | The prize is handed over. Other faces shrink away, the prize grows ×1.85, comes nearer and turns; the marker and crate take its rarity's colour; the lid swings open; the open sound and the rarity's sound; stars and a few sparks thrown out, more the rarer it is (seen by the player alone; no fireworks) |
| Reveal | Name, rarity and status under the reel. 0.7 s later, when the plugin gives a preview, the crate gives way to a pedestal and the prize is tried on there |
| Next | After `hold-seconds` (a click hurries it), the next crate of the opening, from a closed crate |
| Done | OPEN ANOTHER (when the player still has a key: on the account, or in the hand for a key-item opening) and DONE. Sneak or DONE leaves |

The preview: an NPC in the viewer's skin (nameless, turning), an item turning on the pedestal, or an
effect on a mannequin played by the viewer for the viewer alone at `effect-scale` and replayed a second
after it ends; with `previewVanishes` the mannequin is taken down while it plays (an endless effect,
such as an emote, keeps it down). An NPC lives two minutes and is replaced before that.

## Using it without the crate module

```java
CrateScene.of(plugin).play(player, new CrateShow() {
    public List<CrateFace> faces() { ... }          // what may go past
    public List<CrateFace> prizes() { ... }         // decided already, in order
    public CrateReveal reveal(int i) { ... }        // hand prize i over; never twice
    public SpatialPreview preview(int i) { ... }
    public boolean canOpenAnother() { ... }
    public void openAnother() { ... }
    public void ended(boolean quit) { ... }          // quit: the caller's own quit path pays
});
```

`play` answers `false` when nothing can be drawn, having asked nothing of the show. `CrateFace` is
`(id, icon, name, rarity, colour, rank, ranks)`; `rank` 1 is the commonest, `ranks` the rarest.

## Contracts

- **Threads.** `play` on the player's thread; everything else runs on their pointer's poll (every two
  ticks) on that thread. `close(player)` and `isPlaying(player)` are safe from anywhere.
- **Nothing left behind.** Every display and NPC is the plugin's and is taken down when the scene ends
  (deflating in 170 ms); pieces an effect preview drew live their own short life. Every piece lives at
  most ten minutes.
- **Sounds and celebrations** are short, quiet, and played to the player alone.

## Source and tests

- Public: `spatial/crate/` — `CrateScene`, `CrateSceneLayout`, `CrateShow`, `CrateFace`, `CrateReveal`;
  `util/crate/CrateOpening`, `CrateSettings.opening`, `CrateCatalogue.preview` / `previewVanishes`.
- Package-private: `SceneSession` (drawing and the clock), `ReelPath` (the reel's maths).
- Wiring: `util/crate/internal/CrateScreens.runSpatial` turns a paid spin into a `CrateShow` over the
  same once-only `Reel` guards the inventory reels use. `SpatialMenus.hideOthers(player, hidden)` is the
  menus' hiding rule for screens built outside them.
- Tests: `spatial/crate/CrateSceneTest` (the reel stops on the prize for every size, starts and stops
  with the window full, never speeds up or runs back, a near miss touches only the face after the prize,
  sizes along the reel, the layout round-trips, the switch defaults to the inventory and survives a
  block bind). In game: `test-servers/bot/crate-test.js`.
