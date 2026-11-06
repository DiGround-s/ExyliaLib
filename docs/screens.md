# Screens

Full-screen 2D menus drawn in front of the player, with a mouse cursor. No resource pack: every box is a
packet-only text display, so the screen exists only on the viewer's client.

Code: `net.exylia.lib.screen` (`Screens`, `Screen`, `Node`, `ScreenSession`, `ScreenEvent`); internals in
`screen/internal` (`LiveScreen`, `Layout`, `ScreenPackets`, `ScreenRuntime`, `ScreenMath`). Needs PacketEvents.

## Opening one

```java
Screen hub = Screen.of(session -> {
    String tab = session.state("tab", "queue");
    return Node.column(
            Node.text("{letters}Practice").scale(1.4),
            Node.row(
                    Node.text("{letters}Queue").padding(5, 12).background("#2a2240").hover("{primary}")
                            .onClick(event -> event.session().set("tab", "queue")),
                    Node.text("{letters}Duels").padding(5, 12).background("#2a2240").hover("{primary}")
                            .onClick(event -> event.session().set("tab", "duels")))
                    .gap(4),
            Node.text("{letters_black}Open: " + tab))
        .padding(12).gap(8).background("#17131f")
        .align(Node.Align.CENTER).justify(Node.Justify.CENTER);
}).onClose(session -> plugin.getLogger().info("closed"));

Screens.of(plugin).open(player, hub);          // on the player's thread
```

## The render function

- Called on open, after every click or scroll handler, after `session.set(...)`, and once a second. Read a
  moving value (a countdown, a queue size) inside it and it stays current; there is nothing to refresh.
- Runs on the viewer's thread. Read memory, never a database.
- Returns a fresh `Node` tree each time. Only what changed since the last render is sent: a box keeps its
  display while its key stays the same. The key is the node's `id(...)` when it has one, or its position in the
  tree; give ids to rows of a list whose length changes.
- At most 400 displays per screen (each box with a background is one, each text another). Past it the rest
  is left out and the plugin logs it once.

## Layout

A small flexbox in screen pixels. The layout area is `Screen.WIDTH` × `Screen.HEIGHT` (400 × 225), the part
of the view every common window shape shows at the default field of view; `Screen#size` changes it. The
backdrop (`Screen#backdrop`) reaches past it to the edges of the view.

| Node call | Effect |
| --- | --- |
| `row(...)`, `column(...)` | children across or down |
| `text(String)` | one line through `Text` (palette tokens, MiniMessage, `&` codes, small text) |
| `box()` | an empty box: a spacer with `grow`, or a block of colour with `size` |
| `width`, `height`, `size` | fixed size; otherwise a box takes what its children need |
| `grow(share)` | a share of the room left in the parent's main axis |
| `padding`, `gap` | room inside the edges, room between children |
| `align(START/CENTER/END/STRETCH)` | children across the main axis; `STRETCH` by default |
| `justify(START/CENTER/END/SPACE_BETWEEN)` | spare room along the main axis |
| `scale` | text size: 1 is one font pixel per screen pixel, ten pixels a line |
| `background`, `hover` | `{token}`, `#rrggbb` or `#aarrggbb`; `hover` also makes the box pointable |
| `onClick`, `onScroll` | handlers, on the viewer's thread; the screen redraws after them |

A click goes to the topmost box under the cursor that has `onClick`; a wheel step to the topmost that has
`onScroll`, so a list's rows can take clicks while the list itself scrolls.

## Input

- **Cursor:** the player's mouse. Their view does not move; the turn between rotation packets moves the
  cursor, gliding one tick so it reads smooth. The client sends rotation at most 20 times a second, so the
  cursor trails the real mouse by about 50 ms; that is the floor for any server-drawn cursor.
- **Speed:** `Screens.sensitivity(player, value)` from 0.2 to 3 (1 by default), kept until they quit and
  applied at once.
- **Clicks:** left and right, through an invisible interaction box around the eyes, so a click never has the
  vanilla miss cooldown and never reaches the world.
- **Wheel:** off by default. The client is then shown as a spectator: no hand, no crosshair, no hotbar, but a
  spectator's wheel sends nothing. `Screen#wheel(true)` keeps the real mode so the wheel scrolls, at the cost
  of the hand and crosshair. Page lists with buttons when the wheel is off.
- **Shift** closes. So does damage, death, a world change, quitting, the opening plugin being disabled, and
  another screen opening for the same player (one per player across all plugins).

While a screen is open the player is frozen in place (`Packets#movement`).

## Threads and lifecycle

`ScreenSession` is safe from any thread. Handlers and `onClose` run on the viewer's thread; `onClose` only
while the player is online and the plugin enabled. Nothing survives the plugin: its screens close on disable.
The module keeps nothing derived from the palette between renders (each render re-reads the tokens), so it
has no `invalidateAll`.

## Trying it

`/exylialib screentest open [spectator|wheel]` (permission `exylialib.admin`) opens a demo built on this API.
