# Analytics

`net.exylia.lib.analytics.Analytics` reports custom events to Exylia Analytics when it is installed, and does nothing when it is not (no soft-depend crash, no log noise). Failures are swallowed, so a caller never breaks.

```java
Analytics.track(player.getUniqueId(), "casino.round", Map.of("game", "slots", "stake", 10.0));
Analytics.track("market.catalog", Map.of("items", 42)); // server-scoped
if (Analytics.available()) { ... }
```

Limits (enforced by Analytics): name `[a-z0-9_.]{1,48}`, at most 16 props, values are strings up to 200 chars, numbers or booleans. Any thread.

The lib reaches Analytics reflectively, so consumers add nothing to their build; add `ExyliaAnalytics` to `softdepend` so it loads first.
