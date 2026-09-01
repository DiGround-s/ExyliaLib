# Overlays

Items in a player's own inventory that the server does not have.

A staff mode gives somebody a hotbar of tools. The obvious way to do it is to
save their inventory, write the tools in, and put the old one back afterwards.
Every step of that is a way to lose a player's items:

- A crash, a disconnect or an autosave between the write and the restore saves
  the tools to the world as real items. They have actions bound to them, so
  what is left behind is a working staff tool in somebody's chest.
- Putting an item in a real inventory fires the `inventory_changed` advancement
  trigger, so a decorative diamond hands out an advancement.
- An item picked up while the tools are in the way either replaces one or is
  lost.

None of that can happen here, because nothing is ever written. The real
inventory stays exactly as it was and the client is told a different story; a
crash loses the story and keeps the inventory.

```java
PluginOverlays overlays = Overlays.of(this);
overlays.load("staff", getConfig().getConfigurationSection("staff-hotbar"));

overlays.show(player, "staff");   // entering staff mode
overlays.hide(player);            // leaving it
```

## What is needed

PacketEvents. Without it `Overlays.isAvailable()` is `false`, showing an
overlay does nothing, and the plugin is told once in its console.

## What it covers

The inventory and nothing else. Damage, block breaking, flight, mob targeting
and chat are a staff mode's business, not an overlay's — [Packets](packets.md)
has the client-side half of several of them.

## Writing one

The same format a menu's items are written in: same `material`, same `name`,
same `lore`, same `actions`, same `condition`. Only the slot differs, because
an overlay's slots are places in a player's inventory.

```yaml
lock: FULL          # FULL (default) | OWNED
pickup: false       # default true
hide_rest: true     # default false

refresh:
  mode: SMART
  interval: 20

items:
  teleport:
    slot: 0
    material: COMPASS
    name: '{primary}&lTELETRANSPORTE'
    lore:
      - '{secondary}Información:'
      - ' {letters_black}▎ {letters}Click derecho para viajar a un'
      - ' {letters_black}▎ {letters}jugador {highlight}aleatorio{letters}.'
      - ''
      - '{warning}➥ Click derecho para activar'
      - ''
    actions:
      - 'right: staff:random_teleport'

  inspect:
    slot: 1
    material: BOOK
    name: '{primary}&lINSPECCIONAR'
    actions:
      - 'right: staff:inspect'

  leave:
    slot: 8
    material: BARRIER
    name: '{error}&lSALIR DE STAFF'
    actions:
      - 'any: staff:leave'
```

Or in code, for an overlay no file describes:

```java
OverlayDefinition staff = OverlayDefinition.of("staff")
        .slot(0, UiItem.of(compass).bindings(bindings).build())
        .lock(OverlayLock.FULL)
        .pickup(false)
        .hideRest()
        .build();
overlays.show(player, staff);
```

## Slots

The API numbers slots the way `player.getInventory().setItem` does, which is
not how the client numbers them and not how a container window numbers them
either. `OverlaySlots` converts between all three; a file only ever writes the
first.

| Index | What |
| --- | --- |
| `0-8` | Hotbar |
| `9-35` | The three storage rows |
| `36-39` | Boots, leggings, chestplate, helmet |
| `40` | Off-hand |

The five worn slots can be written by name — `boots`, `leggings`,
`chestplate`, `helmet`, `offhand` — because `slot: 39` for a helmet is a
number nobody remembers. Ranges and lists work as everywhere else:
`slots: "0-8"`, `slots: ["0-2", "helmet"]`.

## The three settings

### `lock`

How much of the player's inventory is frozen. Refusals are packet-level: the
client's message never reaches the server, so the server never answers it from
the items it really has.

| Value | What it refuses |
| --- | --- |
| `FULL` (default) | Everything, on the player's own screen and in the rows below any open menu |
| `OWNED` | Only the slots the overlay draws |

Under both, a click whose destination the server picks rather than the player
— shift-click, number key, off-hand swap, double-click, drag — is refused
whatever slot it started from, because it can land in one of the overlay's.
Also refused under both: dropping (`Q`, `Ctrl+Q`), off-hand swap (`F`),
middle-click pick, and the creative-mode slot write. That last one is the only
path by which a drawn item could become a real one, which is why it is refused
even in `OWNED` when the slot is the overlay's.

A refused click has already been drawn by the client, so the module asks the
server to say what it believes and rewrites the answer on its way past. The
overlay is intact one tick later.

### `pickup`

Whether the player may still pick items up. `true` by default: the item lands
in the real inventory, invisible under the overlay, and is there when the
overlay comes off. `false` refuses it, leaving the item on the ground where
the player can see it — which is what a staff mode wants.

This is the one refusal that is server-side rather than packet-side, because
picking an item up is a real change to a real inventory.

### `hide_rest`

Whether the slots the overlay does not draw look empty. `false` by default, so
the player keeps seeing their own items around the overlay's — right for a few
added buttons. `true` blanks all forty-one, which is right for a staff mode:
the real gear is not merely unusable, it is not on screen.

