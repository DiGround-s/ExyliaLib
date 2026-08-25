# Menus

Menus written in configuration, compiled once and opened cheaply.

```java
PluginMenus menus = Menus.of(this);

// when configs load, once
File file = new File(getDataFolder(), "menus/kits.yml");
menus.load("kits", YamlConfiguration.loadConfiguration(file));

// when a player asks
menus.open(player, "kits");
```

## Three things kept apart

| | What it is | Lifetime |
| --- | --- | --- |
| `UiDefinition` | what the file says, compiled | one per menu, shared by everybody |
| `UiSession` | one player's open window | until they close it |
| `UiEntry` | one row of a list | until the list is replaced |

Reading a file is the expensive half and happens once. Opening a menu renders
the slots that are shown and nothing else.

What an item **looks like** is not here at all. That is
[`Item`](items.md), which ExyliaSpecialsV3, PracticeCore, Shields and
SurvivalCore all use without ever opening a menu. A `UiItem` is an `Item` plus
what clicking it does.

## Loading

```java
menus.load(id, section);                 // reports bad parts to the console
menus.load(id, section, problems);       // reports them where you want
menus.register(definition);              // an already-compiled menu
menus.definition(id);                    // one back
menus.unload();                          // forget them all, for a reload
menus.sounds(UiSounds.DEFAULTS);         // what this plugin's menus sound like
```

### Refreshing bundled menus

At startup, refresh a directory of packaged defaults before loading its files:

```java
menus.refreshBundledDirectory(MyPlugin.class, "menus/admin");
```

The method discovers all regular files recursively from the plugin artifact and
atomically replaces only `plugins/MyPlugin/menus/admin`. Files removed from the
artifact disappear from that directory; other data-folder files remain untouched.
The path must be relative and cannot be blank or escape the plugin data folder.
Failure leaves the previous directory intact and logs a warning; success is quiet.

A part that will not compile — an action that does not exist, a mistyped
enchantment — becomes a dead button and a line in the console, and the other
fifty buttons still work. A file that does not describe a menu throws, because
guessing would hide the mistake.

## Opening

```java
menus.open(player, "kits");                                    // safe from any thread
menus.open(player, "leaderboard", Map.of("kit_name", name));   // with context
menus.openNow(player, definition, context);                    // returns the session
menus.back(player);                                            // where they came from
menus.close(player);
menus.session(player);                                         // the open one, if ours
```

`open` moves itself onto the thread that owns the player, so a caller coming
back from a database query does not have to. `openNow` cannot, because it
returns the session it opened — call it on the player's thread.

The **context** fills placeholders everywhere the menu draws: the title, every
fixed slot, every row. A menu titled `%kit_name%` needs no resolver of its own.

