# Config menus

Every plugin's settings changed from a menu in game, with no code in the plugin,
and the content catalogues it registers for editing.
The same files the [web editor](web-editor.md) discovers as `settings`, drawn
from their record. *Since 1.443.0.*

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

Any thread. Clicks need `exylialib.config` (`ConfigMenus.PERMISSION`, op by default),
or the permission given to `permission(...)` for that plugin's files only. The
list of every plugin always needs `exylialib.config`. `back(...)` replaces BACK
on the top screen (the file list, or a single file's first page) and runs on the
viewer's thread; without it BACK goes to the plugin list. A plain `open` forgets
what an earlier `of(...)` set.

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

## The screens

1. **Plugins** (54, paginated): one book per plugin, its version and how many
   files it has. A command opens it, so ESC closes it.
2. **Files** (54, paginated): one per file, with its header comment. Skipped
   when the plugin has a single file.
3. **Section** (54, paginated): one button per key of a section, in the
   record's order. A nested record is a button that opens its own page; BACK
   returns to the section above, then to the files, then to the plugins.

Every button shows the key's `@Comment` lines as *Information* and its value
as *Status*. The lists are lambdas over `ConfigFile.get()`: a value changed in
the YAML and reloaded shows on an open screen within a second.

| Type | Control | Clicks |
| --- | --- | --- |
| `boolean` | switch | any click flips it |
| enum | choice | left next, right previous, wrapping |
| `int`, `long` | stepper | left +1, right −1, shift ±10, Q types a value |
| `double`, `float` | stepper | left +0.1, right −0.1, shift ±1, Q types a value |
| `Duration`, `@Time` number | length of time | asks with `inputs().duration`, prefilled |
| `String` | text | asks with `inputs().text`, prefilled |
| `Material` | icon | the icon picker |
| `List<String>` | lines | one box, one entry per line, prefilled |
| anything else (maps, lists of records) | read-only | edit it with `/exylialib editor` |

## Content screens

A content file is drawn from the schema the web editor infers from it
(see [YAML subjects](web-editor.md#yaml-subjects)):

- **A block of named entries** (every child a block) is a list of entries
  (54, paginated). Click opens one, shift-left-click duplicates it as
  `<id>-copy`, shift-right-click deletes it after a dangerous confirm.
  **NEW ENTRY** (slot 45) asks for an id (letters, digits, `-`, `_`, `.`) and
  starts the entry as a copy of the first one, so it passes the checker; an
  empty block starts it empty. An entry shows the item its `material`, `icon`,
  `item`, `display-item` or `type` key names, if any.
- **Inside an entry or a section**, every key any entry uses is listed, with the
  controls of a record: switch, ±steppers with Q to type, text, lines. Nested
  blocks open their own page. Lists of blocks and keys whose type differs
  between entries are read-only.
- The file is read again only when it changed on disk, so a hand edit shows on
  an open screen within a second.

Every click goes through the path an applied web edit takes, from what the
file holds at that moment: the whole new text is built, the subject's `check`
reads it (any problem refuses the click, tells the admin why, and writes
nothing), a sensitive path or a console line asks a dangerous confirm, the file
is written atomically keeping its comments and order, then `onApplied` runs.

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

The three screens are admin menus in `lang/<code>/menus/config/`, rewritten
from the jar on every start. The words on the buttons (values, click hints,
prompts) are `phrases.yml` lines.

## Reload

Keeps nothing derived from the palette: the screens are recompiled with the
library's other menus when the palette reloads.
