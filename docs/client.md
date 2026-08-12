# Client module

Lunar and Feather client features — waypoints, client-drawn cooldowns,
teammate markers — behind one API. The consuming plugin never asks which
client a player runs. Since 1.7.0.

Entry point: `net.exylia.lib.client.Clients`.

## API

```java
Clients.waypoints().show(player, Waypoint.at("base", location));
Clients.cooldowns().show(player, Cooldown.seconds("pearl", 16));
Clients.markers().update(viewer, teammates);
```

`Clients`:

| Method | Contract |
| --- | --- |
| `waypoints()` / `cooldowns()` / `markers()` | the three feature groups |
| `brandOf(player)` | `ClientBrand.VANILLA`, `LUNAR` or `FEATHER` |
| `isSupported()` | whether any integration is live here |
| `clear(player)` | forget everything sent to that player |

Each group has `show(...)` (returns `boolean` — false when nobody could draw
it), `remove(player, name)`, `clear(player)` and `supported(player)`.
`Markers` has `update(viewer, teammates)`, `updateTeam(team)`,
`clear(viewer)`, `supported(player)`.

`Waypoint`: `at(name, Location)` / `at(name, x, y, z, world)`, then
`.colour(hex | Colour)`, `.lasting(Duration)`, `.locked()`,
`.startHidden()`. `Waypoint.Colour`: `of(r,g,b)`, `hex(String)`, `rainbow()`,
`WHITE`, `argb()`. `.lasting()` is enforced client-side only by Feather; on
Lunar the library removes it when time is up, so behavior matches.

`Cooldown` (client-drawn — whether the action is actually on cooldown is the
plugin's business; this only draws): `of(name, Duration)` /
`seconds(name, double)`, `.icon(Icon)`. `Icon.item(material)` /
`Icon.resource(resource, size)`.

## Behavior

- **The plugin never branches on the client.** It says what the player should
  see; whichever client can draw it, draws it. A vanilla player is a map
  lookup, not a special case.
- **Each client is one `ClientLink` and one line in `ClientRegistry`.**
  Adding a client touches nothing else; what a client cannot do answers
  `false` from its `supports`, never an exception.
- **Apollo and Feather are confined to one class each** (`ApolloLink`,
  `FeatherLink`) — verified in bytecode.
- **Detection is cached per player** and asked one second after join: the
  client announces itself after joining, and asking earlier caches "vanilla"
  for the whole session.
- **The library remembers what it sent and re-sends it** on reconnect and on
  world change (only what belongs to the new world). In memory only.
- A failure inside an integration is contained: it is the other plugin's bug,
  not yours.

## Source and tests

- Public: `client/Clients.java`, `Waypoint.java`, `Cooldown.java`,
  `ClientBrand.java`.
- Internal: `client/internal/` (`ClientLink`, `ClientRegistry`,
  `ClientRuntime`, `ClientState`, `ApolloLink`, `FeatherLink`).
- Tests: `src/test/java/net/exylia/lib/client/`.
