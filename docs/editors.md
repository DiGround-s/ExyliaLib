# Editors

Since 1.56.0.

One paginated screen for editing a list of anything, the pickers that go with
it, and the editors for the types the library already owns. Lives in
`net.exylia.lib.util.editor`.

```java
Rewards.of(this).editor(zone.rewards())
        .title("{primary}&lPOWER-UP REWARDS")
        .onSave(edited -> manager.save(zone, edited))
        .onCancel(() -> setupMenu.open(player))
        .open(player);
```

## What comes in the box

A generic engine nobody has a use for is a generic engine nobody uses, so the
library ships the editors as well as the machine.

| Call | Edits |
| --- | --- |
| `Rewards.of(plugin).editor(rewards)` | [rewards](rewards.md) |
| `Loot.editor(plugin, entries)` | [loot tables](loot.md) |
| `NamedCommands.editor(plugin, commands)` | named console commands |
| `Effects.editor(plugin, effects)` | potion effects |
| `Sequences.of(plugin).editor(effects)` | effects with odds, conditions and an audience |
| `Editors.of(plugin).items(items)` | real items — kits, shop stock |
| `Editors.of(plugin).loadout(items)` | a whole loadout, in an inventory-shaped grid |
| `Editors.of(plugin).locations(places)` | spawn points, arena corners |
| `Editors.of(plugin).list(descriptor, type, entries)` | the sixth thing, the one only your plugin has |

The entry point lives with the type, not with the engine: `PluginRewards` knows
about editors, and the editor knows nothing about rewards. `EditorIsGenericTest`
reads the compiled bytecode and fails if it ever does — that is what keeps
adding a seventh editor from meaning touching the engine.

## The loadout editor

Since 1.110.0.

The one editor that is not a list. A kit, an arena's gear, a class's starting
items: the viewer sees their own inventory and puts real items in real slots.

```java
Editors.of(this).loadout(kit.items())
        .title("{primary}&lKIT ITEMS {letters_black}» {highlight}" + kit.id())
        .onSave(items -> kits.save(kit.withItems(items)))
        .onCancel(() -> KitMenu.open(player))
        .open(player);
```

| Slot | What it is |
| --- | --- |
| `0-4` | helmet, chestplate, leggings, boots, offhand |
| `9-35` | the twenty-seven storage slots |
| `36-44` | the nine hotbar slots |
| `45` `46` `49` `53` | save, import my inventory, cancel, clear |

