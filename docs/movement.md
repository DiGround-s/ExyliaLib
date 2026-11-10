# Movement

A lobby's two ways of getting around faster: a second jump in the air, and
pads that throw whoever steps on them. Since 1.559.0.

Entry points: `net.exylia.lib.movement.DoubleJump` and `JumpPads`.

## Double jump

```java
doubleJump = DoubleJump.of(this)
        .forward(0.9).vertical(0.75)              // the defaults
        .cooldown(Duration.ofSeconds(1))
        .effect(settings.doubleJumpEffect())      // an EffectConfig: sound, particle, title...
        .permission("lobby.doublejump")           // optional
        .when(player -> lobby.contains(player) && prefs.doubleJump(player))
        .start();

doubleJump.refresh(player);    // the predicate's answer changed without the player moving
doubleJump.allows(player);     // what the predicate, permission and game mode say now
doubleJump.stop();             // also done by the library when the plugin disables
```

- **How.** The flight toggle: an eligible player on the ground is allowed to
  fly; pressing jump in the air is the client asking to start flying, which is
  cancelled and turned into the launch (look direction flattened, times
  `forward`, plus `vertical`). Flight stays off until they land.
- **Only its own flight.** Creative and spectator are never touched. A player
  whose flight was allowed by someone else (`/fly`) is left alone. A player who
  stops being eligible (left the area, lost the permission, the predicate said
  no) loses the flight it gave on the next block crossed, `refresh`, world
  change or game-mode change. A quit takes it off before the player's data is
  saved, and disabling the plugin takes it off everybody.
- **Cooldown.** A press inside it does nothing; the player is re-armed on landing.
- **The predicate** runs on the player's thread on every block they cross, so it
  reads memory (an area check, a cached preference), never a database. One that
  throws counts as no.
- **Fall damage** of the landing that ends a jump is cancelled.

## Jump pads

```java
pads = JumpPads.of(this)
        .cooldown(Duration.ofSeconds(1))
        .when(lobby::contains)
        .pads(settings.pads())                    // the plugin's own list
        .start();

// an in-game editor: the plugin saves its list, then hands the change in
pads.put(JumpPad.of("east", PadTrigger.at(target.getLocation()), 1.6, 0.9).towards(-90f));
pads.remove("east");
pads.set(reloaded);                               // after a reload
pads.padAt(player.getLocation());                 // "which pad is this?"
```

| Trigger | Fires when |
| --- | --- |
| `PadTrigger.at(location)` / `new At(world, x, y, z)` | the player's feet enter that block (a plate) or stand on it (a full block) |
| `PadTrigger.materials(pad)` | the feet are in a block of `pad`, or stand on one |
| `PadTrigger.materials(pad, below)` | the same, and that block sits on one of `below` |
| `PadTrigger.plates(below)` | any pressure plate on one of `below` (any block when empty) |

`JumpPad(id, trigger, power, vertical, yaw, effect)`: `power` is the horizontal
speed, `vertical` the upward one, `yaw` the fixed direction in Minecraft degrees
(0 south, 90 west, 180 north, -90 east) or `null` to throw the player the way they
look (`JumpPad.of(...)`; `towards(yaw)` fixes it). `effect` plays to the player
launched. A block pad wins over a material rule at the same spot.

- **The pads are the plugin's data.** Nothing here is saved: the plugin hands its
  list in at start and after every edit it stores. Edits replace one immutable
  snapshot, so they are safe from any thread.
- **Cheap.** Only a move into another block is looked at, and an empty list costs
  one field read per move. Block pads are a hash lookup; material rules are a
  short scan.
- **Not thrown:** creative and spectator players, a player without the
  permission or failing the predicate, and anyone inside the cooldown (shared by
  every pad of one `JumpPads`).
- **Fall damage** of the landing that ends a launch is cancelled.
- **Not the Blocks module.** [Blocks](blocks.md) answers clicks and protects a
  block from being broken; a pad is stepped on, and a lobby's world protection
  already keeps its blocks in place. Register the pad's block there yourself
  when it should also refuse clicks or breaking.

## Threads

Every event runs on the player's own thread, which is what Folia needs;
`refresh` and the pad edits may be called from any thread.

## Where the code is

`src/main/java/net/exylia/lib/movement/` — `DoubleJump`, `JumpPads`, `JumpPad`,
`PadTrigger`, `FallGuard` (package-private). Tests:
`src/test/java/net/exylia/lib/movement/JumpPadsTest.java`.
