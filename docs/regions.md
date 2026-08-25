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

`CommonRegionPolicies` carries the sixteen keys the ecosystem already uses,
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

### What a step costs

Membership is refreshed on every `PlayerMoveEvent` that crosses a block
boundary, so the number that matters is not the lookup but what one step
allocates: allocation here is paid back later as a GC pause, which is what a
player feels as a stutter.

Measured by `RegionBenchmark` (`./gradlew test --tests "*RegionBenchmark*" -i`),
per move, with the test harness floor subtracted:

| Situation | ns | bytes |
| --- | --- | --- |
| no regions registered anywhere | 42 | 48 |
| standing outside every region | 103 | 72 |
| inside one region | 136 | 136 |
| inside two overlapping regions | 241 | 280 |

Four things keep it there. A server with no regions at all skips the lookup
entirely and only advances the stored position. An unchanged membership is
detected by comparing snapshot **references**, which is exact rather than
optimistic because both lists come out of the same immutable index — so the
overwhelmingly common "nothing changed" allocates nothing. The stored position
is held as primitives and materialized into a `BlockPosition` only when an event
is about to carry it. And the enter/exit lists are built only once something
actually crossed a boundary.

Note that a query result is immutable in contract but is not a
`java.util.ImmutableCollections` type, so `List.copyOf` does **not** recognize it
and copies. Storing one defensively is silent work on this path; it is kept as
handed over.

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
    for (RegionSnapshot left : event.exited(regions))  { ... }
    for (RegionSnapshot entered : event.entered(regions)) { ... }
}
```

One event per change, carrying everything that changed. A single step can leave
one region and enter another, and both are in the same event, ordered the same
way a query is.

**Pass your own `PluginRegions`.** The event carries the whole server's regions,
not just yours: a step out of a claim belonging to another plugin arrives in
your listener too. `exited(regions)` and `entered(regions)` return only the ones
you registered, and `involves(regions)` answers whether you have anything to do
at all — which is usually the answer, because a player crossing a border is
usually crossing somebody else's.

The unfiltered `exited()` and `entered()` are still there for the rare listener
that genuinely wants to watch the whole server move. Reading them by mistake is
how a game eliminates a player for leaving a region it does not own.

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

## Who placed this block

The region module never cancels an event on a plugin's behalf. It says what a
region declares; acting on it is the consumer's. `player_build_only` is the one
policy a consumer cannot act on alone, because the answer is not in the event:
whether a block was put there by a player is state that outlives every event,
and every plugin keeping its own copy would mean every plugin paying for the
same table and disagreeing wherever regions overlap.

So the library keeps the record, and the decision stays where the others are:

```java
if (regions.resolve(location, CommonRegionPolicies.PLAYER_BUILD_ONLY).value()
        && !regions.placedByPlayer(event.getBlock())) {
    event.setCancelled(true);
}
```

Blocks are recorded only for regions that declare `player_build_only` or
`temporary_blocks`, and forgotten when the block is broken or when the region
is unregistered, replaced without the policy, or released with its plugin. There
is no separate expiry and nothing to clean up: the record lives exactly as long
as the region does.

A server whose regions declare neither policy pays one volatile read per block
place and break, and nothing else — no lookup, no query, no allocation. When it
is armed, a position costs sixteen bytes rather than the fifty-six a set of
boxed keys would: positions are packed into a `long` and held in a flat table.

### Temporary blocks

`temporary_blocks` is the exception, because nothing about it is a decision. A
block placed in a region declaring it disappears after
`temporary_blocks_seconds`, and comes back to the player's inventory when the
region also declares `re_give_blocks`:

```java
PolicySet.of(CommonRegionPolicies.TEMPORARY_BLOCKS, true)
        .with(CommonRegionPolicies.TEMPORARY_BLOCKS_SECONDS, 10)
        .with(CommonRegionPolicies.RE_GIVE_BLOCKS, true)
```

Commons carried the lifetime as a field on the region object, so every consumer
read it from somewhere else and ran its own clock. It is a policy here, which is
what lets the library own the whole behaviour.

One timer serves the whole server, not one task per block: a region's blocks all
carry the same lifetime, so they expire in the order they were placed and a
queue per region has the earliest at its head. The timer starts with the first
temporary block and cancels itself when the last one is gone, so a server with
none holds no timer at all. Removal runs on the thread that owns the block, and
the material is checked again first — a block broken and replaced with something
else is not the one that was placed.

## Clearing what a region collected

A region that hosts rounds fills up with what the last one dropped. Clearing it
is one call, and it is about geometry rather than ownership — the snapshot is
already in the caller's hand:

```java
regions.get("arena").ifPresent(regions::clearEntities);
```

Loose means dropped items, experience orbs, projectiles, minecarts, end crystals
and fireworks. Armour stands, item frames, paintings and mobs are **not**
touched: a decorated region cleared between rounds would lose its decoration
once and never say so. Widen or narrow it with a predicate, which sees every
non-player entity inside the shape:

```java
int removed = regions.clearEntities(arena, entity -> entity instanceof Monster);
```

Players are never removed, whatever the predicate answers. Membership is the
shape's own, not its bounding box's, so a spherical region leaves the corners of
the cube around it alone; the box only narrows the world read.

It is a world read, so it runs on the thread that owns the region — hop through
`Tasks.of(plugin).runAtLocation(...)` first. A region whose world is not loaded
returns `0` rather than throwing.

For the regeneration case there is nothing to call: `RegenerateOptions` already
carries `clearEntities`, and [schematics](schematics.md) does it inside the same
pipeline. This is for clearing without regenerating.

## Selecting

```java
regions.beginSelection(player).result()
        .thenAccept(result -> save(result.cuboid()));
