# Items

Items described in configuration, read once and drawn per player.

A menu icon, a special item, a kit entry, a lobby hotbar slot and a shield are
the same block of YAML. They were being parsed by five copies of the same code;
this is the one copy.

```java
PluginItems items = Items.of(this);

// when the file loads, once
Item icon = items.parse(section);

// when somebody looks at it
ItemStack stack = items.render(icon, player);
```

## The split that matters

`Item` is a definition, not an `ItemStack`. It holds its placeholders
unresolved, is shared by every player who sees it, and can be compared, cached
and tested without a running server. Turning one into an item is per-viewer work
and happens at render time.

That is the whole point of the module. Reading a file is expensive and happens
once; building an item is cheap and happens constantly. ExyliaCommons did both
together on every render.

## Entry points

| Call | What it does |
| --- | --- |
| `Items.of(plugin)` | the reader belonging to a plugin |
| `Items.parse(section)` | read an item with no owner |
| `Items.parse(section, problems)` | the same, reporting bad parts |
| `Items.banner(base64)` | decode a saved banner design |
| `Items.encode(banner)` | encode one |

On a `PluginItems`:

| Call | What it does |
| --- | --- |
| `parse(section)` | read a definition, reporting problems to the console |
| `parse(section, problems)` | read one, reporting problems where you want them |
| `render(item, viewer)` | build the `ItemStack` |
| `render(item, viewer, problems)` | the same, reporting where you want |
| `render(item, viewer, values)` | the same, with extra placeholder values |
| `render(item, viewer, values, formatted)` | the same, naming the values that carry formatting |
| `build(section, viewer)` | read and build in one call |
| `plugin()` | the plugin these belong to |

`viewer` may be `null`, which means nobody in particular: placeholders are left
visible rather than lost.

A value is inserted as text unless its name is listed in `formatted`. That is
the safe default — a player called `{error}X` shows those characters instead of
recolouring the line — and the other door is for values a server owner wrote,
such as a rank display name written `{highlight}&lMVP`. A colour with no text
of its own cannot travel as a value at all; see the note in
[menus.md](menus.md).

### Why it is per plugin

Values written with `nbt` are stored under the owning plugin's namespace, so two
plugins can both write `id` onto an item without colliding. ExyliaCommons held
one static plugin reference for this, which in a shared library would file every
value under ExyliaLib's own name.

`Items.parse` without a plugin is fine for definitions that carry no stored
values, which is nearly all of them. One that does keeps them in the definition
and drops them when rendered, because there is no namespace to file them under.

## What a file can say

Everything ExyliaCommons accepted, spelled the same way. Where two spellings
exist, either works.

### The object

`material` decides what the item is, and carries more than a material name:

| Written as | Means |
| --- | --- |
| `DIAMOND_SWORD` | a material |
| `%kit_icon%` | a material the viewer decides |
| `basehead-<base64>`, `headbase-<base64>` | a head, by texture |
| `urlhead-<url>`, `headurl-<url>` | a head, by skin URL |
| `playerhead-Notch` | a head, by player name |
| `playerhead-%player_name%` | a head, whose owner depends on the row |
| `bytes:<base64>` | a serialised item |

Both `-` and `:` separate a prefix from its payload, and the prefix is matched
case-insensitively. Heads go through [`Skulls`](skulls.md): a texture or a URL
never touches the network, and a player head that has not been fetched comes
back plain rather than blocking.

**A placeholder is read again once it resolves.** `material: "%arena_icon%"` is
a material when the file is read, because that is all it can be before anybody
fills it in — but what a row hands back is very often a head, since that is what
an icon picker stores. So the resolved text goes through the same table above
rather than straight to the registry, and `%arena_icon%` holding
`headbase-eyJ0…` draws the head. Only values that carried a placeholder are read
twice; a literal material was decided when the file was loaded.

### Storing an item somebody is holding

The other direction, for an icon picker: `Source.of(ItemStack)` returns what to
store, and what it returns is always something the table above can read back.

```java
String icon = Source.of(player.getInventory().getItemInMainHand()).raw();
```

