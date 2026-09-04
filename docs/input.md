# Input

Asking a player for something — a name, a number, a choice, a whole form — and
being told what happened, exactly once.

```java
PluginInputs inputs = Inputs.of(plugin);

inputs.text(player, "Name the arena")
      .maxLength(32)
      .open()
      .thenAccept(result -> result.ifCompleted(this::createArena));
```

The question is drawn with the best thing the player's client supports: a native
dialog window, a Bedrock form, an anvil-plus-chest search, an inventory of
buttons, or chat. The plugin writes the same four lines for all five.

Since 1.31.0.

---

## Why this is in the library

Asking a player for a name looks like it belongs in the plugin that wants the
name. It does not, because almost none of the work is about the name.

ExyliaCommons proved it. `ChatInputAPI` has **191 builder calls across 8
plugins**, plus **8 separate `ChatInputAPI.init(...)` calls** — every plugin
initialising the same shared machinery and hoping the order worked out. What
those call sites bought was a chat prompt and two callbacks, and what they paid
for it was:

- **Four parsers that disagreed.** Commons parsed and range-checked inside the
  chat handler, the Floodgate handler and the dialog handler separately. The
  same text was accepted in one and refused in another, and fixing the range
  check in one left the other two wrong.
- **Endings that never arrived.** There were two callbacks, `onResponse` and
  `onCancel`. A request replaced by a newer one ran **neither**, so a menu that
  reopened itself in `onCancel` simply never reopened, and the player was left
  looking at nothing.
- **No timeout at all.** A player who walked away stayed in a half-finished
  question until they logged out, and the plugin waited for an answer that was
  never coming.

Every one of those is a property of *asking*, not of the thing being asked
about. Written once, they are written correctly once.

---

## `Inputs.of(plugin)`, not static

```java
PluginInputs inputs = Inputs.of(plugin);
```

Cached by exact Bukkit plugin name, and every request carries that name into its
session. That ownership is the whole point: when a plugin is disabled,
`Inputs.release(name)` ends its pending requests with `SHUT_DOWN` and completes
their futures, so a callback holding a disabled plugin's classloader cannot keep
it alive.

`Inputs` itself has four static operations, and they are about a player rather
than a request:

| Method | |
| --- | --- |
| `Inputs.of(plugin)` | the request factory owned by a plugin |
| `Inputs.cancel(player)` | end the player's current request as `CANCELLED` |
| `Inputs.hasActive(player)` | whether any plugin is currently asking them something |
| `Inputs.release(pluginName)` / `Inputs.releaseAll()` | lifecycle; the library calls these |

---

## The requests

Every one of these is a builder. `open()` returns a
`CompletionStage<InputResult<T>>`; `open(consumer)` does the same and runs the
consumer only when there is an answer.

| `PluginInputs` method | Produces | For |
| --- | --- | --- |
| `text(player, prompt)` | `String` | free-form text |
| `id(player, prompt)` | `String` | a strict lowercase identifier |
| `slug(player, prompt)` | `String` | an identifier derived from display text |
| `integer(player, prompt)` | `Long` | whole numbers |
| `decimal(player, prompt)` | `BigDecimal` | exact decimals |
| `amount(player, prompt)` | `BigDecimal` | money, as a player writes it |
| `duration(player, prompt)` | `Duration` | `30s`, `1h30m`, `1d`, `2.5h` |
| `flag(player, prompt)` | `Boolean` | a setting or switch |
| `confirm(player, prompt)` | `Boolean` | an explicit confirmation |
| `choice(player, prompt, choices)` | `T` | one of a few options |
| `search(player, prompt, choices)` | `T` | one of very many options |
| `search(player, prompt)` | `T` | one of a catalogue too large to hold |
| `form(player, prompt)` | `FormValues` | several things in one window |
| `icon(player, prompt)` | `String` | what something is drawn as |

### Modifiers every request has

From `InputRequest`, so they read the same whatever is being asked:

| Method | |
| --- | --- |
| `timeout(Duration)` | how long the player has; must be positive |
| `defaultValue(T)` | what a transport offers when the player types nothing |
| `validate(Predicate<T>, String message)` | a constraint checked after parsing |
| `transform(UnaryOperator<String>)` | changes the raw text before parsing |
| `transports(TransportKind...)` | restricts and orders the fallbacks |
| `open()` / `open(Consumer<? super T>)` | shows it |

`validate` and `transform` live here rather than in each transport, and that is
what stops case folding from working in chat but not in a dialog.

### Room to read, and the value already in the box

Since 1.56.0. Two settings that turn "answer this" into "correct this":

