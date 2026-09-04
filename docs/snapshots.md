# Snapshot module

A player's state, kept for later — held in memory for the length of a menu, or
stored so it survives a disconnect, a restart and a crash. Since 1.34.0.

Entry point: `net.exylia.lib.util.snapshot.Snapshots`.

## Where they were knows its server

*Since 1.109.0* the row stores the location as an `ExyliaLocation`, server
name included, so a snapshot taken in the lobby and restored in an arena can
still say where "back" is. Rows written before hold the six-part `Location`
text and read as a place on whichever server reads them, which is what they
always meant. `restore(..., wentBack)` still hands a `Location` to the
callback, and only when the place is on this server; use `restoreAndReturn`
when it may not be.

## Using

```java
private PluginSnapshots snapshots;

@Override
public void onEnable() {
    snapshots = Snapshots.of(this);
}

// Joining an arena: keep what they own, then hand out the kit.
snapshots.saveAndClear(player, "ffa").thenRun(() -> giveKit(player));

// Leaving it — this tick, or three restarts later.
snapshots.restore(player, "ffa", lobby -> teleport(player, lobby));

// Held in memory instead, for as long as a menu is open:
Snapshot before = snapshots.capture(player);
before.restoreTo(player);
```

A snapshot holds the inventory, armour, off hand, ender chest, health and
maximum health, hunger, experience, potion effects, game mode, flight, and the
physical state — fire ticks, remaining air, velocity, walk speed and
invulnerability.

## One type, two lifetimes

`Snapshot` is the same value whether it lives in a field or in a table. Which it
is depends on the method called, not on the type used.

ExyliaCommons spent four classes on that one distinction — a `SnapshotFactory`,
a `SnapshotRegistry`, a `SnapshotCacheManager` and a `SnapshotManager` — and the
last of them was a static singleton with an `initialize(plugin)`. In a shared
library that means the first plugin to call it owns the module and its
`shutdown` takes it away from every other plugin on the server. There is nothing
to initialise here.

## Context, and the bug it fixes

A stored snapshot is identified by the player **and** the reason it was taken:
`"ffa"`, `"event"`, `"sandbox"`, `"kit-editor"`.

ExyliaCommons stored one row per player. A player in an FFA arena who then
joined an event had the arena snapshot overwritten by the event one — and when
the event gave them "their" inventory back, it gave them the arena kit and
destroyed everything they actually owned. That is the single worst bug in the
module this replaces, and it is the reason the key is a pair.

The library's `@Id` is one component, on purpose: a find, a delete, a generated
key and a Mongo `_id` all mean one value. So the pair is folded into one derived
key, `uuid:contextId`, which nobody ever types. Both halves are also stored as
their own columns and indexed together, so `where("uuid", …)` is a real index
lookup rather than a scan.

## Contracts

- **Everything that touches the database is a `CompletableFuture`.** There is no
  synchronous form, here or anywhere else in the library.
- **Capture and restore run on the thread that owns the player.** The store
  schedules that itself through `runAtEntity`; a caller holding a snapshot in a
  field does it from an event handler, which already is that thread.
- **`saveAndClear` clears only after the write is durable**, and back on the
  player's own thread. ExyliaCommons cleared first, so a write that failed left
  the player with neither their inventory nor a snapshot of it.
- **A failed write clears nothing**, and the caller's own `thenRun` does not run
  either — so nobody is handed a kit on top of their own gear.
- **Clearing takes the inventory, armour and off hand and nothing else.** Health,
  experience, game mode and the ender chest are the caller's to change. A player
  whose health and mode were also wiped would be handed a kit while dying in
  spectator.
- **A restore removes the row only after the player has actually been restored.**
  A player who leaves mid-restore keeps their snapshot and gets it on their next
  join.
- **One unreadable item costs that slot.** The rest of the snapshot survives, and
  the problem is reported once. ExyliaCommons caught everything and returned
  `null`, so a single item written by a version of the server that no longer
  exists silently discarded a whole inventory.
- **An absent part is skipped, not defaulted.** A row written by ExyliaCommons
  has no ender chest and no physical state; restoring those parts from it leaves
  the player's own alone.
