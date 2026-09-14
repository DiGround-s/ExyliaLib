# Packets

Client-side tricks a staff plugin needs and the server has no API for: hiding
a player from some viewers, drawing the invisible ones for staff, showing
blocks that are not there, pinning a player in place, making one client
believe it is a spectator, watching a chest without opening it, and world
borders only some players see.

Nothing here changes what the server believes. That is the point — no other
plugin's checks break — and the limit: every helper below says what it does
not do.

```java
PluginPackets packets = Packets.of(this);

// vanished staff are invisible to everyone without the permission
packets.visibility().rule((viewer, target) ->
        !vanished.contains(target.getUniqueId()) || viewer.hasPermission("staff.see"));
packets.visibility().refresh(staff);      // after toggling vanish

// a frozen suspect stays put but may look around
packets.movement().freeze(suspect);

// the suspect's chest, without the lid opening
packets.silentContainer().open(staff, chest.getInventory(), title, false);
```

## What is needed

PacketEvents. Without it `Packets.isAvailable()` is `false`, every helper is
a silent no-op, and the plugin is told once in its console. The one exception
is `SilentContainer`, which is plain Bukkit and works anywhere.

## Visibility

```java
Visibility visibility();
void rule(VisibilityRule rule);              // one per plugin; all plugins' rules must agree
void refresh(Player target);                 // re-evaluate for every online viewer
boolean canSee(Player viewer, Player target);
```

`refresh` despawns the target for viewers who lost sight — `hidePlayer` plus
an explicit destroy and tab-list removal — and respawns them for viewers who
regained it. A viewer the server still hides the target from, while every
rule now agrees they may see them, is shown them too: a hide left behind by a
quit or by another plugin is undone on the next `refresh` of that target.
Every hide and show is made in the library's name, so one plugin's `refresh`
undoes another's. Between refreshes every outbound packet about a hidden target is
dropped on its way to a viewer who may not see them: spawn, metadata,
movement, equipment, animations, entity sounds, damage. Tab-list updates lose
the hidden rows and keep the rest.

Limits: the server still knows where the target is — they collide, block
arrows and appear in `getNearbyEntities`. Positional sounds and particles
carry no entity id and pass through. Pair with `setCollidable(false)` and
`setSilent(true)`.

## Reveal

```java
Reveal reveal();
void show(Player viewer, RevealStyle style);   // SOLID or OUTLINE
void hide(Player viewer);
RevealStyle styleOf(Player viewer);            // null when this plugin reveals nothing
```

A client draws a player invisible because of one bit in the entity flags. On
its way to a viewer who asked for this, `SOLID` clears that bit — the player
is drawn whole — and `OUTLINE` leaves it and adds the glow bit, which the
client draws as a bare outline through the world. The rest of the byte is kept
as the server sent it, so sneaking, sprinting and burning still read right.

Turning it on has the invisible players around the viewer tracked again, so
the new flags reach the client; the viewer sees them respawn a tick later.

Limits: only players are drawn, so an invisible armour stand holding a
hologram stays invisible. A player another plugin hid with `hidePlayer` is
never sent to the viewer at all — there is no packet to rewrite, and that one
needs the owning plugin. When the plugin is disabled the filter stops at once,
and whatever is on screen keeps its render until those flags change again.

## FakeBlocks

```java
FakeBlocks fakeBlocks();
void show(Player viewer, Map<Location, BlockData> blocks);
void clear(Player viewer);
void clear(Player viewer, Collection<Location> positions);
```

Sent as one multi-block-change per chunk section (a single block change when
a section has one). The module remembers what each viewer was shown so
`clear` can send the real blocks back, and forgets it when they leave or
change world. Positions in another world are ignored.

Limits: a fake wall stops nobody, and a chunk the server resends shows the
truth again.

## Movement

```java
Movement movement();
void freeze(Player player);
void unfreeze(Player player);
boolean isFrozen(Player player);
```

Position packets from a frozen client are dropped and answered with a
teleport back to the anchor, so the server never sees the move. Rotation is
kept: the teleport carries it relative and as zero, so the camera is never
turned back to where it pointed a round trip ago. A dropped packet takes its
rotation with it, though; a plugin that needs every turn of a frozen player
reads the rotation packets itself, before this hook cancels them. A
`PlayerMoveEvent` guard covers the case PacketEvents is missing.

