# Scoreboard module

Packet sidebars declared in config, built on scoreboard-library 2.8.1
(shaded and relocated to `net.exylia.lib.internal.scoreboardlibrary`).
Since 1.5.0.

Entry point: `net.exylia.lib.scoreboard.Scoreboards`.

## Declared in config, not in Java

```java
Scoreboards.show(this, player, config.ffa());
```

`SidebarConfig` nests in the plugin's config record:

| Field | Meaning |
| --- | --- |
| `enabled` | show at all |
| `title` | title lines |
| `lines` | body lines |
| `update` | `interval` (in **ticks**, like ExyliaCommons), `smart`, `cache` |

The YML keys are the ones ExyliaCommons wrote, so migrating a plugin does not
make the server owner touch their file. The interval being ticks is a
deliberate, scoped deviation from the seconds-with-decimals rule the rest of
the library follows — an existing `interval: 15` must keep meaning 15 ticks.

## API

`Scoreboards`:

| Method | Contract |
| --- | --- |
| `show(plugin, player, config)` / with `data` | show a board; returns the `Board` |
| `hide(player)` | remove the visible board |
| `get(player)` / `has(player)` | queries |
| `isSupported()` | false when scoreboard-library cannot run here |

`Board`: `player()`, `config()`, `refresh()`, `updateData(Map)`,
`stop()`, `stopped()`.

## Behavior

- **Boards stack per player**: showing one pauses the previous; closing the
  new one brings the old one back. A paused board renders nothing.
- **Only what changed is sent.** The diff runs on the rendered string, before
  parsing and before packets. An unchanged board costs a string compare and
  zero packets.
- **The raw template is parsed, not the resolved one** — measured 26.8µs for
  a full re-render vs 4.2µs per changed line.
- **One async timer moves all boards**, offset by UUID so renders do not pile
  into the same tick. That is also why a PlaceholderAPI placeholder in a line is
  left as written rather than resolved: see [placeholders](placeholders.md).
- **A render that fails is reported once per board, with the stack.** A board
  renders every interval for as long as its player is online, so the message
  used to repeat per player per tick and carried only `getMessage()` — enough
  noise to bury the one line that said where the failure came from.
- **The slot is taken back every 3 seconds.** Nothing tells a plugin that
  another one claimed the client's sidebar, and every server has something that
  does: a global scoreboard plugin, TAB, a minigame with its own copy of the
  packet library. A visible board re-sends the display-slot packet on a timer —
  one packet per player per sweep with PacketEvents installed, and nothing
  blinks — and instantly after a teleport, a world change, a respawn and a
  join. Without PacketEvents the board is re-sent instead, which also works.
- **TAB is asked to stand down rather than out-shouted.** When TAB is
  installed, the first board shown to a player turns its sidebar off for that
  player, and the last board taken down turns it back on. Only a player whose
  TAB sidebar was visible is touched, and only their own setting is changed.
- Nothing outlives its owner: quit, plugin disable and palette reload (which
  re-sends everything, since the text is the same but what parses it changed)
  all clean up.

## Source and tests

- Public: `scoreboard/Scoreboards.java`, `Board.java`, `SidebarConfig.java`.
- Internal: `scoreboard/internal/` (`BoardImpl`, `BoardManager`,
  `MegavexSidebar`, `NoopBoard`, `SidebarFactory`, `SidebarHandle`,
  `SidebarLibrary`, `TabHook`).
- Tests: `src/test/java/net/exylia/lib/scoreboard/`.
