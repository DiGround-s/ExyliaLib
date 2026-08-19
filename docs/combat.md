# Combat

Whether a player is in combat, without asking which plugin decides that.

```java
if (Combat.isTagged(player)) {
    Text.of("{error}You cannot warp while in combat.").send(player);
    return;
}

// a plugin that starts a fight of its own says so
Combat.tag(target, attacker, Duration.ofSeconds(15));
```

## What this replaces

Four plugins each grew their own hook for the same question, and each one knew
a different set of combat plugins — so the same server answered "is this player
in combat" differently depending on which plugin asked. One of those hooks
returned `true` from `canAttack` unconditionally, with a `TODO` above it.

## The API

| Call | Answers |
| --- | --- |
| `isTagged(player)` | whether they are in combat |
| `remaining(player)` | how long it lasts, `Duration.ZERO` when untagged |
| `opponentOf(player)` | who they are fighting |
| `tag(target, attacker)` | puts them in combat for the plugin's own time |
| `tag(target, attacker, duration)` | for a set time |
| `untag(player)` | takes them out |
| `isProtected(player)` | new-player or respawn protection |
| `isPvpEnabled(player)` / `setPvpEnabled(...)` | their PvP switch |
| `canAttack(attacker, defender)` | whether the hit is allowed |
| `statsOf(player)` | kills, deaths, streaks, points — when anybody counts them |

## What is cached, and what is not

Only the tag, and only for half a second.

`isTagged` is asked on damage, on movement, on every scoreboard refresh, and the
answer is the same for a whole second of game time. Asking another plugin's map
each time is the cost this cache exists to avoid.

Three things are deliberately **not** cached:

- **The remaining time.** It is a countdown, and a cached countdown is a number
  that sits still and then jumps.
- **Writes.** `tag` and `untag` reach the plugin directly and then drop the
  cached answer for that player. A cached "not in combat" that outlives the tag
  is a player who warps out of a fight.
- **Anything with no key.** There is no "who is fighting" query, because no key
  predicts when that changes.

Stats are cached for five seconds: they change on a kill, not on a tick.

## Failing open

Every fallback is what a server with **no** combat plugin would answer:

| Question | When nothing is installed, or the plugin throws |
| --- | --- |
| `isTagged` | `false` |
| `remaining` | `Duration.ZERO` |
| `isPvpEnabled` | `true` |
| `canAttack` | `true` |
| `statsOf` | empty |

Failing the other way would silently stop every fight on the server because one
integration broke. A combat plugin that throws is their bug; it is logged once
and ignored.

## Empty is not zero

`statsOf` returns empty when the active plugin counts nothing, rather than a
record full of zeroes. "No kills" and "nobody is counting" are different
answers, and a leaderboard that cannot tell them apart shows every player on a
PvPManager server as having exactly zero of everything.

`CombatStats.ratio()` is computed here rather than taken from the plugin,
because plugins disagree about zero deaths. A player who never died counts as
having died once, so ten kills and no deaths is `10.0` rather than infinity.

## Which plugin is used

Detection order, first one installed wins:

1. **DeluxeCombat** — the only one that counts kills, deaths and streaks.
2. **PvPManager** — tags only; `statsOf` is empty, honestly.

Both are reached by reflection, for the same reason the clan module uses it:
this library loads on servers that have neither, and naming a type it cannot
resolve would fail the class before the check inside it ever ran.

### A plugin we do not know

```java
Combat.registerBridge(new CombatBridge() {
    public String name() { return "CelestCombat"; }
    public boolean isTagged(Player player) { return celest.inCombat(player); }
    public Duration remaining(Player player) { return celest.timeLeft(player); }
}, 10);
```

Everything except `name()` and `isTagged` has a default, so a bridge writes only
what its plugin can actually answer — and every default is what a quiet server
does. Any bridge beats automatic detection; the highest priority wins.

## Threading

Every method is safe from any thread. A read may come from the cache; a write
reaches the underlying plugin on whichever thread called it, so that plugin's
own rules apply.

## Cleanup

A player who quits is dropped from both caches. Everything is cleared when the
library stops.

## Where the code is

| | |
| --- | --- |
| API | `net.exylia.lib.util.combat` — `Combat`, `CombatBridge`, `CombatStats` |
| Internal | `net.exylia.lib.util.combat.internal` — `CombatRuntime`, `CombatProvider`, `DeluxeCombatProvider`, `PvpManagerProvider` |
| Tests | `src/test/java/net/exylia/lib/util/combat/internal/CombatTest.java` |
