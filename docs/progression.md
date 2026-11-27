# Progression

The numbers of an endless progression game, with nothing kept and nothing
Bukkit: layered prestige, offline income, prices read as time, the engine of a
pace simulator and an onboarding funnel. A plugin keeps its own player model
and its own words; these answer what a step costs, pays, resets and how long it
takes. All of it lives in `net.exylia.lib.progression`, any thread.
*Since 1.683.0.* Extracted from ExyliaClicker, which runs on it.

Pair it with [`Curve`](util.md) for upgrade prices, `Numbers.endless` for
showing big numbers, [Modifiers](modifiers.md) for boosts and
[Ledger](ledger.md) for money created and sunk.

## Prestige — rebirth and the layers above it

```java
// The rebirth: the n-th one costs price(n) and pays tokens(n).
BigDecimal cost = Prestige.price(theme::price, done, count);
long tokens   = Prestige.reward(theme::tokensFor, done, count);
int  max      = Prestige.affordable(theme::price, done, coins, maxAtOnce);
if (Prestige.reached(before, after, autoRebirthAt)) announce(...);

// A layer above it: needs 25 of the layer below, 5 more each climb, pays 3 stars
// plus one for every 5 past the need, and adds 1 to the multiplier each climb.
Prestige.Layer ascension = new Prestige.Layer(25, 5, 3, 5, 1.0);
ascension.ready(have, climbed);
ascension.reward(have, climbed);
ascension.multiplier(climbed);

List<String> order = List.of("ascension", "transcendence");
Prestige.below(order, "transcendence");   // "ascension"; null for the first
Prestige.resets(order, "transcendence");  // what a climb starts over
```

- Steps count from 1: `price.apply(n)` is the n-th step. Sums use
  `Curve.MC` (20 digits), the same precision as upgrade prices.
- `affordable` stops at the first step the coins do not cover, so a batch
  never leaves the player in debt.
- What a reset clears (coins, levels, zones) is the plugin's: the library only
  names which layers sit under the one climbed.

## Offline — earnings while away

```java
long cap = (offlineHours + nightShiftLevels) * 3_600_000L;
Offline away = Offline.of(lastCounted, now, cap, 0.3);   // 30% of the pace
if (away != null) {
    credit(player, away.minutes());                     // minutes of full-pace income
    send(player, "While you were away: %h%h %m%m", away.hours(), away.minutesPart());
}
```

`of` returns null when there is nothing to pay: never counted, a clock that
went back, or less than one minute of income. Keep `lastCounted` up to date on
quit and on every count while online, so time is never paid twice.

## Waits — prices read as time

```java
double s = Waits.secondsPerMinute(coins, price, incomePerMinute); // 0 now, -1 no income
String label = Waits.text(s);          // "12s", "2h 30m"; null for none or over ten years
double bar = Waits.progress(coins, price);
BigDecimal reward = Waits.minutes(incomePerMinute, floor, 15);    // 15 minutes of income
```

Use it for "Ready in 12s" holograms, the three goals on a scoreboard (seconds,
minutes and hours away; which goals they are is the plugin's) and rewards that
stay worth having at every stage.

## Pacing — the engine of a pace simulator

The plugin plays a model player: it knows what the player earns, what they can
buy and what a purchase does. `Pacing` says which purchase a greedy player
makes, and reads the results against the design's ranges.

```java
Choice pick = Pacing.pick(coins, rate, nextRebirth, choices, Choice::price,
        choice -> rateAfterBuying(choice));    // 0 when it cannot be bought
if (pick == null) farmUntil(nextRebirth); else buy(pick);

double run = Pacing.median(runMinutes);
String problem = Pacing.outside("A rebirth every", run, 40, 120, "min"); // null on pace
```

`pick` only takes a purchase that brings the target closer than farming for it
alone, and among those the one that pays itself back first; anything priced at
or above the target is skipped.

## Funnel — onboarding for an admin screen

```java
Funnel funnel = Funnel.<Profile>builder(p -> p.counter(JOINED), p -> p.counter(SEEN))
        .step("sold", p -> p.counter(FIRST_SALE) > 0)
        .step("reborn", p -> p.rebirths() > 0)
        .returned("day", Funnel.DAY, p -> p.counter(BACK_DAY) > 0)
        .returned("week", 7 * Funnel.DAY, p -> p.counter(BACK_WEEK) > 0)
        .left(7 * Funnel.DAY, p -> furthestZone(p))
        .build(profiles, System.currentTimeMillis());

funnel.step("sold");               // players who reached it
funnel.returned("day").percent();  // of those who joined a day ago or more; -1 with none
funnel.leftAt();                   // where players unseen for a week stopped, most first
```

It stores nothing: build it from the players the plugin already holds when the
admin screen opens. Steps and returns keep the order they were added in.