A plain item is stored as its material name — `STONE`, not four hundred
characters of base64 — so the common case stays short enough for a column and
legible enough to edit by hand. Anything carrying meta, which is to say a
textured head or a custom model, is stored whole as `bytes:`, because that is
the only spelling that keeps it. An empty hand is `AIR`.

### Text

| Key | Meaning |
| --- | --- |
| `name` | the name painted on the item |
| `display-name` | the name in plain form, for messages and logs |
| `lore` | the tooltip lines; `<nl>`, real CRLF/LF/CR, and literal `\n` split one entry into several, in the file or in a substituted value ([`Lines`](text.md#lines-written-for-several-lines) reads the same shape from your own keys) |
| `amount` | the stack size, as a number or a placeholder |

`name` and `display-name` are **separate**, not a fallback pair. `name` is what
is painted on the item — bold, gradient-filled, with a counter in it — and
`display-name` is what a plugin quotes back to a player. `Item.label()` returns
the second, falling back to the first.

Everything goes through [`Text`](text.md), so palette tokens, MiniMessage and
legacy codes all work, and italics are off unless asked for.

### Appearance

| Key | Meaning |
| --- | --- |
| `glow`, `glowing` | the enchantment shimmer, with no line in the tooltip |
| `hide-tooltip`, `hide_tooltip` | hides the tooltip entirely |
| `hide-attributes`, `hide_attributes` | hides everything vanilla writes by itself |
| `unbreakable` | marks it unbreakable |
| `custom-model-data`, `custom_model_data` | the model number |
| `max_stack_size`, `max-stack-size` | the stack limit |
| `flags`, `item-flags` | `ItemFlag` names to hide |
| `item_model`, `item-model` | an item model key, `namespace:key` |
| `tooltip_style`, `tooltip-style` | a tooltip style key |
| `enchantments` | a section of `NAME: level`, or a list of `NAME:level` |

`hide-attributes` covers the whole of what the client writes on its own: the
damage and speed lines on a tool, and the block a smithing template, a potion, a
firework or a banner adds to describe itself. A menu asking for a clean tooltip
means all of it — a smithing template in a list otherwise still says *"Applies
to: Armor"*.

That needs both an item flag and a data component, and the flag alone is not
enough: an `ItemFlag` is only persisted alongside the data it hides, and a
smithing template holds none — its block comes from the item *type*. So the
flag was set, and the tooltip stayed. The component is defined against the type,
which is what reaches it.

The component is looked up by name, because the versions this library runs on do
not agree on it: it is `hide_additional_tooltip` on 1.21.4, and gone by 1.21.11
where `tooltip_display` replaced it. Both are handled. On a server that knows
neither, the flags are the whole of what happens — the block may stay visible,
the menu opens either way, and it is said once rather than once per item. A
tooltip is not worth a screen that fails to draw.

It leaves enchantments alone. ExyliaCommons applied every flag here, so an item
hiding its attributes also lost the enchantment lines it meant to show; hiding
those stays something a file asks for, through `flags`.

### Traits

Only some materials have these, and fewer than twenty configured items across
the ecosystem use any of them. They are kept in a separate `Traits` record so
the other few thousand items carry one shared reference instead of six null
fields each.

```yaml
potion:
  base_type: HEALING
  upgraded: true            # STRONG_HEALING
  color: "#ff4d4d"
  custom_effects:
    - type: SPEED
      amplifier: "%level%"  # resolved per viewer
      duration: 600

armor_trim:
  pattern: "%helmet_trim_pattern%"
  material: "%helmet_trim_material%"

banner_patterns:
  base_color: WHITE
  patterns:
    - pattern: STRIPE_BOTTOM
      color: LIGHT_GRAY

banner_design: "<base64>"   # the same thing, as an editor saves it
banner_design: "%shield_preview%"   # or a design computed per viewer

force-consumable: true
consumable-time: 1.0
consumable-nutrition: 6
consumable-saturation: 14.4
consumable-sound: ITEM_HONEY_BOTTLE_DRINK

attributes:
  - "attack_damage|8"
  - "movement_speed|0.05"

nbt:
  kind: special
  uses: 3
```

A trait that does not fit its material does nothing. Setting a potion on a sword
is a leftover key in a config file, not a reason to fail while drawing a menu.

### A banner design computed per viewer

`banner_design` also takes a placeholder, for a row whose design is not known
until there is somebody looking at it:

```yaml
material: SHIELD
banner_design: "%pattern_preview%"
```

```java
session.entries("patterns", patterns.stream()
        .map(id -> UiEntry.of(id)
                .with("pattern_preview", Items.encode(previewOf(design, id)))
                .build())
        .toList());
```

This is a different thing from a placeholder *inside* a design, which
`banner_patterns` already allowed. There the shape is known when the file is
read and only the values arrive later; here the whole design arrives at once,
base colour and an unknown number of layers together, so there is nothing to
read at load time at all. A shield editor previewing *"your current design, plus
the layer this row would add"* needs the second, and no arrangement of the first
expresses it.

An item whose design is a placeholder is **dynamic**: it is rendered per viewer
rather than once and shared. Without that a menu of twenty different shields
would draw whichever one was built first, twenty times.

A placeholder nothing resolves, or a value that is not a design, draws no banner
and is reported. A row that quietly loses its picture is a bug somebody has to
notice; a reported one is a bug somebody can find.

## Problems are reported, not swallowed

An item is many independent pieces, and one bad enchantment should not cost the
other twenty. A part that cannot be read is skipped and named:

```java
Item icon = items.parse(section, (where, problem) ->
        getLogger().warning(where + ": " + problem));
```

`PluginItems.parse` reports to the console by default. `Items.parse` without a
handler ignores them.

Commons agreed with the first half and skipped the second: a mistyped
enchantment simply did not appear, so a broken item looked exactly like a
working one until somebody counted the levels.

## What it costs

An item that cannot look different for different players is rendered once and
copied thereafter, which is what makes a menu full of decorations free to
redraw. Anything with a placeholder, a head template or a placeholder-driven
trim is built per viewer, because it has to be.

Items carrying `nbt` are never cached: those values go under the owning plugin's
namespace, so two plugins sharing a definition are two different items.

The cache is bounded and expiring, and dropped whenever the palette changes —
its entries hold text already parsed, so what they say stays right and what
colour they say it in does not. See [reloading](reload.md).

Nothing here touches the network, and nothing blocks. Rendering must happen on
the thread that owns the viewer, like anything else that builds an inventory;
parsing is pure and safe anywhere.

## Four bugs that are fixed rather than reproduced

Migrating a plugin to this module changes four behaviours. All four are cases
where the file said one thing and the server did another.

**`flags` was parsed nowhere.** Fifteen files in ExyliaSpecialsV3 have asked to
hide their enchantment lines for years and been showing them.

**`hide-attributes` could not be turned off.** Commons wrote
`getBoolean(a, true) || getBoolean(b, true)`, whose value is `true` whatever the
file says. Writing `false` now turns it off — which also means a file that never
wrote the key at all gets the vanilla tooltip, where commons silently hid it.

**`upgraded` was dropped.** `PotionConfig` stored it and `PotionProcessor` never
read it, so a refill kit configured for Instant Health II handed out Instant
Health I. `HEALING` with `upgraded: true` is now `STRONG_HEALING`.

**`display-name` was treated as a fallback for `name`.** A plugin quoting an
item back to a player quoted its bold, gradient-filled tooltip name.

## Where the code is

| | |
| --- | --- |
| Public API | `item/Items`, `PluginItems`, `Item`, `Source`, `Appearance`, `Traits`, `Potion`, `Trim`, `Banner`, `Consumable`, `Modifier`, `Problems` |
| Internal | `item/internal/ItemReader`, `ItemRenderer`, `TraitApplier`, `Registries`, `BannerCodec`, `ItemCache` |
| Tests | `src/test/java/net/exylia/lib/item/` |

`RealItemsTest` loads the seventy-seven item files ExyliaSpecialsV3 and
ExyliaShields ship, unedited. A parser that handles the examples somebody
thought to write down is not the same as one that handles what is deployed.
