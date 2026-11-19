# Cosmetics

What every cosmetic plugin (kill effects, hit effects, arrow trails, shields,
armour trims and skins, chat cosmetics) shares, in `net.exylia.lib.cosmetic`.

| Type | For | Since |
| --- | --- | --- |
| `Cosmetics` / `CosmeticRule` | whether a player's cosmetics are drawn at all (staff on duty, event kits); rules from mode plugins, `Cosmetics.shows(player)` from cosmetic plugins | 1.94.0 |
| `CosmeticPurchases` / `Price` | buying a cosmetic: confirm, charge, grant, refund on failure | 1.239.0 |
| `CosmeticDefault` | what a player who never picked one gets, by rank | 1.468.0 |

## A choice of currencies (1.629.0)

A price (`price:` of a cosmetic, or a line of `shop.tier-prices`) can be a list: the player pays in
any one of them.

```yaml
shop:
  tier-prices:
    rare:
      - {amount: 1500, currency: dinero}   # counts on this server only, with ownership.scope: server
      - {amount: 250, currency: zenith}    # counts everywhere, in ownership.global-currencies
```

A currency this server has no economy for is left out (`Price.options()`), so one list works on
every server. When the player can pay more than one, `CosmeticPurchases` asks which, each choice
saying whether it unlocks on this server only or the whole network (`purchase.choose`,
`option-local`, `option-shared` in ExyliaLib's messages); with only one they can pay, it is charged.
`%price%` shows every choice, as `1,500 Dinero / 250 Zeniths`. No plugin change is needed.

## Defaults by rank

A server wants new players to have *something*, and VIPs something better,
without each of them opening a menu. A permission per cosmetic cannot say
that: ranks inherit, so a VIP holds the default group's node too and nothing
says which one wins. The plugin keeps an ordered list instead, and the first
line the player qualifies for is theirs:

```yaml
defaults:
  - permission: group.vip
    cosmetic: lightning
  - permission: ''        # everyone else
    cosmetic: blood
```

```java
// Settings record (editable in game: a list of records opens its entries)
@Comment("What a player who never picked one gets. The first line they qualify for wins.")
List<CosmeticDefault> defaults

// Where the plugin decides what to draw
String chosen = data.effect();
if (CosmeticDefault.isNone(chosen)) return null;              // they turned it off
if (chosen != null && usable(player, chosen)) return chosen;   // their own choice wins
return CosmeticDefault.resolve(player, settings().defaults(), registry::exists);
```

The contract every plugin keeps:

- **Read live, never stored.** The default is resolved where the cosmetic is
  drawn. Nothing is written to the player's row, so a rank bought or expired,
  or a line edited in the file, shows on the next draw.
- **The player's own choice always wins.** A choice they can no longer use
  (the rank that granted it ran out) falls back to the default.
- **Unequipping stores `CosmeticDefault.NONE`**, not an empty column. Empty
  means "never chose", which is when the default applies; without the
  sentinel nobody given a default could go without one. A line whose cosmetic
  is `none` gives that group nothing and stops the search.
- **Not an unlock.** A default plays whether or not the player owns it and
  never becomes theirs: not added to unlocks, not shown as owned. Menus show
  it as the active entry with a *Default* marker so the player knows where it
  came from.
- **Unknown ids fall through** to the next line, so removing a cosmetic from
  the catalogue never leaves a rank with nothing.
- Defaults ship **empty**: a server that never sets them behaves exactly as
  before.

A plugin with several slots (armour pieces, chat cosmetic types) keeps one
list per slot, as a record of lists so each one is editable in game.

`resolve` asks `Player#hasPermission`, which Bukkit allows on any thread.
