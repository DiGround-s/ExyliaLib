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

## Text shadow

Since 1.111.3. Minecraft draws a shadow under every glyph and, from 1.21.4,
lets a message say what colour it is. One value in
`plugins/ExyliaLib/config.yml` sets it for every line of every Exylia plugin —
messages, item names, lore, scoreboards, holograms:

```yaml
text-shadow: "#000000"     # a black shadow under everything
text-shadow: "#41dba880"   # #rrggbbaa: the same colour at half strength
text-shadow: "none"        # no shadow at all, not even the client's own
text-shadow: ""            # the default: whatever the client draws by itself
```

The alpha goes last, the way MiniMessage's own `<shadow>` tag spells it, so a
colour copied out of a gradient generator reads the same here.

Empty is the default and changes nothing, which is what every server had
before this existed. Set it deliberately.

| Written | Drawn |
| --- | --- |
| the line's own `<shadow:...>` | kept — the configured one is only a fallback |
| a line with no shadow of its own | the configured shadow |
| every child of that line | inherits it, the way the client inherits any style |

`shadowColorIfAbsent` is applied where the component is built, so a cached
line is shadowed once rather than on every read, and changing the value drops
the caches exactly as `small-text` and a palette change do. Flipping it and
running `/exylialib reload` restyles the server live.

On a server older than 1.21.4 the Adventure that ships with it has no shadow
colour at all. Nothing breaks: the value is ignored, and the class that names
the type is never loaded.

## Gradients

Since 1.102.0. `<gradient:#a:#b>` is for text an owner writes in a file: it is
parsed once and cached. It cannot colour something that only exists after the
parse — an animation frame, a colour a player chose, a name painted after its
placeholders were substituted. `Gradients` paints a `Component` that already
exists, one character at a time, and leaves everything else in it alone.

```java
Component name    = Gradients.paint("DiGround", List.of(gold, orange));
Component painted = Gradients.apply(component, List.of(a, b, c));   // keeps bold, hover, click
Component frame   = Gradients.apply(component,
        index -> Gradients.wrap(stops, (index + tick) / 12.0));      // a shifting gradient
```

| Method | Contract |
| --- | --- |
| `blend(from, to, position)` | the colour `position` of the way between two, clamped to `[0, 1]` |
| `at(stops, position)` | along a run of evenly spaced stops; one stop is a solid colour |
| `wrap(stops, position)` | around a loop: the last stop blends back into the first and `1.25` is `0.25` |
| `paint(String, stops)` | a plain string, first character at the first stop, last at the last |
| `apply(Component, stops)` | the same across a component, children counted in reading order |
| `apply(Component, IntFunction<TextColor>)` | the caller picks the colour per character index |
| `length(Component)` | how many characters `apply` will colour |

Only the colour changes: decorations, hover, click, insertion and font stay
where they were. The parent's own colour is dropped, since every character now
carries its own. A character outside the basic plane counts as one. Stateless
and safe from any thread; the original component is never touched.

## Character maps

Since 1.102.0. The walk behind small capitals, opened up so a plugin can ship
its own "fonts" — letters swapped for the look-alike glyphs of another
alphabet — without copying the part that is easy to get wrong.

```java
String fraktur = CharMaps.transform(line, codePoint -> FRAKTUR.getOrDefault(codePoint, codePoint));
```

`transform(text, map)` asks the map once per code point and writes back what it
answers; the same code point means "leave it", and a line where nothing changes
is returned as the same instance. Everything that is an instruction rather than
text is copied through exactly: MiniMessage tags, `{tokens}`, `%placeholders%`,
`&l` and `&#8a51c4`. An unclosed `<`, `{` or a lone `%` is text, which is also
how MiniMessage reads it. The map may answer with a code point outside the
basic plane — fraktur, double-struck and monospace all live there — and a
surrogate pair in the input is handed to it as one code point.

## Lines written for several lines

Since 1.49.0. A description belongs next to the thing it describes, so a server
owner writes it on the one YAML line that already names that thing. Drawn as
written it is a tooltip running off the screen, so `<nl>`, a real line break, or
the literal `\n` sequence marks where it should break — one written line,
several readable ones.

