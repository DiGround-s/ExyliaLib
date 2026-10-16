# Web editor

Editing a plugin's configuration from the browser, the way LuckPerms' web editor
works: a command uploads a snapshot and answers with a link, the admin edits on
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

## Why this is in the library

Every plugin would need the same four things, and each of them done wrong is a
hole in the server: a transport that never opens a port, a secret that never
travels in the link, a reader that trusts the browser no more than it trusts a
hand-edited YAML, and a preview of what is about to change before it changes.
The library does them once. A plugin adds one line per file.

## What the admin does

| Command | What it does |
| --- | --- |
| `/exylialib editor [plugin]` | Snapshots every subject the sender may edit (or one plugin's) and sends the link |
| `/exylialib editor apply <code> [confirm]` | Brings down what was saved in the browser and writes it; `confirm` applies what the preview held back |
| `/exylialib editor trust <code> <challenge>` | Lets a second browser, or the same link from another address, edit the session |

All three sit behind `exylialib.editor` (default: op). Each subject can narrow
it with a `permission` of its own: a subject the sender may not see is left out
of the snapshot and refused at apply time. The console passes every check.

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

The snapshot carries a hash of every subject's values; the browser sends it
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
  them, and with them every secret. A snapshot over 4 MB is refused before it
  is sent. No player data leaves the server beyond the name of the admin who
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

What the editor must answer, for a server running its own:

| Request | Answer |
| --- | --- |
| `POST {url}/api/v1/editor/sessions` with the snapshot | `201 {"code","url","expiresAt"}` |
| `GET {url}/api/v1/editor/sessions/{code}/changes/pending` | `200 {"id","subjects":{key: values},"baseHash":{key: hash},"author"}`, or `204` |
| `POST {url}/api/v1/editor/sessions/{code}/changes/{id}/applied` with `{"outcome","snapshot"}` | `204` |
| `POST {url}/api/v1/editor/sessions/{code}/trust` with `{"challenge"}` | `204`, or `404` for an unknown challenge |

Every request carries `Authorization: Bearer <secret>` and
`User-Agent: ExyliaLib/<version>`. `404` or `410` on a session makes the server
forget it. Answers over 8 MB are refused unread.

The snapshot is `{version, server{id, platform, software, bukkit, lib, language,
smallText, palette}, issuer{name, player}, plugins[{name, version, editable}],
subjects[{key, plugin, id, title, kind, file, sensitive[], schema, values,
hash}], placeholders{"%token%": value}}`. `palette` is the server's colour
tokens as hex, `placeholders` what each `%placeholder%` found in the values
reads as for the admin who opened the session, so the preview can show
`%player_name%` as a name.

## Threads

Registration is safe from any thread. `open` builds the snapshot where it is
called (placeholders resolve there) and uploads it asynchronously. `apply`
fetches asynchronously, then decodes, compares, writes and runs `onApplied` on
the global thread. Chat goes to the sender from whichever thread finished.

## Reload

Keeps nothing derived from the palette: the palette is read into each snapshot
as it is built. `invalidateAll()` does not apply.

## Code

`net.exylia.lib.web` — `WebEditor`, `PluginWebEditor`, `Subject`, `YamlSubject`.
`net.exylia.lib.web.internal` — `WebEditorRuntime` (registry, sessions, open,
apply, trust), `Source` (a record file or a `YamlSource`), `Snapshot`, `ValueCodec`, `Diff`, `EditorClient` and
`HttpEditorClient`, `EditorMessages`. `net.exylia.lib.internal.EditorCommand`
is the Lamp command. `net.exylia.lib.config.internal.RecordJson` is the
schema-and-values bridge next to the binder.