```java
inputs.text(player, "{primary}New display name")
      .defaultValue(item.displayName())   // prefilled, ready to edit
      .lines(4)                           // four lines tall, not one
      .open(name -> item.rename(name));
```

`defaultValue` is what a dialog puts **in** the box, so editing a name is
correcting it rather than retyping thirty characters of colour tokens from
memory. It was always there and simply never used.

`lines` asks for a taller box. A one-line dialog field shows about twenty
characters, which is editing a display name or a command blind. It is a hint: a
transport with no notion of height — chat — ignores it, and a one-line box never
refused a long answer to begin with. `FormField.lines(int)` is the same setting
per field, so a form can hold a one-line id next to a five-line lore.

### Saying what a valid answer looks like

Since 1.60.0. A label names the field; a hint answers what the label leaves
open.

```java
inputs.text(player, "{primary}Command the console runs")
      .hint("%player_name% is the player. No leading slash.")
      .open(command -> reward.command(command));
```

`FormField.hint(String)` is the same setting per field, and `EditorForm.hint`
attaches one to the field just added. `Command the console runs` does not say
whether the player is `%player%` or `%player_name%` — both work in
`NamedCommands` and in `CommandLine` — nor whether a slash belongs in front, and
a wrong guess is found later, in a reward that silently does nothing.

Where it is drawn belongs to the transport: a Bedrock input has a real
placeholder and uses it, a dialog draws the hint muted under the label, chat
sends it as its own line, and a transport with nowhere to put it drops it.

### Modifiers particular types add

| Type | Method | |
| --- | --- | --- |
| `TextInput` | `maxLength(int)`, `minLength(int)`, `lines(int)`, `hint(String)` | lengths counted in Unicode code points, not chars; `lines` asks a transport for a taller box; `hint` says what a valid answer looks like |
| `NumberInput<T>` | `range(min, max)`, `min(T)`, `max(T)` | inclusive; an inverted range throws |
| `AmountInput` | `minimum(BigDecimal)`, `maximum(BigDecimal)` | inclusive |
| `DurationInput` | `atLeast(Duration)`, `atMost(Duration)` | inclusive; a negative bound throws |
| `ConfirmInput` | `confirmLabel(String)`, `denyLabel(String)`, `dangerous()` | `dangerous()` lets a transport use danger styling |
| `ChoiceInput<T>` | `label(fn)`, `key(fn)`, `icon(fn)` | |
| `SearchInput<T>` | `label(fn)`, `key(fn)`, `icon(fn)`, `iconItem(fn)`, `source(Pages<T>)`, `pageSize(int)`, `matcher(BiPredicate<T,String>)` | `iconItem` draws a built stack instead of a material; `source` fetches results a page at a time |

`ChoiceInput` and `SearchInput` answer with **the element itself**, not a key
the caller has to look up again:

```java
inputs.choice(player, "Pick a kit", kits)
      .label(Kit::displayName)
      .key(Kit::id)
      .icon(Kit::icon)
      .open(kit -> give(player, kit));   // kit is a Kit
```

`key` must produce unique values. Two buttons submitting the same raw key would
make the resolved object depend on collection order, so a duplicate is an
`InputException` at open time rather than a coin flip in production.

---

## `InputResult` and the seven outcomes

```java
inputs.integer(player, "How many slots?")
      .range(1L, 64L)
      .open()
      .thenAccept(result -> result
          .ifCompleted(this::resize)
          .otherwise(outcome -> {
              if (outcome.byPlayer()) reopenMenu(player);
          }));
```

| Method | |
| --- | --- |
| `completed()` | whether there is an answer |
| `value()` | the answer; throws `NoSuchElementException` when there is none |
| `orElse(T)` | the answer or a fallback |
| `optional()` | the answer as an `Optional<T>` |
| `outcome()` | why it ended |
| `ifCompleted(Consumer)` | run only when answered; returns `this` |
| `otherwise(Consumer<InputOutcome>)` | run only when not; returns `this` |
| `map(Function)` | convert the answer, keep the outcome |

**Exactly one outcome is delivered exactly once per request**, whatever happened.

| Outcome | What it means | Why it is not merged into another |
| --- | --- | --- |
| `COMPLETED` | answered, parsed and validated | |
| `CANCELLED` | the player said no: the cancel word, the cancel button, closing the window | |
| `TIMED_OUT` | nobody answered in time | **Commons had no timeout.** A player who walked away was pending forever, and the caller waited forever with them |
| `REPLACED` | a newer request for the same player took over | **Commons ran no callback at all here.** A menu that reopens itself on cancel never reopened. It is also not a cancel: a cancel is the player changing their mind, a replace is the plugin changing its. Reopening a menu on a replace is how two menus end up fighting over the screen |
| `DISCONNECTED` | the player left | |
| `UNAVAILABLE` | nothing could ask: the player was offline, or every transport declined | Never a silent nothing — a command that is waiting gets told, so it can answer instead of hanging |
| `SHUT_DOWN` | the owning plugin was disabled, or the server is stopping | Distinct from a cancel so a caller does not try to save what it was asking about while its own plugin is being torn down |

