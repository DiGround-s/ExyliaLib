# Block module

Blocks in the world that answer clicks instead of behaving like their material.
Since 1.110.0.

Entry point: `net.exylia.lib.block.Blocks`.

```java
PluginBlocks blocks = Blocks.of(this);

blocks.at(crate.location())
        .onRight(click -> open(click.player(), crate))
        .onLeft(click -> preview(click.player(), crate))
        .register();
```

A crate, a shop, a warp pad, a quest board. Every plugin that has placed one
wrote the same three things: an interact listener that finds the block by its
coordinates, a break listener so nobody takes it away, and a guard so a barrel
does not open its own inventory.

## What it does not do

**It does not remember anything across restarts.** Where a plugin's blocks are
is that plugin's data, stored in its own table next to what each block *means*;
a registry holding only locations would keep half of a row. Register them again
on enable, from whatever the plugin already loads.

## API

### `Blocks`

| Method | Contract |
| --- | --- |
| `of(plugin)` | that plugin's view, the same instance every time |
| `at(location)` | what is registered there, whoever owns it, or `null` |
| `isRegistered(block)` | whether any plugin owns it |
| `active()` | how many are registered across the server |
| `release(pluginName)` / `releaseAll()` | called by the library; consumers do not |

### `PluginBlocks`

| Method | Contract |
| --- | --- |
| `at(location)` / `at(block)` | starts a registration; nothing happens until `register()` |
| `registered(location)` | this plugin's registration there, or `null` — including when another plugin owns it |
| `unregister(location)` | takes down this plugin's, and says whether there was one |
| `count()` | how many this plugin has |
| `unregisterAll()` | takes down all of them — what a reload wants before re-registering |

### The builder

| Method | Default |
| --- | --- |
| `onLeft(handler)` | none; a left click is refused and nothing runs |
| `onRight(handler)` | none |
| `onClick(handler)` | both buttons at once |
| `protect(boolean)` | `true` — cannot be broken, blown up, pushed by a piston or burnt |
| `vanilla(boolean)` | `false` — the click does not do what the material does |
| `register()` | returns the `ClickableBlock` |

`ClickableBlock` carries `plugin()`, `owner()`, `location()`, `isRegistered()`
and `unregister()`. A plugin that keeps its blocks for as long as it is enabled
can throw the handle away.

### The click

`BlockClick` is `player()`, `block()`, `location()`, `button()` and
`sneaking()`, so one block can offer a second action behind shift without a
second registration. `button()` is `BlockButton.LEFT` or `RIGHT`.

## Behavior

- **One block, one registration.** Registering over an existing one replaces it,
  whoever owned it. The replaced handle knows it is no longer standing, and its
  `unregister()` cannot take down the one that replaced it.
- **A location means the block that contains it.** A player's feet, the centre
  of the block and its corner all resolve to the same registration. The corner
  is built arithmetically rather than through `Location#getBlock()`, which loads
  the chunk — registering happens at startup, long before anybody is near.
- **Handlers run on the thread that owns the block**, from the interact event,
  so the world can be read and written from them on Folia as well as Bukkit.
- **A repeated click is one click.** A held left button fires every tick and both
  hands fire a right click; within 250ms, the same button on the same block by
  the same player does not call the handler again. A crate that opened twice
  from one press would cost a key.
- **A handler that throws costs its own click.** It is reported through `Debug`
  against its own plugin, and the interact event carries on to the plugins
  behind it.
- **An explosion loses the protected blocks, not its blast.** They are taken out
  of the block list rather than the event being cancelled, so one crate in range
  does not save the rest of the street.
- **A piston that would move one is cancelled**, because a moved block is a
  registration pointing at air.
- **Nothing survives its plugin.** Disabling it unregisters every block it owns;
  the blocks themselves are left standing, which is what a restart expects to
  find.

## Performance

One `ConcurrentHashMap` for the whole server, keyed by world and block
coordinates. Every handler leaves on a single lookup when nothing is registered
where the event happened, so a server with no clickable blocks pays a map miss
per interact. The only thing that expires is the record of who clicked what a
moment ago: a Caffeine cache, five seconds, two thousand entries.

## Source and tests

- Public: `block/` — `Blocks`, `PluginBlocks`, `ClickableBlock`, `BlockClick`,
  `BlockButton`.
- Internal: `block/internal/` — `BlockRuntime`, `BlockListener`.
- Tests: `BlockRegistryTest` covers block resolution across worlds, replacement,
  ownership, per-plugin release and the debounce.
