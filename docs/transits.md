# Transits

`net.exylia.lib.session.Transits`, since 1.622.0.

A player changing mode (lobby to FFA, FFA back to the lobby, into a sandbox
world) is covered by neither side for a moment. The lobby stops protecting them
as soon as another plugin claims them, and the arena only dresses them once
they land. On the way back it is the same in reverse. Whatever they drop, pick
up, park on the cursor or in the 2x2 crafting grid, or use in that window ends
up in the wrong mode.

While a player is **in transit**, the library refuses:

- dropping items and picking them up;
- clicks and drags in their own inventory and in any container, except library
  menus, prompts and search results (item editors, the insert window and
  trades wait like everything else);
- using the item in hand (blocks, buckets, pearls, food). Pressing a button or
  opening a door still works.

Commands, chat, movement and opening menus are not touched, and there is no
message.

## How long

A transit lasts one second. A library teleport that starts during it stretches
it until one second after that teleport lands. A snapshot restore keeps it open
until the restore ends, plus one second when something was given back. A
transit never lasts more than ten seconds in all.

## What starts one

You rarely call it yourself:

- `Snapshots.of(plugin).save(player, ...)` (and `saveAndClear`) begins one.
- Every `restore`, `returnAndRestore`, `restoreAll` and `returnAndRestoreAll`
  holds the player while it runs.
- `Teleports...start()` only stretches a transit that is already running. A
  plain teleport holds nothing.

A plugin that swaps a player's items without a snapshot calls
`Transits.begin(player)` at the point where its own protection stops: right
after the claim on the way in, and right before the move home on the way out.

```java
Transits.begin(player);
teleports.to(player, arenaSpawn).then(this::dress).start();
```

`Transits.isHeld(uuid)` says whether a player is in transit now.

## The crafting grid

No snapshot holds the 2x2 crafting grid. `save` puts what is parked there back
into the inventory before it captures it (what does not fit stays in the grid),
and applying a snapshot empties the grid, because what sits there belongs to
the state being replaced.

`Transits.clearLoose(player)` empties the cursor and the crafting grid. A plugin
that dresses a player for a new mode by hand (`getInventory().clear()` and its
own items) calls it too, since `PlayerInventory#clear` reaches neither.