`InputOutcome` carries the two questions callers actually ask:

- `hasValue()` — true only for `COMPLETED`.
- `byPlayer()` — true for `CANCELLED` and `DISCONNECTED`. This is the one to
  branch on when deciding whether to reopen the menu the request came from: a
  player who cancelled wants to go back; a request that timed out or was
  replaced should leave the screen alone.

---

## Forms

The headline feature, and the one Commons could not do at all. A form collects
**several things of different types in one window**, validates them together,
and reports every problem at once.

```java
static final FormKey<String>     NAME     = FormKey.text("name");
static final FormKey<Long>       MIN      = FormKey.integer("minPlayers");
static final FormKey<Long>       MAX      = FormKey.integer("maxPlayers");
static final FormKey<BigDecimal> REWARD   = FormKey.decimal("reward");
static final FormKey<Boolean>    RANKED   = FormKey.flag("ranked");
static final FormKey<Duration>   COOLDOWN = FormKey.duration("cooldown");

inputs.form(player, "Create an arena")
      .text(NAME, "Name")
      .integer(MIN, "Minimum players")
      .integer(MAX, "Maximum players")
      .amount(REWARD, "Reward")
      .flag(RANKED, "Ranked")
      .duration(COOLDOWN, "Cooldown")
      .validate(values -> values.getLong(MIN) <= values.getLong(MAX)
              ? Validation.ok()
              : Validation.error(MAX, "The maximum cannot be below the minimum"))
      .submitLabel("Create")
      .open(values -> arenas.create(
              values.get(NAME),
              values.getLong(MIN),
              values.getLong(MAX),
              values.getDecimal(REWARD),
              values.getBoolean(RANKED),
              values.getDuration(COOLDOWN)));
```

One window. Text, two numbers, an amount, a switch and a duration, each parsed
into its own type. In Commons this was six chat prompts in a row, and the sixth
answer arrived with no memory of the first.

### `FormKey<T>` — declared once, used at both ends

The alternative is a `Map<String, Object>`, where reading a field is a cast and
a guess, and where a key spelled `"minPlayers"` going in and `"min_players"`
coming out compiles perfectly and fails on the server. A `FormKey` is written
once and used at both ends, so the compiler checks the type and the name.

| Factory | Type |
| --- | --- |
| `FormKey.text(name)` | `String` |
| `FormKey.integer(name)` | `Long` |
| `FormKey.decimal(name)` | `BigDecimal` |
| `FormKey.flag(name)` | `Boolean` |
| `FormKey.duration(name)` | `Duration` |
| `FormKey.of(name, Class<T>)` | anything |

### Building the form

| `FormInput` method | |
| --- | --- |
| `text` / `integer` / `decimal` / `amount` / `flag` / `duration` `(key, label)` | add a field in display order |
| `field(FormKey<T>, FormField<T>)` | add a field configured further |
| `validate(Function<FormValues, Validation>)` | a rule spanning fields |
| `timeout(Duration)` | positive |
| `transports(TransportKind...)` | |
| `submitLabel(String)` | text of the final action |
| `open()` / `open(Consumer<? super FormValues>)` | |

A `FormField` adds the per-field controls: `optional()`, `required()`,
`defaultValue(T)`, `validate(Predicate<T>, String)`. Its `Kind` — `TEXT`,
`INTEGER`, `DECIMAL`, `AMOUNT`, `DURATION`, `FLAG`, `CHOICE` — is what a
transport reads to pick a control, and it is semantic rather than tied to any
one client protocol.

### Two levels of validation, and why they run in that order

**Per field** is a predicate on one value. **Cross field** is a rule that no
single field is wrong about on its own — a maximum below the minimum is a
perfectly good number and a perfectly good other number.

Cross-field rules run **only after every field parsed**. If they ran anyway,
the rule would call `values.getLong(MIN)` on a field that failed to parse, and
throw inside the library instead of reporting the real problem. Tested by
`FormInputTest.crossFieldRunsOnlyWhenFieldsParsed`.

A cross-field rule that throws is caught and reported as
`"The form could not be validated."` — a bug in a caller's lambda must not
escape onto the player's thread.

### Every error at once

```java
form.parseRaw(Map.of("name", "Boxing", "minPlayers", "two", ...));
```

