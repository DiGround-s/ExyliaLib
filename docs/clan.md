# Clan module

One API over clan plugins — SimpleClans, Kingdoms, UltimateClans — plus
externally registered bridges. The consuming plugin never asks which clan
plugin is installed. Since 1.8.0.

Entry point: `net.exylia.lib.clan.Clans`.

## API

| Method | Contract |
| --- | --- |
| `clanOf(UUID | Player)` | the player's clan, if any |
| `byTag(tag)` / `byId(id)` | lookups |
| `all()` | every clan |
| `hasClan(UUID | Player)` | quick check |
| `clanName(UUID)` / `clanTag(UUID)` | display helpers |
| `areInSameClan(a, b)` | membership check |
| `areAllied(a, b)` / `areRivals(a, b)` | relations; false when either has no clan |
| `onlineMembersOf(UUID)` | for team features |
| `registerBridge(ClanBridge, priority)` | plug an external provider; priority 10 beats any built-in detection |
| `providerName()` / `isSupported()` | what is active, if anything |
| `invalidate()` | drop the per-player cache |

`Clan` — the view of one clan: id, name, tag, leaders, moderators, members.

`ClanBridge` — the SPI: `name()`, `available()`, `all()` (snapshots),
`hasClan(player)`, `alliesOf(clanId)`, `rivalsOf(clanId)`,
`sameClan(a, b)`.

## Behavior

- **One provider is active at a time.** Each built-in provider references its
  plugin by reflection (`SimpleClansProvider`, `KingdomsProvider`,
  `UltimateClansProvider`); a `BridgeAdapter` wraps external `ClanBridge`s.
  Adding a provider touches nothing else.
- **External bridges beat built-ins** by priority.
- **The cache is Caffeine with a 3-second TTL**, because these calls sit on
  the hot path of damage events, kill messages and scoreboards. Dropped by
  `invalidate()` and by player quit.
- **What a plugin does not have comes back empty.** UltimateClans has no
  alliances → `alliesOf()` returns an empty set, not an exception.

## Source and tests

- Public: `clan/Clans.java`, `Clan.java`, `ClanBridge.java`.
- Internal: `clan/internal/` (`ClanProvider`, `ClanRuntime`, `BridgeAdapter`,
  `SimpleClansProvider`, `KingdomsProvider`, `UltimateClansProvider`).
- Tests: `src/test/java/net/exylia/lib/clan/`.
