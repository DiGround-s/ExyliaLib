# Interaction module

What happens when a player clicks something in the world — a hologram, an NPC,
anything a plugin draws — written as a list of lines. One syntax for every
clickable thing, compiled once. Since 1.291.0.

Entry point: `net.exylia.lib.interaction.Interactions`.

## Using

```java
Interaction onClick = Interactions.compile(this, config.getStringList("click"), "npc \"guide\"");

// when somebody clicks it — from any thread, the network thread included
onClick.run(player, Click.of(attack, player.isSneaking()), Map.of("npc_id", npc.id()));
```

```yaml
click:
  - '[SOUND] UI_BUTTON_CLICK;1;1.2'
  - 'left: [MESSAGE] {letters}Right-click to join {highlight}%arena%{letters}.'
  - 'right: arena join %arena%'
  - 'shift_right: console: arena reset %arena%'
  - 'right: [DELAY] 1s'
  - 'right: [SERVER] lobby'
```

Holograms already use it: `click.commands` are interaction lines (see
[hologram.md](hologram.md#clicks)).

## A line

An optional **click filter**, then one action.

| Filter | Runs on |
| --- | --- |
| none, `any:` | every click |
| `left:` / `right:` | that button, sneaking or not |
| `shift_left:` / `shift_right:` | that button while sneaking |
| `shift:` | either button while sneaking |
| `left,right:` | any of a list |

`shift-left` is read as `shift_left`, and case does not matter. `left:` means
only the plain left click, as in menus.

| Action | What it does |
| --- | --- |
| `console: ...`, `player: ...`, `player-proxy: ...`, `console-proxy: ...`, a bare line | a command, exactly as [`CommandLine`](../src/main/java/net/exylia/lib/command/CommandLine.java) reads it; bare runs as the player |
| `[CONSOLE] ...`, `[PLAYER] ...` (1.502.0) | the same as `console: ...` and `player: ...` (the DeluxeMenus spelling) |
| `[DELAY] 1s` | waits before every line after it: seconds (`0.5`) or written out (`1m30s`) |
| `[SERVER] lobby` | sends the player to another server — `PluginTeleports.toServer`, no Redis needed |
| `[TELEPORT] world;x;y;z[;yaw;pitch]` | moves them on this server through the [teleport module](teleport.md) (commas work too) |
| `[ACTION] namespace:id args` | runs a registered [action](actions.md) for the player |
| `[MONEY] take\|give <amount> [currency]` | charges or pays them through the [economy](economy.md) (1.501.0); a take they cannot afford stops the lines after it |
| `[BROADCAST] {highlight}%player_name% {letters}won!` | one chat line to everybody online and the console, with the clicking player's placeholders (1.552.0) |
| `[URL] https://dc.exylia.net;Discord` | offers an address in a native dialog (open or copy, then Minecraft's own "open this page?" screen), so it works while a [screen](screens.md) is up; the title after `;` is optional; a clickable chat line on clients older than 1.21.6 (1.554.0) |
| `[CHANCE] 30` | the lines after it run 30% of the time; otherwise the list stops there (1.552.0) |
| `[HUD] <text>[;anchor:top-right][;seconds:3][;group:kill]` | a notice sliding onto the player's [HUD](hud.md); a `group` counts repeats (1.566.0) |
| `[MOMENT] <text>[;seconds:2]` | a big moment just above the crosshair, replacing the one up (1.566.0) |
| `[SAY] ...` or any token a plugin registered | that plugin's handler |
| any other `[TOKEN]` | a [sequence](sequences.md) line — `[MESSAGE]`, `[SOUND]`, `[TITLE]`, `[ACTION_BAR]`, `[TOAST]`, `[PARTICLE]`, `[FIREWORK]`... — played at the player, seen only by them |

`%player_name%` and every registered placeholder resolve for the clicking
player; the `data` map given to `run` fills `%name%` first.

## Tokens of your own

```java
Interactions.token(this, "SAY", (player, click, text, data) ->
        bubbles.say(npcs.get((String) data.get("npc_id")), text));
```

The handler runs on the clicking player's thread, in its place in the list,
with everything after `[SAY]` rendered. Registering after the lines compiled
works too (since 1.392.0): a token that is not an effect is looked up when the
line runs, so another plugin that enables later still reaches lines compiled
before it. A token nobody has registered by the time it is clicked is reported
once per plugin, naming it, and the line is skipped; the rest still run. `DELAY`, `SERVER`, `TELEPORT`, `ACTION`, `MONEY`, `BROADCAST` and `CHANCE` are built in and
cannot be taken; a token another plugin holds throws. Tokens are released when
their plugin disables, and a compiled line whose token was released does
nothing.

A token that means something to one clicked thing only is passed to
`compile` instead of registered (since 1.297.0):

```java
Interactions.compile(this, lines, "hologram \"rules\"",
        Map.of("PAGE", (player, click, argument, data) -> turn(player, argument)));
```

It is known to those lines alone, wins over a registered token of the same
name, and runs like one. Holograms use it for `[PAGE]`.

## Contracts

- **Compiled once, never throws.** A line that cannot be read (a broken effect,
  `[DELAY] soon`, `[TELEPORT] world;1;2`, a filter with nothing after it) is
  reported once through `Debug`, naming where it came from, and left out.
  `lines()` lists what survived.
- **In order, each on its own thread.** One line at a time: commands on the
  thread their actor needs, tokens, effects and actions on the player's, and a
  `[DELAY]` on the scheduler. `run` returns a future that completes after the
  last line.
- **What stops a list.** A command the server refuses or an action that does
  not succeed stops the lines after it, as a command list always has; so does a
  player who leaves. A line that throws is reported and the rest still run.
- **`[ACTION]` is resolved when it runs**, so an action another plugin registers
  after these lines load still counts; an unknown one is reported each time.
- `runToEnd(player, click, data)` (1.501.0) is `run` answering whether every line went through (`false` when one
  stopped the list or the player left), for a caller with more to do afterwards.
- `handles(click)` says whether any line answers a click, `isEmpty()` whether
  any line survived. `Interaction.empty()` does nothing.

## Not here

- No `[CLOSE]`: nothing is open when a player clicks something in the world.
- No permission or condition prefix per line; gate in the plugin, or with a
  `[ACTION]` registered with a permission.
- `[TELEPORT]` is this server only; another server's place is
  `PluginTeleports.to(player, ExyliaLocation)` in code.

## Source and tests

- Public: `interaction/Interactions.java`, `Interaction.java`,
  `InteractionToken.java`, `Click.java`.
- Tests: `src/test/java/net/exylia/lib/interaction/InteractionsTest.java`,
  hologram clicks in `hologram/internal/HologramTest.java`.
