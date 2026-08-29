# Packets

Client-side tricks a staff plugin needs and the server has no API for: hiding
a player from some viewers, showing blocks that are not there, pinning a
player in place, making one client believe it is a spectator, and watching a
chest without opening it.

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
regained it. Between refreshes every outbound packet about a hidden target is
dropped on its way to a viewer who may not see them: spawn, metadata,
movement, equipment, animations, entity sounds, damage. Tab-list updates lose
the hidden rows and keep the rest.

Limits: the server still knows where the target is — they collide, block
arrows and appear in `getNearbyEntities`. Positional sounds and particles
carry no entity id and pass through. Pair with `setCollidable(false)` and
`setSilent(true)`.

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
kept. A `PlayerMoveEvent` guard covers the case PacketEvents is missing.

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

## Lifecycle

What a plugin hid, froze, faked or opened is undone when that plugin is
disabled. `Packets.releaseAll()` runs when the library disables and drops the
listeners. Nothing survives a player leaving.

## Threading

Every method is safe from any thread except `SilentContainer.open`, which is
an inventory open. Anything that touches an entity hops to that entity's
thread, so the module behaves the same on Folia.
