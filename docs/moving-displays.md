# Moving displays

Displays sent to one viewer that travel along a path, with a cap on how many
each viewer has on the way at once: items riding a conveyor, coins flowing to a
collector, ore rolling to a furnace. The flow is the plugin's numbers; what is
drawn is a sample of it.

Since 1.676.0. Class `net.exylia.lib.display.MovingDisplays`, built on
[displays](displays.md).

```java
MovingDisplays belt = MovingDisplays.of(this, 40);        // once, on enable
DisplayMotion ride = MovingDisplays.along(List.of(
        new Vector(0, 0, 0), new Vector(6, 0, 0), new Vector(6, 0, 4)), 2.0); // 2 blocks a second

int every = MovingDisplays.stride(itemsPerSecond, ride.lifeMillis(), belt.cap());
if (made++ % every == 0) belt.send(owner, DisplayModel.item(ore), ride, beltStart);
```

## API

```java
static MovingDisplays of(Plugin plugin, int cap);
int cap();
DisplayHandle send(Player viewer, DisplayModel model, DisplayMotion motion, Location origin); // null at the cap
int live(Player viewer);
void clear(Player viewer);
static DisplayMotion along(List<Vector> points, double blocksPerSecond);
static int stride(double perSecond, long travelMillis, int cap);
```

## Contracts

- **One viewer each.** A display is sent to the player given and nobody else;
  it is removed when its motion ends. `send` returns `null`, and sends
  nothing, while that viewer already has `cap` on the way.
- **The client moves it.** `along` is one linear leg per pair of points (two
  poses a leg, offsets from the origin), so a display costs a spawn, one
  metadata packet per corner and a destroy, whatever its speed. A leg lasts
  at least a tick.
- **Sampling:** `stride` is how many real items to skip per drawn one so
  `perSecond × travel` on the way stays under the cap. Draw one in every
  `stride`; the income stays the plugin's numbers.
- Forgotten on quit. `clear` removes a viewer's displays at once (a rebirth,
  leaving the base).

## Cost

Measured with `tycoon-spike.js` on a uvideo slot (Paper 1.21.x): see the
numbers in the ExyliaTycoon plan. Keep the cap low enough that the viewers on
one region stay within the tick budget; 40 per viewer was the plan's target,
15 the fallback.