- **Nothing is cached.** A snapshot is read once when it is restored and then
  deleted. Caching a value that is used once and destroyed would be a map that
  only ever holds stale entries.
- **The palette reload does not apply.** Nothing here is derived from the
  palette — a snapshot holds items and numbers, and nothing in it is a
  `Component` — so there is no `invalidateAll` and no hook in `loadPalette`.

## Partial restore

`SnapshotPart` is a typed set rather than a bag of booleans. Full restore stays
the default and needs no argument.

```java
snapshots.restore(player, "ffa");                        // everything

snapshots.restore(player, "ffa", null,
        SnapshotPart.set(SnapshotPart.HEALTH, SnapshotPart.HUNGER));

snapshots.restore(player, "ffa", null,
        SnapshotPart.allExcept(SnapshotPart.INVENTORY));  // keep the kit
```

| Part | What it covers |
| --- | --- |
| `INVENTORY` | The 36 main slots |
| `ARMOR` | The four armour slots |
| `OFF_HAND` | The off-hand slot |
| `ENDER_CHEST` | The ender chest — new, absent from a commons row |
| `HEALTH` | Health and maximum health |
| `HUNGER` | Food level and saturation |
| `EXPERIENCE` | Level and progress |
| `POTION_EFFECTS` | Active effects; whatever is active is cleared first |
| `GAME_MODE` | The game mode |
| `FLIGHT` | Allowed, flying, and fly speed |
| `PHYSICAL` | Fire, air, velocity, walk speed, invulnerability — new |

A partial restore still removes the row: it is a decision about what to put
back, not about whether the snapshot has been used. A caller who wants to keep it
reads it with `find` and applies it by hand.

## What replaced `restoreSync`

ExyliaCommons offered a `restoreSync` for a plugin shutting down or a player
quitting, and it blocked the main thread on a database read and a delete. There
is no synchronous form here, and there should not be: a shutdown that waits on a
database is a shutdown that hangs when the database is the thing that went wrong.

What replaces it is nothing, and that is the point. The snapshot became durable
the moment the player entered the context, so a player who is disconnecting or
whose server is stopping needs no work at all — the row is still there, and the
next join restores it.

| Situation | What to call |
| --- | --- |
| A player quits | Nothing. The row is already durable |
| A player joins | `restoreAll(player, …)` — every context, oldest applied last |
| A plugin disables | Nothing. `Snapshots.release` only forgets the repository |
| Needing their old location before they go | `pending(uuid, contextId)` — reads, touches nothing; a live location only when that place is on this server. `pendingPlace` answers with an `ExyliaLocation` wherever it is. *Since 1.109.0.* |
| Putting them back where they were, on whichever server that was | `restoreAndReturn(player, contextId, parts)` — restores the parts, then a plain teleport on the same server or a handover through the proxy elsewhere. *Since 1.109.0.* |

Callers that used `restoreSync` to move a player before they left should stop:
teleporting during `PlayerQuitEvent` does nothing, and teleporting during
`onDisable` races the server's own save.

## Double restore

ExyliaCommons guarded against restoring twice with an in-memory
`ConcurrentHashMap` of generations — which is exactly the guarantee a restart
destroys, and the restart is what the database exists to survive. It is gone.

The durable path is correct on its own instead: the row is deleted as part of the
restore, and a second restore of the same context finds nothing and does nothing.
Two restores racing each other both read the row, both apply it, and one deletion
wins — which puts the player back in the state they were in, twice. That is
idempotent by construction, because applying a snapshot is.

## Migration from ExyliaCommons

The first store a plugin opens copies whatever ExyliaCommons left in
`snapshot_player_states` into `exylia_snapshots`, once, in the background.

- **It copies and never deletes.** The old table is left byte for byte as it was,
  so a server can go back. The price is a table left on disk, which is the
  cheapest insurance available against a migration going wrong at three in the
  morning.
- **It marks itself done with a row in the new table**, keyed under the nil
  `UUID` and the context `$legacy-import`. In the database rather than on disk,
  because two servers sharing one MySQL must not each decide from their own disk
  that the copy still needs doing.