Limits: knockback, pistons and a plugin teleport still move the player
server-side; the anchor does not follow. Freeze again after moving them on
purpose.

## FakeGameMode

```java
FakeGameMode fakeGameMode();
void spectator(Player player, boolean enabled);
boolean isSpectator(Player player);
```

Sends the game-mode change and spectator abilities without `setGameMode`.
The server keeps the real mode, so other plugins' checks are unaffected.

Limits: the server simulates the real mode — the player collides, can be
hit, and interacts with blocks. Pair with `Visibility` and
`setCollidable(false)`. Any real game-mode change resends the truth.

## SilentContainer

```java
SilentContainer silentContainer();
InventoryView open(Player viewer, Inventory source, Component title, boolean editable);
void close(Player viewer);                   // if this plugin opened their mirror
void closeAll();                             // every mirror this plugin opened
```

Opens a mirror of `source`. No lid, no sound. The mirror follows the source
once a tick while open; when `editable`, only the slots a click or drag
changed are written back, so a change the owner makes at the same moment in
another slot survives. The write lands on the source's thread — its holder
entity, its block, or the global region — so it is safe on Folia. Read-only
mirrors cancel every click. Call `open` from the viewer's thread; `close` and
`closeAll` are safe from any thread.

Limits: changes on the source show a tick late; a source that is not a
multiple of nine slots is padded to the next row and the padding cannot be
touched (an item a shift-click lands there is handed back to the viewer);
the source's own slot rules (a furnace's fuel slot) are not enforced on an
editable mirror.

## WorldBorders

```java
WorldBorders worldBorders();
VirtualBorder create(Location center, double size);   // nobody sees it yet
VirtualBorder seenBy(Player viewer);                  // null while they see the world's own

// VirtualBorder
void show(Player viewer);                    // replaces whatever border they saw
void hide(Player viewer);                    // back to the world's own
void size(double size, Duration over);       // one packet; each client animates it
void size(double size);
void center(double x, double z);
void warning(int blocks, int seconds);       // when the screen turns red
void damage(double perBlock, double buffer); // zero hurts nobody
boolean contains(Location at);
double size();                               // right now, mid-resize included
Collection<Player> viewers();
void remove();                               // hides it from everyone
```

A border per arena, per match or per zone, in the same world, each with its
own edge and its own clock:

```java
VirtualBorder zone = Packets.of(this).worldBorders().create(arena.center(), 200);
match.players().forEach(zone::show);
zone.size(20, Duration.ofMinutes(2));
```

Every change is one initialize-border packet to each viewer. A resize is sent
as where it starts, where it ends and how long it takes, so the client draws
the whole animation and the server sends nothing while it runs; the width at
any moment is worked out from that clock when asked. The world's own border
packets are dropped on their way to a viewer who sees a virtual one in that
world, so a `/worldborder` elsewhere cannot overwrite it; a respawn or a world
change resends the right border a tick later, and a viewer in another world
sees that world's border.

The client stops at the edge, but a modified client, an ender pearl or a
vehicle does not ask, so the server hurts viewers outside the way vanilla
does: every ten ticks, `perBlock` damage for each block past `buffer`, at
least one, with the `OUTSIDE_BORDER` damage type. Defaults are vanilla's —
warning at 5 blocks or 15 seconds, 0.2 damage per block past 5.

Limits: one border per player, across every plugin. Square only; a circle is
particles or `FakeBlocks`. The server's collision, block placement and portals
still follow the world's own border. `hide` sends the world's border as it
stands, without a resize it may be in the middle of. Without PacketEvents a
border can be created and shaped but shows to nobody and hurts nobody.

## Lifecycle

What a plugin hid, froze, faked or opened is undone when that plugin is
disabled, and every border it created is removed. `Packets.releaseAll()` runs when the library disables and drops the
listeners. Nothing survives a player leaving.

## Threading

Every method is safe from any thread except `SilentContainer.open`, which is
an inventory open. Anything that touches an entity hops to that entity's
thread, so the module behaves the same on Folia.
