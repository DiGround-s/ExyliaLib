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
| `withColored(placeholder, value)` | substitute a value a player styled (a clan name, a nickname): colours, gradients and decorations only; in a click's command it is its plain words. *Since 1.236.0.* |
| `colored(String)` | the same restricted parse as a component, in the letters written; for checking a styled name before storing it. *Since 1.399.0.* |
| `legacyRgb(Component)` | section-sign legacy that keeps hex (`§x§r§r§g§g§b§b`), for PlaceholderAPI values TAB and scoreboards read. *Since 1.399.0.* |
| `forPlayerFormatted(player)` | like `forPlayer`, but resolver values that carry formatting (a display name) are honoured instead of shown raw |
| `forPlayer(player)` | resolve `%placeholders%` for that viewer when building |
| `build()` | the final `Component` |
| `send(CommandSender)` | build and deliver |
| `plain()` / `legacy()` / `raw()` | serializers; `legacy()` is only for old APIs that still demand it |
| `wire()` / `fromWire(plugin, String)` | the text as one string for a [channel](redis.md#channels), and back; the receiving server builds it with its own prefix, palette and sound. A `forPlayer` viewer does not travel. *Since 1.155.0.* |

`Colors`:

- `get(token)` / `get(token, fallback)` — a palette token as `TextColor`.
- `apply(Palette)` — swap the palette (done by the library when `colors.yml`
  reloads).
- `names()` — every registered token.

`Palette` — the 16 Exylia roles (`primary`, `secondary`, `secondary_light`,
`letters`, `letters_black`, `error`, `error_light`, `success`, `success_light`,
`warning`, `warning_light`, `info`, `info_light`, `accent`, `neutral`,
`highlight`, `muted`), each a hex string. `error_light` (`#d4617d`) is the light
end of an error gradient; it arrived in 1.477.0 and is added to an existing
`colors.yml` with its default.

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
| `Prefixes.get(plugin)` | the prefix as stored (sealed with a reset), or `null` |
| `Prefixes.fill(message, prefix)` | the message with each `%prefix%` and the spaces after it replaced by the prefix, sealed as `set` seals it, and one space; `null` leaves it (since 1.471.0) |
| `Prefixes.release(name)` | forget it; done for you when the plugin disables |

Contracts:

- Two plugins can both use `%prefix%` in their own files and each gets its own.
- `Text.of` leaves `%prefix%` alone. Text that does not say which plugin it came
  from has no prefix to use, and guessing would be worse than showing the token.
- A plugin that never set one leaves `%prefix%` visible, so the omission is
  obvious rather than silent.
- The prefix is substituted **before** parsing and **before** centring: it
  carries its own colours, and its width counts towards a centred line.
- A prefix never styles the message after it. `set` drops trailing spaces and
  adds a `<reset>` when the prefix does not end in one (`<reset>`, `&r`, `§r`),
  so an owner's `{primary}&lX »` cannot bold every line (since 1.471.0).
- Exactly one space follows the prefix: `%prefix% text`, `%prefix%text` and a
  prefix that ends in its own space all read `X • text`. Nothing is added
  before a line break, and an empty prefix takes its space with it.
  `Prefixes.fill(message, prefix)` does the same for code that substitutes by
  hand (since 1.471.0).
- The house prefix is `{primary}&lNAME {letters_black}•<reset>`, and lines are
  written `%prefix% text`.

## A plugin's own lines: `PluginMessages` and `Values` (since 1.266.0)

A plugin that reads its lines from a messages file holds one `PluginMessages`
and builds a `Values` bag per message:

```java
messages = PluginMessages.of(this);                       // onEnable

messages.send(player, config.created(), Values.of("mine", mine.id()));
messages.send(player, config.renamed(), Values.of("old", old).putText("new", typedName));
menus.open(player, "mine_list", values.map());            // the same bag as a menu context
inputs.confirm(player, values.apply(config.deletePrompt())); // spliced into a raw string
```

- A blank line (or a `null` receiver) sends nothing: emptying a message in the
  file is how an owner turns it off. `Text#send` still sends an empty line.
- `put` is a server-written value and is parsed (`withFormatted`); `putText` is
  something a player typed and stays literal (`with`). In `apply` and `map()` a
  typed value is spliced in through `Values.inert`, which is `Text.escape` plus
  a broken `%`, so a typed `%player_ip%` is never resolved.
- `text(line, values)`, `item(line, values)` (not italic) and
  `plain(line, values)` give the same line as a `Text`, an item `Component` or
  plain text.
- `EffectConfig#filled(values)` writes a bag into a configured title, action
  bar and boss bar before they are parsed.


## Counts and their words: `Plurals` (since 1.472.0)

A line names a count as `%name%` and the word after it as `%name:forms%`, the forms split by bars, so each
translation writes its own words and order:

```yaml
slot-line: '{info}%layers% %layers:layer|layers%'      # en: 1 layer, 2 layers
slot-line: '{info}%layers% %layers:слой|слоя|слоёв%'   # ru: 1 слой, 3 слоя, 5 слоёв
slot-line: '{info}%layers% katman'                     # tr: one form, no placeholder needed
```

```java
String line = Plurals.apply(text.slotLine(), "layers", design.layerCount());
```

- `apply(line, name, count)` writes the count into `%name%` and the form that goes with it into every
  `%name:forms%`; run it before the line is parsed. A line without `%name:...%` only gets its count, so an older
  wording in a server's file keeps working.
- `form(count, forms)` is the word alone. The number of forms picks the rule: one is every count's; two are one
  and other (the first for exactly 1: English, Spanish, Portuguese, French); three are Russian's and Ukrainian's
  one, few and many (1, 21 / 2-4, 22-24 / 0, 5-20, 11-14).
- `Text` and `Values` do it by themselves (since 1.473.0): `Text.from(plugin, line).with("%amount%", amount)`,
  `Values.of("amount", n)` and a menu's row and context values pick the words for a whole number, so
  `%keys% %keys:key|keys%` in a message or a menu needs no code. `apply` is for a line filled any other way.

## Effects inside a message

A message can ask for a sound, particles or a firework, in the notation
ExyliaCommons used — existing message files work unchanged:

```yaml
level-up: "[sound:ENTITY_PLAYER_LEVELUP|0.6|1.2;particle:HAPPY_VILLAGER|8;center]{success}Well done"
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
| emoji shortcodes, `:smile:` | unchanged, so ItemsAdder, Nexo and Oraxen still swap them for their glyph (1.328.0) |
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
text-shadow: "auto"        # a quarter of each letter's own colour, as vanilla
text-shadow: "auto:0.4"    # the default: the same, keeping 40% instead
text-shadow: "auto:0.5"    # the same, keeping half
text-shadow: "#000000"     # one flat colour under every line
text-shadow: "#41dba880"   # #rrggbbaa: a colour at half strength
text-shadow: "none"        # no shadow at all, not even the client's own
text-shadow: ""            # whatever the client draws by itself
```

The alpha goes last, the way MiniMessage's own `<shadow>` tag spells it, so a
colour copied out of a gradient generator reads the same here.

`auto:0.4` is the default: the shadow follows the colour of whatever is drawn,
so a gold name casts a brown shadow and a gradient casts a gradient. Vanilla
keeps a quarter, which all but disappears against the dark of a menu or the
chat; 40% stays visible there without doubling the letter. A colour applied
after the parse gets one too. A server that wants one flat
colour under everything writes `text-shadow: "#000000"`.

A server upgrading keeps whatever is already in its file; only a fresh install
gets the new default.

| Written | Drawn |
| --- | --- |
| the line's own `<shadow:...>` | kept — the configured one is only a fallback |
| a line with no shadow of its own | the configured shadow |
| every child of that line | inherits it, the way the client inherits any style |

### auto

`auto` derives the shadow from the colour each part of the line is drawn in,
rather than giving the whole line one colour. A gradient casts a gradient:
`#41dba8` casts `#10372a`, `#4c00ff` casts `#130040` — a quarter of each
channel, which is exactly what vanilla does and what the gradient generators
write out by hand. `auto:0.5` keeps half instead, for a shadow that reads as
a glow of the letter's own colour; `auto:0` is black.

Written down so it can be changed: the value vanilla uses is not configurable
in vanilla, and every part of a line asking for its own is what makes a
gradient's shadow follow it.

Only a part that carries a colour of its own is given a shadow. A part with no
colour is drawn in whatever it inherits — from the line around it, or from
wherever it is substituted in later — and it inherits the matching shadow down
the same path. A placeholder value is parsed on its own, before it knows what
colour it will be read in, so deriving one for it there would put a grey
shadow under gold text; left alone, it casts the shadow of the line it lands
in. A line with no colour anywhere keeps the quarter-strength shadow the
client draws by itself.

The walk stops at any part that already carries a `<shadow>`: everything under
it inherits what was written there.

Anything painted **after** the parse — a gradient laid over a name, an
animation frame, a colour a player picked — has no colour to derive from while
it is being parsed. Those ask once the colour is on:

```java
Component painted = Gradients.paint("DiGround", stops);
player.sendMessage(Text.shadowed(painted));
```

Text parsed by this module is already shadowed; `Text.shadowed` is only for
components a plugin built itself, and calling it twice changes nothing.

Repainting takes the old shadow with the old colour: `Gradients.apply` over an
already-parsed line drops the shadow that was derived from the colour it is
replacing, so no character is drawn over the shadow of the colour it used to
be. A flat configured shadow, or one a `<shadow>` tag wrote, is not derived
from any colour and survives the repaint.

### What it costs

Measured on a 35-component gradient — one component per character, the worst
shape there is — against the same line with no shadow:

| | one parse, nothing cached | a cache hit |
| --- | --- | --- |
| no shadow | 10.6 µs | 0.13 µs |
| one colour | 11.9 µs | 0.12 µs |
| `auto` | 14.9 µs | 0.16 µs |

A cache hit costs the same either way, which is the case that matters: a
scoreboard line, a menu lore and a repeated message are parsed once and read
for ever after. `auto` adds about 4 µs to a parse that misses, and a plain
line with no gradient adds a tenth of that, since there is one component to
walk instead of thirty-five.

What is never cached is a colour applied after the parse — a chat message, an
animation frame. Those pay the walk each time: at ten frames a second, a
hundred players wearing an animated gradient cost about half a percent of one
core. It is the same walk the gradient painter already does, so it roughly
doubles that step and nothing else.

`shadowColorIfAbsent` is applied where the component is built, so a cached
line is shadowed once rather than on every read, and changing the value drops
the caches exactly as `small-text` and a palette change do. Flipping it and
running `/exylialib reload` restyles the server live.

On a server older than 1.21.4 the Adventure that ships with it has no shadow
colour at all. Nothing breaks: the value is ignored, and the class that names
the type is never loaded.

## Clicks from configuration

A click written in a file is built by the name of what it does:

```java
ClickEvent event = Text.click("suggest_command", "/msg Notch ");   // null for a name nothing answers to
```

`run_command`, `suggest_command`, `open_url`, `open_file`,
`copy_to_clipboard` (also `copy`), in any case.

Use this rather than a switch over `ClickEvent.Action.RUN_COMMAND`: Adventure
5 turned `Action` from an enum into a class whose constants are typed
subclasses, so a `getstatic` compiled against Adventure 4 throws
`NoSuchFieldError` the moment the server runs 5 — which is a server on
Minecraft 1.21.9 and newer. The per-action factories kept their signature
across both.

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

### Palette tokens inside a tag (since 1.477.0)

A token written as a tag's argument is the bare colour, so gradients follow the
palette like every other colour does:

```yaml
name: '<gradient:{primary}:{secondary_light}>&lRESET</gradient>'
```

`{primary}` is `<#8a51c4>` in text and `#8a51c4` inside `<gradient:…>`,
`<transition:…>`, `<color:…>` or `<shadow:…>`. A palette change repaints them.

### A gradient over a value (since 1.477.0)

A value is put in after the parse, so a gradient around `%kit_name%` used to
paint the whole name in its first colour. A gradient whose body holds a value
is now painted once the value is in, across its real letters, together with any
text written beside it:

```yaml
name: '{letters}Queue {letters_black}▸ <gradient:{info}:{info_light}>%kit_name%</gradient>'
```

Bold and every other decoration written inside it are kept. A gradient with no
value in it is still MiniMessage's own, parsed once and cached. Only a
gradient's colours are read; a phase argument is ignored when a value is
inside.

## Icons (since 1.477.0)

`{icon:...}` draws a game sprite inside a line: an item, a block, a particle or
an interface icon, eight pixels square, the way 1.21.9 draws text objects.

| Written | Draws |
| --- | --- |
| `{icon:clock}` | a named icon, in its own colours |
| `{icon:clock\|✧}` | the same icon with another stand-in glyph |
| `{icon:items:item/diamond\|♦}` | any sprite, as `atlas:id`; `items`, `blocks`, `particles`, `gui`, or a namespaced atlas in full |
| `{icon:items:item/diamond\|♦\|tint}` | the sprite in the colour around it instead of its own |

Where a sprite cannot be drawn the glyph stands in for it, in the colour around
it. Write that colour first, so the glyph is right everywhere:
`{info}{icon:clock} {letters}Played`.

- **A server older than 1.21.9** (or whose Adventure has no sprites) writes
  the glyph. `Features.sprites()` says which.
- **A client older than 1.21.9** joining a newer server through ViaBackwards
  gets the glyph too: with PacketEvents installed, ExyliaLib swaps each sprite
  on items (name and lore) on their way to that client. Without PacketEvents
  that client sees whatever ViaBackwards makes of a sprite.
- **Item textures** live in the `items` atlas from 1.21.11 and in `blocks`
  before it. `items:` is read from the right one for the server, and a
  1.21.9–1.21.10 client on a 1.21.11 server gets the glyph for item icons.

A sprite ignores bold and italic. An unknown name stays as written, like any
other brace. Icons are for item names and lore; a title or a chat line has no
per-client swap.

Named icons, with their glyph (`tinted` ones take the colour around them):

| Name | Sprite | Glyph |
| --- | --- | --- |
| `clock` | item/clock_00 | ⌚ |
| `xp` | item/experience_bottle | ✦ |
| `sword` · `kills` | item/iron_sword · item/diamond_sword | ⚔ |
| `deaths` | item/bone | ☠ |
| `heart` · `armor` · `food` | gui hud/heart/full · hud/armor_full · hud/food_full | ❤ · ⛨ · ♨ |
| `apple` · `totem` · `arrow` | item/golden_apple · item/totem_of_undying · item/arrow | ❤ · ✦ · ➹ |
| `crit` (tinted) | particle critical_hit | ✦ |
| `star` · `shard` · `key` | item/nether_star · item/amethyst_shard · item/trial_key | ✦ |
| `diamond` · `emerald` | item/diamond · item/emerald | ♦ |
| `gold` · `coin` | item/gold_ingot · item/gold_nugget | $ |
| `book` · `quill` · `tag` · `sign` | item/book · item/writable_book · item/name_tag · item/oak_sign | ✎ |
| `paper` · `map` | item/paper · item/map | ✉ |
| `compass` · `feather` | item/compass_16 · item/feather | ➤ · ➹ |
| `eye` · `pearl` · `spyglass` · `search` | item/ender_eye · item/ender_pearl · item/spyglass · gui icon/search | ◉ |
| `info` · `check` · `barrier` · `ping` | gui icon/info · icon/checkmark · item/barrier · gui icon/ping_5 | ℹ · ✔ · ✘ · ▮ |
| `pickaxe` · `redstone` · `hopper` · `iron` · `blaze` | item/diamond_pickaxe · item/redstone · item/hopper · item/iron_ingot · item/blaze_powder | ⛏ · ⚡ · ⬇ · ■ · ☀ |
| `spark` · `glint` · `note` (tinted) | particle spark_4 · glint · note | ✦ · ✧ · ♪ |
| `flame` | particle flame | ☀ |
| `click` · `right_click` | gui toast/mouse · toast/right_click | » |
| `on` · `off` | gui pending_invite/accept · pending_invite/reject | ✔ · ✘ |
| `locked` · `unlocked` | gui widget/locked_button · widget/unlocked_button | ■ · □ |
| `up` · `down` | gui statistics/sort_up · statistics/sort_down | ▲ · ▼ |
| `warning` | gui world_list/warning | ⚠ |
| `music` | gui icon/music_notes | ♪ |
| `signal_1` … `signal_5` | gui server_list/ping_1 … ping_5 | ▮ |

The interface icons (`gui` atlas) exist from 1.21.9 on; all of them were checked
on 1.21.9, 1.21.10 and 1.21.11, so none needs its glyph on a 1.21.9+ client.
Older servers and clients draw the glyph. What each is for:

- **Click lines.** `{icon:click}` opens every action line of a card, in the
  card's tone (`{primary}{icon:click} Left-click to edit`); `{icon:right_click}`
  opens a right-click line. `Lore.action` and `Lore.click` write them, choosing
  the right button when the text says right-click.
- **States.** `on` and `off` for enabled and disabled; `locked` and `unlocked`
  for permission or progress gates.
- **Trends.** `up` and `down` beside a value that rose or fell.
- **Card problems.** `warning` before a line that needs attention.
- **Level bars.** `signal_1` … `signal_5` read as five-step levels (quality,
  load, strength); `music` marks sounds and songs.
- **Time.** A time stat in item lore writes `{info}{icon:clock}` before its
  label and no trailing ⌚: `  {letters_black}· {info}{icon:clock} {letters}Cooldown{letters_black}: {info}15s`.
  Chat, action bar, boss bar, title and Discord lines keep ⌚.

## Card lore in code: `Lore` (since 1.477.0)

Lore a plugin builds in Java follows the same card layout its YAML menus do.
`Lore` writes the strings, so code and files cannot drift apart:

```java
String name = Lore.name(Lore.Tone.PRIMARY, "Mine", "%mine_name%");
List<String> lore = Lore.of(Lore.Tone.PRIMARY)
        .subtitle("Mine setting")
        .section("Information").text("How and when this mine").text("refills its blocks.")
        .section("✦", "Status").stat("clock", "info", "Interval", "%interval%")
        .click("Left-click", "edit interval")
        .lines();
```

| Method | Writes |
| --- | --- |
| `name(tone, context, subject)` | `{letters}Mine {letters_black}▸ <gradient:{primary}:{secondary_light}>Reset</gradient>` |
| `name(tone, subject)` | the subject alone, in the gradient |
| `subtitle(text)` | `{letters_black}Mine setting`, the first lore line |
| `section(label)` / `section(glyph, label)` | a blank line, then `{primary}✎ Information` |
| `text(line)` | ` {letters}…` |
| `stat(icon, colour, label, value)` | `  {letters_black}· {info}{icon:clock} {letters}Interval{letters_black}: {info}15m` |
| `action(text)` / `click(click, effect)` | `{primary}{icon:click} Left-click to edit` / `{primary}{icon:click} Left-click {letters_black}· {letters}+1` (`{icon:right_click}` for a right-click) |
| `note(text)` | `{letters_black}You will be asked to confirm.` |

`Tone` picks the colour pair: `PRIMARY` (a subject's own card, settings),
`INFO` (browsing, choosing, read-only), `SUCCESS` (create, claim, join, buy),
`HIGHLIGHT` (rankings, rewards), `WARNING` (pending, limited), `ERROR`
(destructive, leave, back), `ACCENT` (cosmetics). Words a player reads come
from the plugin's messages, as always; `Lore` only places them.

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
| `wrap(text, width)` | prose wrapped word by word to about `width` visible characters per line, joined with `<nl>`; palette tokens, `&x` codes and `<tags>` take no width, written breaks stay, a longer word gets its own line; `Lines.OMIT` for null or blank (*1.387.0*) |

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