```

That one line hands the player a golden axe, draws the box as they pick it, and
answers only once they confirm. The result is coordinates; what to do with them
is the plugin's business. One selection per player across the whole server, and
it ends when they leave, when the plugin is disabled, or when they walk away.

### What the player actually does

| Gesture | What happens |
| --- | --- |
| Left-click a block | first corner |
| Right-click a block | second corner |
| **Shift + left-click** | accepts the box; a block under the cursor is not needed |

Either corner can be moved for as long as the box is unconfirmed, and the
outline follows it. Nothing is answered until the confirmation, so a misclick
costs a click rather than an arena.

The selector is put in a **free slot** — the main hand only when the main hand
is empty — and taken back however the selection ends. ExyliaCommons wrote it
straight into the main hand, which destroyed whatever was there; that is not
reproduced. A player with no room is told to hold the material themselves, and
the selection still runs: what selects is the material, not the item we gave.

### The switches

Every part of that is a switch on `SelectionOptions`, and the defaults are the
ExyliaCommons selector rather than WorldEdit's wand:

```java
SelectionOptions quiet = SelectionOptions.builder()
        .selectorMaterial(Material.GOLDEN_AXE)   // default
        .giveSelector(true)                      // default — hand it over
        .requireConfirmation(true)               // default — shift + left-click
        .previewParticle("END_ROD")              // default; null draws nothing
        .previewSpacing(1.0)
        .previewPeriodTicks(5L)
        .feedback(true)                          // default — coordinates and volume
        .selectorName("{primary}&lARENA SELECTOR")
        .selectorLore(List.of("{letters}Pick two corners"))
        .build();
```

A plugin that draws its own outline turns the preview off; one asking for a
corner inside its own flow turns the confirmation off and answers on the second
click. `feedback(false)` silences the library's own messages for a plugin that
words them itself.

### The states

| State | Means |
| --- | --- |
| `ACTIVE` | fewer than two corners are set |
| `AWAITING_CONFIRMATION` | both corners are set and the box is a proposal |
| `COMPLETED` | confirmed, and the stage has the result |
| `CANCELLED` | ended without one |

`SelectionSession.confirm()` is the same door the shift-click uses, for a plugin
that accepts a selection from its own button. Without a confirmation the second
corner still answers — and only the second: a left click never completes, so
correcting a corner already placed cannot end the selection.

### Threads and endings

The outline and the two inventory writes run on **ExyliaLib's** scheduler, not
the owning plugin's: a plugin is already disabled by the time its selections are
released, and a disabled plugin cannot schedule the return of its own tool. On
Spigot and Paper the write happens inline when the caller is already on the main
thread, so the player has the axe the moment they are told to select.

Nothing about giving the tool back can strand a selection. The session leaves the
registry and completes its stage first, and the inventory work runs after that,
guarded — a failed write costs a console line, never the player's ability to
select again with any plugin.

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

### An outline that belongs to a match

The defaults describe an admin being shown where a region is: two hundred ticks
and gone. A zone that is part of a game is the other case — drawn for as long as
the game lasts, to everybody online, and paid for every frame by every viewer.

```java
VisualizationOptions zone = VisualizationOptions.builder()
        .particleName("WAX_ON")
        .periodTicks(40L)
        .untilClosed()          // the event decides when, not a tick count
        .viewDistance(48.0)     // the default; the far side of the map is skipped
        .build();

handles.add(regions.visualize(player, zoneId, zone));
```

`untilClosed()` is what makes that possible without naming a duration nobody can
know in advance. It is not "forever": the visualization still stops on its own
when the viewer leaves, when the region is unregistered and when the owning
plugin is disabled, so an event that ends badly cannot leave particles running.
Closing the handle is the fourth way, and the one the event itself uses.

`viewDistance` is what keeps it affordable. A viewer farther than that from the
**nearest point of the region's bounds** is skipped *before* the outline is
worked out — measured to the bounds rather than to the centre, because standing
on the edge of a hundred-block arena is standing at it, and a centre-based check
would stop drawing the border to the players actually on it. The default is 48
blocks, the same as a hologram's, and for the same reason: past it the client has
nothing to draw, so the packets were only ever bandwidth.

`VisualizationOptions` is a builder rather than a record since 1.61.0, the way
`SelectionOptions` already was. The four-argument constructor still compiles and
picks up the view distance it never knew about.

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
| Internal | `region/internal/RegionIndex`, `RegionRuntime`, `RegionListener`, `PlacedBlockRuntime`, `PlacedBlockListener`, `PositionSet`, `RegionEntities`, `SelectionRuntime`, `SelectionListener`, `SelectorWand`, `SelectionPreview`, `VisualizationRuntime`, `OutlineSampler` |
| Lifecycle | `ExyliaLib` — listener registration, release before `Tasks.release` |
