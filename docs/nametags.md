# Nametags

Changes how one player looks **to another player**: the colour of the name
above their head, whether they glow through walls, whether they can be pushed,
and whether they are still drawn while invisible.

Every player sees it, vanilla clients included. This is the difference from
[the client module](client.md), which talks to Lunar and Feather and does
nothing for anyone else.

```java
PluginNametags tags = Nametags.of(this);

// clanmates are green to each other, and visible through walls
tags.paint(viewer, clanmate, NametagStyle.of(NamedTextColor.GREEN).withGlow());

// an enemy is red, to this viewer only
tags.paint(viewer, enemy, NametagStyle.of(NamedTextColor.RED));

// and back to normal
tags.reset(viewer, clanmate);
```

## Per viewer, not per player

Two players can be told different things about the same third player at the
same time. The same person is red to their enemy and green to their clan, and
neither of those is on the server: there is no scoreboard, no team object, and
no server-side state to keep in step.

That is what makes this cheap. A vanilla scoreboard team is one truth for the
whole server, so a plugin that wanted per-viewer colours had to build a
scoreboard per player and keep every one of them updated.

## A style, not a team name

```java
NametagStyle.of(NamedTextColor.GREEN)   // just a colour
        .showingInvisible()             // drawn faintly while invisible
        .withGlow()                     // outlined through walls
        .withCollision();               // can be pushed again
```

Minecraft draws all of this through scoreboard teams, but the team name is an
implementation detail. Every caller used to invent its own (`"clan_" + id`,
`"allies_" + id`, …) and then had to keep those names in step with the colours
they meant.

Here the team name is derived from the style, so two viewers who paint a player
the same way share a team without either of them knowing there is one. Painting
a second player in a style already on screen sends **one** add packet rather
than creating a second team that draws identically.

Glow is deliberately not part of the team name. It rides on entity flags rather
than on the team, so two styles that differ only by it still share one.

## What is sent, and what is not

| You call | The client gets |
| --- | --- |
| first paint in a style | one team create, one flags refresh |
| second player, same style | one team add |
| same player, same style again | **nothing** |
| a colour change | one team remove, one team create |
| `reset` | one team remove, one flags refresh |

A colour that did not change is a packet nobody needed, so it is not sent.

Teams are left on the client when their last member leaves rather than deleted:
they are shared, they will be reused the moment anybody else is painted that
way, and an empty team on a client costs nothing while deleting and recreating
one costs two packets every time.

## Glow

Glow cannot be sent once and left alone. The server re-sends an entity's flags
whenever anything about it changes — a sprint, a hit, an item swap — and each of
those would put the outline out again. The module rewrites those packets as they
pass, which is why it needs PacketEvents and why it keeps an index of who should
glow for whom.

The check that runs on every metadata packet is one lookup that misses for a
viewer with nothing painted, which is almost everybody almost all the time.

The outline takes the team's colour.

## Ownership

`Nametags.of(plugin)` scopes everything to its plugin:

- A plugin resets only what **it** painted. A game cannot silently undo a
  clan's colour by calling `reset`.
- Everything a plugin painted is put back when it is disabled, so a game that
  ends badly cannot leave a player permanently red.

## Without PacketEvents

`Nametags.isSupported()` is `false` and every call does nothing rather than
failing. The server keeps working, with everybody in white.

## Threading

Every method is safe from any thread.

## Cleanup

| When | What happens |
| --- | --- |
| a player quits | forgotten as a viewer and as a target |
| a plugin is disabled | everything it painted is put back |
| the server stops | the packet listener is dropped and state cleared |

Nothing is written to disk.

## Where the code is

| | |
| --- | --- |
| API | `net.exylia.lib.nametag` — `Nametags`, `PluginNametags`, `NametagStyle` |
| Internal | `net.exylia.lib.nametag.internal` — `NametagRuntime`, `State`, `NametagSink`, `NametagPackets` |
| Tests | `src/test/java/net/exylia/lib/nametag/internal/NametagTest.java` |

`NametagPackets` is the only class that names PacketEvents types, so a server
without it never loads that one. `NametagSink` is the seam a test installs to
record what would have been sent.