returns either a `FormValues` or a `Validation` — never a half-filled object.
When it is a `Validation`, **every bad field is named**, keyed by field name, so
the transport can draw each message next to the box that caused it.

Reporting one error, waiting for a correction, then reporting the next is how a
six-field form takes six attempts to fill in. `FormInputTest.allErrorsAtOnce`
feeds five bad fields and asserts five errors come back together.

### Values are preserved when the form is re-shown

A rejected submission is re-shown with what the player already typed still in
the boxes. Clearing them would punish somebody for one typo by making them
retype five correct fields.

### Reading the answers

`FormValues` is immutable and typed:

| Method | |
| --- | --- |
| `get(FormKey<T>)` | the value; `InputException` naming the field if it was never declared |
| `getOr(FormKey<T>, T fallback)` | for optional fields |
| `has(FormKey<?>)` | whether it was answered |
| `getText` / `getLong` / `getDecimal` / `getBoolean` / `getDuration` | typed shorthands |
| `asMap()` | read-only, for logging and iteration |

An optional field left blank is **absent**, not empty, so "not answered" and
"answered with nothing" stay different things.

---

## Search

For choosing one item out of a registry: every sound, every particle, every
material. A menu of buttons does not work at 1,500 options, and neither does a
chat prompt that expects the player to spell `block.note_block.pling` correctly.

```java
inputs.search(player, "Pick a sound", List.of(Sound.values()))
      .label(sound -> sound.getKey().getKey())
      .icon(sound -> Material.NOTE_BLOCK)
      .open(sound -> config.setSound(sound));
```

The player types into an anvil's rename box; the matches appear in a chest
window and refilter as they type.

### A catalogue instead of a collection

`source(Pages<T>)`, since 1.82.0, answers the same window from somewhere else —
a table, an HTTP API, anything with its own index — one page at a time:

```java
inputs.<Head>search(player, "{warning}Browse a head")
      .source((query, offset, limit) -> catalogue.fetch(query, offset, limit))
      .label(Head::name)
      .iconItem(Head::item)
      .open(head -> save(head.icon()));
```

| | Snapshot (`search(player, prompt, choices)`) | Paged (`source(...)`) |
| --- | --- | --- |
| Held in memory | every option, indexed at open | the page on screen |
| Matching | lowercased strings, locally | whoever answers the page |
| Count while typing | live, per keystroke | on confirm, with the page |
| Fetches | none | one per query and per page turn |

What that buys and what it costs:

- **Nothing is loaded until somebody searches**, and a request that is never
  opened costs nothing at all.
- **The count in the anvil is not live.** Counting a catalogue is a request of
  its own, and one per keystroke is not a price a text box pays, so the confirm
  button says what it does rather than what it found.
- **A paged request is pinned to `ANVIL_SEARCH`**, because a chat prompt has no
  page to turn. `source()` sets that preference itself.
- **A page that cannot be fetched says so** in the window and leaves the query
  in place, so searching again retries.
- Answers are applied on the thread that owns the window, and a slow answer to
  a query the player has already replaced is dropped rather than drawn.

`iconItem(fn)` exists for the same reason: a catalogue of heads is forty-five
`PLAYER_HEAD`s, so the material says nothing and the texture says everything.
The stack the function returns is the row's base; name and lore are written onto
it. Without it, rows are drawn from `icon(fn)` as before.

### Results live in the chest's TOP inventory, never the player's

The obvious layout is "type in the anvil, list the matches in the player's own
inventory grid". It is **destructive**, and this transport is shaped
specifically to make it impossible.

Verified against paper-api 1.21.4: `InventoryView#setItem(int)` takes a **raw**
slot, and every index past the top inventory addresses the player's real
`PlayerInventory`. Writing a result icon there does not paint a decoration —
it **overwrites the item the player was carrying in that slot**, and the
overwritten stack is gone the moment the write lands, with nothing to restore it
from. Snapshotting the 36 slots first does not fix it either: the window can end
without a close event anyone gets to act on (a crash, a kick during shutdown, a
world unload), and every one of those endings leaves the player holding icons
instead of their inventory.

The other tempting variant — leaving the real inventory alone and sending fake
`SET_SLOT` packets for the bottom 36 slots — is unsafe for a different reason.
Those slots stay genuinely clickable and genuinely server-authoritative: the
client believes the fake stack is real, sends a click carrying it, and the
server answers from the true contents. **Packet-faking those slots cannot
guarantee items are never lost**, because that desync sits in the one place a
desync costs items, and neither the container state id nor a cancelled click
closes the gap between "the client thinks it is holding a result icon" and "the
server thinks it is holding a diamond".

