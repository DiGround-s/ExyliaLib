# Toasts

The advancement popup in the top-right corner, for anything a plugin wants
noticed without a line of chat. *Since 1.278.0.*

```java
Toasts.send(player, Material.EMERALD, "{success}Sold {highlight}x16 Diamond", Toasts.Frame.GOAL);
Toasts.send(player, crateKeyItem, "{primary}&lKEY RECEIVED", Toasts.Frame.TASK);
```

| Method | What it does |
| --- | --- |
| `Toasts.send(Player, Material, String title, Frame)` | a toast with a material icon; air is drawn as paper |
| `Toasts.send(Player, ItemStack, String title, Frame)` | the same with an item icon, so custom models and heads show |
| `Toasts.Frame` | `TASK` (square, silent), `GOAL` (rounded), `CHALLENGE` (spiked, with the challenge fanfare); `Frame.of(name)` reads one ignoring case, `TASK` when unknown |

The title is Exylia notation: palette placeholders, `&` codes and gradients,
filled per player. A toast fits about 25 characters on a line; the client wraps
a longer title onto a second line.

## Nothing is kept

The toast is a fake advancement that exists on one client for one packet: it is
added already done, which is what pops the toast, and removed in the next
packet. It never shows in the advancements screen, the server never grants
anything, nothing is saved and nothing is announced in chat.

## Without PacketEvents

The title is shown the way `toasts.fallback` in ExyliaLib's `config.yml` says:

```yaml
toasts:
  # What a toast becomes without PacketEvents: action-bar, title, chat or none.
  fallback: action-bar
```

`title` shows it as a subtitle under an empty title. A plugin never has to check
which one happened.

## In a sequence

`[TOAST] icon;frame;text`, with `;who:` as an optional last segment like
`[TITLE]` — see [sequences.md](sequences.md):

```yaml
- '[TOAST] NETHER_STAR;CHALLENGE;{highlight}&lLEGENDARY DROP;who:both'
```

## Threads and lifecycle

Safe from any thread: the packets and every fallback are packet writes and
touch no world state. Nothing is kept per player or per plugin, so there is
nothing to release, and nothing derived from the palette is cached, so a reload
needs nothing either.

## Where the code lives

`toast/Toasts` (public); `toast/internal/ToastPackets`, the only class that
names PacketEvents.
