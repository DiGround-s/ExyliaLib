# Text module

Every player-facing string goes through `net.exylia.lib.text.Text` and becomes
an Adventure `Component`. Since 1.2.0.

## Notations

All four mix freely in one string:

| Form | Example |
| --- | --- |
| Palette token | `{primary}`, `{error}` |
| Legacy code | `&a`, `&l` |
| Legacy hex | `&#8a51c4`, `&x&8&a&5&1&c&4` |
| MiniMessage | `<bold>`, `<gradient:#8a51c4:#ff6b9d>...` |

```java
Text.of("{primary}&lWELCOME &8[{success}online&8]").send(player);
```

## API

`Text`:

| Method | Contract |
| --- | --- |
| `of(String)` | the chainable form |
| `component(String)` | straight to a `Component` |
| `with(placeholder, value)` | substitute on the parsed component; values that change per player go here, never concatenated |
| `forPlayer(player)` | resolve `%placeholders%` for that viewer when building |
| `build()` | the final `Component` |
| `send(CommandSender)` | build and deliver |
| `plain()` / `legacy()` / `raw()` | serializers; `legacy()` is only for old APIs that still demand it |

`Colors`:

- `get(token)` / `get(token, fallback)` — a palette token as `TextColor`.
- `apply(Palette)` — swap the palette (done by the library when `colors.yml`
  reloads).
- `names()` — every registered token.

`Palette` — the 15 Exylia roles (`primary`, `secondary`, `secondary_light`,
`letters`, `letters_black`, `success`, `success_light`, `warning`,
`warning_light`, `info`, `info_light`, `accent`, `neutral`, `highlight`,
`muted`), each a hex string.

## Performance

- Text with no formatting characters skips the parser entirely.
- Everything else is parsed once and cached; re-sending the same line every
  tick is a cache lookup, not a parse. That is what makes this safe inside
  scoreboard and action bar loops.
- Concatenating values into the string breaks that cache — use `.with()`.

## Rules

- Return `Component`, not `String`.
- Colors by role (`{primary}`), not by hex, so the owner recolors everything
  from `colors.yml`.
- Adventure is the server's own: compiled against the version paper-api
  carries, pinned by `resolutionStrategy`. Compiling against a newer one
  compiles fine and then explodes in production with `NoSuchMethodError`.

## Source and tests

- Public: `text/Text.java`, `text/Colors.java`, `text/Palette.java`.
- Internal: `text/internal/` (`TextEngine`, `FormatScanner`,
  `LegacyTranslator`, `TokenResolver`).
- Tests: `src/test/java/net/exylia/lib/text/TextModuleTest.java`.