So: **results live in a plugin-owned chest top inventory, the anvil is used only
as a text field, and every click and drag in either window is cancelled before
anything else runs.** No code path in this transport reads or writes
`Player#getInventory()`.

### The anvil query item is cleared before every close

An anvil's rename box is inert unless top slot 0 holds an item, so the transport
puts a disposable, plugin-created stack there. Vanilla **returns anvil input
items to the player when the view closes** — left alone, that hands the player a
free item every single search.

All three anvil slots are therefore cleared **before** the window is closed, on
every ending: the player confirming, the player closing, the session ending for
any reason, disconnect, plugin disable and server stop. The clearing runs
through the runtime's terminal cleanup, so it also covers the endings that never
produce a close event anyone handles. It is unconditional and safe to run twice,
because skipping it once is a free item.

### Cost

Each element's search text is lowercased **once when the request opens**, so a
keystroke never re-derives a label or calls a caller-supplied function per
element. A redraw builds `ItemStack`s for the visible page only: a 5,000-element
registry costs one page of item construction per keystroke, not 5,000.
`SearchInputTest` asserts both — that labels are not recomputed per keystroke,
and that it scales to a real registry.

---

## Icons

What an arena, a kit, a warp or a reward is drawn as. The answer is a
`material` value — the same string a menu file writes, read by the same grammar
([items.md](items.md)) — so it goes straight into a column and straight into
`material: "%arena_icon%"`.

```java
inputs.icon(player, "{warning}Choose an icon")
      .open(icon -> arenas.save(arena.withIcon(icon)));
```

One question, four answers, because an icon is four different things:

| Way | What the player does | What is stored |
| --- | --- | --- |
| `MATERIAL` | the search picker, over every item the server has | `DIAMOND_SWORD` |
| `INSERT` | puts the item in a slot | its material name, or `bytes:` when it carries meta |
| `HEAD` | pastes a head string | `playerhead-Notch`, `basehead-…`, `urlhead-…` |
| `BROWSE` | searches the head catalogue by name | `urlhead-<texture>` |

A material is chosen from a list nobody can spell from memory, a custom item is
easiest to point at by putting it down, and a head can only be pasted.
ExyliaCommons had a menu of its own for each of those, in every plugin that
needed one.

**`BROWSE` is for the head nobody has the base64 of yet**, added in 1.82.0: the
same picker again, over the catalogue described in [heads.md](heads.md), with
real textures drawn in every row. It needs the server to reach the catalogue;
when it cannot, that window says so and the other three ways are untouched.

**`wholeItem()` stores the inserted item as it is**, since 1.111.0, name and
lore included. Without it the answer is an *icon*, and an icon drops the name
and the lore because whatever draws it writes its own — which is right for a
menu row and wrong for the item a reward hands over. Raise `maxLength` with it:
512 is what an icon column allows, and a written item is longer than that.

**`INSERT` replaced reading the player's main hand**, in 1.59.0. Holding the
item meant closing whatever screen you were on, finding it, holding it and
reopening — and from inside a menu it could not be done at all. A window with
one slot works from inside the screen that asked.

The item is described, never taken: whatever is in the slot **goes back to the
player on every ending** — confirming, closing the window, leaving the server,
the plugin being disabled — and what will not fit is dropped at their feet. An
icon picker that ate a diamond sword would be a theft, not a feature. Every
other slot in the window is a screen, so only that one accepts a click or a
drag.

| Method | |
| --- | --- |
| `ways(Way...)` | which ways are offered, in that order; **one way skips the question** |
| `maxLength(int)` | how long an answer may be; 512 by default |
| `timeout(Duration)` | how long the player has, per step |

`maxLength` defaults to 512 because that is what every table storing an icon
allows. A serialised item can be longer, and finding that out at the database is
finding out after the screen already said yes.

Endings pass through unchanged, so a timeout stays a timeout and a caller that
reopens its menu on a cancel keeps working. A window closed with nothing in the
slot ends as `CANCELLED`: there is nothing to ask again, the slot is either
filled or it is not.

---

## The five transports

Tried in this order. The first that can represent the request and returns
`true` wins; a decline or an isolated failure falls through to the next.

| Order | `TransportKind` | Chosen when | Can represent |
| --- | --- | --- | --- |
| 1 | `DIALOG` | `prefer-dialogs` is on, PacketEvents is loaded, and the client is **1.21.6 or newer** | everything except `SearchInput`; a choice is one button per option, up to 12 of them |
| 2 | `BEDROCK` | the player is on Bedrock and Floodgate is installed and enabled | forms and single requests, except `SearchInput` |
| 3 | `ANVIL_SEARCH` | the request is a `SearchInput` | searchable choices only |
| 4 | `MENU` | an inventory of buttons can express it | `ChoiceInput`, `ConfirmInput`, `FlagInput` |
| 5 | `CHAT` | always | every request shape, including forms asked one field at a time |