The same key is a String in one file and a list in the next, because one
sentence reads as a String and five bullets read as a list. `Lines` accepts both
rather than making the owner learn which shape a key wanted:

```yaml
# Either of these, and both read the same.
description: "Hits nearby players<nl>and knocks them back."

description:
  - "Hits nearby players"
  - "and knocks them back."

# YAML double quotes turn \n into a real line break; either form is accepted.
description: "Hits nearby players\nand knocks them back."

# A literal backslash plus n is also a separator.
description: "Hits nearby players\\nand knocks them back."
```

```java
List<String> lore = Lines.read(section, "description", "lore");

// Or as one value for a menu row, expanded into lore lines as it is drawn.
entry.withFormatted("description", Lines.value(section, "description"));
```

| Method | Contract |
| --- | --- |
| `Lines.NEWLINE` | the canonical `<nl>` token |
| `read(section, keys...)` | the lines a key describes; String or list, all supported separators split, never `null` |
| `value(section, keys...)` | the same read back as one canonical `<nl>` value, or `""` |
| `split(String)` | split an already-resolved value; normalizes CRLF, LF, CR, and literal `\n`; `List.of()` for null or empty |
| `join(List)` | join lines into canonical `<nl>` format; `""` for null or empty |

Contracts:

- **Keys are tried in order and the first one the file carries answers.** That
  is how a renamed key keeps reading files written before the rename.
- **A key holding nothing falls through** to the next spelling, so an empty list
  does not shadow the key that has the text.
- **Trailing blank lines survive.** A lore block ends on a blank line on
  purpose, and dropping it closes the gap the owner asked for.
- **What comes back cannot be modified**, and is never `null`.

`<nl>`, real CRLF/LF/CR breaks, and literal `\n` written in a **file** are
normalized and split when the file is read, so they cost nothing at render time.
In a **value** the canonical `<nl>` form is split as the row is drawn — see
[menus](menus.md).

## Performance

- Text with no formatting characters skips the parser entirely.
- Everything else is parsed once and cached; re-sending the same line every
  tick is a cache lookup, not a parse. That is what makes this safe inside
  scoreboard and action bar loops.
- Concatenating values into the string breaks that cache — use `.with()`.

## What the library itself says — `messages.yml`

Some player-facing text belongs to no plugin: the gestures a guided flow asks
for, and the lines the block selector sends while somebody picks two corners.
Those describe the *library's* tools, so they live in the library's own file,
`plugins/ExyliaLib/messages.yml`, generated on first start and reloaded by
`/exylialib reload`.

```java
String prompt = LibraryMessages.get().wizard().region();
String confirmed = LibraryMessages.get().selection().confirmed();
```

Two sections today, `wizard` and `selection`. A deleted line falls back to the
Exylia default rather than reaching a player empty, and a whole section deleted
falls back the same way. A plugin's own messages file keeps everything the
plugin has an opinion about; what lands here is only what the library would
otherwise make each plugin invent — six copies of one sentence, five of which go
stale.

## Rules

- Return `Component`, not `String`.
- Colors by role (`{primary}`), not by hex, so the owner recolors everything
  from `colors.yml`.
- Adventure is the server's own: compiled against the version paper-api
  carries, pinned by `resolutionStrategy`. Compiling against a newer one
  compiles fine and then explodes in production with `NoSuchMethodError`.

## Source and tests

- Public: `text/Text.java`, `text/Colors.java`, `text/Palette.java`,
  `text/Lines.java`, `text/LibraryMessages.java`, `text/Gradients.java`,
  `text/CharMaps.java`.
- Internal: `text/internal/` (`TextEngine`, `FormatScanner`,
  `LegacyTranslator`, `TokenResolver`, `SmallText`).
- Tests: `src/test/java/net/exylia/lib/text/TextModuleTest.java`,
  `SmallTextTest.java`, `LinesTest.java`, `LibraryMessagesTest.java`,
  `GradientsTest.java`, `CharMapsTest.java`.
