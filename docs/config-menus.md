# Config menus

> **A plugin's own settings screens come first** (since 1.555.0). The generic
> tree below draws every key of every file, in file order, and that is not a
> screen an admin can work with. A plugin's admin hub carries settings pages it
> designs itself: only the settings an admin really tunes, grouped by what they
> are for, each card explaining what the value does, built with
> [`ConfigEdits`](#your-own-settings-screens). The generic tree stays behind
> `/exylialib config` as a power tool; `ConfigMenus.of(...)` from a plugin's hub
> is deprecated. Reference: ExyliaMarket's `/marketadmin` → Settings.

## Your own settings screens

`net.exylia.lib.config.ConfigEdits` is the plumbing; the plugin writes the
screen (an ordinary admin menu, cards per *Item cards*) and decides what is on it.

```java
ConfigEdits fees = ConfigEdits.of(plugin, AuctionHouseDefaults::file);

// open: every editable key as a live value, %ah_settings_listing_fee_percent%
menus.open(admin, "menus/admin/settings_ah", fees.placeholders("ah_"));

// one action for every card: exyliamarket:setting ah settings.listing-fee-percent
actions.registerSync("setting", "myplugin.admin.settings", (ctx, args) -> {
    fees.card(ctx.player(), args.string(1, ""), ctx.get(UiKeys.CLICK).orElse(null), () -> reopen(ctx.player()));
    return ActionResult.success();
});
```

A card, the way every settings card reads (since 1.562.0):

```yaml
  listing-fee:
    slot: 10
    material: PAPER
    name: "{letters}Auction house {letters_black}▸ <gradient:{primary}:{secondary_light}>Listing fee</gradient>"
    lore:
      - "{letters_black}Auction house setting"
      - ""
      - "{primary}✎ Information"
      - " {letters}Charged to the seller when an"
      - " {letters}item goes up, as a share of its"
      - " {letters}price. Never refunded."
      - ""
      - "{primary}✦ Status"
      - "  {letters_black}· {info}{icon:gold} {letters}Fee{letters_black}: %ah_settings_listing_fee_percent%"
      - "  {letters_black}· {info}{icon:book} {letters}Default{letters_black}: %ah_settings_listing_fee_percent_default%"
      - ""
      - "%ah_settings_listing_fee_percent_click%"
    actions:
      - "exyliamarket:setting ah settings.listing-fee-percent"
```

The plugin writes what only it can say (the name, what the value does); the
library writes the rest in the server's language: the value, its default and
the click lines.

| Call | Does |
| --- | --- |
| `ConfigEdits.of(plugin, file)` | the edits of one `Configs.define` file; `file` is read on every call, so a reload is followed |
| `value(key)` / `live(key)` | a key's value as the screens show it, coloured (`{success}Enabled`, `{info}48h`, money in its currency, percent); `live` is a lambda for a menu context |
| `defaultValue(key)` (1.562.0) | the value the record's no-argument constructor gives, shown the same way |
| `hint(key)` (1.562.0) | the card's click lines, translated, several lines in one value: the click and what follows it (`Type an amount, such as 10k.`), plus `Shift-right-click · reset` while the value is not its default |
| `placeholders(prefix)` | every editable key as live values: `prefix` + key with `.` and `-` as `_`, and the same name with `_default` and `_click` |
| `placeholders(prefix, keys)` | the same for only these keys (1.573.0): a hub showing two values of each page passes just those, since every live value is read on each redraw |
| `card(viewer, key, click, back)` (1.562.0) | the click every card has: shift-right-click resets, a number or amount is typed prefilled (`10k`, `2.5M`, `-1` read as written), a switch flips, a choice steps (right-click back), a length of time, text, an item, a currency or a list of items is asked for |
| `reset(viewer, key)` (1.562.0) | puts a key back to its default |
| `tone(Lore.Tone)` (1.562.0) | the tone the click lines are drawn in, primary by default |
| `click(viewer, key, click, back)` | the generic tree's click: a number steps (shift ×10) or, on `DROP`, is typed |

Every change is checked against `@Menu.Range`, `AtLeast` and `AtMost`, written
with `ConfigFile.set` (comments and order kept) and followed by the plugin's
`Reloads`. Stepped values redraw the open screen; `back` reopens it after a prompt.

### Money, currencies and items (1.562.0)

- `@Menu.Format(value = MONEY, currency = "currency")`: the amount is shown in the
  currency the sibling key `currency` names (dotted keys from the root work too);
  empty or no `currency`, the default one. A bazaar priced in gems reads in gems.
- `@Menu.Currency` on a text key: a click picks from the server's currencies
  (`Economy.currencies()`), the first answer being the default currency; the
  value shows the currency's name.
- `@Menu.Material` on a `List<String>`: a click asks *Add an item* (the icon
  picker), *Remove an item* (the list, each with its icon) or *Edit as text*.

What goes on the screen, and what stays in the file:

- **In game:** what an admin changes while running the server: fees, taxes,
  limits, prices, durations, switches, blocked items, currencies, module switches.
- **File only:** database and network, intervals and caches, sounds, toasts,
  debug, anti-abuse thresholds, layout numbers. They are set once or not at all.
- **One page per module** (sub-page layout, 27 for up to 7 cards, 36 for 8-14),
  reached from a settings hub with one card per page showing its two values
  that matter. A card is `{letters}<Module> ▸ <Setting>`, a subtitle
  `<Module> setting`, two or three lines saying what the value does (and what
  `-1`/`0` mean), the current value and its default as the stats, then `%…_click%`.
- Module switches apply on the next restart; the hub's header says how many
  changes wait for one (`PluginModules.pendingRestart()`).
- Writing the pages: ExyliaLib `tools/settings-cards/settings_cards.py` turns a
  short spec (key, label, icon, two or three lines) into the English YAML of a
  settings hub and its pages; a worker translates them. Reference: ExyliaMarket.

## The generic tree

```java
// a "Settings" button in a plugin's admin hub
actions.registerSync("settings", "myplugin.admin", (context, arguments) -> {
    ConfigMenus.of(plugin)
            .permission("myplugin.admin")   // edits allowed with this as well as exylialib.config
            .back(hub::open)                // BACK on the top screen returns to the hub
            .open(context.player());
    return ActionResult.success();
});
```

The button, ready to copy into the hub (slot 36, the hub's auxiliary corner):

```yaml
  settings:
    slot: 36
    material: COMPARATOR
    name: '{primary}&lSETTINGS'
    lore:
      - ''
      - '{secondary}Information:'
      - ' {letters_black}▎ {letters}Change this plugin''s settings'
      - ' {letters_black}▎ {letters}without editing its {highlight}files{letters}.'
      - ''
      - '{warning}➥ Click {letters_black}» {letters}Open settings'
      - ''
    actions:
      - 'myplugin:settings'
```

The cosmetics admin hub every crate-backed cosmetic plugin shares already has
this button (1.445.0).

Entry point: `net.exylia.lib.config.ConfigMenus`.

| Call | Opens |
| --- | --- |
| `ConfigMenus.open(viewer)` | the Exylia plugins with settings |
| `ConfigMenus.open(viewer, plugin)` | that plugin's settings: its only file straight away, or the list of its files |
| `ConfigMenus.of(plugin).permission(p).back(b).open(viewer)` | the same, from the plugin's own hub (1.445.0) |
| `ConfigMenus.of(plugin).permission(p).back(b).file(name).open(viewer)` | one settings file straight away, by the name handed to `Configs.define` (`modules/hotbar/config`): a module's card in the hub opening that module's settings (1.557.0) |

Any thread. Clicks need `exylialib.config` (`ConfigMenus.PERMISSION`, op by default),
or the permission given to `permission(...)` for that plugin's files only. The
list of every plugin always needs `exylialib.config`. `back(...)` replaces BACK
on the top screen (the file list, or a single file's first page) and runs on the
viewer's thread; without it BACK goes to the plugin list. A plain `open` forgets
what an earlier `of(...)` set.

With `file(name)`, that file is the top screen: BACK from its hub (or from its
only page) runs `back(...)`, never the plugin's list of files, and the BACK
button says *Previous screen*. A name the plugin did not define, or one whose
module is off, opens the plugin's settings as if no file had been asked for.

```java
ConfigMenus.of(plugin).permission("lobby.admin").back(hub::open)
        .file("modules/hotbar/config").open(player);
```

## What the admin does

| Command | What it does |
| --- | --- |
| `/exylialib config` | The plugins with settings |
| `/exylialib config <plugin>` | One plugin's settings |

The console is pointed at `/exylialib editor`.

## Which files

What the web editor's discovery offers in the `settings` group: every
`Configs.define` file of an enabled Exylia plugin that is not `translated()`,
not covered by a registered web editor subject, and not excluded with
`WebEditor.of(plugin).exclude(...)`. `discover(false)` leaves the plugin out
here too. Messages, menus and unregistered raw YAML are not shown; ExyliaLib's own files are.

**Content files** come after them (since 1.446.0): every YAML file the plugin
registered with [`WebEditor.of(plugin).yaml(...)`](web-editor.md#yaml-subjects),
which is how a catalogue it compiles itself (cosmetics, classes, patterns)
gets a checker. One line in the plugin, next to the loader that reads the file:

```java
WebEditor.of(this).yaml("effects", "effects")          // id, path without .yml
        .title("Effects")
        .permission("myplugin.admin")                   // optional; else the hub's or exylialib.config
        .check(yaml -> catalog.problems(yaml))          // one line per entry that would not load
        .onApplied(catalog::reload);
```

The same line makes the file editable in the browser. A subject of kind
`lib/menu` and the YAML files discovery finds on its own stay in the web
editor: they have no checker of their own to stand between a click and the
file.

Since 1.447.0 a record file the plugin registered with
[`WebEditor.of(plugin).subject(id, file[, kind])`](web-editor.md#what-a-subject-is)
(ExyliaDisplays' holograms and NPCs) is a content file too, behind the
subject's own permission and sensitive paths, read through the binder. Entries
a plugin stores itself (`entries(...)`) stay in the web editor.

## The screens

Laid out the way the hand-made admin screens are (ExyliaMines, ExyliaMarket's
`/marketadmin`): a header on top, cards centred on their row, the screen cut
to the rows it needs, BACK in the middle of the bottom row. *Since 1.449.0.*

1. **Plugins** (54, paginated): one book per plugin with its description, and
   *Problems* when its files hold any. A command opens it, so ESC closes it.
2. **A plugin's files** (hub): one card per settings file and content file,
   with its header comment. Skipped when the plugin has a single file.
3. **A file's pages** (hub): one card per page, each with the first four
   values it holds, live. Skipped when the file has a single page.
4. **A page**: a header (the page's card), then one button per key, centred.
   A nested section is a button opening its own page.

Every line on these screens has to help the admin decide something (since
1.451.0): no plugin version, no count of files or keys. A plugin's or a file's
*Problems* line appears only when the last read found some, and a file card
keeps its `File » x.yml`, the place to edit it by hand.

```
A file's hub (45)            A page with 3 keys (36)
. . . . H . . . .            . . . . H . . . .      H  header (the file / the page)
. . . . . . . . .            . . K . K . K . .      C  a page's card
. C . C . C . C .            . . . . . . . . .      K  a key
. . . . . . . . .            . . . . B . . . .      B  BACK
. . . . B . . . .
```

Buttons per row follow the house rule: 1 → 13, 2 → 12/14, 3 → 11/13/15,
4 → 10/12/14/16, 5 to 7 side by side. Eight keys are two rows of four. A page
holds three rows (21 keys) before it pages; a hub, two rows of cards.

### What goes on which card

Without annotations, a file is grouped the way it is written:

| In the record | On screen |
| --- | --- |
| keys at the root | a **General** card |
| each section (nested record) at the root | a card of its own |
| each map or list of entries at the root | a card that opens the list |
| a section further down | a button on its parent's page |

A button is named after its key in capitals (`min-distance` reads
`MIN DISTANCE`), its *Information* is the first sentence of its `@Comment`,
and its icon is chosen from the words in its key (`price`, `fee` → gold
nugget; `cooldown`, `delay` → clock; `max`, `limit` → hopper; `radius` →
compass; `sound` → note block...), or from its type. A switch is a lime or gray
dye; a `Material` shows itself.

### Annotations

`net.exylia.lib.config.Menu` holds optional annotations that make a record read
like a hand-made screen. None changes the YAML.

| Annotation | On | Does |
| --- | --- | --- |
| `@Menu.Label("Listing fee")` | key, section, record type | the name on screen (drawn in capitals); on the root record, the file's |
| `@Menu.Icon("GOLD_NUGGET")` | key, section, record type | the button's item; on the root record, the file header's |
| `@Menu.About({"line", "line"})` | key, section, record type | the *Information* lines, palette tokens allowed; the `@Comment` stays the owner's manual |
| `@Menu.Page("fees")` | key | puts the key on that page, whatever section it is written in |
| `@Menu.Pages({@Menu.Card(...)})` | any record of the file | describes the pages: `id`, `label`, `icon`, `about` |
| `@Menu.Format(Menu.Style.MONEY)` | number | reads as money (`Economy.format`, default currency), `PERCENT` (`5` → `5%`), `FRACTION` (`0.25` → `25%`, the file keeps the fraction) or `MULTIPLIER` (`1.25` → `×1.25`) |
| `@Menu.Range(min, max, step, none, preset)` | number, `@Time` number | clicks stay inside `[min, max]`, a typed value outside is refused; `step` per click (shift ×10); `none` (such as `-1`) is also accepted and reads *None*; `preset` fills what is not written: `POSITIVE` (0 – ∞), `HUNDRED` (0 – 100, step 1), `FRACTION` (0 – 1, step 0.05), `MULTIPLIER` (0 – 100, step 0.1) |
| `@Menu.Unit("min")` | number | the unit beside the value; a unit of time (`ms`, `t`, `s`, `min`, `h`, `d`, `w`) reads as a length (`90` → `1h 30m ⌚`). The file is unchanged |
| `@Menu.AtLeast("key")` / `@Menu.AtMost("key")` | number | a change that would put it below / above that key is refused with the reason. A sibling key, or a dotted key from the root; annotate both ends so either change keeps the order |
| `@Menu.Choices(value = {...}, labels = {...})` | text | cycles through a fixed set like an enum; `labels` name them on screen. A value outside the set is shown as written until a click moves it |
| `@Menu.Language` | text | the plugin's `language`: cycles `default`, `en`, `es`, `fr`, `pt`, `ru`, `tr` |
| `@Menu.Material` | text | picked with the icon picker, written in the case the file already uses |
| `@Menu.Sound` | text `NAME\|volume\|pitch` | left / right walk the house UI sounds (`ui.button.click`, `entity.experience_orb.pickup`, `block.note_block.*`, `item.book.page_turn`, `item.bundle.remove_one`, `entity.villager.no`), each played to the admin alone; volume, pitch and the way the name is written are kept; Q types any sound |
| `@Menu.Hidden` | key | left out of the screens (still in the file and the web editor) |

Since 1.451.0: `FRACTION`, `MULTIPLIER`, `preset`, `Unit`, `AtLeast`, `AtMost`,
`Choices`, `Language`, `Material`, `Sound`.

```java
record Detection(
        @Menu.Label("Warning") @Menu.Range(preset = Menu.Preset.HUNDRED) @Menu.AtMost("critical") int warning,
        @Menu.Label("Critical") @Menu.Range(preset = Menu.Preset.HUNDRED) @Menu.AtLeast("warning") int critical,
        @Menu.Label("Window") @Menu.Unit("min") @Menu.Range(min = 1, max = 1440) int windowMinutes,
        @Menu.Label("Directness") @Menu.Format(Menu.Style.FRACTION) @Menu.Range(preset = Menu.Preset.FRACTION) double directness,
        @Menu.Label("Game mode") @Menu.Choices(value = {"survival", "adventure", "creative", "spectator"}) String gamemode,
        @Menu.Label("Alert sound") @Menu.Sound String alertSound) { ... }
```

A section whose keys all went to pages has no card of its own. Pages are listed
in the order their first key is declared.

```java
@Menu.Label("Auction house")
@Menu.Icon("BARREL")
@Menu.About({"Players list items at a fixed price", "and buy from each other."})
@Menu.Pages({
        @Menu.Card(id = "fees", label = "Fees & tax", icon = "GOLD_NUGGET",
                about = {"What a listing costs to put up", "and what a sale pays the server."}),
        @Menu.Card(id = "limits", label = "Limits", icon = "CHEST",
                about = {"How many listings, how cheap or", "dear, and for how long."})})
public record AuctionHouseSettings(
        @Menu.Page("fees") @Menu.Label("Listing fee") @Menu.Icon("PAPER")
        @Menu.About({"Charged to the seller when a listing", "goes up, as a share of its price."})
        @Menu.Format(Menu.Style.PERCENT) @Menu.Range(min = 0, max = 100, step = 0.5)
        @Comment("Percent of the price charged when an item is listed. Never refunded.")
        double listingFeePercent,

        @Menu.Page("limits") @Menu.Label("Highest price") @Menu.Icon("GOLD_BLOCK")
        @Menu.About({"The most a listing may ask for.", "{highlight}-1 {letters}removes the ceiling."})
        @Menu.Format(Menu.Style.MONEY) @Menu.Range(min = 0, max = 1e12, none = -1)
        double maxPrice,

        @Menu.Page("limits") @Menu.Label("Listing time")
        @Time(Time.Unit.HOURS) @Menu.Range(min = 1, max = 8760)
        int listingHours) {
    ...
}
```

That is ExyliaMarket's auction-house *Fees* and *Limits* screens, drawn from
the record with no menu file and no code of its own.

The lists are lambdas over `ConfigFile.get()`: a value changed in the YAML and
reloaded shows on an open screen within a second.

| Type | Control | Clicks |
| --- | --- | --- |
| `boolean` | switch | any click flips it |
| enum | choice | left next, right previous, wrapping |
| `int`, `long` | stepper | left/right ±step (1), shift ±10 steps, Q types a value |
| `double`, `float` | stepper | left/right ±step (0.1), shift ±10 steps, Q types a value |
| `Duration`, `@Time` number | length of time | asks with `inputs().duration`, prefilled |
| `String` | text | asks with `inputs().text`, prefilled |
| `Material` | icon | the icon picker |
| `List<String>` | lines | one box, one entry per line, prefilled |
| `Map<String, Record>`, `List<Record>` | entries | opens the list of entries (see *Content screens*) |
| `Map<String, value>` (text, number, boolean, enum, `Duration`) | entries | the list of entries; a click changes the one value an entry is |
| anything else (a map of lists, a list of numbers) | read-only | edit it with `/exylialib editor` |

A map or a list of a settings file (since 1.447.0) opens the content screens
below, written through the same `ConfigFile` and binder. BACK from it returns
to the page its button is on, or to the file's hub.

## Content screens

A content file is drawn from the schema the web editor infers from it
(see [YAML subjects](web-editor.md#yaml-subjects)):

- **A block of named entries** (every child a block, or a record's `Map`) is a
  list of entries (54, paginated). Click opens one, shift-left-click duplicates
  it as `<id>-copy`, shift-right-click deletes it after a dangerous confirm.
  **NEW ENTRY** (slot 45) asks for an id (letters, digits, `-`, `_`, `.`). In a
  record file the entry starts as the record's defaults (the binder fills
  them); in a YAML file, which has none, as a copy of the first entry, so it
  passes the checker. An entry shows the item its `material`, `icon`, `item`,
  `display-item` or `type` key names, if any.
- **A list of blocks** (a record's `List<Record>`, or a YAML list of blocks,
  since 1.447.0) is the same screen in order: `#1`, `#2`... with the entry's
  `name`, `id` or `title` when it has one. NEW ENTRY appends one without asking
  for an id: the element record's defaults in a record file, a copy of the last
  one in a YAML file. Duplicate puts the copy right after the original.
  Reordering is not offered; use `/exylialib editor`.
- **A map of single values** (`Map<String, Integer>`, of text, of booleans...)
  lists its entries with their values; a click changes one straight away
  (types a number or text, flips a switch, steps a choice). A new entry starts
  at zero, empty, off, or the first choice.
- **Inside an entry or a section**, every key any entry uses is listed, with the
  controls of a record: switch, choice, ±steppers with Q to type, length of
  time, text, lines, and the icon picker for a record's `Material`. Nested
  blocks open their own page. Keys whose type differs between entries, and
  lists of anything but text or blocks, are read-only.
- The file is read again only when it changed on disk, so a hand edit shows on
  an open screen within a second.

Every click goes through the path an applied web edit takes, from what the
file holds at that moment: the whole new text is built, the subject's `check`
reads it (any problem refuses the click, tells the admin why, and writes
nothing), a sensitive path or a console line asks a dangerous confirm, the file
is written atomically keeping its comments and order, then `onApplied` runs.
A record file is bound instead, exactly as a reload binds it: a value of the
wrong shape refuses the click with the binder's reason.

## How a change is written

Through `ConfigFile.set(key, value)`, with the value in the shape the file holds
it (an enum as `lower-hyphen`, a `Duration` as `1m30s`, a `@Time` number in its
declared unit), so the YAML reads as if the owner had typed it. The file is
re-read, bound and validated as after a reload; a refused value is reported to
the admin with the reason.

Then the plugin is brought up to date the way an applied web edit does it: its
declared `Reloads`, or for ExyliaLib what `/exylialib reload` does. Text that
starts a console or op command (`console:`, `[CONSOLE]`, `op:`...) is written
only after a dangerous confirm.

The write runs on the global thread; the screen is redrawn in place, or
reopened when a prompt had closed it.

## Restyling

The screens are admin menus in `lang/<code>/menus/config/`, rewritten
from the jar on every start. The words on the buttons (values, click hints,
prompts) are `phrases.yml` lines.

## Reload

Keeps nothing derived from the palette: the screens are recompiled with the
library's other menus when the palette reloads.