Chat is the universal fallback: every online Java player can answer in chat even
when no packet or bridge integration exists.

A request can narrow and reorder this with `.transports(...)`. An empty call
restores the runtime order, and duplicates are collapsed so a transport never
receives contradictory preference data.

### Both optional dependencies are confined

Neither PacketEvents nor Floodgate is required, and the library loads without
them.

- Transports are **discovered reflectively** by class name. A transport whose
  dependency is missing fails to construct and is simply absent from the list.
- Every PacketEvents descriptor is confined to `DialogPackets`, behind
  `DialogTransport`. If the descriptors were on the transport class itself, the
  JVM could try to resolve a PacketEvents method while discovering built-ins and
  fail *before* chat or menu fallback was ever attempted.
- Floodgate is reached reflectively in `Bedrocks` (detection) and
  `BedrockForms` (form construction), and its methods are resolved once rather
  than per capability check.

`prefer-dialogs: false` is checked **before anything else** in the dialog
transport. A setting that is parsed and then ignored is worse than no setting:
an owner turns it off, watches nothing change, and reports the plugin as broken.

---

## Parsing

**One parser per type, shared by every transport.** Transports collect raw
strings and nothing else; `InputParser` is where text becomes a value.

The practical consequence: **`10M` means ten million in a dialog, in chat and in
a Bedrock form.** Commons parsed in four places and they diverged — the same
text accepted in one handler and refused in another, and a fix to the range
check in one leaving the other two wrong.

| Parser | Accepts | Notes |
| --- | --- | --- |
| `text()` | anything | unchanged |
| `integer()` | `10k`, `2,500`, `64` | a fraction is **refused**, not truncated |
| `decimal()` | exact decimals | a `BigDecimal`, never a `double` |
| `amount()` | `10M`, `1.5k`, `2,500` | the same reader `/pay` uses; `1,5` is refused as ambiguous |
| `flag()` | `yes`, `y`, `on`, `1`, `enable`, `si`, `sí`, and the negatives | generous on purpose: nobody typing `y` meant no |
| `duration()` | `30s`, `5m`, `1h30m`, `2d`, `500ms`, `1w`, `1mo`, `1y`, `2.5h` | a bare number is **seconds**; every unit `TimeFormats` writes reads back (since 1.87.0) |
| `id()` | strict identifier | spaces become `_`, case folds; a stray `!` is **reported** |
| `slug()` | forgiving identifier | anything that is not an id is **dropped** |

Every value goes through one pipeline — `InputRequest.parseRaw` — which applies
`transform`, then the parser, then the validations, in that order. A transform
or predicate that throws is turned into a rejection, so player-controlled input
can never escape a transport callback as an exception.

Three refusals worth knowing:

- **`integer` refuses `1.5`** rather than truncating. Somebody typing `1.5` for
  a slot count meant something, and giving them one is not it. The message
  distinguishes "not a number" from "not a whole number", because those send a
  player to two different corrections.
- **`amount` refuses `1,5`.** Fifteen tenths in Europe, fifteen elsewhere;
  guessing transfers the wrong amount.
- **`id` refuses `arena!`** instead of quietly returning `arena`. Silently
  dropping the `!` is how somebody creates a second arena they cannot tell from
  the first. `slug` drops it, because a caller asking for a slug has already
  decided that whatever survives is the answer.

`id` and `slug` fold case with `Locale.ROOT`, not the host's. **ExyliaCommons
had this bug**: in a Turkish locale `toLowerCase` maps `I` to a dotless `ı`, so
the same name typed on two servers became two different ids.

---

## `input.yml`

Generated at `plugins/ExyliaLib/input.yml` on first start, reloaded by
`/exylialib reload` alongside the palette, `formats.yml` and `economy.yml`. Keys
are kebab-case, as everywhere in the config module.

```yaml
# How Exylia plugins ask you for things: a name, a number, a choice.
#
# Every plugin uses this file, so a change here applies everywhere.
#
# Run /exylialib reload after editing. No restart is needed.

# How long a question waits for an answer, in seconds.
# A player who walks away should not stay stuck in a
# half-finished form forever, and the plugin that asked
# should not wait for an answer that is never coming.
# Set it high enough for somebody to read and think.
timeout-seconds: 60

# What a player types in chat to stop being asked.
# Only used when the question was asked in chat: a dialog,
# a form and a menu all have their own cancel button.
cancel-word: cancel

# Whether to use the client's own dialog windows when it
# supports them (Minecraft 1.21.6 and above).
# These are proper windows with real text boxes, and they
# can ask several things at once instead of one question
# per chat line. Turn it off to send everything to chat and
# menus, which is how it looked before dialogs existed.
prefer-dialogs: true

# Layout version of this file. ExyliaLib uses it to upgrade the file automatically.
# Do not edit.
config-version: 1
```