`onSave` is told a `List<ItemStack>` in the order [`Loadout`](#the-loadout-layout)
defines, with the empty tail dropped: an admin who clears the last row means the
loadout is shorter, not that it ends in nulls.

**Closing saves.** The other editors here treat a close as walking away, because
their working copy is a list nobody lost anything by dropping. Here the viewer
put items into a window and those items left their inventory to get there, so an
accidental Escape would destroy work with nothing to show for it. Cancel is a
button, and it says what it does. The one ending that does not save is the owning
plugin disabling — writing through a plugin on its way down is worse than losing
a layout.

Clicks go through the menu module's own `ClickPolicy`, so the shift-click and
double-click cases that duplicate items out of a window are refused here exactly
as they are in a menu.

### The loadout layout

`Loadout` is where "what does position five mean" is answered, once:

```
0..3    helmet, chestplate, leggings, boots
4       offhand
5..31   the twenty-seven storage slots
32..40  the nine hotbar slots
```

`partOf`, `offsetIn`, `storage(n)`, `hotbar(n)` and `at(items, index)` read it;
`capture(player)` writes what somebody is wearing and carrying into it, and
`trim` drops the empty tail. It is deliberately not Bukkit's `getContents()`
order, which starts at the hotbar and ends with the armour upside down — a
loadout is read by people far more often than it is handed to `setContents`.

This is the mapping ExyliaSurvivalCore had written out three times, and the three
did not agree: the preview drew the twenty-eighth item under a pane labelled
"hotbar" and the sixth in the middle of the armour row.

## What the viewer gets

Pagination, add, edit, delete, copy, paste, save and cancel, on every list.

| Gesture | What happens |
| --- | --- |
| Left click a row | edit it |
| Right click a row | delete it |
| **Shift + left** a row | copy it |
| A button in the bottom band | whatever the plugin made it do |
| `ADD` | create a row and configure it — or several at once, where the type says so |
| `PASTE` | add whatever is on the clipboard |
| `COPY ALL` | put the whole list on the clipboard |
| `SAVE` | keep everything |
| `CANCEL` | keep nothing |

A row that is not finished — a command entry with no command — is drawn with a
mark rather than hidden or refused. An editor is where a half-configured row
gets finished, and one that vanished would take its place in the list with it.

## Nothing is written until save

The list handed in is copied. Every change goes into the copy, so cancel is free
and an editor opened just to look writes nothing at all.

An editor ends five ways — save, cancel, closing the window, leaving the server,
the owning plugin being disabled — and the first one there wins. Four of them
are cancel: a screen taken away was never confirmed, and writing a working copy
nobody approved is worse than losing it.

## The clipboard

```java
Clipboard.copy(player, "exylia:loot", entries);
List<LootEntry> pending = Clipboard.take(player, "exylia:loot", LootEntry.class);
```

The clipboard belongs to the **player**, not to a screen, so a loot table copied
out of one chest pastes into the next twelve — and into a spawner, and into an
event's pool, because they are the same rows in the same format.

| Method | Contract |
| --- | --- |
| `copy(player, typeKey, elements)` | replaces the bucket; an empty list clears it |
| `take(player, typeKey, type)` | reads it **without emptying it** |
| `size` / `has` / `clear` | what is waiting, and how to drop it |
| `forget(playerId)` | called when they leave; consumers do not call it |

One bucket holding however many elements were copied, not ExyliaCommons' two
with four buttons between them. Pasting does not consume: an admin pasting the
same table onto twelve chests presses paste twelve times.

Which bucket comes from `EditorDescriptor.typeKey()`. Two editors sharing a key
can paste into each other, which is the point; two that do not, cannot, which is
also the point. Elements are type-checked one at a time on the way out, so a key
somebody reused for their own type answers with nothing rather than a class cast.

## Editing a row

One dialog with every field of the row already filled in.

```java
return EditorForm.of(plugin, viewer, "{primary}&lEDIT ENTRY")
        .text(NAME, "Display name", entry.name(), 3)      // three lines tall
        .integer(WEIGHT, "Weight", entry.weight())
        .text(COMMAND, "Command", entry.command(), 3)
        .hint("%player_name% is the player. No leading slash.")
        .ask(values -> entry.toBuilder()
                .name(values.getText(NAME))
                .weight(values.getLong(WEIGHT))
                .build());
```

Where ExyliaCommons drew a menu with an icon per field and asked one question
per click, this is one trip. Every field takes the value it is editing, which is
the difference between correcting a display name and retyping thirty characters
of colour tokens from memory.

The height argument is why the tall box exists: a one-line dialog field shows
about twenty characters, and a display name is a dozen colour tokens around six
words. See [input.md](input.md) — `TextInput.lines` and `FormField.lines` are
the same setting, and chat, which has no notion of height, ignores it.

`hint` (1.60.0) attaches a note to the field just added, and answers what the
label leaves open — `Command` does not say whether the player is `%player%` or
`%player_name%`, and a wrong guess is found later, in a row that silently does
nothing. A Bedrock form draws it as a placeholder, a dialog as a muted line
under the label, chat as its own line.

A client too old for dialogs, or a Bedrock player, is asked the same fields
through whichever transport can. The editor never knows which one answered.

## Pickers

```java
editors.pick().particle(player).thenAccept(name -> name.ifPresent(entry::setParticle));
```

| Method | Answers with |
| --- | --- |
| `particle`, `sound`, `enchantment`, `potionEffect`, `material` | the name, uppercase |
| `colour` | a vanilla colour name, or a `#rrggbb` the viewer typed |

Each is a `SearchInput` underneath, so paging, filtering and every transport
were already solved. The lists are read from the **registry** rather than from
an enum's `values()`: several of these stopped being enums, and a data pack can
add to any of them.

The answer is a name rather than an object because a name is what goes in a
config column, and a caller that wants the object looks it up once.

## The icon picker

It is not here: an icon is a question, and questions live in the input module.

```java
Inputs.of(this).icon(player, "{primary}&lARENA ICON")
      .open(icon -> arenas.save(arena.withIcon(icon)));
```

Its three ways — the searchable material list, a one-slot **insert** window, and
a pasted head — are documented in [input.md](input.md). The reward and loot
editors call it when a row needs an item, and `items(...)` uses the same window
to add and to replace a row, because an item is not something you type.

## Buttons of your own

```java
Loot.editor(this, table.entries())
    .button(EditorButton.preset(() -> Loot.parseAll(config.defaultPool())))
    .onSave(store::save)
    .open(player);
```

The way to extend an editor without forking it: a recommended preset, a bulk
import, a jump to a related screen.

```java
EditorButton.<LootEntry>of("CHEST_MINECART")
        .name("{highlight}&lLOAD DEFAULTS")
        .lore(" {letters_black}▎ {letters}Replaces this table with the preset.")
        .glowing()
        .onClick(view -> {
            view.replaceAll(Loot.parseAll(preset));
            Text.from(this, "{success}Preset loaded").send(view.viewer());
        })
        .build();
```

| On the view | |
| --- | --- |
| `viewer()` | who clicked |
| `entries()` | the list as it stands, unsaved edits included; unmodifiable |
| `replaceAll(list)` | what the list should hold now |
| `ask(question)` | ask something, then bring the editor back |

`replaceAll` is the only mutator, because it is the only one a button has ever
needed: appending is `replaceAll` over the current list plus the new rows, and
it reads as what it is. The page is clamped afterwards, so a button that
shortens the list does not leave the viewer on a page that is gone. The screen
redraws by itself; a handler changes the list and stops.

**Nothing a button does is persisted.** It changes the working copy, so even one
that replaces forty rows is undone by cancel — which is what makes a destructive
button safe to offer at all.

### A button that asks something

```java
.onClick(view -> view.ask(() -> EditorForm.of(this, view.viewer(), "{primary}&lSETTINGS")
        .decimal(CHANCE, "Chance out of 100", BigDecimal.valueOf(settings.chance()))
        .ask(values -> values.getDecimal(CHANCE).doubleValue())
        .thenAccept(chance -> chance.ifPresent(settings::chance))))
```

A dialog, an anvil and a search all need the screen, so a button cannot simply
open one: the close would read as the viewer walking away and the editor would
throw its working copy out. `ask` is the same door `EditorDescriptor.edit` goes
through — the window comes down for the question and back up on the page it was
on, with the answer already applied.

The stage is waited on, never read: what the answer *means* is the caller's
business. A button that changes the list does it in the stage's own callback
through `replaceAll`; one that changes something else — the gating around the
list, a setting — writes it wherever it keeps it. A question that fails is
logged and the editor still comes back, because a screen that never reopens is
worse than an answer that was lost.

### Where they go

The editor decides; **a caller never names a slot**. A screen with buttons gives
up its bottom row of entries to hold them, so a page shows 36 rows instead of
45, and they sit in the order they were added. A screen with none keeps all 45.

Slots were the one thing ExyliaCommons made callers write, and it is how a
button ends up on top of the save button on a screen somebody later changed. Nine
buttons fit; a tenth is refused when the editor is built rather than silently not
drawn, because a button an admin was promised and cannot find is worse than an
exception in the log.

`EditorButton.preset(supplier)` is the one every editor eventually grows, worded
and drawn the way commons drew it. The supplier is asked when the button is
pressed rather than when the editor opens, so a config reloaded in between is the
one that answers.

## Writing an editor for your own type

One interface. No screen, session, holder or clipboard.

```java
public interface EditorDescriptor<T> {
    String label(T entry);                  // the row's name
    String icon(T entry);                   // material, head string or bytes: snapshot
    List<String> lore(T entry);             // the detail lines
    T create();                             // a blank element
    T copy(T entry);                        // a duplicate, under a new identity
    CompletionStage<Optional<T>> edit(Player viewer, T entry);

    default CompletionStage<Optional<T>> create(Player viewer);  // when creating is a question
    default CompletionStage<List<T>> createAll(Player viewer);   // when one press makes several
    default String typeKey();                                    // the clipboard bucket
    default boolean isComplete(T entry);                         // whether to mark the row
}
```

Everything except `edit`, `create(viewer)` and `createAll(viewer)` is called
while drawing a page, up to 45 rows at a time and again after every click, so it
must be cheap and pure. Nothing may throw: a row nobody can describe is still drawn, as itself, so an
admin can delete it.

Override `create(viewer)` where creating the thing **is** a question. A reward
has to be told whether it gives an item, a command or money before a form over
it can even name its fields; a warp does not.

Override `createAll(viewer)` (1.77.0) where one press of add can honestly
produce **several** rows — the loot editor's "everything in a chest" is the
case it exists for. The default wraps `create(viewer)`, so a descriptor that
does not override it behaves exactly as before. Exactly one element still goes
through `edit`; more than one does not, because a form per row is not what
importing thirty items asked for.

`copy` must produce a new identity. An implementation that returns the element
unchanged makes two rows that are the same object, and deleting one deletes both.

## Rows are addressed by what they carry

Never by slot, page or index. Four of ExyliaCommons' five editors resolved a row
from its slot number, so an edit that landed after the list had changed
underneath — a paste, a delete, a second screen — edited a different row.
`EditorHolderTest` keeps that out with two equal elements and an edit that must
land on exactly one of them.

## Threads

| What | Where |
| --- | --- |
| `open` | any thread — it relocates itself onto the thread that owns the viewer |
| `onSave`, `onCancel` | the viewer's thread |
| descriptor `label`/`icon`/`lore` | the viewer's thread, while drawing |
| `Clipboard` | any thread |

Everything goes through `net.exylia.lib.task`, so it behaves identically on
Spigot, Paper and Folia. The owning plugin's scheduler runs the screens while it
is alive, and ExyliaLib's runs the closing of them when it is not — a disabled
plugin cannot schedule anything, including its own cleanup.

## State lives on the window

Never in a `Map<UUID, Session>`. A player with a chest open on top of an editor
is looking at the chest; a map would still say "this player has an editor", so a
click in the chest would be handed to it. The holder knows what a window is; the
player does not.

## What is not here

Nothing, now. The effect editor that ExyliaCommons had is
`PluginSequences.editor` — see [sequences.md](sequences.md), where an effect is
its gating plus a sequence rather than a forty-field bean over eight types.

## Where the code lives

| Part | Where |
| --- | --- |
| Public API | `util/editor/Editors`, `PluginEditors`, `ListEditor`, `EditorDescriptor`, `EditorForm`, `EditorButton`, `EditorView`, `Clipboard`, `Pickers` |
| Loadout editor | `util/editor/Loadout`, `LoadoutEditor`, `internal/LoadoutHolder` |
| Shipped descriptors | `util/reward/RewardDescriptor`, `util/loot/LootDescriptor`, `util/command/NamedCommandDescriptor`, `util/sequence/EffectDescriptor`, `util/PotionEffectDescriptor`, `util/editor/ItemListEditor`, `LocationDescriptor` |
| Internal | `util/editor/internal/` — `EditorRuntime`, `EditorHolder`, `EditorListener`, `Icons` |
| Tests | `src/test/java/net/exylia/lib/util/editor/` |

## Reload

The module keeps nothing derived from the palette. Buttons are built when a page
is drawn, from raw text such as `{primary}&lSAVE`, and a page is redrawn after
every click — so there is no `invalidateAll()` and no hook in
`ExyliaLib.loadPalette`. See [reload.md](reload.md).
