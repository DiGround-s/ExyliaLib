# Client mobs

Mobs of any vanilla type that only one player sees, which walk, look around and
animate, sent as packets: the helpers that work a player's farm, a pet that
follows them. Since 1.634.0.

Entry point: `net.exylia.lib.clientmob.ClientMobs`. Needs PacketEvents; without
it mobs are created and do nothing (`ClientMobs.isAvailable()` says so).

## Using

```java
ClientMobs mobs = ClientMobs.of(this)
        .range(32)                                     // sent within 32 blocks (default)
        .onClick((player, mob, click) -> pet(player, mob));

ClientMob golem = mobs.spawn(player, EntityType.IRON_GOLEM, spot);
golem.name("<gradient:#8a51c4:#b48fd9>Helper</gradient>");
golem.moveTo(crop, 2.5).thenRun(() -> { golem.swing(); harvest(crop); });
golem.lookAt(player.getLocation());
golem.sitting(true);                                    // waiting
mobs.clear(player);                                     // this plugin's mobs for one player
```

| Method | Contract |
| --- | --- |
| `of(plugin)` | the plugin's mobs; every call shares one handler and one range |
| `spawn(viewer, type, at)` | a mob facing the yaw and pitch of `at`; `PLAYER` and unspawnable types throw |
| `onClick(handler)` | runs on the viewer's thread, only for the viewer and only within 8 blocks of their eyes |
| `range(blocks)` | how close a viewer must stand to be sent a mob; it stays sent 4 blocks further |
| `clear(viewer)` | removes this plugin's mobs for one player |

`ClientMob`: `viewer()`, `location()`, `moveTo(target, blocksPerSecond)`,
`teleport(Location)`, `lookAt(Location)`, `swing()`, `status(byte)`,
`name(miniMessage)`, `glow(Color)`, `sitting(boolean)`, `baby(boolean)`,
`remove()`, `isRemoved()`. Every method may be called from any thread.

`moveTo` walks a straight line (height included), turning body and head the
way it goes, and returns a future completed on arrival, or as soon as a newer
`moveTo`, a `teleport` or a removal replaces the walk (check `isRemoved()` or
the location if it matters which). The future completes on the ticker thread.
There is no pathfinding and no gravity: the caller picks a clear line.

## Per type

- `swing()`: iron golem, ravager, hoglin, zoglin and warden play their own
  attack (status 4); every other mob gets the main-arm swing, which only
  mobs with an arm animation (zombie, skeleton, villager, piglin...) show.
- `sitting(true)`: wolf, cat and fox sit; camel gets the sitting pose; every
  other type crouches (pose and flag; a mob with no crouch animation shows
  nothing, so use `name` to say "waiting" there).
- `baby(true)`: animals, villagers and zombies. Other types ignore it.
- `name`: the vanilla custom name above the head, which every type shows.
- `glow`: the nearest of the sixteen chat colours, through a per-viewer team.
- `status(byte)`: the raw status; what each number does depends on the type.

## How it works

A mob is one entity on its viewer's client and nothing on the server; its own
hitbox is the click box. One async pass, four times a second, sends a mob when
its viewer comes within range and takes it off past range plus a margin; changes
made while it is off screen are kept and sent with it. A walk keeps advancing
off screen, so a helper arrives whether anyone watches or not.

One shared async ticker moves walking mobs every 2 ticks with relative-move
packets (the client interpolates between them), the last step a teleport so
rounding never piles up, and stops when nobody walks. Idle mobs cost nothing.

A respawn or world change drops every entity on the client, so mobs are sent
again on the next pass. A player who leaves loses their mobs, and a plugin's
mobs go away when it is disabled.

Mobs do not collide, take damage or exist for other players. For farms, see
[client nodes](nodes.md).
