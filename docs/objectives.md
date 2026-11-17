# Objectives

What players do, turned into objective progress that any quest, mission,
journey or battle pass plugin subscribes to. One set of listeners serves every
plugin, and each event reaches only the plugins that want that player now.

Since 1.620.0. Package `net.exylia.lib.objectives`.

## Subscribing

```java
ObjectiveIndex<ObjectiveType> waiting = new ObjectiveIndex<>();   // who has an open mission of each type

Objectives.of(this).listen(ObjectiveType.BREAK_BLOCK,
        player -> waiting.has(ObjectiveType.BREAK_BLOCK, player.getUniqueId()),
        progress -> {
            if (progress.placedByPlayer()) return;                // placed and broken again
            for (Mission mission : missions.of(progress.player())) {
                if (Objectives.matches(mission.filter(), progress.thing())) mission.add(progress.amount());
            }
        });
```

- `listen(type, wants, handler)` returns a `Subscription`; `cancel()` stops it.
  Every subscription of a plugin goes away when the plugin is disabled.
- `wants` is asked before anything about the event is read, so a type nobody
  wants costs one list check per event. It may run on any thread (chat is
  asynchronous): read memory only, such as an `ObjectiveIndex`.
- `handler` runs on the player's thread (Folia included). A handler that
  throws is logged under its plugin and the others still run.
- The listeners are registered under the library the first time any plugin
  subscribes: a server with no objective plugin pays nothing.

`ObjectiveIndex<K>` (`has`, `any`, `set`, `remove`) answers "does this player
wait for this kind" with one set lookup, from any thread. It is keyed by
anything, so a plugin may index its own step kinds too.

## Types

| `ObjectiveType` | Fired when | `thing` | `amount` |
| --- | --- | --- | --- |
| `BREAK_BLOCK` | a block is broken (`HIGHEST`) | material | 1 |
| `PLACE_BLOCK` | a block is placed; one spot counts once per player per session | material | 1 |
| `INTERACT_BLOCK` | a block is right-clicked with the main hand and not denied (cancelled events count: protection plugins cancel doors and buttons players may use) | material | 1 |
| `CRAFT` | an item is crafted | result material | recipe result amount |
| `SMELT` | smelted items are taken out of a furnace | material | items taken |
| `ENCHANT` | an item is enchanted at a table | item material | 1 |
| `FISH` | an item is caught fishing | material | stack size |
| `PICKUP_ITEM` | an item nobody dropped is picked up | material | stack size |
| `CONSUME` | an item is eaten or drunk | material | 1 |
| `EQUIP` | a piece of armour is put on | material | 1 |
| `LEVEL` | experience levels are gained | the new level | levels gained |
| `KILL_MOB` | a player kills a mob | entity type | 1 |
| `KILL_PLAYER` | a player kills another | `PLAYER` | 1 |
| `REACH` | a player walks into a library [region](regions.md) | region id, `namespace:value` | 1 |
| `INTERACT_NPC` | a placed [NPC](npcs.md) is clicked, any plugin's | NPC id | 1 |
| `RUN_COMMAND` | a command is run; delivered on the next tick, after the command | the line as typed | 1 |
| `CHAT` | a chat message is sent; delivered on the player's thread | the plain message | 1 |
| `CUSTOM` | a plugin calls `Objectives.fire` | the thing it gave | the amount it gave |

## Progress

`ObjectiveProgress(player, type, thing, at, amount, placedByPlayer, origin, victim, customType)`:

- `placedByPlayer`: for `BREAK_BLOCK`, whether a player placed the block. It is
  read at `HIGHEST`, before the library forgets the block at `MONITOR`, and is
  known only for materials some plugin tracks with
  [`PlacedBlocks.track`](blocks.md).
- `origin`: for `KILL_MOB`, the mob's `SpawnOrigin`. `fromSpawner()` is
  `origin == SPAWNER`; `farmed()` is any origin players make on purpose
  (spawner, egg, breeding). Iron and snow golems are `BUILT`.
- `victim`: for `KILL_PLAYER`, who was killed.
- `customType`: for `CUSTOM`, the type the firing plugin gave.

The library skips nothing a plugin might want to count: skipping placed blocks
or farmed mobs is each subscriber's choice.

## Custom objectives

```java
Objectives.fire(player, "exylia:mines:break", "gold", 1);   // any thread
```

Subscribers of `CUSTOM` get it on the player's thread. Match the type with
`Objectives.key(expected, fired)`: the fired key may be more specific than the
expected one, never less, so `exylia:mines:break` counts
`exylia:mines:break:gold`.

## Matching

| Helper | Matches |
| --- | --- |
| `matches(filter, thing)` | `*` or empty: anything; `diamond_ore`: that one; `*_ore`, `oak_?og`: a glob; `#minecraft:logs`: a block, item or entity type tag. Case and a `minecraft:` prefix are ignored |
| `matchesAny(filters, thing)` | any filter of a list; an empty list matches anything |
| `command(expected, typed)` | the same command or one with more arguments, ignoring case and the slash: `/spawn` matches `/SPAWN now`, not `/spawner` |
| `chat(phrase, message)` | a message that contains the phrase, ignoring case |
| `key(expected, fired)` | custom types, as above |
| `npc(expected, clicked)` | an NPC id, ignoring case; `plugin:id` matches the bare id too |

Globs use only `*` and `?`, with no regular expressions, so a filter written in
a config can never be slow.

## Where it lives

- `net.exylia.lib.objectives.Objectives`, `PluginObjectives`, `Subscription`,
  `ObjectiveType`, `ObjectiveProgress`, `ObjectiveIndex`.
- `objectives.internal.ObjectiveRuntime` holds the subscriptions and the
  thread hops. `ObjectiveListener` holds the handlers, moved from
  ExyliaJourney with its anti-exploit rules.
- `ExyliaLib.onPluginDisable` calls `Objectives.release`.
