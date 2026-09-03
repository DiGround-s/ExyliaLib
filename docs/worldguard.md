# WorldGuard

Regions and region flags, without a compile-time dependency on WorldGuard in the
plugin that asks. Everything answers normally on a server that does not have it:
nothing is inside any region, every listing is empty, and every flag allows.

## Regions

```java
if (WorldGuardRegions.isIn(player, "spawn")) { ... }

for (WorldGuardRegion region : WorldGuardRegions.at(location)) {
    // highest priority first
}
```

Regions are per world — `test` in `world` and `test` in `world_nether` are two
unrelated regions — so every answer is scoped to the world of the location asked
about, and `WorldGuardRegion.qualified()` gives the `world:id` form a config file
needs to tell them apart.

## Flags

```java
if (!WorldGuardFlags.allows(WorldGuardFlags.KILL_EFFECTS, victim.getLocation())) {
    return;
}
```

The flags ExyliaLib registers:

| Flag | Denying it stops |
|------|------------------|
| `kill-effects` | Kill effects playing where somebody died inside the region |
| `hit-effects` | Hit effects playing on a blow landed inside the region |
| `arrows-effects` | Arrow launch, trail and impact effects inside the region |

Each is a state flag defaulting to **allow**, so a server that never touches
WorldGuard behaves exactly as it did before the flag existed. A region only ever
takes something away:

```
/rg flag lobby kill-effects deny
```

### Registration has exactly one moment

WorldGuard locks its flag registry the instant it enables, and a flag registered
after that throws. Every plugin's `onLoad` runs before any plugin's `onEnable`,
so `onLoad` is the only point in a server's life when a flag can still be added
— whatever order the plugins happen to load in.

That is why ExyliaLib registers the ecosystem's flags from **its own** `onLoad`
rather than each plugin registering its own. The Exylia plugins are started by a
licence loader that hands their code control at enable, by which time the
registry is shut. A new flag is therefore one line in `WorldGuardFlags.DEFAULTS`,
not a call from the plugin that reads it.

A plugin that is a plain `JavaPlugin` can still register its own from its
`onLoad`:

```java
@Override
public void onLoad() {
    WorldGuardFlags.register("my-flag", true);
}
```

A name another plugin already registered as a state flag is adopted rather than
refused, so two plugins wanting the same gate share one flag.

## Performance

A query is two field reads when WorldGuard is not installed or the flag was
never registered, and one WorldGuard region lookup otherwise — the same lookup
WorldGuard itself does for every block broken and every blow landed.

Ask it **last** all the same. Every consumer in the ecosystem checks whether an
effect would play at all first, so a death that draws nothing never pays for a
region lookup.

## Contracts

- Nothing outside `worldguard/internal/` names a WorldGuard type, which is what
  lets the library load on a server without it.
- An unregistered flag, a missing world, a world WorldGuard has no manager for
  and a registry that refused all answer "allowed". A gate that cannot be asked
  never withholds anything.
- Registration never throws. A flag that cannot be registered is a line in the
  log, never a plugin that fails to load.

## Source and tests

- Public: `util/worldguard/` — `WorldGuardRegions`, `WorldGuardRegion`,
  `WorldGuardFlags`.
- Internal: `util/worldguard/internal/` — `WorldGuardAccess`,
  `WorldGuardFlagAccess`, the only two classes that name WorldGuard.
- Tests: `WorldGuardFlagsTest` asserts the safety property — with no WorldGuard
  on the server, nothing registers and every flag allows.
