# Web editor

Editing a plugin's configuration from the browser, the way LuckPerms' web editor
works: a command uploads what is editable and answers with a link, the admin edits on
the web, and a second command brings the result back. Since 1.304.0.

Entry point: `net.exylia.lib.web.WebEditor`.

```java
WebEditor.of(this)
        .subject("holograms", hologramsFile, "displays/hologram")
        .title("Holograms")
        .permission("exyliadisplays.holograms")
        .sensitive("holograms.*.click.commands", "holograms.*.pages.click")
        .onApplied(this::reloadFile);
```

That is the whole integration. `hologramsFile` is the `ConfigFile` the plugin
already reads; the editor learns its shape from the record (keys, types,
`@Comment` lines) and its values from `get()`, and writes through `update()`.

Since 1.307.0 a plugin does not even need that line for its files: every
config file it defined and every other YAML file in its data folder is
**discovered** and editable on its own (see *Discovery*). Registering is for
what discovery cannot know: a richer screen (`kind`), a permission narrower
than `exylialib.editor`, sensitive paths, a checker, entries kept in a
database.

## Why this is in the library

Every plugin would need the same four things, and each of them done wrong is a
hole in the server: a transport that never opens a port, a secret that never
travels in the link, a reader that trusts the browser no more than it trusts a
hand-edited YAML, and a preview of what is about to change before it changes.
The library does them once. A plugin adds one line per file.

## What the admin does

