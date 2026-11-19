# Client nodes

Blocks and items that only one player sees and clicks, sent as packets: the
crops of a farm only its owner works, ores a player mines alone, a target that
pops when hit. Since 1.628.0.

Entry point: `net.exylia.lib.node.ClientNodes`. Needs PacketEvents; without it
nodes are created and do nothing (`ClientNodes.isAvailable()` says so).

## Using

```java
ClientNodes nodes = ClientNodes.of(this)
        .range(32)                                    // sent within 32 blocks (default)
        .onClick((player, node, click) -> harvest(player, node));

ClientNode wheat = nodes.block(player, slot, Material.WHEAT.createBlockData());
wheat.glow(Color.YELLOW);                              // an outline, null takes it off
wheat.look(ripe);                                      // the crop's next stage
wheat.pop();                                           // shrink and spring back
wheat.scale(0f, 0); wheat.scale(1f, 10);               // regrow over half a second
wheat.remove();

ClientNode coin = nodes.item(player, center, new ItemStack(Material.GOLD_NUGGET));
nodes.clear(player);                                   // this plugin's nodes for one player
```

| Method | Contract |
| --- | --- |
| `of(plugin)` | the plugin's nodes; every call shares one handler and one range |
| `block(viewer, at, data)` | a block display filling the block at `at`, with a 1×1 click box |
| `item(viewer, at, stack)` | an item display centred on `at`, with a 0.8 click box |
| `onClick(handler)` | runs on the viewer's thread, only for the viewer and only within 7.5 blocks of their eyes |
| `range(blocks)` | how close a viewer must stand to be sent a node; it stays sent 4 blocks further |
| `clear(viewer)` | removes this plugin's nodes for one player |

`ClientNode`: `viewer()`, `location()`, `look(BlockData)` / `look(ItemStack)`
(a node keeps its kind), `glow(Color)`, `scale(size, ticks)` (stands on its
base; `0` hides it and keeps the box), `pop()`, `remove()`, `isRemoved()`.
Every method may be called from any thread.

## How it works

Each node is two entities on its viewer's client and nothing on the server: a
block or item display and an interaction box. Nothing ticks. One async pass,
four times a second, sends a node to its viewer when they come within range and
takes it off past range plus a margin; changes made while a node is off screen
are kept and sent with it. Clicks on node ids never reach the server; a click
from another player or from out of reach is dropped as forged.

A respawn or world change drops every entity on the client, so nodes are sent
again on the next pass. A player who leaves loses their nodes, and a plugin's
nodes go away when it is disabled.

Nodes do not hide players or real blocks: pair them with
[packets](packets.md) `visibility()` or block masks when a farm must look empty
to everybody else.
