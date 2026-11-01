# Build zones — may this player build, break or use this block

*Since 1.432.0.* Class `net.exylia.lib.util.BuildZones`.

One question with one answer across every region and claim plugin on the
server, for actions that happen outside a block event: a menu opening a
block's storage, a remote sell, a block a plugin places itself. Inside a
`BlockPlaceEvent` listener at `ignoreCancelled = true` the claim plugins have
already spoken; this is for everything else.

```java
if (!BuildZones.canOpen(player, spawner.getBlock())) {
    messages.send(player, text.notYourLand());
    return;
}

// only the sources the server owner left on, and which one refused
BuildZones.Source refused = BuildZones.deniedBy(player, block, BuildZones.Action.BUILD, settings.zoneSources());
```

| Call | Contract |
| --- | --- |
| `canBuild(player, block)` | `BUILD` against every source |
| `canBreak(player, block)` | `BREAK` against every source |
| `canInteract(player, block)` | `INTERACT` against every source |
| `canOpen(player, block)` | `CONTAINER` against every source |
| `allows(player, block, action)` | any action against every source |
| `deniedBy(player, block, action, sources)` | the first source that refuses, in enum order, or `null` |
| `allows(source, player, block, action)` | one source; `true` when it cannot answer |
| `Source.available()` | whether the plugin behind it is enabled (for `EXYLIA_REGIONS`, whether any region is registered) |
| `ALL` | every source, unmodifiable |

| Source | Asks | `BUILD` / `BREAK` / `INTERACT` / `CONTAINER` |
| --- | --- | --- |
| `WORLDGUARD` | `RegionQuery.testBuild(location, player, flag)` after the bypass check | `block-place` / `block-break` / `interact` / `chest-access` |
| `EXYLIA_REGIONS` | the region policy at the block centre | `build` / `break` / `interact` / `interact` |
| `EXYLIA_PROTECTIONS` | `ProtectionsService` from the `ServicesManager` | `canBuild` / `canBreak` / `canInteract` / `canInteract` |
| `LANDS` | `Area.hasRoleFlag(player, flag, material, false)`; wilderness allows | `BLOCK_PLACE` / `BLOCK_BREAK` / `INTERACT_GENERAL` / `INTERACT_CONTAINER` |
| `GRIEF_PREVENTION` | `allowBuild` / `allowBreak`, and the claim's `checkPermission` for use | build / break / `Access` / `Inventory` trust |
| `TOWNY` | `PlayerCacheUtil.getCachePermission` | `BUILD` / `DESTROY` / `SWITCH` / `SWITCH` |
| `SUPERIOR_SKYBLOCK` | `Island.hasPermission(superiorPlayer, privilege)`; outside islands allows | `BUILD` / `BREAK` / `INTERACT` / `CHEST_ACCESS` |

- **Soft everywhere.** A source whose plugin is missing, disabled, cannot be
  hooked or throws when asked says "allowed"; a broken hook is logged once and
  not asked again for that plugin instance. A gate that cannot be asked never
  withholds anything.
- **Each plugin's own bypass applies**, because each is asked through the check
  its own listeners use: WorldGuard's bypass permission, GriefPrevention's
  ignore-claims, Lands admins, Towny admins.
- **Nothing loads without them.** The claim plugins are reached by reflection
  through their own class loader, resolved once per plugin instance (a reloaded
  plugin is resolved again) — the same hook cache `PvpZones` uses. WorldGuard
  types stay in `WorldGuardAccess`.
- **Threads.** Ask on the thread that owns the block (the player's region on
  Folia): the claim plugins read their caches there. Nothing is scheduled.
- **ExyliaProtections** answers through `ProtectionsService.canBuild`,
  `canBreak` and `canInteract` (default methods since 1.432.0, answering `true`
  until ExyliaProtections implements them).
- **Reload:** keeps nothing derived from the palette; not applicable.

## Source and tests

- Public: `util/BuildZones.java`; `exylia-api/.../protections/ProtectionsService.java` (`canBuild`, `canBreak`, `canInteract`).
- Internal: `util/internal/ClaimBuild.java` (the claim plugins), `util/internal/ClaimHooks.java` (the hook cache shared with `ClaimPvp`), `util/worldguard/internal/WorldGuardAccess.allows`.
- Tests: `src/test/java/net/exylia/lib/util/BuildZonesTest.java` (no claim plugin allows; region policies per action).
