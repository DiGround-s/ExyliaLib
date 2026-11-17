# Downed

Downed instead of dead: a lethal hit leaves the player crawling with a bleed-out
timer on a boss bar, and a teammate who sneaks beside them long enough brings them
back up. Since 1.621.0.

Entry point: `net.exylia.lib.downed.Downed`.

## Using

```java
private Downed downed;

@Override
public void onEnable() {
    downed = Downed.of(this)
            .when(player -> runs.isInRun(player))                       // who goes down; others die
            .reviver((helper, down) -> Parties.together(helper.getUniqueId(), down.getUniqueId()))
            .bleedOut(Duration.ofSeconds(30))
            .revive(Duration.ofSeconds(4))
            .range(2).health(6)
            .bledOut(player -> runs.eliminate(player))                 // default: a real death
            .onEnd((player, end) -> runs.checkWipe(player))            // REVIVED, BLED_OUT, QUIT, CANCELLED
            .messages(() -> messages.get().downed())                    // DownedMessages, English by default
            .start();
}

@Override
public void onDisable() {
    downed.stop();   // stands everyone up (CANCELLED)
}
```

`down(player)` and `lift(player)` do it by hand; `isDowned(player)` and
`downed(uuids)` (how many of a group are down) feed a wipe check.

## Rules

- A held totem saves the player the vanilla way; a player `when` refuses dies as usual.
- While down: no damage (void still kills), no attacking, using or eating.
- The bleed-out clock stops while someone revives. A hit on the helper restarts
  the revive; a helper revives one player at a time.
- The crawl is a client-side barrier above the head plus the swimming pose; the
  real block is sent back when they stand.
- Every step runs on the player's own thread (Folia safe).
