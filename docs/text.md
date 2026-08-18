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
| `from(plugin, String)` | same, for a message belonging to a plugin, so `%prefix%` resolves |
| `component(String)` | straight to a `Component` |
| `with(placeholder, value)` | substitute on the parsed component, as literal text; values that change per player go here, never concatenated |
| `withFormatted(placeholder, value)` | substitute a value that carries its own formatting — a display name from a config; never for text a player typed |
| `forPlayerFormatted(player)` | like `forPlayer`, but resolver values that carry formatting (a display name) are honoured instead of shown raw |
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

## The prefix

Nearly every message a plugin sends starts with the same tag. Written into each
line, changing it means editing the whole file; registered as a placeholder, two
plugins fight over the one name `%prefix%`. So a prefix belongs to a plugin:

```java
Prefixes.set(this, messages.prefix());                       // on enable, and on reload
Text.from(this, messages.warmup().ready()).send(player);     // %prefix% resolves
```

| Method | Contract |
| --- | --- |
| `Prefixes.set(plugin, prefix)` | set it; call again after a reload |
| `Prefixes.get(plugin)` | the prefix, or `null` |
| `Prefixes.release(name)` | forget it; done for you when the plugin disables |

Contracts:

- Two plugins can both use `%prefix%` in their own files and each gets its own.
- `Text.of` leaves `%prefix%` alone. Text that does not say which plugin it came
  from has no prefix to use, and guessing would be worse than showing the token.
- A plugin that never set one leaves `%prefix%` visible, so the omission is
  obvious rather than silent.
- The prefix is substituted **before** parsing and **before** centring: it
  carries its own colours, and its width counts towards a centred line.

## Effects inside a message

A message can ask for a sound, particles or a firework, in the notation
ExyliaCommons used — existing message files work unchanged:

```yaml
level-up: "[sound:ENTITY_PLAYER_LEVELUP|1.0|1.2;particle:FLAME|20;center]{success}Well done"
```

One bracketed block, only at the very start of the line. Inside it, `;`
separates the kinds, `,` separates several of one kind, and `|` separates a
kind's own arguments.

| Kind | Arguments | Example |
| --- | --- | --- |
| `sound` / `sounds` | `NAME|volume|pitch` | `sound:PLING|0.5|1.8` |
| `particle` / `particles` | `NAME|count|offsetX|offsetY|offsetZ|speed` | `particle:FLAME|20` |
| `firework` / `fireworks` | `SHAPE|colour|fade|flicker|trail|power` | `firework:BALL|#ff0000` |
| `center` / `centered` | — | `center` |

Contracts:

- **The tag never reaches the screen, a log, or an item name.** It is an
  instruction, dropped by `build()` wherever the text ends up.
- **Only a player gets the effects.** A console cannot hear a sound, so it
  receives the message alone.
- **A bracketed prefix that means nothing is left alone**: `[Server] Restarting`
  keeps its prefix, and an unclosed `[` is text.
- **A tag only counts at the start of the line.**
- **A malformed entry never eats the message.** A nonsense sound name is
  reported once and skipped; the message still arrives, because the message
  is the point.
- Nothing is examined when a line does not begin with `[`, so messages
  without effects pay nothing.

## Centring

`Centering.center(line)` pads a line so it sits in the middle of the chat
window, measured in **pixels rather than characters** — Minecraft's font is
not monospaced, so an `i` is one pixel and a `W` is five. Same widths as
ExyliaCommons, so a line centred there is centred here.

```java
Text.of("[center]{primary}WELCOME").send(player);   // in a message
Centering.center("{primary}WELCOME");               // directly
```

| Method | Contract |
| --- | --- |
| `center(String)` | pads to the middle of the 320px chat window |
| `center(List<String>)` | the same, line by line |
| `centerWithin(String, width)` | centres within a width you name; a line too wide is returned unchanged |
| `pixelWidth(String)` | how wide a line is on screen |

Formatting is measured, not counted: MiniMessage tags, legacy codes and
palette tokens take no space, and bold takes one pixel more per character
(a colour code ends bold, exactly as the client does).

## Small capitals

Every line of every Exylia plugin is drawn in small capitals — `WELCOME`
reaches the screen as `ᴡᴇʟᴄᴏᴍᴇ`. This is the Exylia look, so it is on by
default; one switch in `plugins/ExyliaLib/config.yml` turns it off:

```yaml
small-text: false
```

It is not a Minecraft font — the client has one default font and no way to
switch it from a message — so the letters are swapped for the Unicode small
capitals that look like them.

A server already running 1.29.0 has `small-text: false` written in its file
from that release, and a value already in the file wins over the default. Set
it to `true`, or delete the line, to pick up the new look.

| Written | Drawn |
| --- | --- |
| letters, either case | the small capital (`a` and `A` both give `ᴀ`) |
| `s` and `x` | unchanged; Unicode has no small capital for them |
| digits, punctuation, symbols | unchanged |
| tags, tokens, legacy codes | unchanged, and still work |
| substituted values | unchanged |

**Values are never transformed.** A player named `Steve` stays `Steve` and a
balance stays `1500`: the template is parsed once and shared, and values are
inserted into the parsed component afterwards. That is the same design that
makes the parse cache work, and it is why the switch costs nothing per player.

There is no separate "force uppercase" option. ExyliaCommons shipped one for
years and it could not change a single character, because both cases already
map to the same glyph.

Centring accounts for it: a capital is five pixels and the small capital that
replaces it is four, so `pixelWidth` measures the glyph that will be drawn
rather than the letter that was written.

Flipping the switch and running `/exylialib reload` restyles the server live —
the parse cache is dropped, and boards, holograms, effects and items re-send
themselves, exactly as a palette change does.

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
  `LegacyTranslator`, `TokenResolver`, `SmallText`).
- Tests: `src/test/java/net/exylia/lib/text/TextModuleTest.java`,
  `SmallTextTest.java`.
