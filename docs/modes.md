# Modes

Every way to play the plugins on a server offer — a board game, an event, a
queue, an arena — registered into shared categories, and one screen that lists
them with their state and player count live. *Since 1.356.0.*

Entry point: `net.exylia.lib.modes.Modes`.

```java
@Override
public void onEnable() {
    Modes.category(this, "tabletop", "OAK_SIGN", "Tabletop", 10,
            "Board games against", "another player.");
    Modes.register(this, Mode.of("chess", player -> lobbies.open(player, "chess"))
            .category("tabletop")
            .icon("BLACK_CONCRETE")
            .name("Chess")
            .description("Checkmate the other king", "before yours falls.")
            .open(() -> games.isPlayable("chess"))
            .players(() -> matches.playing("chess"))
            .build());
}

// From a command, an NPC, a lobby item:
Modes.open(player);               // every category
Modes.open(player, "tabletop");   // straight to one
```

## A mode

`Mode.of(id, action)` — the id is lower case letters, digits, `_`, `.` or
`-`, unique within the plugin; the action is what a click does.

| Builder | Default | |
| --- | --- | --- |
| `category(id)` | `general` | the category it is listed under; several plugins may share one |
| `icon(material)` | `PAPER` | anything a menu's `material:` accepts |
| `name(text)` | the id | plain text, drawn in capitals |
| `description(lines...)` | none | body lines, palette tokens allowed |
| `order(n)` | `0` | lower first within its category, then by name |
| `permission(node)` | none | a player without it sees the mode locked |
| `open(BooleanSupplier)` | always open | live: a closed mode is shown, not hidden |
| `players(IntSupplier)` | not shown | live: on the card, and added up on its category's |
| `playing(Predicate<Player>)` | claims nobody | whether a player is in it now, for `Modes.current` (*1.386.0*) |

`open` and `players` are asked every second while the screen is open, on the
viewer's thread: read memory (a map, a counter the plugin keeps), never a
database or a file.

A click on an open mode the player may play closes their screen and runs the
action on their thread. The card is checked again first, so a mode that closed
or went away a second before the click answers with a refusal instead.

## Categories

`Modes.category(plugin, id, icon, name, order, description...)` describes a
category's card. A category id is global, not a plugin's: two plugins listing
modes under `games` share one card, and whoever describes it last decides its
name, icon and order. A category nobody describes is still shown, named after
its id and drawn as its first mode, after the described ones. A category with
no modes is not shown.

## Reading the catalog

| Call | Answers |
| --- | --- |
| `Modes.modes()` | every mode, by category order, then mode order and name |
| `Modes.modes(category)` | one category's modes |
| `Modes.categories()` | every category with at least one mode, by order and name |
| `Modes.current(player)` | the mode whose `playing` claims the player, or `null` (*1.386.0*); what the ability `mode: { id }` condition asks |
| `Modes.unregister(plugin, id)` | removes one mode |

For a plugin that draws its own selector from the same catalog. Lists are
copies, read from memory, safe from any thread.

## The screen

- **Hub** (45 slots): a header at 4 with everybody playing, up to seven
  category cards centred on the third row. Past seven categories the hub is the
  paged list instead. With a single category it is skipped.
- **List** (54 slots): one category's modes on slots 0-35, page arrows at 48 and
  50, BACK at 49 to the hub. `Modes.open(player, category)` opens it as a root
  screen, without BACK.
- **Cards** show the description, the state (`Open`, `Closed`, `Locked`), the
  players when counted, and `Click to play` only when it can be played. Rows are
  built again on every redraw, so a mode that opens, closes or is registered
  shows without reopening.

The screens are the server's to restyle: files in the library's language folder,
`plugins/ExyliaLib/lang/<code>/menus/modes/` (`modes.yml` for the hub,
`modes_list.yml` for the list), installed and updated the same way as the
[settings screens](settings.md#the-screen): an update only adds missing keys, a
file that does not parse is reported and the packaged English one is shown. The
hub leaves `pagination.slots` to the library. The cards' own text is the
library's phrase table.

## Lifecycle

Everything a plugin registered, its modes and the categories it described, is
forgotten when it is disabled. Register from `onEnable`; registering an id again
replaces it. Nothing is stored. Nothing here is derived from the palette beyond
a single render; the screens are recompiled with the library's other menus on
`/exylialib reload`.

## Source and tests

- Public: `modes/Modes.java` (`Category`), `modes/Mode.java` (`Builder`).
- Internal: `modes/internal/ModesMenu.java`, compiled into the library's menus
  from `internal/UpdatesMenu`.
- Library hooks: `Modes.release` in `releaseAfterDisable`, `Modes.releaseAll`
  on shutdown.
- Tests: `src/test/java/net/exylia/lib/modes/internal/ModesTest.java` — shared
  and undescribed categories in order, replacing an id, release taking only the
  owner's modes, `current` following `playing`, live state and player counts on a card, locked modes, and every
  screen compiling with the hub centred and the root list without BACK.
