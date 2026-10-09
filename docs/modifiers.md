# Modifiers

One multiplier pipeline for everything players earn: money, experience, drops,
and any type a plugin adds. Plugins that pay call it; plugins that boost feed
it. *Since 1.263.0.*

```java
// paying: run the payout through the pipeline
BigDecimal paid = Modifiers.pay(player, "missions", mission.money());
List<ItemStack> loot = Modifiers.drops(id, "mines", drops, "mine:" + mine.id());
int xp = Modifiers.xp(id, "mines", points);
plugin.rewards().give(player, Modifiers.rewards(id, "crates", won));
double elo = Modifiers.apply(id, "elo", "ranked", 25);

// boosting: list what you pay and hand over a factor
Modifiers.source(this, "ranked", "IRON_SWORD", "elo");
Modifiers.register(this, (player, type, source, scope) ->
        "elo".equals(type) ? eloBoosts.factor(player) : 1.0);
```

## Types and sources

- **Types** are open lower-case strings. Built in: `Modifiers.MONEY`
  (`money`), `Modifiers.XP` (`xp`), `Modifiers.DROPS` (`drops`). A plugin uses
  its own (`elo`, `clan-exp`) just by passing it.
- **Sources** name what pays: `mines`, `shop-sell`, `votes`. `Modifiers.ANY`
  (`*`) as a booster's source means every source *of its type*.
- **A type is never a wildcard.** A provider answers `1.0` for a type it does
  not name, so a money booster for `*` never moves Elo.
- `Modifiers.source(plugin, id, icon, types...)` lists a source for booster
  screens; `sources()`, `sources(type)` and `source(id)` read the list, in the
  order it was registered. A source nobody listed still works — it is just not
  offered on screen. Listing the same id again from the same plugin replaces
  it; when two plugins list one id, the first stays.

## How factors combine

Every provider's factor is **multiplied** together, the permission provider's
included: a booster plugin at x2 and a rank at x1.5 make x3. Each provider
combines its own boosts however it likes (adding, multiplying, the highest, a
cap) and hands over one number. No providers is the identity: amounts come
back unchanged, reward lists as the same list.

A provider that throws is logged and counted as `1.0`; `NaN` is `1.0`; below
zero is zero.

## Providers

```java
public interface ModifierProvider {
    double factor(UUID player, String type, String source, @Nullable String scope);
}
```

- **Memory only, synchronous, any thread.** It runs on every block broken and
  every orb picked up.
- `type` and `source` arrive lower case. `scope` is what the caller passed
  (`mine:gold`) or `null`: a provider that boosts one place matches it, the
  rest ignore it.
- `Modifiers.register(plugin, provider)`; released when the plugin disables.
  `Modifiers.unregister(provider)` takes one away earlier, for a module that is
  switched off at runtime.

## Permissions

Built in, no setup: `exylia.modifier.<type>.<source>.<factor>`.

| Node | Means |
| --- | --- |
| `exylia.modifier.money.*.1.5` | x1.5 on every money source |
| `exylia.modifier.drops.mines.2` | x2 drops from mines |
| `exylia.modifier.elo.ranked.1.25` | x1.25 on a plugin's own `elo` type from `ranked` |

Among a player's matching nodes (the exact source and `*`) the **highest
wins**; that one is multiplied with the other providers. A player's nodes are
read once and kept 10 seconds, and dropped on join, quit and
`/exylialib reload`; `Modifiers.invalidate(uuid)` drops them on demand. Only a
player online on this server has permission modifiers.

## Facade

| Method | Does |
| --- | --- |
| `factor(uuid, type, source[, scope])` | the combined multiplier |
| `apply(uuid, type, source[, scope], amount)` | `amount × factor`, for a plugin's own type |
| `moneyFactor(uuid, source, currency)` | the money factor for a price in that currency: `1.0` unless it is the default |
| `money(uuid \| player, source[, scope], amount)` | money in the default currency, boosted, rounded down to its decimals |
| `pay(player, source, amount)`, `pay(uuid, source, scope, amount)` | deposits the boosted amount in the default currency; answers what was paid, zero when nothing was |
| `xp(uuid, source[, scope], points)` | points, boosted, fraction rolled (`Multipliers.scale`) |
| `drops(uuid, source, items[, scope])` | items, boosted, fraction rolled, cut to stack size; never changes the input |
| `rewards(uuid, source[, scope], entries)` | a `RewardEntry` list: `ECONOMY` lines in the default currency by money, `EXPERIENCE` by xp, `ITEM` by drops, ranged amounts on both ends; commands and the rest untouched. The scope (1.265.0+, such as `event:koth`) reaches providers like every other call's |

## What is boosted, and when

- **Earnings only.** Refunds, transfers between players, market and auction
  sales and admin gives never go through here: they move exactly what was
  taken or agreed.
- **The default currency only.** Money in another currency is paid as it is
  (`moneyFactor` is `1.0` for it, and `rewards` leaves its lines alone).
- **Boost at queue time.** A payout kept for a player who is offline — a
  pending reward, a vote that waits for a join — is boosted when it is queued,
  not when it is claimed, so it keeps the boost it was earned with and a
  booster bought later cannot be applied to old earnings.

## Lifecycle, threads, reload

Everything is memory and synchronous; safe from any thread on Spigot, Paper
and Folia (nothing is scheduled). Providers and sources are released with
their plugin. The module keeps nothing derived from the palette; reload only
drops the permission cache.

## Code

`net.exylia.lib.modifier`: `Modifiers`, `ModifierProvider`, `ModifierSource`.
Internals in `modifier/internal/` (`ModifierRuntime`, `PermissionModifiers`).
Built on `util/Multipliers` for the rolled fractions. Tests:
`ModifiersTest`, `PermissionModifiersTest`.

The deprecated `SurvivalService.boostDrops`, `boostExperience` and
`payEarnings` forward here in ExyliaSurvivalCore.