- **It is safe to run twice anyway.** A legacy row is skipped when the new table
  already holds that player and context, so even with the marker lost a second
  run cannot overwrite a newer snapshot with the stale one it superseded.
- **A row it cannot read is left where it is** and reported, rather than dropped.
- **A row with no context is filed under `legacy`.** Commons allowed the column
  to be null, and the inventory in such a row belongs to somebody just as much as
  any other.

Reading a table is what creates it, so on a server that never ran ExyliaCommons
the import leaves one empty table behind. Turn `import-legacy` off there.

## The stored format

The blob is what ExyliaCommons wrote, because rows it wrote are in production and
hold everything owned by every player who was in an arena at the last restart.

```json
{"gameMode":"SURVIVAL","armor":[null,null,null,"rO0…"],"inventory":[…],
 "offHand":null,"health":20.0,"maxHealth":20.0,"foodLevel":20,
 "saturation":5.0,"level":30,"exp":0.5,
 "potionEffects":[{"type":"SPEED","duration":600,"amplifier":1,
                   "ambient":false,"particles":true,"icon":true}],
 "allowFlight":false,"flying":false,"flySpeed":0.1}
```

Each item is its own Base64 string — `serializeAsBytes` through `Base64`, an
empty slot as JSON `null`. This is deliberately **not** the library's
`ItemStack[]` codec, which writes a whole array through one
`BukkitObjectOutputStream`. The two are incompatible and the one already in the
database wins.

`enderChest` and `physical` are added by this library, and written only when the
snapshot carries them. ExyliaCommons reads by key and ignores anything it does
not know, so a row written here still restores an inventory on a server still
running it; and a snapshot read from a commons row and stored again is
byte-identical to what commons would have written, rather than growing two keys
it never had.

## Configuration

`SnapshotSettings` nests in a plugin's own config record.

```yaml
snapshots:
  import-legacy: true   # copy the ExyliaCommons table on first use
```

There is deliberately nothing about expiry. An orphaned snapshot is somebody's
inventory, and a rule that deletes it after a fortnight is a rule that deletes an
inventory belonging to a player who was on holiday.

## What was fixed

- **A second context no longer destroys the first.** The key is the pair, so an
  arena snapshot and an event snapshot coexist.
- **`saveAndClear` writes before it clears.** A failed write leaves the player
  holding everything they owned.
- **One unreadable item costs one slot**, and is reported. It used to cost the
  whole snapshot, silently.
- **`restoreSync` is gone**, and with it a blocking database call made from
  `onDisable` and from a quit handler.
- **The in-memory double-restore guard is gone.** The durable path is correct on
  its own.
- **The static singleton is gone**, replaced by `Snapshots.of(plugin)`.

## What was added

- **The ender chest**, which ExyliaCommons never stored.
- **The physical state** — fire ticks, remaining air, velocity, walk speed,
  invulnerability. A player who was on fire and drowning used to come back
  neither, which is a small gift in a lobby and a real one in a minigame that put
  them there on purpose.
- **Partial restore**, as a typed set of parts.
- **`restoreAll`**, which applies every context a player has, oldest last, so
  they end up in the state they were in before any of it.

## Source and tests

- Public: `util/snapshot/` — `Snapshots`, `PluginSnapshots`, `Snapshot`,
  `SnapshotPart`, `SnapshotCodec`, `SnapshotSettings`.
- Internal: `util/snapshot/internal/` — `PlayerState` (the only class that talks
  to a live player), `SnapshotRow` (the new table and its derived key),
  `LegacyRow` (the ExyliaCommons table, read only), `LegacyImport` (the one-time
  copy and its marker), `SnapshotRuntime` (codec registration, reporting, and
  the ordering stamp).
- Tests: `SnapshotCodecTest` covers the wire format both ways — a hand-written
  ExyliaCommons row, every key it wrote, an empty slot as `null`, and one
  unreadable item costing one slot. `SnapshotCaptureTest` covers capture and
  restore without a database, including partial restore and absent parts.
  `SnapshotStoreTest` covers the store against H2: two contexts coexisting, a
  failed write clearing nothing, a player who leaves keeping their snapshot, and
  the legacy import being correct and idempotent.