| Command | What it does |
| --- | --- |
| `/exylialib editor [plugin]` | Opens a session over every subject the sender may edit (or one plugin's), sends the link, then uploads plugin by plugin |
| `/exylialib editor apply <code> [confirm]` | Brings down what was saved in the browser and writes it; `confirm` applies what the preview held back |
| `/exylialib editor trust <code> <challenge>` | Lets a second browser, or the same link from another address, edit the session |

All three sit behind `exylialib.editor` (default: op). Each subject can narrow
it with a `permission` of its own: a subject the sender may not see is left out
of the session and refused at apply time. The console passes every check.

A plugin may offer the same from its own command with
`WebEditor.open(sender, plugin)`; ExyliaDisplays does as `/displays editor`.

## What a subject is

One `ConfigFile`, described to the browser by its schema and its values.

- **Generic.** Without a kind (`subject(id, file)`), the browser draws a form
  from the schema: comments become help text, an enum a select, a `@Time` key
  a duration field, a map a list of named blocks. Every file is editable this
  way with no work on the web side.
- **Kind.** `subject(id, file, "displays/hologram")` names a richer screen the
  editor ships for that shape, such as the hologram editor with its preview. A
  kind the editor does not know falls back to the form.
- **Title.** What the editor calls it. Default: the id, capitalised.
- **Group.** Where the editor files it (`Subject.group()`, since 1.307.0):
  `content` for every registered subject; a discovered one is `settings`,
  `messages`, `menus` or `files`.
- **Sensitive paths.** Dotted YAML paths, `*` for any one key, matched as a
  prefix with list indices ignored: `holograms.*.click.commands` covers every
  hologram's click lines. A change under one is shown first and applied only
  with `confirm`. On top of these, a changed line that starts a console
  command (`console:`, `[CONSOLE]`, `op:`...) is sensitive by itself.
- **onApplied.** Runs on the global thread after the file was written, so the
  plugin refreshes what it draws from the file, exactly as after its own reload
  command. `update()` only writes.

Registering the same id twice replaces the earlier registration. A plugin's
subjects are forgotten when it is disabled; `remove(id)` forgets one while the
plugin keeps running.

## YAML subjects

Settings are records; content often is not. A catalogue of cosmetics or a menu
is a YAML file its plugin compiles entry by entry, and no record describes it.
`yaml(id, path)` makes such a file editable all the same (since 1.305.0):

```java
WebEditor.of(this).yaml("tags", "cosmetics/tags", "chatcosmetics/catalog")
        .title("Tags")
        .permission("exyliachatcosmetics.admin")
        .check(yaml -> catalog.problems(yaml))
        .onApplied(catalog::reload);
```

- **Path.** Relative to the data folder, without `.yml`.
- **Schema, inferred.** From what the file holds: a block whose children are
  all blocks is a map of named entries, and the keys of every entry are merged,
  so adding an entry offers every key any entry uses. A key whose type differs
  between entries (`price: 500` here, `price: {amount, currency}` there) is
  `any`, edited as whatever it holds. Comments above a key are its help text;
  the header is the subject's.
- **Check.** No binder stands in between, so the plugin's own reader does.
  `check` gets the exact text about to be written, read as the plugin reads it,
  and answers one line per problem that would stop an entry from loading. Any
  answer refuses the subject whole; a checker that throws refuses it too.
  Sensitive paths and console lines are held for `confirm` as for any subject.
- **Write.** Atomic, in the browser's key order, with the comments of every
  key that still exists, the header and the footer kept. Keys may hold dots.

`Subject.file()` is `null` for a YAML subject.

## Entries a plugin stores itself

Mines, kits, arenas: things a plugin keeps in a database, or anywhere that is
not one config file, and that admins create and delete as well as edit.
`entries(id, type, store)` makes them editable (since 1.306.0):

```java
WebEditor.of(this).entries("mines", MineEdit.class, new MineStore(manager), "mines/mine")
        .title("Mines")
        .permission("exyliamines.admin")
        .sensitive("mines.*.blocks.loot", "mines.*.lucky-rewards");
```

- **One entry is a record.** Declared like a config, with a no-arg constructor
  holding the defaults; the plugin maps it to and from what it stores. The
  browser draws the form from it, and lists the entries under `id`, where the
  admin adds, duplicates and removes them.
- **Read through the binder, then the store.** Each entry is bound against what
  it is now, a new one against `blank(id)` (the record's defaults unless the
  store says otherwise), then passed to `check(id, value, created)`. One problem
  in one entry refuses the subject whole, so the store never sees half a change.
- **Only what changed is written.** `save(id, value, created)` for an entry that
  is new or differs, `delete(id)` for one the browser removed. **Every deletion
  is shown first and needs `confirm`.**
- **Writes answer with a future.** Called on the global thread, they hand the
  database work off as the plugin always does. The admin hears the outcome once
  every future has completed; one that completed exceptionally is reported with
  its message, and `onApplied` runs only when every write succeeded.
- **The `file` the index names is empty**: there is no file.

An enum in a schema carries its class (`"class": "Material"`), so the editor
can offer a block or item picker rather than a list of a thousand names. On
the wire the list itself is sent once per plugin, under `enums` (see *Wire
contract*).

## Discovery

Since 1.307.0, for every enabled Exylia plugin (`Exylia` + name) and a sender
holding `exylialib.editor`, the library offers on top of the registered
subjects:

1. **Every `ConfigFile` the plugin defined** (`Configs.loaded()`), as a record
   subject: group `messages` when it is translated (the active language's
   file), `settings` otherwise. Id from its name (`/` becomes `-`), title from
   it too.
2. **Every other `.yml` under the data folder**, read as a YAML subject, except:
   - a file a registered subject or step 1 already covers (compared as
     canonical files; a registered subject wins whatever permission it asks
     for, so discovery never opens a file a registration keeps narrower);
   - directories `PluginMenus.refreshBundledDirectory` regenerates on start
     (the jar's, not the owner's);
   - `lang/<code>/` of every language but the active one (`lang/<active>/`
     and `lang/custom/` stay);
   - folders named `data`, `players`, `playerdata`, `logs`, `backups`,
     `cache`, `storage`, `database`, `exports`, `schematics` (any depth, any
     case), and hidden ones;
   - files over 1 MiB, files that do not parse, and paths the plugin excluded.

   A file whose root has an `items` section and one of `size`, `rows`, `type`
   or `title` is a **menu**: group `menus`, kind `lib/menu`, sensitive
   `items.*.actions`, and checked before it is written by compiling it with the
   plugin's own menu compiler (`PluginMenus.check`, against the action
   namespace the plugin's menus use): any problem refuses it. Any other file is
   group `files`, kind `generic`, with no checker beyond being valid YAML.
3. **Applying** any discovered subject runs the plugin's declared `Reloads`
   once per apply, however many of its files changed. A plugin that declares
   none has each written record file re-read instead, so its `onReload`
   listeners run.

Ids are unique per plugin and valid subject ids: `menus/user/mines` is
`menus-user-mines`, cut to 48 characters, with `-2`, `-3`... on a clash.
Discovered subjects carry `exylialib.editor` as their permission.

Opting out, from the plugin:

```java
WebEditor.of(this)
        .exclude("arenas/**", "generated/*.yml")   // relative paths, * and **, with or without .yml
        .discover(false);                          // or turn it off entirely
```

A pattern that matches a folder keeps out everything in it; exclusions apply
to step 1's files too. Exclude what the plugin writes for itself outside the
folders above, and what a registered `entries` store already edits.

## How a change is read

Through `net.exylia.lib.config.internal.RecordJson`, the same binder the YAML
goes through: the same coercions, the same fallback for a key the browser left
out (the file's current value), the same pruning of a key the record does not
know, and the same issues for a value of the wrong shape. A subject with an
`INVALID_VALUE` or `BROKEN_FILE` issue is refused whole and reported; the other
subjects of the same change still apply.

Before writing, the decoded record is rendered back to JSON and compared with
the file's current values leaf by leaf. That diff is what counts the changes
the admin is told about, what the sensitive patterns are matched against, and
what the console-line check reads. A block added or removed whole is walked as
if the other side were empty, so a sensitive branch inside a brand-new entry
is still seen.

Every uploaded subject carries a hash of its values; the browser sends it
back with a change. A hash that no longer matches means the file changed on
the server after the browser loaded it (a command, a hand edit), and the
change is held for `confirm` so applying it is a decision, not an accident.

## Security

- **The server never listens.** Every exchange is a request the server makes
  over `java.net.http` to the editor named in `config.yml`. Nothing can be
  pushed to it; only `apply`, typed in game, pulls.
- **Two secrets.** The link carries only the public session code. A 256-bit
  secret, generated when the session opens, stays in memory on the server and
  authorises every request as a bearer header. A leaked link lets somebody look
  and propose; it never lets them apply.
- **Trusted browsers.** The first browser to open a link owns the session and is
  bound to it with its address. Another browser, or the same link from another
  address, is read-only and shows a short challenge; `trust <code> <challenge>`
  typed in game, by somebody with the permission, is what makes it an editor.
  The editor keeps that trust per server, so a trusted browser stays trusted
  for that server's later sessions.
- **The binder is the trust boundary.** A change is never written as text:
  it is bound to the record, refused when it does not fit, and lines that run
  with the server's authority are confirmed first.
- **A session lives a day, in memory.** At most 32 at once; a restart forgets
  them, and with them every secret. A body over 32 MiB (or over 8 MiB once
  compressed) is refused before it is sent; a plugin refused this way is named
  in chat and the others still upload. No player data leaves the server beyond the name of the admin who
  opened the session; no address ever does.

## Settings

In `plugins/ExyliaLib/config.yml`:

```yaml
web-editor:
  enabled: true
  url: https://utils.exylia.net
```

`enabled: false` makes `/exylialib editor` refuse; nothing is sent until an
admin runs it anyway. `url` is the editor sessions are opened on, for a server
running its own copy.

## Wire contract

Protocol v2 (since 1.307.0). What the editor must answer, for a server running
its own:

| Request | Answer |
| --- | --- |
| `POST {url}/api/v1/editor/sessions` with the index | `201 {"code","url","expiresAt"}` |
| `PUT {url}/api/v1/editor/sessions/{code}/plugins/{plugin}` with one plugin's payload | `204`; `404` for a plugin the index did not list |
| `GET {url}/api/v1/editor/sessions/{code}/changes/pending` | `200 {"id","subjects":{key: values},"baseHash":{key: hash},"author"}`, or `204` |
| `POST {url}/api/v1/editor/sessions/{code}/changes/{id}/applied` with `{"outcome":{"applied":[keys],"rejected":{key: reason}},"version":2}` | `204` |
| `POST {url}/api/v1/editor/sessions/{code}/trust` with `{"challenge"}` | `204`, or `404` for an unknown challenge |

Every request carries `Authorization: Bearer <secret>` and
`User-Agent: ExyliaLib/<version>`, and every body is gzip-compressed
(`Content-Encoding: gzip`): at most 8 MiB compressed and 32 MiB decompressed.
`404` or `410` on a session makes the server forget it. Answers over 8 MB are
refused unread.

**Index** (the open): `{version: 2, server{id, platform, software, bukkit,
lib, language, smallText, palette}, issuer{name, player}, plugins[{name,
version, editable, subjects[{key, id, title, kind, group, file,
sensitive[]}]}]}`. `plugins` lists every enabled Exylia plugin, editable or
not; `subjects` is metadata only, empty when the sender may edit nothing
there. `file` is the path relative to the data folder without `.yml`
(`menus/user/mines`, `lang/en/messages`), or `""` for entries a plugin stores.

**Plugin payload** (one PUT per plugin, one after another once the index was
accepted, and again for every plugin a change touched once it is applied):
`{subjects[{key, plugin, id, title, kind, group, file, sensitive[], schema,
values, hash}], enums{Class: [values]}, placeholders{"%token%": value}}`. An
enum node in a schema that carries a `class` has its `values` moved into
`enums[class]`, sent once per plugin; a class name another enum of the same
plugin already took with different values keeps its own inline. `placeholders`
is what each `%placeholder%` found in the plugin's values reads as for the
admin who opened the session, so the preview can show `%player_name%` as a
name. `palette` is the server's colour tokens as hex.

Version 1 servers (1.304.0 to 1.306.0) sent one snapshot with every subject's
schema and values inline and `applied` with `{"outcome","snapshot"}`.

## Threads

Registration is safe from any thread. `open` checks permissions and reads the
plugin list where it is called; discovery, reading files and the uploads run
on the async scheduler, and each plugin's `EntryStore.entries()` and
placeholders are read on the global thread on the way. `apply` fetches
asynchronously, then decodes, compares, writes and runs `onApplied` (and a
discovered subject's `Reloads`) on the global thread; the `applied` call and
the re-uploads go back off it. Chat goes to the sender from whichever thread
finished.

## Reload

Keeps nothing derived from the palette: the palette is read into each snapshot
as it is built. `invalidateAll()` does not apply.

## Code

`net.exylia.lib.web` — `WebEditor`, `PluginWebEditor`, `Subject`, `YamlSubject`, `EntryStore`.
`net.exylia.lib.web.internal` — `WebEditorRuntime` (registry, sessions, open,
apply, trust), `Discovery`, `Source` (a record file, a `YamlSource` or an `EntrySource`), `Snapshot`, `ValueCodec`, `Diff`, `EditorClient` and
`HttpEditorClient`, `EditorMessages`. `net.exylia.lib.internal.EditorCommand`
is the Lamp command. `net.exylia.lib.config.internal.RecordJson` is the
schema-and-values bridge next to the binder.