| Key | Default | |
| --- | --- | --- |
| `timeout-seconds` | `60` | how long a question waits before giving up |
| `cancel-word` | `cancel` | what a player types in chat to stop being asked |
| `prefer-dialogs` | `true` | whether to use the client's native dialog windows |

`timeout-seconds` is copied into **newly created** builders. A reload cannot
move a deadline that is already running, so a player halfway through a form does
not lose it because somebody edited a file.

### Why `bedrock-prefix` is in `config.yml`, not here

It is the one setting a reader expects here and will not find. It lives in
`plugins/ExyliaLib/config.yml` as `bedrock-prefix`, deliberately.

The prefix says **which players are on Bedrock**, and that is a fact about the
server's players rather than about asking them questions. A scoreboard, a
tablist, a name formatter and a menu that adapts to the client all need the same
answer. Putting it in `input.yml` would make every one of them read the input
module's configuration to find out something that has nothing to do with input —
or, worse, keep a second copy that drifts.

It is a fallback in the first place: Floodgate is asked authoritatively when it
is installed, and the prefix only classifies players on installations that
expose Bedrock users through Geyser without Floodgate. An empty prefix disables
the fallback rather than matching every name, because treating "no prefix" as
"everybody" would route all Java players into forms their clients cannot show.

---

## Threading and lifecycle

The contract, in five statements:

1. **Exactly once.** A session holds one atomic terminal slot; only the thread
   that changes it from `null` wins. A dialog response followed by its own close
   packet delivers `COMPLETED` and not also `CANCELLED`.
2. **On the player's thread.** Packet listeners and async chat may complete a
   session from their own thread, but the result — and every callback chained to
   it — is delivered through `runAtEntity` on the player's owning thread. A
   caller's `thenAccept` may touch Bukkit. If the player is gone, delivery uses
   the global scheduler, because there is no entity thread left to target.
3. **The timeout is cancelled on every ending**, not only on the answer. It is
   cancelled in the runtime's terminal cleanup, so a completed, cancelled,
   replaced or disconnected request leaves no task behind. A session may
   schedule only one timeout task, and trying to schedule a second is an error
   rather than a leak.
4. **One request per player.** Opening a second ends the first as `REPLACED`,
   and the map is swapped **before either is displayed**, so even two racing
   submissions cannot strand the older future.
5. **Nothing outlives its owner.** A quit ends the request as `DISCONNECTED`; a
   plugin disable ends that plugin's requests as `SHUT_DOWN`; server shutdown
   ends all of them. In each case the transport is closed first — which is what
   guarantees the anvil is emptied — and then the future completes.

Chat deserves a note: the Paper chat event is asynchronous. The listener cancels
it immediately, copies only its plain text, and schedules parsing and completion
onto the player's thread. That is what prevents both public-chat leakage of an
answer and unsafe Bukkit access from the chat thread.

Transport failures are isolated. A transport that throws while showing is logged
and the next one is tried; a transport that throws while closing is logged as a
contract violation and delivery continues; a **caller's** callback that throws is
logged and does not corrupt the runtime.

---

## What throws, and what is returned

The line is: **a caller's bug throws, a player's behaviour is returned.**

`InputException` is thrown for things that should fail loudly during
development, never on a live server:

| Thrown for |
| --- |
| a null plugin, player, prompt, parser or predicate; a blank prompt or plugin name |
| a zero or negative timeout |
| a range whose minimum is above its maximum; a negative duration bound; a negative length; a non-positive `pageSize` |
| a choice or search with no options; duplicate choice keys; a choice key that cannot be normalised; a null label or icon |
| a form with no fields; two fields under one key; a field whose key does not match the one it is registered under |
| reading a `FormValues` field the form never declared; reading a field as the wrong type |

Everything a player can cause is an `InputResult`:

| Returned for |
| --- |
| text that does not parse — reported to the player, who tries again |
| a value that fails a validation — same |
| cancelling, closing the window, or typing the cancel word → `CANCELLED` |
| running out of time → `TIMED_OUT` |
| logging out → `DISCONNECTED` |

