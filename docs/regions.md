# Regions

Areas of the world that plugins can ask about, register and be told about.

```java
PluginRegions regions = Regions.of(this);

// When your database load finishes, hand over the whole set at once.
regions.replaceAll(rows.stream()
        .map(row -> regions.region(row.id(),
                new WorldIdentity(row.worldId(), row.worldName()),
                Cuboid.blocks(row.minX(), row.minY(), row.minZ(),
                              row.maxX(), row.maxY(), row.maxZ()),
                row.priority(),
                PolicySet.of(CommonRegionPolicies.PVP, row.pvp())))
        .toList());

// Ask, from anywhere.
boolean pvp = Regions.resolve(player.getLocation(), CommonRegionPolicies.PVP).value();
```

## Three things kept apart

`RegionSnapshot` is a region as it is right now: an id, an owner, a world, a
shape, a priority and its policies. It is immutable, so nothing can change under
a query, and it holds no `World`, `Player`, `Location`, `Plugin` or callback.

`PolicySet` is what a region *says*. Saying nothing is not the same as saying
the default, which is the distinction that makes overlapping regions work.

`RegionData` is the same region as something a database can store. The region
module never touches a database itself.

## Shapes

```java
Cuboid.blocks(0, 64, 0, 15, 79, 15);          // an arena, a mine, a portal
Cuboid.block(100, 70, 100);                    // a single block
new UnboundedYRectangle(0, 0, 64, 64);         // a claim: bedrock to sky
new Sphere(0, 64, 0, 16);                      // spawn protection, a payload
new HorizontalCylinder(0, 0, 100);             // a safe zone with no ceiling
```

`Cuboid.blocks` takes the **inclusive** block corners an admin selects and the
ones the old system stored, so `0..15` is sixteen blocks. Coordinates inside are
half-open, which is what makes a block's own volume come out right.

Containment is arithmetic on three doubles. No `Location` is created, which
matters because this runs on every step of every player.

There is no polygon and no rotated box: nothing in the ecosystem has ever used
one. A shape that moves or shrinks is not mutated — a new snapshot replaces it,
and the index follows. Mutating a shape in place is what left the old system
pointing at bounds a region no longer had.

## Priority and policies

```yaml
world_rules   priority 0    pvp: true
claim         priority 10   build: false
mine          priority 20   pvp: false
```

Standing in all three, `Regions.at(...)` returns them innermost first. Asking
about a policy walks that order and takes **the first region that mentions it**:

- `pvp` → `false`, from the mine.
- `build` → `false`, from the claim; the mine is silent and does not mask it.
- `interact` → `true`, nobody said otherwise, so the key's own default.

A region that does not declare a key is skipped rather than answering for it.
The old system let the top region decide everything, so a small high-priority
region silently switched off every rule of the one it sat in.

`CommonRegionPolicies` carries the fifteen keys the ecosystem already uses,
with the same names and the same defaults. Policies are open namespaced keys,
not an enum, so a plugin can add its own without changing the library.

## Queries

```java
Regions.at(location);                          // everything here, any plugin
Regions.at(worldId, x, y, z);                  // without building a Location
Regions.resolve(location, key);                // one policy, everybody's regions
Regions.get(id);

regions.at(location);                          // only this plugin's regions
regions.resolve(location, key);                // only this plugin's answer
```

Global by default because a claim has to see the arena it is inside. The
owner-scoped versions live on `PluginRegions`, where the name says so.

### What a lookup costs

Regions are held in an immutable index, keyed by **world UUID** and by a
hierarchy of aligned power-of-two cells. A shape is filed at the smallest level
that covers it, so it occupies at most four buckets no matter how big it is: a
claim a hundred thousand blocks wide costs the same to index as a house.

A point lookup reads one bucket per level and merges preordered arrays. Nothing
is sorted per query, no candidate set is built, an empty answer allocates
nothing, and a single answer allocates nothing but the singleton list.

The old index kept a `Map<World, ...>`. Bukkit hands out a new `World` instance
when a world is unloaded and loaded again, so every region in it silently became
unreachable while the dead world was pinned in memory. World UUIDs do not have
that problem.

## Registering

```java
regions.register(snapshot);      // fails if that id already exists
regions.replace(snapshot);       // fails if it does not
regions.unregister(id);
regions.replaceAll(all);         // this plugin's whole set, atomically
```

`replaceAll` is what a database reload should call. The new index is built and
validated off to the side and swapped in with a single assignment, so a rejected
build leaves the server exactly as it was. It replaces **only the calling
plugin's** regions; the old system emptied one global registry, so reloading one
plugin turned off protection everywhere.