Context values are **parsed**, unlike row values. A context value describes the
whole screen and was written by whoever wrote the menu — usually in the same
file — so `"{success}&lNEW SHIELD"` out of a config arrives as a green button
rather than as those characters. Row values stay literal unless asked otherwise,
because a list is full of names players chose; see
[literal values](#literal-values-and-the-ones-that-are-not). A row naming the
same key as the context shadows it, and keeps its own rule.

### The page in the title

A title may say which page is being read, and the menu answers on its own:

```yaml
title: '{primary}&lMY SHIELDS {muted}%current_page%/%total_pages%'
```

`%current_page%` and `%total_pages%` are supplied by the list itself — as are
the shorter `%page%` and `%pages%`. No plugin passes them in, because the
section already knows how many rows it has and which page it is showing; the
list is the authority, so a context value of the same name never shadows them.

The title **follows the reader through the list**. Bukkit cannot do this — a
title is an argument to `createInventory`, read once when the window is built —
so the new title goes out as a packet, which the client accepts as a retitle of
the container it already has open. The slots stay put and nothing flickers.

It is sent only when the title names a page *and* the text actually changed:
retitling makes the client re-request the window's contents, which is far too
much for a title that reads the same. A menu filled after it opened is retitled
too, since that is when the total stops being one.

This needs **PacketEvents**. Without it the title stays on the page it opened
at — what every menu did before — and paging, drawing and clicking are
unaffected.

## What kind of window

```yaml
type: SIMPLE     # a chest; the default
size: 54         # only a chest is resizable
```

The old `type` described the container and whether it paginated at the same
time. `SIMPLE`, `PAGINATION`, `MULTI_PAGINATION`, `ITEM_INPUT` and `STATIC` are
all chests — whether a menu paginates is decided by whether it has a list, which
is the only thing that ever really decided it. Existing files keep working
unchanged.

Any other container works too, and brings its own fixed size:

```yaml
type: HOPPER     # five slots, whatever size says
```

`BARREL`, `HOPPER`, `DROPPER`, `DISPENSER`, `ANVIL`, `ENCHANTING`, `FURNACE`,
`BREWING`, `BEACON`, `CRAFTING`, `MERCHANT`, `SMITHING`, `GRINDSTONE`,
`CARTOGRAPHY`, `LOOM`, `STONECUTTER`. A barrel looks like a chest and is not: it
is always twenty-seven slots.

Sizes are the server's own numbers, checked against Bukkit in `UiKindTest`.
Getting one wrong is not cosmetic — creating an inventory whose size disagrees
with its type throws, so the menu never opens at all.

## Lists

A menu can have several paginated lists on one screen, each paging
independently. Configuration writes them two ways and both mean the same thing.

One list, which is how a hundred and fifty deployed files are written:

```yaml
pagination:
  slots: '10-16,19-25,28-34'
  item_template:
    material: "%kit_icon%"
    name: "{warning}&l%kit_name%"
    actions:
      - "practice:select %kit_id%"
  navigation:
    previous: { slot: 45, material: ARROW }
    next:     { slot: 53, material: ARROW }
```

An arrow under `navigation` **pages its own list** — the `actions` are implied,
because an arrow in a `navigation` block has no other job. Naming them anyway
works and is what most existing files do; an arrow that names something else
keeps what it named.

In a menu with several lists each arrow pages the section it is declared in, so
neither has to name it.

Several, which is how thirteen are:

```yaml
sections:
  players:
    slots: "1-7,10-16,19-25,28-34"
    player_template: { ... }
    navigation: { previous: { slot: 37 }, next: { slot: 43 } }
  stat_types:
    slots: "46-52"
    not_selected_template: { ... }
    selected_template:     { ... }
    navigation: { previous: { slot: 45 }, next: { slot: 53 } }
```

A `pagination` block becomes one section named `main`, so a menu with one list
never has to know section names exist.

### Filling one

```java
session.entries(kits.stream()
        .map(kit -> UiEntry.of(kit)
                .with("kit_name", kit.name())
                .with("kit_icon", kit.icon())
                .template(kit.equals(selected) ? "selected" : "not_selected")
                .build())
        .toList());
```

A row can also bring its own item, for lists no template could describe — a kit
room showing the stacks it has stored:

```java
session.entries("items", stored.stream()
        .map(stack -> UiEntry.of(stack).item(stack).build())
        .toList());
```

`UiEntry.of(kit)` is the part ExyliaCommons lacked. A handler that needs to know
which kit was clicked reads it back:

```java
actions.registerSync("select", (context, args) -> {
    Kit kit = (Kit) context.require(UiKeys.ENTRY);
    ...
});
```

Before, a handler worked the kit back out from the item it was drawn as, which
is why menus kept static maps keyed by player — and why two menus open at once
could hand the wrong answer to the wrong click.

Replacing the rows keeps the reader where they were, clamped to what still
exists: a leaderboard refreshing under somebody on page three leaves them on
page three.

### Literal values, and the ones that are not

`with()` inserts a value as **text**. A kit somebody named `{error}&lX` shows
those characters rather than repainting the row, which is what you want for
anything a player typed.

`withFormatted()` parses the value instead, for values a server owner wrote and
that say what they look like:

```java
UiEntry.of(player)
        .with("player_name", player.getName())          // data
        .withFormatted("rank", config.rankDisplay())    // "{highlight}&lMVP"
        .build();
```

Getting this backwards is a bug either way: a display name printing `{highlight}`
to the screen, or a player called `<rainbow>` recolouring a menu.

The question to ask is **whose value it is**, not what type it is: the server
owner wrote it, or a player typed it.

### A value that spans several lore lines

A value containing `<nl>` becomes several lore lines. `Lines.value` normalizes
real CRLF/LF/CR breaks and literal `\n` from configuration into that canonical
form. This is for descriptions that live in a config and are too long for one:

```java
.withFormatted("description", String.join("<nl>", effect.lore()))

// Straight from a config key that may be a String or a list:
.withFormatted("description", Lines.value(section, "description"))
```

```yaml
lore:
  - "{muted} ┃ {letters}%description%"     # one written line, two drawn
```

Each drawn line keeps whatever the template puts around the placeholder, so the
second line gets the same bullet as the first. Values beside it repeat on every
line rather than vanishing after the first, and only lines that actually mention
the multi-line value are stretched.

Supported separators written in the **file** are normalized and split when the
file is read, so they cost nothing at render time. In a **value**, the canonical
`<nl>` is split as the row is drawn — every expanded line comes from the same
template string, so they share one parse and the cost is a substitution per line,
never a parse per line. Reading your own config key into either shape is
[`Lines`](text.md#lines-written-for-several-lines).

An expanded value is still literal unless you asked for `withFormatted`:
expanding is not a second door into the parser.

**A colour on its own cannot be a value.** This does not work, and cannot:

```yaml
name: "%name_color%&l%kit_name%"    # no
```

Substitution happens on the parsed component tree — that is what lets a template
be parsed once and shared by every row drawn from it. A bare colour parses to an
empty component carrying a colour, and a colour on one node does not reach the
text beside it. Pass the whole coloured phrase, or say which state the row is in
and let the templates below decide how it looks.

### Templates by name

A row is not always drawn the same way. Any key ending in `template` is one,
named by what comes before it:

| Written | Named |
| --- | --- |
| `item_template` | the default |
| `selected_template` | `selected` |
| `no_permissions_template` | `no_permissions` |

There are a hundred and sixty-seven distinct names across the ecosystem, so they
are read by shape rather than from a list, and a plugin is free to invent
another. A name the file does not declare draws the ordinary row rather than
leaving an empty slot.

## Fillers

Three different jobs, not one list:

```yaml
filler:
  global:                      # everything left over
    material: BLACK_STAINED_GLASS_PANE
    hide_tooltip: true
  pagination:                  # a list's empty slots, when it is short
    material: LIGHT_GRAY_STAINED_GLASS_PANE
    name: "{muted}No kits available"
  custom:                      # named panels, each with its own slots
    header:
      material: GRAY_STAINED_GLASS_PANE
      slots: "0-8"
```

The `pagination` filler is worth being careful about: it is what a player sees
when a list has fewer rows than slots, and it usually **says something**.
Treating it as another background would tell somebody with an empty list
nothing at all.

Panels are drawn before the background, in file order, so the first one to claim
a slot keeps it where two overlap.

A page button that has nowhere to go is not drawn either, and its slot goes back
to whatever would otherwise cover it — the panel that claims it, or the global
filler, or nothing at all when the menu fills nothing. An arrow that is there
and does nothing is the same lie in every menu with a single page of rows, which
is most of them on a quiet server.

## Redrawing

```java
session.invalidate("stats");   // only slots that said they depend on stats
session.invalidateSlot(13);    // one slot
session.refresh();             // everything — rarely the right answer
```

A slot declares what it is derived from:

```yaml
elo:
  slot: 22
  material: DIAMOND
  name: "{letters}Rating: {highlight}%elo%"
  depends-on:
    - stats
```

Nothing else on screen is touched: no full rebuild, no flicker, and no packets
for slots that did not change.

A menu can also ask to redraw itself:

```yaml
refresh:
  mode: SMART      # DISABLED | FULL | SMART | ON_CLICK
  interval: 20     # ticks, for the timed modes
  click_delay: 4   # ticks after a click, for ON_CLICK and SMART
```

| Mode | When |
| --- | --- |
| `DISABLED` | only when a plugin asks — the default |
| `FULL` | everything, on the interval |
| `SMART` | on the interval, but only slots that can actually differ, and after a click |
| `ON_CLICK` | after a click, once `click_delay` has passed |

`SMART` is the one to reach for: a timer that redraws static decorations is
packets for an identical item. The timer only starts if the menu has something
that could change, and it dies with the player.

Deciding a slot's values costs nothing when the slot carries no row values,
which is what a fixed slot — a decoration, a button, a title bar — always is.
Measured by `SessionValuesBenchmark`, per drawn slot:

| Slot | ns | bytes |
| --- | --- | --- |
| fixed, no row values | 2.9 | 0 |
| list row, 2 values | 113 | 320 |
| list row, 1 formatted | 74 | 360 |

A row genuinely has to merge its own values over the menu's context, so it
allocates. A fixed slot does not, and no longer pretends to.

A click redraws **everything that can change**, not only the slot it landed on.
A button rarely changes just itself: adding a layer moves a counter, a preview
and a list, and none of those is the slot that was clicked. The redraw reads the
context as it is when it runs, so a plugin that redrew the menu itself in the
meantime is not undone.

## Animations

```yaml
animation: center_out
```

Or with a pace, in ticks between frames:

```yaml
animation:
  type: rows_alternate
  speed: 3
```

Seventeen shapes:

| Name | What it does |
| --- | --- |
| `center_out` | outwards from the middle |
| `explosion` | outwards in square rings |
| `corners` | inwards from all four corners |
| `cascade` | diagonally, top-left to bottom-right |
| `slide_left`, `wave_horizontal` | column by column, left to right |
| `slide_top`, `wave_vertical` | row by row, top to bottom |
| `rows_alternate` | rows from the top and bottom alternately |
| `columns_alternate` | columns from the left and right alternately |
| `checkerboard` | light squares, then dark |
| `snake` | left to right, then back again |
| `spiral` | round the outside, inwards |
| `spiral_out` | from the middle, outwards |
| `typewriter` | one slot at a time, in reading order |
| `random` | scattered, but the same scatter every time |
| `none` | appears at once |

Slots appear a frame at a time. Everything is drawn and recorded **before** the
animation starts, so a click landing on a slot that has not visibly appeared yet
still works — the alternative is a window during which buttons silently do
nothing. Clicking skips the rest of it, because somebody who is interacting has
stopped watching.

`random` is seeded by the menu's size rather than by the clock, so it looks the
same for everyone and can be cached like the rest.

A name that is not one of these is reported when the file is read, and the menu
appears at once. Reporting it matters: a misspelt name looks exactly like a menu
that was never animated, so silence would leave an admin re-reading a config
that was fine apart from one letter.

## Conditions

```yaml
join:
  slot: 10
  material: LIME_DYE
  condition: "%lfc_state% == none"
```

Operators: `==` `!=` `>` `<` `>=` `<=` `contains` `startsWith` `endsWith`, and a
bare value read as a boolean. A slot whose condition fails is not blank — it is
**not there**, so clicking it does nothing.

A condition that cannot be read hides the slot. Failing the other way would hand
a button to somebody who should not have it.

## Built-in actions

Registered for every plugin that asks for menus, because turning a page is
nobody's feature:

| Action | What it does |
| --- | --- |
| `next_page`, `previous_page` | move the only list, or the one named: `next_page players` |
| `back` | the menu they came from, at the page they left it |
| `close` | shut the window |
| `refresh` | redraw everything |

A plugin registering its own action by one of these names wins. Silently
replacing a plugin's handler would be worse than not having the convenience.

## Clicks

Every decision is made against the session, never against the item the client
says it clicked. A packet carries a slot number and a click type; the server
already knows what it drew there.

A button is never picked up, and a drag touching any button is refused. Slots
listed under `editable_slots` stay the player's, and `session.inputs()` returns
whatever they left in them.

### A grid of editable slots is an editor

`editable_slots` plus buttons around them is how a kit layout, a shop's stock or
an arena loadout is edited: the admin puts real items in real slots, and the
slots themselves are the record. Nothing is paginated and nothing is retyped, so
armour stays in the armour slots and the hotbar stays the hotbar.

```yaml
size: 54
editable_slots: '0-4,9-44'
```

The buttons around them write into the same slots, which is what makes it an
editor rather than a drop box:

```java
session.input(slot, null);                          // clear one
session.inputs(fromInventory(player));              // fill from what they carry
Map<Integer, ItemStack> layout = session.inputs();  // read it back to save
```

Both writers refuse a slot that is not an input slot, and `inputs(map)` checks
every slot before writing any of them — a half-applied layout is one the player
cannot tell apart from the one they asked for.

Click kinds: `left`, `right`, `middle`, `shift_left`, `shift_right`, `drop`,
`control_drop`, `swap`, `double`, `number_key`, and `any` for a line with no
prefix.

```yaml
actions:
  - "left: practice:adjust_priority 1"
  - "right: practice:adjust_priority -1"
  - "left,right: practice:open_details"
```

## Sounds

```yaml
open_sounds:
  - "ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2"
click_sounds:
  - "UI_BUTTON_CLICK|1.0|1.5"
```

Or, more tidily, a block naming each one — `open`, `close`, `click`, `denied`,
`failed`, `back`, `page`:

```yaml
sounds:
  open: "BLOCK_BARREL_OPEN|0.6|1.4"
  denied: ""        # silence, which is not the same as absent
```

Both spellings work and the block wins where a file has both. `denied` and
`failed` play when a button refuses, so a click that did nothing sounds
different from one that worked — the single most common "the menu is broken"
report.

## Lifecycle

Nothing outlives its screen. An action sequence with a delayed step, started by
a button, is cancelled when the menu closes; disabling a plugin closes its
windows before releasing its tasks, because a button whose actions come from a
dying classloader must not answer another click.

Menus are found through the window's holder rather than a map keyed by player,
so a player opening a chest on top of a menu is not mistaken for one of ours.

## Where the code is

| | |
| --- | --- |
| Public API | `ui/Menus`, `PluginMenus`, `UiSession`, `UiDefinition`, `UiSection`, `UiEntry`, `UiItem`, `UiKeys`, `UiFillers`, `UiRefresh`, `UiSounds`, `UiAnimationSpec`, `ClickBindings`, `ClickKind`, `ClickPolicy`, `Pages`, `Slots` |
| Internal | `ui/internal/MenuLoader`, `MenuRuntime`, `MenuListener`, `Session`, `MenuHolder`, `Rendered`, `Conditions`, `OpenAnimation`, `BuiltInActions` |
| Tests | `src/test/java/net/exylia/lib/ui/` |

`RealMenusTest` loads the sixty menu files ExyliaPracticeCore ships, unedited.