A blanked slot is still the player's hand. Holding one and right-clicking a
door, a chest or a mob reaches the world normally, because the client is being
shown an empty hand and the server has one too. What the overlay refuses there
is the other case: a blanked slot with a **real** item under it, where letting
the press through would use something that is not on the player's screen.

Which of the two it is depends on what the wearer happens to be carrying, so a
plugin that wants a blank slot to *do* something binds it — see below.

## What a press runs

The same click vocabulary a menu button answers to, so an overlay item and a
menu item are written the same way. An overlay item is pressed in the world as
well as on the inventory screen:

| Written | What does it |
| --- | --- |
| `left` | Left-clicking: a block, an entity, or the air |
| `right` | Right-clicking, in the air, on a block or on an entity |
| `shift_left`, `shift_right` | The same while sneaking |
| `drop`, `control_drop` | `Q` and `Ctrl+Q` |
| `swap` | `F` |
| `middle`, `double`, `number_key` | On the inventory screen |

Actions are given `overlay.id`, `overlay.slot`, `overlay.click`, and —
when the press was on something — `overlay.target` (the entity) and
`overlay.block`. `OverlayKeys` names them.

Left-clicking **air** is the swing of the arm, because that is the only packet
the client sends for it. A block press stops reaching the server as soon as the
block is out of reach, so a tool aimed at the horizon — jump to where I am
looking — would otherwise do nothing at all. The swing that goes with a block
or entity press is swallowed, so one click stays one action; a swing sent while
the button is held down is another click as far as the client is concerned, and
is bound as one.

A slot the overlay draws nothing in presses nothing, unless `empty_hand` says
otherwise. Under `hide_rest` every slot is the overlay's, so this is what keeps
an empty hand a hand.

## What an empty hand does

```yaml
empty_hand:
  actions:
    - 'right: staff:inspect'
  commands:
    - 'shift_right: player: co i'
```

The same lines an item takes, minus the item, for every slot the overlay owns
and draws nothing in. A staff mode uses it for the tools that answer a place
rather than a button: right-clicking a chest to look inside it without opening
it, wherever the wearer's hotbar happens to be.

Binding a click here **takes it away from the world for good**. A bound press
is answered by the overlay whether or not a real item sits under the blank
slot, which is the entire point: the alternative is a tool that works on one
hotbar slot and not the next, for a reason the wearer cannot see. Clicks left
unbound behave as they do above — the world when the hand is really empty,
refused when it is not.

In code:

```java
overlays.show(player, OverlayDefinition.of("staff")
        .hideRest()
        .emptyHand(new ClickBindings.Builder()
                .add("right: staff:inspect", actions::template)
                .build())
        .build());
```

## Limits

The armour slots are drawn, not worn. Blanking slots `36-40` empties them on
the inventory screen; what the player's body is wearing is a different packet
entirely and does not change. A staff mode that also wants the armour to
disappear off the body is asking for vanish, which is
[Packets](packets.md).

Left-clicking air is not bound, for the reason above.

An overlay is one player's. Two players in staff mode each have their own, and
nothing is shared between them.

## Redrawing

`refresh` is the menus' block and means the same thing: `SMART` redraws only
the slots that can actually change, `FULL` redraws all of them, `ON_CLICK`
redraws what was pressed. A static overlay never starts a timer at all, and a
slot that renders identical to what is on screen sends no packet.

```java
overlays.refresh(player);   // now, without waiting for the timer
```

## Threading

Every method is safe from any thread; anything that touches a player hops to
that player's thread first, so the module behaves the same on Folia. Presses
arrive on a Netty thread and are handed to the player's thread before any
action runs.

## Lifecycle

A player wears one overlay at a time, whichever plugin put it there — two staff
tools fighting over a hotbar would be a screen nobody can read. Showing a
second takes the first off.

Everything a plugin put on comes off when it is disabled, so a reload never
leaves somebody wearing buttons whose actions come from a classloader that is
gone. Taking one off is the server saying what it always believed; there is
nothing to restore.

```java
boolean  isShowing(Player viewer);
Optional<OverlayDefinition> showing(Player viewer);
void     hide(Player viewer);
void     hideAll();
Overlays.hide(viewer);     // whoever put it there
Overlays.worn();           // how many players are wearing one
```

## Where the code is

| Class | What |
| --- | --- |
| `overlay/Overlays` | Entry point |
| `overlay/PluginOverlays` | One plugin's overlays |
| `overlay/OverlayDefinition` | A compiled overlay, and what its empty hand does |
| `overlay/OverlayLock` | How much is frozen |
| `overlay/OverlaySlots` | The three slot numberings and every conversion |
| `overlay/OverlayKeys` | What an action is told about the press |
| `overlay/internal/OverlayRuntime` | Who is wearing what |
| `overlay/internal/OverlayView` | One player's overlay, and what it draws |
| `overlay/internal/OverlayClicks` | Whether a click is refused |
| `overlay/internal/OverlayPackets` | The only class that names PacketEvents |
| `overlay/internal/OverlayLoader` | The file format |
| `overlay/internal/OverlayListener` | Pickups, and forgetting a player who left |
