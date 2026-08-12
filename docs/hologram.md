# Hologram module

Floating text, items and blocks made of packets — never real entities.
Since 1.6.0.

Entry point: `net.exylia.lib.hologram.Holograms`.

## Declared in config, not in Java

```java
Holograms.show(this, "trophy", location, config.trophy());
```

`HologramConfig` nests in the plugin's config record:

| Field | Meaning |
| --- | --- |
| `enabled` | show at all |
| `lines` | the text lines (Kind TEXT) |
| `item` / `block` | what floats for Kind ITEM / BLOCK |
| `perPlayer` | each viewer sees their own placeholder values |
| `viewDistance` | how far it is visible |
| `offsetX/Y/Z` | position adjustment |
| `kind` | `TEXT`, `ITEM` or `BLOCK` |
| `properties` | `billboard`, `alignment`, `scaleX/Y/Z`, and more display properties |

The YML keys match what ExyliaCommons' `HologramTemplateSerializer` wrote,
minus the ones that mean nothing here (chunks, disk persistence — a hologram
is packets, not a file). The interval is in ticks, like the scoreboard:
another scoped deviation so commons files keep working.

## API

`Holograms`:

| Method | Contract |
| --- | --- |
| `show(plugin, id, location, config)` / with `data` | create and show |
| `get(plugin, id)` / `all(plugin)` | queries |
| `remove(plugin, id)` / `removeAll(plugin)` | cleanup |
| `isSupported()` | false when PacketEvents is absent — nothing draws, everything still works |
| `isViewing(player, hologram)` | whether that player currently sees it |

`Hologram`: `id()`, `location()`, `moveTo(location)` (teleport, not respawn —
no flicker), `attachTo(entity)` (rides the entity; the client moves it, zero
packets while moving), `lines(List)`, `refresh()`, `updateData(Map)`,
`visibleIf(Predicate<Player>)`, `isViewing(player)`, `viewerCount()`,
`remove()`, `removed()`.

## Behavior

- **Packets or nothing.** No PacketEvents → `isSupported()` is false; there
  is no real-entity fallback ticking on the server.
- **Visibility is checked four times a second** with squared distance per
  player per hologram; packets only go out when someone crosses the edge.
- **Only what changed is sent.** A line with no placeholders never refreshes;
  one that does diffs and re-sends just that line.
- Nothing outlives its owner: plugin disable, player quit and palette reload
  all clean up (palette reload re-sends everything).

## Source and tests

- Public: `hologram/Holograms.java`, `Hologram.java`, `HologramConfig.java`.
- Internal: `hologram/internal/` (`HologramImpl`, `HologramRuntime`,
  `DisplayPackets`, `DisplaySink`, `DisplayState`, `NoopHologram`).
- Tests: `src/test/java/net/exylia/lib/hologram/`.