A plugin can only touch its own regions. Ownership is the exact plugin name, so
two plugins with similar names are still two plugins.

## Entering and leaving

```java
@EventHandler
public void onRegionChange(PlayerRegionChangeEvent event) {
    for (RegionSnapshot left : event.exited())  { ... }
    for (RegionSnapshot entered : event.entered()) { ... }
}
```

One event per change, carrying everything that changed. A single step can leave
one region and enter another, and both are in the same event, ordered the same
way a query is.

It is fired **after** the change is committed and is not cancellable: the move
itself was already accepted, and a cancellable exit is what let the old system
commit half a transition and leave a player permanently marked as inside a
region they had left.

The tracker holds a UUID, a block position and a list of ids. It never expires.
Commons dropped a tracker after ten minutes of standing still, so an away player
was re-entered on their next twitch and every enter effect fired again.

What causes an event:

| Cause | When |
| --- | --- |
| `MOVE` | crossed into a different block |
| `TELEPORT` | arrived somewhere else |
| `WORLD_CHANGE` | changed world |
| `REGISTER`, `REPLACE`, `UNREGISTER`, `RELEASE` | a region changed under a standing player |

That last row is the one people forget. Creating a region around somebody who is
already standing there enters them; deleting the region they are in exits them.
Without it, an admin who makes a region and does not move sees nothing happen.

Joining inside a region does **not** fire an enter — they did not walk in — and
quitting does not fire an exit.

## Selecting

```java
regions.beginSelection(player).result()
        .thenAccept(result -> save(result.cuboid()));
```

Left-click a corner, right-click the other. The result is coordinates; what to
do with them is the plugin's business. One selection per player, and it is
cancelled when they leave or when the plugin is disabled.

## Showing a region

```java
regions.visualize(player, "arena");
```

Particles along the outline, on the player's own scheduler, for as long as the
options say. The region is looked up again on **every frame**, so replacing a
shrinking zone updates what the viewer sees without touching the handle.

A frame draws at most 512 points, and the budget is spent *before* the points
are worked out. Generating a whole outline and thinning it afterwards is the
same picture and a very different cost: a claim a hundred thousand blocks wide
would produce eight hundred thousand points to keep five hundred.

Shapes with no ceiling — a claim, a safe zone — are drawn at the viewer's own
height, because a rectangle drawn at y=0 is underground.

## Storing regions

The region module does not talk to a database. It offers the value that one
would store:

```java
RegionData data = RegionCodec.encode(snapshot, myPolicyKeys);
RegionSnapshot back = RegionCodec.decode(data, myPolicyKeys);
```

`RegionData` is a format version, an id, an owner, a world UUID with its name
kept as a fallback, a shape type with its coordinates, a priority and scalar
policies. It maps onto one encoded column or onto plain columns, whichever the
database module ends up wanting.

Decoding a policy the caller did not declare **fails** rather than dropping it
silently. The old serializer swallowed every error and returned `null`, so a
region with one bad field simply stopped existing with nothing in the console.

## Threads

Queries are safe from anywhere: the index is immutable and swapped atomically.

Registering is safe from anywhere; the work happens under a lock and publishes
once.

Events are fired on the thread that owns the player. Reconciliation after a
registry change is scheduled through `Tasks` on ExyliaLib's own scheduler, not
the changing plugin's — a plugin being disabled must still be able to correct
the players standing in its regions on the way out.

## Lifecycle

Nothing survives the plugin that made it. Disabling one releases its regions,
its selections and its outlines, and leaves every other plugin's alone.
ExyliaLib registers one listener for the whole server.

## Where the code is

| | |
| --- | --- |
| Public API | `region/Regions`, `PluginRegions`, `RegionSnapshot`, `RegionId`, `WorldIdentity`, `BlockPosition`, `RegionShape` (`Cuboid`, `UnboundedYRectangle`, `Sphere`, `HorizontalCylinder`), `HorizontalBounds`, `VerticalBounds`, `PolicyKey`, `PolicySet`, `PolicyResolution`, `CommonRegionPolicies`, `RegionData`, `RegionCodec`, `PlayerRegionChangeEvent`, `RegionChangeCause`, `SelectionOptions`, `SelectionSession`, `SelectionResult`, `SelectionState`, `VisualizationOptions`, `RegionVisualization` |
| Internal | `region/internal/RegionIndex`, `RegionRuntime`, `RegionListener`, `SelectionRuntime`, `SelectionListener`, `VisualizationRuntime`, `OutlineSampler` |
| Lifecycle | `ExyliaLib` — listener registration, release before `Tasks.release` |
