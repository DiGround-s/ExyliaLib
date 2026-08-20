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

A paginated title is filled in too: `%current_page%`, `%page%`, `%total_pages%`
and `%pages%` all resolve to `1`, which is the page a menu opens on. A window's
title is fixed when the window is created, so it does not follow the reader
through the list — changing it would mean closing and reopening the screen on
every click. What it must never do is show the player the placeholder's own
name.

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
    previous: { slot: 45, material: ARROW, actions: ['previous_page'] }
    next:     { slot: 53, material: ARROW, actions: ['next_page'] }
```

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

A value containing `<nl>` becomes several lore lines. This is for descriptions
that live in a config and are too long for one:

```java
.withFormatted("description", String.join("<nl>", effect.lore()))
```

```yaml
lore:
  - "{muted} ┃ {letters}%description%"     # one written line, two drawn
```

Each drawn line keeps whatever the template puts around the placeholder, so the
second line gets the same bullet as the first. Values beside it repeat on every
line rather than vanishing after the first, and only lines that actually mention
the multi-line value are stretched.

`<nl>` written in the **file** is split when the file is read, so it costs
nothing at render time. In a **value** it is split as the row is drawn — every
expanded line comes from the same template string, so they share one parse and
the cost is a substitution per line, never a parse per line.

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
| `SMART` | on the interval, but only slots that can actually differ, and the clicked slot after a click |
| `ON_CLICK` | only the slot that was clicked, after `click_delay` |

`SMART` is the one to reach for: a timer that redraws static decorations is
packets for an identical item. The timer only starts if the menu has something
that could change, and it dies with the player.

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