A player typing nonsense is the expected use of a text box, not a bug. The one
exception on the result side is `InputResult.value()`, which throws
`NoSuchElementException` when there is no answer — check `completed()` first, or
use `orElse`, `optional()` or `ifCompleted`.

---

## Migrating from ExyliaCommons

`ChatInputAPI` has **191 builder calls across 8 plugins**, plus **8
`ChatInputAPI.init(...)` calls**.

| Commons | Calls | ExyliaLib |
| --- | --- | --- |
| `ChatInputAPI.text(p, prompt)` | 110 | `inputs.text(p, prompt)` |
| `ChatInputAPI.integer(p, prompt)` | 41 | `inputs.integer(p, prompt)` |
| `ChatInputAPI.id(p, prompt)` | 21 | `inputs.id(p, prompt)` |
| `ChatInputAPI.decimal(p, prompt)` | 14 | `inputs.decimal(p, prompt)` — now a `BigDecimal`, not a `double` |
| `ChatInputAPI.option(p, prompt)` | 3 | `inputs.choice(p, prompt, options)`, or `search` for a long list |
| `ChatInputAPI.numbers(p, prompt)` | 2 | `inputs.form(p, prompt)` — heterogeneous, not numbers only |
| `ChatInputAPI.bool(p, prompt)` | 0 | `inputs.flag(p, prompt)` |
| `ChatInputAPI.confirm(p, prompt)` | 0 | `inputs.confirm(p, prompt)` |
| `ChatInputAPI.init(plugin)` | 8 | **deleted** — `Inputs.of(plugin)`, no initialisation |
| `ChatInputAPI.cancel(p)` | | `Inputs.cancel(p)` |
| `ChatInputAPI.hasActiveSession(p)` | | `Inputs.hasActive(p)` |

Builder methods:

| Commons | ExyliaLib |
| --- | --- |
| `.validator(predicate)` | `.validate(predicate, "message")` — a message is now required, because a rejection with no reason is a player guessing |
| `.range(min, max)` | `.range(min, max)` — still inclusive |
| `.maxLength(n)` | `.maxLength(n)` — now counted in code points |
| `.onResponse(consumer)` | `.open(consumer)`, or `.open().thenAccept(...)` |
| `.onCancel(runnable)` | `.otherwise(outcome -> ...)` — and now it tells you *why* |
| `.forceChat()` | `.transports(TransportKind.CHAT)` |
| `.forceTitle(text)` | dropped; the prompt is the prompt |
| `.searchable()` / `.pageSize(n)` on options | `inputs.search(...)` / `.pageSize(n)` |
| `.ask()` | `.open()` |

Three things change shape rather than name, and each is worth checking at the
call site:

**`onResponse` handed `IntegerBuilder` and `DecimalBuilder` a `Consumer<Number>`.**
Requests are now typed: `integer` gives a `Long`, `decimal` and `amount` give a
`BigDecimal`. Any call site doing `number.doubleValue()` on money should stop —
that is the conversion `BigDecimal` exists to avoid.

**`onCancel` was every non-answer.** It now splits into five outcomes. Code that
reopened a menu in `onCancel` should reopen on `outcome.byPlayer()` only —
reopening on `REPLACED` is what makes two menus fight over the screen, and
reopening on `SHUT_DOWN` reopens a menu belonging to a plugin that is being torn
down.

**`numbers` collected numbers into a `Map<String, Number>`.** `form` collects
anything into typed `FormValues`, so the two call sites using it can ask for a
name and a switch in the same window instead of a second prompt afterwards.

---

## Where the code is

| | |
| --- | --- |
| Public API | `input/Inputs`, `PluginInputs`, `InputRequest`, `TextInput`, `NumberInput`, `AmountInput`, `DurationInput`, `FlagInput`, `ConfirmInput`, `ChoiceInput`, `SearchInput`, `IconInput`, `FormInput`, `FormField`, `FormKey`, `FormValues`, `InputResult`, `InputOutcome`, `Validation`, `InputParser`, `InputException`, `InputSettings` |
| Internal | `input/internal/` — `InputRuntime`, `InputSession`, `Transport`, `TransportKind`, `InputListener`, `DialogTransport`/`DialogPackets`, `BedrockTransport`/`Bedrocks`/`BedrockForms`, `SearchTransport`/`SearchView`, `MenuTransport`, `ChatTransport` |
| Lifecycle | `ExyliaLib` — `input.yml` read and applied at enable, re-applied on `/exylialib reload`; sessions ended on quit, on plugin disable and on shutdown |
| Tests | `src/test/java/net/exylia/lib/input/` — `InputParserTest`, `FormInputTest`, `SearchInputTest`, `IconInputTest`, `internal/InputSessionTest` |
