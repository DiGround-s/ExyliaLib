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
  into the same tick.
- Nothing outlives its owner: quit, plugin disable and palette reload (which
  re-sends everything, since the text is the same but what parses it changed)
  all clean up.

## Source and tests

- Public: `scoreboard/Scoreboards.java`, `Board.java`, `SidebarConfig.java`.
- Internal: `scoreboard/internal/` (`BoardImpl`, `BoardManager`,
  `MegavexSidebar`, `NoopBoard`, `SidebarFactory`, `SidebarHandle`,
  `SidebarLibrary`).
- Tests: `src/test/java/net/exylia/lib/scoreboard/`.
