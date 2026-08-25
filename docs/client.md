# Client module

Lunar and Feather client features — waypoints, client-drawn cooldowns,
teammate markers — behind one API. The consuming plugin never asks which
client a player runs. Since 1.7.0; teams since 1.36.0.

For nametag colours, glow and collision — which every player sees, vanilla
included — see [nametags](nametags.md). That is a different module on purpose:
this one does nothing for a player on an unmodified client.

Entry point: `net.exylia.lib.client.Clients`.

## API

```java
PluginClients clients = Clients.of(this);

clients.waypoints().show(player, Waypoint.at("base", location));
clients.cooldowns().show(player, Cooldown.seconds("pearl", 16));
clients.teams().create(redPlayers);
clients.clear(player);          // only what this plugin drew

Clients.markers().update(viewer, teammates);
```

`Clients`:

| Method | Contract |
| --- | --- |
| `of(plugin)` | this plugin's own waypoints, cooldowns and teams |
| `waypoints()` / `cooldowns()` / `markers()` | the three feature groups, unowned |
| `teams(plugin)` | teams whose members see each other's markers |
| `brandOf(player)` | `ClientBrand.VANILLA`, `LUNAR` or `FEATHER` |
| `isSupported()` | whether any integration is live here |
| `clear(player)` | forget everything sent to that player, by anyone |

### Ask for `of(this)`, not the static groups

Since 1.48.0. What a plugin sends is keyed by that plugin, which is what makes
three things true:

- Two plugins can both show a waypoint named `spawn`. Keyed by name alone, the
  second `show` deleted the first one's marker off the player's screen.
- `clients.clear(player)` takes down what this plugin drew. `Clients.clear(player)`
  wipes the client, including markers another plugin is still relying on — the
  same mistake as calling `Effects.stopFor` to end one game.
- Disabling a plugin removes its waypoints and cooldowns. A marker whose owner
  is gone cannot be removed by anybody and sits on the minimap until the player
  reconnects.

The static groups still work and still share one unowned bucket. They are for a
one-off, not for a plugin that keeps state.

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

## Teams

Since 1.36.0. `markers()` is a push: it draws a set of teammates and forgets.
A game that lasts has to answer "who is on this team" again on every join,
death, quit and reconnect, and every caller that kept that list in a map of its
own got the same three things wrong — a player in two teams at once, a team left
behind when the game ended, and a member who had logged out.

```java
PluginTeams teams = Clients.teams(this);

ClientTeam red = teams.create(redPlayers);
red.add(latecomer);        // draws everyone's markers again
teams.leave(deadPlayer);   // without knowing which team held them
red.delete();              // clears every member's markers
```

`ClientTeam`: `id()`, `add`, `addAll`, `remove`, `has`, `members()`, `size()`,
`refresh()`, `delete()`, `alive()`.
`PluginTeams`: `create()`, `create(players)`, `find(id)`, `of(player)`,
`leave(player)`, `all()`, `clear()`.

- **One team per player, server-wide.** Joining a second leaves the first, and
  the team left behind is re-drawn. `of(player)` answers across plugins,
  because "which team is this player in" does not depend on who asked.
- **Members are held as ids, not `Player` objects.** A team that outlives a
  session must not be the reason the server keeps an entity alive. A member who
  logged out is dropped when the team is next read, so a team nobody cleaned up
  still shrinks to nothing.
- **Teams die with the plugin that created them**, so a game that ends badly
  cannot leave markers on a screen.

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
- **The library remembers what it sent and re-sends it** while the player is
  connected: after a world change, only what belongs to the new world. In
  memory only, and forgotten the moment they leave — see below.
- A failure inside an integration is contained: it is the other plugin's bug,
  not yours.

## Coming back

A player who disconnects is forgotten. Nothing is held for them, on purpose: a
marker is drawn from a row in a plugin's own table, and that row is the only
copy that is still true after the player has been away. A home deleted while its
owner was offline must not come back as a waypoint.

So the plugin keeps the truth, and the library keeps the timing:

```java
clients.waypoints().restoreWith(player -> homes.waypointsOf(player));
```

The timing is the half a plugin cannot get right alone. A modified client
announces itself a moment **after** joining, so anything sent from a
`PlayerJoinEvent` goes out while the player still looks vanilla and is dropped
on the floor. This runs once that has settled, and only then: a world change
re-sends what is already remembered, so asking the owner there would draw
everything twice.

One function per plugin — registering again replaces it. It is called on the
thread that owns the player, so read memory rather than a database, and return
an empty collection when there is nothing to show.

## Source and tests

- Public: `client/Clients.java`, `PluginClients.java`, `Waypoint.java`,
  `Cooldown.java`, `ClientBrand.java`, `ClientTeam.java`, `PluginTeams.java`.
- Internal: `client/internal/` (`ClientLink`, `ClientRegistry`,
  `ClientRuntime`, `ClientState`, `TeamRegistry`, `ApolloLink`,
  `FeatherLink`).
- Tests: `src/test/java/net/exylia/lib/client/`.
