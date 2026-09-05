# AGENTS.md — ExyliaLib

Project doctrine. Read this before touching code or designing a new module. If
something you are about to write contradicts this document, either the document
is wrong and needs to be changed with an argument behind it, or the code is
wrong.

---

## What ExyliaLib is

High-performance shared library for the Exylia plugins.

- **It is not shaded.** It lives on the server exactly once, as a plugin, and
  plugins consume it with `compileOnly`. One copy, one class, one cache.
- **It is not a feature plugin.** It adds no commands, items or game mechanics.
  It gives infrastructure to other plugins.
- **It is open source.** Third parties read it. The public API and its
  documentation are product, not internal notes.

Repository: <https://github.com/DiGround-s/ExyliaLib>
Coordinate: `com.github.DiGround-s:ExyliaLib`

### Guiding goal

Make the server process as little as possible. Concretely, and in order of real
impact:

1. **Never block the main thread.** It is the scarce resource; that is where TPS
   is lost.
2. **Don't ask the server to hold state that doesn't need to be state.** When
   something can be solved with a packet to the client instead of real entities
   or blocks, it is solved with packets (PacketEvents).
3. **Don't repeat work.** Cache in memory before querying again.

What optimizing is **not**: micro-optimizing paths that already run off the main
thread, or adding layers to save nanoseconds where the real cost is I/O. See
*Settled decisions*.

---

## Stack

| | |
| --- | --- |
| Java | 21 |
| API | paper-api 1.21.4 (`compileOnly`) |
| Platforms | Spigot, Paper, Purpur and **Folia**, from a single build |
| Packets | PacketEvents 2.13.0 (`compileOnly`) |
| Commands | Lamp 4.0.0-rc.17 (`compileOnly` + `libraries:` in plugin.yml) |
| Sidebars | scoreboard-library 2.8.1 (**shaded and relocated**) |
| Cache | Caffeine 3.2.4 |
| Build | Gradle, `java-library` + `maven-publish` + `shadow` |
| Tests | JUnit 5 |

PacketEvents and Caffeine are the latest stable releases (verified on CodeMC and
Maven Central). Caffeine has no 4.x branch: 3.x is the stable line.

Both are installed on the server exactly once, so the version has to be the same
across every Exylia plugin. If you bump one here, it gets bumped everywhere: we
don't want two versions of the same library living side by side. The plugins are
on Caffeine 3.2.2 today, so moving them to 3.2.4 is pending work, not something
already done.

---

## Permanent rules

### Tasks — always the ExyliaLib API

**Never** `BukkitRunnable`, `Bukkit.getScheduler()`, `new Thread(...)`, or your
own `ExecutorService`. Everything goes through `net.exylia.lib.task`.

```java
private TaskScheduler tasks;

@Override
public void onEnable() {
    this.tasks = Tasks.of(this);
}
```

You pick the method by **what the task touches**, and that is what makes the
plugin Folia-compatible without branching per platform:

| Touches | Method |
| --- | --- |
| an entity or a player | `runAtEntity(...)` |
| blocks, chunks, a position | `runAtLocation(...)` |
| nothing thread-bound (HTTP, DB, files) | `runAsync(...)` |
| global server state | `run(...)` |

On Spigot and Paper every non-async variant lands on the main thread, so picking
the right one costs nothing and the plugin runs on Folia untouched.

Details the library already handles, and which therefore you must **not**
reimplement in each plugin: cancellation on disable, exception isolation, tick
normalization, entity timers that stop on their own, and a `cancel()` that is
safe from any thread.

### Cache — always Caffeine

Every cache uses **Caffeine**. It is the best option available on the JVM and it
is already the one the Exylia plugins use.

- No `HashMap` as a cache. A map with no expiry policy and no cap is a memory
  leak with extra steps.
- No Guava Cache: Caffeine is its successor, faster and with a better eviction
  policy (W-TinyLFU).
- Every cache carries a **cap** (`maximumSize`) or an **expiry**
  (`expireAfterWrite` / `expireAfterAccess`). Without one of the two, it is not a
  cache.
- Per-player data: invalidate on quit, or expire it. A player who left must not
  keep occupying memory.

### Configs — always records, never hand-written YAML

A config is declared as a `record` and used as a `record`. We don't hand-write
YAML files and we don't look values up by string in hot paths.

```java
ConfigFile<Storage> storage = Configs.define(this, "storage", Storage.class).load();
int pool = storage.get().poolSize();
```

- **The YAML is output, not input.** The file is generated from the record, with
  its comments. Editing the `.yml` in `resources/` by hand is the sign that
  something is wrong.
- **No `getInt(path)` in a hot path.** Reading from the snapshot is a field
  access; going to the `FileConfiguration` on every event is repeated parsing.
- **Renaming a key demands a `Migration`.** Changing the annotation without a
  migration makes the server silently lose whatever the owner had configured.
- **A user's typo never brings the plugin down.** It is reported and the default
  is used.
- The `@Comment` texts are the server owner's manual: they explain what the value
  changes, in what unit and in what range. **Only what the key name doesn't
  already give away**: the accepted values, the unit, the placeholder it writes.
  `fade-in: 0.5` doesn't need "Seconds to fade in".
- **A record implementing `Sparse` is not written while it is empty**, and its
  absence is not reported as a missing key — otherwise the block reappears on the
  next load. That is what keeps a single-bossbar effect from also writing an
  empty title, action bar, sound, particle set and firework, each with its own
  comment per key. Only for sections whose defaults are empty by nature.
- **Blank lines separate top-level groups, not the keys inside them.** Inside a
  seven-key block they double its height without making it more readable.

### Text and color — always `Text`, always Components

Everything a player sees goes through `net.exylia.lib.text`. Never `ChatColor`,
never `translateAlternateColorCodes`, never concatenating `§`.

```java
Text.of("{primary}&lWELCOME").send(player);
```

- **You return a `Component`, not a `String`.** A legacy String can't carry
  hover, click or reliable RGB. `legacy()` exists only for old APIs that still
  demand it.
- **Colors by role, not by hex.** You write `{primary}`, not `<#8a51c4>`. That is
  how the server owner recolors everything from `colors.yml`.
- **Changing values go through `.with()`**, never concatenation. Concatenating
  breaks the cache and forces a re-parse every tick; `.with()` substitutes on the
  already-parsed Component.
- **Adventure belongs to the server.** We compile against the version
  `paper-api` ships, pinned with `resolutionStrategy`. Compiling against a newer
  one compiles fine and then blows up with `NoSuchMethodError` in production.
- **A message can request effects with the commons notation**
  (`[sound:X|1|1;particle:Y|20;center]text`). It is kept **identical to
  ExyliaCommons** on purpose: migrating a plugin must not force rewriting its
  message files.
  - The tag is an instruction, not text: it never reaches the screen, a log or an
    item name.
  - Only a `Player` receives the effects; a console receives the text.
  - A bracketed prefix we don't recognize is left untouched
    (`[Server] Restarting`), and an unclosed `[` is text.
  - A malformed effect is reported and skipped: **the message always arrives**.
  - `Text` plays nothing on its own: it asks `Effects`, like any other plugin.
- **The packet path is a preference, never a requirement.** If the PacketEvents
  registry doesn't know a name — or the classloader can't see PacketEvents — the
  effect goes out through the Bukkit API. A `false` from the packet path means "I
  don't know that name", not "it played".
- **Sound keys are not derived with string rules.**
  `BLOCK_NOTE_BLOCK_PLING` is `block.note_block.pling` (underscore *inside* the
  key) but `ENTITY_PLAYER_LEVELUP` is `entity.player.levelup`. It is resolved
  through the Bukkit enum; inventing the key wrong is paid for by the client in
  silence.
- **A config is pruned against its record on load.** A key no field declares is
  removed and reported once, the way the strict mode in commons did. Migrations
  run first (they can still read the old layout) and `config-version` is
  reserved. Warning without deleting only turns every retired key into a
  startup-log tradition.
- **Small text is a boolean, defaults to `true`, and is applied to the template,
  not to the values.** It is the Exylia look, so it turns itself on;
  `small-text: false` turns it off. It is transformed inside the cached parse, so
  the cost is zero per player and per tick; a player name and a number are
  substituted afterwards and stay intact. Transforming the value would make
  `Steve` read `sᴛᴇᴠᴇ` and would force a per-player transform on every render,
  which is exactly what the cache exists to avoid.
- **The transform respects whatever is an instruction**: tags, tokens, legacy
  codes and `%placeholder%`. The first three stop working if they get rewritten;
  the fourth fails **silently**, because the value is substituted by looking up
  the literal name on the Component and a rewritten name no longer matches: the
  raw `%coins%` reaches chat.
- **There is no "force uppercase", and that is not an oversight.** In commons `a`
  and `A` pointed at the same glyph in the map, so the flag couldn't change a
  single character. It sat in every server's config for years doing nothing;
  porting it would be copying a bug shaped like a feature.
- **Centering measures the drawn glyph, not the written one.** An uppercase
  letter takes five pixels and its small cap four: measuring the original pushes
  every centered line to the right. And flipping the switch drops the parse
  cache, same as the palette.
- **Substituted values are literal by default, formatted on request.** `with()`
  inserts plain text (what a player types can't inject formatting);
  `withFormatted()`/`forPlayerFormatted()` parse the value (a display name from a
  config *is* its formatting). Choosing wrong in either direction is a bug
  visible in chat. The distinction is **whose the value is**, not what type it
  is: the server owner wrote it, or a player typed it.
- **A bare color cannot be a value.** Substitution happens on the already-parsed
  Component — that is what lets us parse the template once and share it across
  every row. A color with no text parses to an empty component with a color set,
  and a color on one node doesn't reach its sibling: in `%name_color%WILD`,
  `WILD` sits next to it, not inside it. The hex or the token makes no
  difference, and neither does `withFormatted`. Pass the whole colored phrase, or
  use one template per state. ExyliaArmorTrims used to send `{accent}` as a row
  value and painted all eight characters into the item.
- **The unknown-placeholder warning distinguishes "no owner" from "no value".** A
  registered resolver that returns null is not an unknown placeholder, and
  calling it that sends the author hunting for a registration that already
  exists.
- **The `Map` in `apply` supplies values, not just context.** A registered
  resolver always wins; the map is consulted when nobody owns the name. The most
  common need when sending a message is "substitute this here", and for a while
  the obvious signature did nothing: the first migrated plugin sent a literal
  `%class%` to the chat of a live server.
- **A placeholder that doesn't resolve is reported**, once per name. Failing
  silently means the bug is found by a player, not by the developer.
- **The prefix belongs to a plugin, not to the server.** The placeholder registry
  is a flat map keyed by name: a global `%prefix%` would have two plugins
  fighting over it. `Text.of` leaves it untouched on purpose — text that doesn't
  say which plugin it comes from has no prefix to use. It is substituted before
  parsing and before centering, because it carries its own colors and its width
  counts.
- **Centering is measured in pixels, not characters.** The Minecraft font is not
  monospaced. The width table is the one from commons so that a line centered
  there stays centered here; formatting (tags, legacy codes, palette tokens)
  takes no width, and bold adds one pixel per character.

### Placeholders — one type, one registration per group

Every placeholder is registered with `net.exylia.lib.placeholder`. There are no
four kinds of resolver and no four registries: there is **one**.

```java
Placeholders.group(this, "clan")
        .add("name", r -> clans.of(r.requireViewer()).name())
        .add("top", r -> clans.leaderboard().at(r.arg(0, 1)))
        .register();
```

- **A resolver is a `(Request) -> Object`.** Whether it needs a player, arguments
  or extra data shows up in what it reads from the `Request`, not in a category
  picked at registration time. That is what removes registering the same thing
  four times.
- **The prefix is declared once**, on the group. And the whole group is released
  automatically when the plugin is disabled.
- **Formatting belongs to the placeholder, not to the plugin.** You write
  `%eco_balance:comma%` in the config; you don't format by hand in Java. That is
  how the server owner controls presentation.
- **Never return an empty value to mean "there is none".** Return `null`: the
  module then applies the fallback (`%clan_name|No clan%`) or leaves the
  placeholder visible so the typo shows.
- **A resolver that blows up brings nothing down.** It is reported once and
  treated as having no value. That is why a resolver reports failures by
  returning `null`, not by throwing.
- **`.async()` is a promise, not an optimization.** Only if the resolver doesn't
  touch the Bukkit API. Marking it wrong blows the server up late and somewhere
  else.
- **A line that repeats gets `compile()`.** A scoreboard compiles its template
  once and keeps it: measured, 3.4x faster than passing the string every tick.

### Effects — always `Effects`, always configurable

Everything a player sees or hears goes through `net.exylia.lib.effect`. Never
`player.sendTitle`, never a Bukkit `BossBar`, never a hand-rolled
`spawnParticle`.

- **The effect is declared in config, not in Java.** The plugin says *what
  happened* (`Effects.play(config.onWin(), player)`); the owner decides whether
  that is a title, a sound or fireworks. `EffectConfig` nests inside the plugin's
  record.
- **Time is in seconds with decimals.** `countdown(3.3)` is a real 3.3s, and
  `%time%` shows it as `3.3`. No multiplying by 20 by hand.
- **`%time%` belongs to the effect, not to the world.** A timer belongs to an
  effect; if it were a registered placeholder, two countdowns on screen would
  show the same thing.
- **With no timer, the effect is permanent** until it is stopped. And `onEnd`
  runs **exactly once**, however it ends.
- **Static text schedules no task.** If nothing changes, it is drawn once. A
  permanent bar with fixed text costs one packet, not one task per tick.
- **Changing values go in `Text`, never in the string.** `bar.text("Health: " + hp)`
  is a new string and a MiniMessage parse per redraw; `bar.text(Text.of(template)
  .with("%hp%", hp))` parses the template once and substitutes on the component.
  The same string twice in a row doesn't redraw.
- **Fireworks are the exception**: they are spawned and detonated in the same
  tick. Everything else is a packet.

### Actions — compile once, adapt at the edge
Actions is the core shared by menus, items and other triggers, but it doesn't
know what a click, a hand or a slot is.

- Registry always per plugin and namespace: `Actions.of(plugin, "practice")`.
- In public YAML always `namespace:id`; bare IDs are accepted only when compiling
  from the owning `PluginActions`.
- The string is compiled when the config loads (`ActionCall`), never on every
  click.
- Sync runs straight through. Async only for real I/O via `registerAsync`, which
  uses Tasks; don't schedule a plain `closeInventory()`.
- Specific data goes through `ActionKey<T>` defined by UI/Items, never string-keyed
  maps in the core.
- Only SUCCESS continues an `ActionSequence`; STOP, DENIED and FAILED end it.
- Delays belong to `ActionStep` and are scheduled on the player's entity.
- `execute` returns an `ActionExecution`: whoever opens something with delayed
  steps (a menu, an item) has to cancel it on close. Don't leave tasks alive.
- Actions that depend on the row being drawn: `PluginActions.template`. A
  template with no placeholders is compiled at load and costs nothing afterwards.
- Don't duplicate cooldowns, permissions, auditing or rate limits in a pipeline:
  use the specialized module when a particular action needs it.

### Items — one definition, many renders

Every item that comes from a config goes through `net.exylia.lib.item`. Never an
`ItemStack` built by hand from a `ConfigurationSection`, and never a copy of the
parser inside a plugin.

- **`Item` is a definition, not an `ItemStack`.** It keeps its placeholders
  unresolved, it is shared by everyone looking at it, and it is tested without a
  server. Reading the file is expensive and happens once; building the item is
  cheap and happens constantly. Commons did both together on every render.
- **It is not a menu module.** SpecialsV3, PracticeCore (the lobby hotbar),
  Shields and SurvivalCore use it without opening a single GUI; `ui` is just
  another consumer. That is why it lives outside `ui` and not inside it.
- **`Items.of(plugin)`, not static.** `nbt` values go under the owning plugin's
  namespace. Commons kept a static `plugin`, which in a shared library would file
  everything under `exylialib:`.
- **The head prefix lives in `material`.** `basehead-`, `headbase-`, `urlhead-`,
  `playerhead-`, `bytes:` — 440 real usages. `Source` resolves them at load time;
  `startsWith` in a hot path was a commons thing.
- **`playerhead-%player_name%` is its own type** (`OfHeadTemplate`): the only one
  that forces per-player resolution. Knowing that at load time is what lets the
  other fifty slots pay nothing.
- **`name` and `display-name` are different things**, not a pair with a fallback.
  The first is drawn; the second is quoted by a plugin inside a message. Treating
  them alike used to send a bold gradient name with a counter inside it to chat.
- **A static item is rendered once and copied.** Dynamic is anything with a
  placeholder, a templated head or a trim with a placeholder. Anything carrying
  `nbt` is never cached: the owner's namespace makes them distinct.
- **The cache is dropped when the palette reloads.** A quality-bar requirement;
  hooked into `ExyliaLib.loadPalette` alongside the rest.
- **Zero reflection.** Against paper-api 1.21.4 data components are direct API.
  Commons had 202 lines of reflection just for consumables and 152 for attributes
  because it supported older servers; we don't.
- **An unreadable part is reported and skipped**, the item goes on. Commons
  swallowed it, so a misspelled enchantment was indistinguishable from a correct
  one.
- **Four commons bugs are fixed on purpose**, and each one has its test: `flags`
  was never parsed anywhere; `hide-attributes` was
  `getBoolean(a,true) || getBoolean(b,true)` and couldn't be turned off (now it
  turns off by writing `false`, and it is still on by default); `upgraded` was
  stored and never read, so an Instant Health II refill gave I; and
  `display-name` was a fallback for `name`.
- **`hide-attributes` hides everything the client writes on its own**, not just
  the damage and speed lines: also the block a smithing template adds to itself
  ("Applies to: Armor"), a potion, a firework or a banner. A menu asking for a
  clean tooltip means all of that. Since 1.20.5 that lives in data components and
  the flag is `HIDE_ADDITIONAL_TOOLTIP`; against paper-api 1.21.4 they are direct
  API, so the reflection commons used for the same thing isn't needed here.
- **The `ItemFlag` alone is not enough, and the enum says so**: a flag set
  "without also setting the data it hides may not be persisted". A smithing
  template stores no such data — its block comes from the item *type* — so the
  flag was applied, the test saw it set, and the item still said "Applies to:
  Armor" on screen. You **also** need the `HIDE_ADDITIONAL_TOOLTIP` data
  component, which is defined against the type. Commons reached the same
  component through reflection because it supported old servers.
- **The component is written at the end of the render, after the last
  `setItemMeta`.** Every `setItemMeta` replaces the item's entire component map,
  and `TraitApplier` calls it six times *after* `write`. Writing it inside
  `write` set it and erased it an instant later: with no warning — because the
  write did happen — and with no effect. It looks identical to a client ignoring
  the component, and it took three deploys to tell them apart.
- **`-Dexylia.item.components=true` tells you.** It prints once which path did
  the write and which components survived on the finished item. Off, it costs
  nothing; it exists because "it was written but you can't see it" and "it was
  never written" are indistinguishable from the outside.
- **`DataComponentTypes` is confined to `ItemComponents`.** It resolves each
  constant against the server registry in a static initializer, so merely naming
  it already demands a live server. Confined, `ItemRenderer` still loads without
  one — which is what makes the decision testable. Verified in bytecode, same as
  PacketEvents and Folia.
- **That component is looked up by name in the registry, never as a field.** We
  compile against 1.21.4 and servers run ahead of that: on 1.21.11
  `HIDE_ADDITIONAL_TOOLTIP` no longer exists (`TOOLTIP_DISPLAY` replaced it).
  Naming the field compiles just fine and blows up at runtime with
  `NoSuchFieldError` **inside the render**, so the whole menu fails to open. It
  happened in production on 1.46.0. `Registry.DATA_COMPONENT_TYPE.get(...)`
  exists in both versions and answers `null` when the name is gone.
- **Declared exception to "zero reflection": `TooltipDisplay`.** It is the only
  one in the library and it lives entirely inside `ItemComponents`. The rule was
  written against the commons case — reflecting to support servers older than
  data components; this is a different one: the component **changed its name
  between two versions we support at the same time**, and neither name compiles
  on both. `hide_additional_tooltip` is resolved through the registry;
  `TooltipDisplay` does not exist in paper-api 1.21.4, so you can't even name the
  type. Raising the minimum baseline would be the alternative, and today it is
  1.21.4 on purpose.
- **Methods are looked up on the interface, never on `builder.getClass()`.** The
  builder Paper returns is its internal implementation and may not be public: a
  method found there can't be invoked without `setAccessible`, which this doesn't
  use. `TooltipDisplay$Builder` and `DataComponentBuilder` are public API.
- **`hide_tooltip` rides in that same component since 1.21.5, and it is
  preserved.** `hide_tooltip` and `hide-attributes` are different keys: the first
  hides the whole tooltip, the second only what vanilla writes by itself. Up to
  1.21.4 they were two components and never got in each other's way; since 1.21.5
  `hide_tooltip` is a field of `tooltip_display`, so the write that hides the
  type's block was bringing back the tooltip of every decoration written with
  `hide_tooltip: true`. It is read off the item before rewriting the component
  and put back, just like the `hiddenComponents` it already carried.
- **`tooltip_display` hides components, not a category**, so the category is
  enumerated (`WRITTEN_BY_TYPE`) and filtered against the registry: a name that
  server doesn't have is skipped. That way one list serves several versions. The
  smithing template's block comes from `provides_trim_material`.
- **What can't be written is said once per server, not once per item.** Opening a
  menu renders every slot: reporting per item put eighteen identical lines in the
  console for a single screen. It is a fact about the version, not an incident of
  the item.
- **A tooltip isn't worth a broken screen.** What can't be written is reported
  through `Problems` and the item is drawn anyway. The rule holds for every data
  component whose name isn't stable across the supported versions; the ones that
  are (`FOOD`, `CONSUMABLE`, `ATTRIBUTE_MODIFIERS`) are still named directly in
  `Components`.
- **Compiling against 1.21.4 is not running on 1.21.4.** The minimum baseline is
  1.21.4 on purpose, so every new API is also verified against the version the
  server actually runs before being used.
- **A test that asks about the flag doesn't prove this.** The flag was set the
  whole time while the player saw the opposite; what is verified is that the
  component is requested, not that the flag is added.
- **But it doesn't hide enchantments.** Commons applied `ItemFlag.values()`, so
  an item asking to hide attributes lost the enchantment lines it wanted to show.
  Hiding those is still something the file asks for, via `flags`.
- **The consumable's sound is asked of `Effects`.** The rule isn't mechanical
  (`BLOCK_NOTE_BLOCK_PLING` keeps its underscore) and it is already written once.
- **Registries go through `Registry`, never `valueOf`.** Several of those types
  stopped being enums in 1.21: `values()` compiles and blows up at runtime with
  `IncompatibleClassChangeError`.

### Menus — state lives in the session, never in a per-player map

Every menu goes through `net.exylia.lib.ui`. Never an `Inventory` opened by
hand, never your own `InventoryClickEvent`, never a static map from player to
whatever they have open.

- **Three things kept separate on purpose.** `UiDefinition` is what the file
  says, compiled once and shared; `UiSession` is one player's window and the only
  thing a click is validated against; `UiEntry` is a row with its values and
  **the thing it is about**.
- **`UiItem` composes an `Item`.** Appearance belongs to the `item` module, which
  four plugins use without opening a single GUI. Only what means something
  exclusively on a screen lives here: clicks, condition, dependencies, animation.
- **Sections are first class.** A menu can have several paginated lists at once
  (13 real files do). A `pagination` block is read as a section called `main`, so
  the 153 single-list files never notice.
- **Templates are read by shape, not by list.** Any key ending in `template` is
  one, named after whatever comes before it. There are 167 distinct names across
  the ecosystem and a plugin can invent another one tomorrow.
- **The row carries its value.** `UiKeys.ENTRY`. Commons had nowhere to put it,
  so a handler rebuilt the kit from the drawn item — hence the static per-player
  maps, and hence two open menus answering each other wrong.
- **A row says what state it is in, not what color it is.** `.template(...)`
  picks `selected` / `no_permissions`, and the file decides how each one looks.
  Sending the color as a value hides the palette in Java and doesn't work (see
  *Text and color*); on top of that it leaves the server owner with a
  `%name_color%` they can't touch.
- **`withFormatted` on a row is for values carrying color *and* text.** A display
  name from a config. What a player types goes through `with`. Choosing wrong
  shows on screen and gets reported; the other way round would be a silent
  injection, so the default is the one that doesn't parse. Three plugins chose
  wrong on the same line (`effect_description`), which says more about the
  ergonomics than about them.
- **A value with `<nl>` becomes several lore lines.** It is the only way for a
  config description to take two lines: `<nl>` in the file is split by
  `ItemReader` at load, and that never reaches a value that only exists at draw
  time. Commons allowed it because it substituted on the string; returning a
  single line dropped the rest **silently**.
- **Expanding doesn't cost a parse per line.** Every line comes out of the same
  template string, so they share a cache entry: the cost is one substitution per
  line. That is why the "the template is parsed once" premise still stands.
- **Every expanded line keeps whatever the template puts around it** (the bullet,
  the color), neighbouring values repeat on all of them, and only the line
  mentioning the long value is stretched. Stretching the rest would turn a
  five-line tooltip into fifteen.
- **Expanding is not a second door into the parser**: an expanded line is still
  literal unless `withFormatted` was asked for.
- **A click is validated against what was drawn, not against the packet.** The
  client sends a slot number; the server already knows what it put there. A slot
  whose condition fails isn't blank: it **isn't there**.
- **The session is found through the window's holder**, never through a player →
  menu map. A player who opens a chest on top of a menu isn't ours.
- **An unreadable condition hides the slot.** Failing the other way gives a
  button to someone who shouldn't have it; this way it is merely invisible.
- **Nothing outlives its screen.** An `ActionExecution` with delayed steps is
  cancelled on close; disabling a plugin closes its windows **before** releasing
  its tasks, because a button whose classloader is dying must not answer another
  click.
- **`next_page`, `previous_page`, `back`, `close`, `refresh` belong to the lib.**
  Turning a page is nobody's feature and 500 files already write them. If a
  plugin registers its own under that name, its own wins.
- **Page numbers are supplied by the menu, not by the plugin.** The section knows
  how many rows it has and which one it is looking at; asking the caller for them
  is asking them to compute what the list already computed. That is why a context
  value with that name does **not** override them: it would survive the very
  click that moved them.
- **The title follows the reader, and that is a packet.** In Bukkit the title is
  an argument to `createInventory` that is read once; the client, on the other
  hand, accepts a second "open window" for the container it already has open and
  treats it as a retitle — the slots don't move and it doesn't flicker. Without
  PacketEvents it stays on the page it opened on, which is what it did before,
  and everything else works the same.
- **It is only resent if the title names a page and it actually changed.**
  Retitling forces the client to request the window contents again: too much for
  a title that says the same thing.
- **Changing pages redraws that list and nothing else.** `invalidate(dep)`
  redraws only the slots that declared a dependency on it. No full rebuild, no
  flicker, no packets for what didn't change.
- **A click redraws everything that can change, not just the clicked slot.** A
  button rarely changes only itself: adding a layer moves a counter, a preview
  and a list, and none of them is the slot that received the click. Redrawing
  just one left the rest showing the old state, which is exactly "you have to
  click twice". And the live context is read, so a redraw the plugin performed in
  between isn't undone.
- **`refresh: SMART` redraws only what can change.** A timer repainting static
  decoration is packets for an identical item. The timer only starts if there is
  something that can change, and it dies with the player.
- **The animation draws first and hides afterwards.** Everything is registered
  before it starts, so a click on a slot that isn't visible yet still works. The
  other way round would be a window whose buttons silently do nothing. A click
  skips the rest.
- **The three fillers are three different things.** `global` is background;
  `pagination` is what someone with an empty list sees and **usually says why**;
  `custom` are panels with their own slots. Treating the second as background
  leaves the player with no explanation.
- **An arrow under `navigation` paginates on its own.** The `actions` are
  implicit: an arrow declared there has no other job, and Commons paginated **by
  slot** (`MultiPaginationMenu.handleClickInternal`), so no file in the ecosystem
  ever wrote them. Demanding it left 12 real blocks with arrows that were drawn,
  could be clicked and did nothing. One that names something else keeps its own,
  and if the built-ins aren't registered the arrow is drawn anyway: it is a
  convenience, not a reason to refuse to load the menu.
- **A page button with no page isn't drawn**, and its slot falls back to whatever
  background it belonged to (the panel that claims it, or `global`, or nothing).
  Commons painted the fillers *before* navigation, so the button that wasn't
  drawn was already covered; here navigation comes last and you have to tell it
  what to fall back to. An arrow that exists and does nothing is half the menus of
  a quiet server.
- **Sounds are read from `open_sounds:` at the root**, which is how all 2028
  files are written. Reading only a `sounds:` block left the entire ecosystem
  mute and passed the tests all the same.

### Wizards — one flow, one owner, nothing half-done

Every guided flow goes through `net.exylia.lib.util.wizard`. Never a chain of
hand-wired inputs, and never a `static Map<UUID, ...>` holding half-built state.

- **ExyliaEvents' `EventConfigWizard` is what this fixes**, and it is real code:
  94 lines to ask two questions. `askConfigId` calls `askDisplayName` calls
  `finishCreation`, and in between the event type lives in a
  `static Map<UUID, String>`. That is where the three consequences come from, and
  all three are in the file: the cancel branch copied four times (one per exit
  somebody remembered — and the one nobody remembered, a timeout or a quit, clears
  nothing, so `hasPendingWizard` keeps saying `true` for a flow that no longer
  exists); no way back, so whoever gets the id wrong finds out at step two and
  starts over; and the menu that opened it only reopens on the success path, so
  whoever cancels is left staring at nothing. It wasn't badly written: there was
  simply no object that **was** the flow, only callbacks that each knew the next
  one.
- **One per player, across the whole server.** A second flow ends the first as
  `REPLACED`. It is the same rule the `input` module already enforces with its
  single active question, and for the same reason: a wizard **is** a chain of
  inputs, so two live flows would be waiting on the same slot — the next question
  would answer the other one's step, and the other one's would answer this one's.
  They swap answers and neither delivers anything usable.
- **Nothing is applied until confirmation.** With `summary()`, `onFinish` runs
  **exactly once** and only on `COMPLETED`. A run that was cancelled, expired,
  disconnected, replaced or failed never gets there. That is what lets you build
  the whole thing in one go instead of accumulating half-objects step by step,
  which is what commons does.
- **One single cleanup path**, not a branch per step. Every exit claims the
  terminal slot atomically and releases the four things the player notices: the
  open question, the block selector, the progress bar and the player's wizard
  slot. Each one is guarded on its own: a bar that refuses to stop can't prevent
  the selector from being released.
- **The block selector is unique per player across the WHOLE server**, and it is
  the sharp reason the path is a single one. The other three things are ours: a
  stuck bar is our bar, a stuck question is our question, and a stuck slot only
  blocks this lib's wizards. A stuck selector leaves that player unable to select
  a block for **any** plugin — a WorldGuard claim, another arena's setup, a shop
  region — until they reconnect. It is the module's only leak the player carries
  outside the plugin that caused it. A `region` step that can't claim it ends as
  `REPLACED` naming the current owner, instead of fighting over the clicks.
- **Back lives in the summary, and it is a `confirm` plus a `choice` and nothing
  else.** Denying the summary offers the list of answers; picking one asks it
  again and returns to the summary. It is built from those two requests on
  purpose: it asks for no control that some transport might lack, so it works
  identically in a native dialog, a Bedrock form, an anvil, a menu and chat. A
  review screen of its own would have to be written five times, and four of them
  would rot. The rounds are bounded by `maxRedos`; going over cancels, because
  whoever is on their fourth pass isn't answering any more.
- **Changing an answer a branch depends on re-resolves the flow.** The whole
  definition is re-walked from the top against the current answers: whatever
  belonged to steps that no longer apply is dropped, and whatever applies now is
  asked **before** the summary comes back. The rest survives by name, so the
  player doesn't retype what they already typed. Going straight back to the
  summary — which is what the first version did — is wrong in both directions and
  silently so: whoever swaps KOTH for CONQUEST sends `onFinish` a `points` that
  event type doesn't have, and the other way round sends answers missing a
  required one. Neither shows up until the plugin's creation code reads a field,
  which is to say: in production, reported by whoever created the broken event.
  Redoing a key no branch depends on still goes straight to the summary, which is
  the common case.
- **It caches nothing derived from the palette**, so it has no `invalidateAll()`
  and is deliberately out of `ExyliaLib.loadPalette`. Every prompt, summary line
  and bar is built with `Text` at display time, and the `Wizard` title is stored
  raw, not parsed. It is declared on purpose: point 8 of the quality bar demands
  saying it, not staying quiet about it.

### Scoreboards — always `Scoreboards`, always configurable

Every sidebar goes through `net.exylia.lib.scoreboard`. Never Bukkit's
`Scoreboard`, never hand-made objectives and teams, never your own copy of
scoreboard-library.

- **The board is declared in config, not in Java.** The plugin says *who* to show
  *which board* to (`Scoreboards.show(this, player, config.ffa())`); the owner
  writes the title, the lines and the interval. `SidebarConfig` nests inside the
  plugin's record.
- **The YML is the ExyliaCommons one.** Same keys (`enabled`, `title`, `lines`,
  `update.interval/smart/cache`) and the same library underneath, so migrating a
  plugin from commons to lib doesn't force the owner to touch their file.
- **Here the interval is in ticks**, not in seconds like the rest of the library.
  It is a conscious deviation scoped to this section: an existing `interval: 15`
  has to keep meaning 15 ticks.
- **Only what changed is sent.** The diff is done on the rendered string, before
  parsing and before touching packets. A board whose values didn't move costs a
  string comparison and zero packets.
- **The raw text is parsed, not the resolved one.** The template text never
  changes, so it is parsed once and the values are substituted on the Component.
  Measured: 26.8µs against 4.2µs per changed line.
- **Boards stack per player.** Showing one pauses the previous, and closing it
  brings the other back automatically. A paused board renders nothing.
- **A single async timer moves every board**, with a per-UUID offset so the
  renders don't pile up on the same tick.
- **Nothing outlives its owner**: when the player leaves, when the plugin is
  disabled, or when the palette reloads (there it is resent whole, because the
  text is the same but what parses it isn't).

### Holograms — always `Holograms`, always configurable

Every floating object goes through `net.exylia.lib.hologram`. Never a real
Bukkit entity, never an ArmorStand, never a hand-spawned `TextDisplay`.

- **They are declared in config, not in Java.** The plugin says *where* to put it
  (`Holograms.show(this, id, location, config.trophy())`); the owner writes the
  lines, the type, the colors and the visibility. `HologramConfig` nests inside
  the record.
- **The YML is the ExyliaCommons one.** The same keys
  `HologramTemplateSerializer` used to write, minus the ones that mean nothing
  here (chunks, on-disk persistence: a hologram is only packets, it isn't a
  file).
- **The interval is in ticks**, like the scoreboard. Another scoped deviation so
  that the commons files keep working.
- **They are packets or they are nothing.** If PacketEvents isn't there,
  `isSupported()` is `false` and everything keeps working while drawing nothing.
  There is no real fallback entity ticking away and taking up a slot in the
  server registry.
- **Visibility is checked four times per second** with squared distance per
  player per hologram; packets are only sent when the boundary is crossed.
- **Only what changed is sent.** A line with no placeholders never refreshes. One
  that has them is diffed and only it is resent, not the whole hologram.
- **Moving is a teleport, not a respawn.** That way a hologram following a player
  doesn't flicker. And mounting it on an entity (`attachTo`) doesn't even send
  packets while it moves: the client moves it along with the vehicle.
- **Nothing outlives its owner**: when the plugin is disabled, when the player
  leaves, or when the palette reloads (there it is resent whole).

### Modded clients — always `Clients`, never branching

Everything that depends on Lunar or Feather goes through
`net.exylia.lib.client`. Never `Apollo.getPlayerManager()` or `FeatherAPI`
inside a plugin.

- **The plugin never asks which client a player is on.** It says what they should
  see (`Clients.waypoints().show(...)`) and whoever can draw it, draws it. A
  vanilla player isn't a special case: it is a map lookup and nothing more.
- **Each client is a `ClientLink` and one line in `ClientRegistry`.** Adding a
  new one touches nothing else. Whatever a client can't do is answered with
  `false` from its `supports`, not with an exception.
- **Apollo and Feather are confined to one class each**, just like PacketEvents.
  Verified in bytecode: only `ApolloLink` and `FeatherLink` name them.
- **Detection is cached per player** and asked one second after the join: the
  client announces itself *after* joining, and asking earlier leaves "vanilla"
  cached for the whole session.
- **The library remembers what it sent and restores it** on reconnect and on
  world change (only what belongs to the new world). In memory: a waypoint is
  something on a screen, not a record deserving disk.
- **What a plugin sends carries its name.** `Clients.of(plugin)` files by owner
  and name, never by name alone. Two plugins have every right to call a waypoint
  `spawn` — a lobby and a match do — and with a flat key the second `show` wiped
  the first one's marker off the player's screen. It is the same class of bug as
  `Effects.stopFor`: an owned view's `clear` takes down its own, the static one
  takes down everybody's.
- **What a plugin drew is taken down when it is disabled.** Before, only the
  teams were released, so a waypoint whose owner was gone couldn't be removed by
  anyone: it stayed on the minimap until the player reconnected. When restoring
  after a reconnect it is filed under the same owner again, or the marker would
  move to the ownerless bag and its plugin would no longer be able to touch it.
- **An integration failure doesn't leave the integration.** It is the other
  plugin's bug; whoever asked for the waypoint did nothing wrong.
- **A team is a record, not a nudge.** `markers()` draws a list and forgets;
  a match that lasts has to answer "who is on this team" on every join, death,
  quit and reconnect. The three bugs that come from keeping that list in a map of
  your own are always the same: a player on two teams, a team that outlives the
  match, and a member who already left. `ClientTeam` answers all three once.
- **A player is on one team at a time, across the whole server**, and
  `of(player)` crosses plugins: whose the team is doesn't change which one the
  player is on.
- **Members are stored by id, never as `Player`.** A team that outlives a session
  can't be the reason the server keeps an entity alive. Whoever disconnected
  drops out on read, so a team nobody cleaned up shrinks anyway.
- **A team dies with its plugin.** Same as everything else in the library.

### Nametags — packets to everyone, not just to modded clients

Everything that changes how one player sees another (name color, glow,
collision, seeing invisibles) goes through `net.exylia.lib.nametag`. Never a
Bukkit `Scoreboard`, never hand-made teams, never `setGlowing`.

- **It sits outside `client` on purpose, and that isn't a packaging detail.**
  `Clients` exists to talk to Lunar and Feather and to do nothing for everyone
  else; this is vanilla teams and entity flags over packets, so a player with no
  mods sees exactly the same thing. Putting it in there would make `ClientLink`
  and `ClientBrand` meaningless across half the module. In commons they lived
  under the same package without sharing a single line of code.
- **It is per viewer, not per player.** The same player is red to his enemy and
  green to his clan at the same time, and none of that exists on the server: no
  scoreboard, no real team, no state to keep in sync.
- **The caller declares a style, not a team name.** The name is derived from the
  style, so two that look alike share a team without knowing it. Every plugin
  used to invent its own (`"clan_" + id`) and then had to keep it in sync with
  the colors it meant.
- **Glow is not part of the team name.** It travels in the entity flags, so two
  styles differing only in that share one.
- **A color that didn't change isn't sent.** And a team is created once and then
  added to; deleting and recreating it costs two packets every time, and an empty
  team on a client costs nothing.
- **Glow is rewritten on the fly, not sent once.** The server resends an entity's
  flags every time anything happens to it, and each of those would switch the
  outline off. That is why the module needs PacketEvents.
- **A plugin only undoes what it painted.** A match can't silently cover a clan's
  color. And on disable everything of its own is undone: a match that ends badly
  doesn't leave anyone red forever.
- **Without PacketEvents it doesn't fail, it doesn't draw.** `isSupported()` is
  `false` and everybody stays plain.

### Combat — one answer, and it fails open

Everything that asks whether someone is in combat goes through
`net.exylia.lib.util.combat`. Never a per-plugin hook of your own.

- **Four plugins had their own hook for the same question**, and each knew a
  different set of combat plugins: the same server answered different things
  depending on who asked. One returned `true` from `canAttack` with a `TODO`
  above it.
- **It fails open, always.** With no plugin installed, or if the plugin blows up:
  nobody is tagged and everybody can fight. The other way round, an integration
  failure would stop every fight on the server.
- **The tag is cached and nothing else.** It is the hot-path question (damage,
  movement, scoreboard) and it doesn't change within half a second. The remaining
  time is **not** cached: it is a countdown, and cached it sits still and then
  jumps. Neither is a write: tagging while reading a stale value tags for a fight
  that already ended, so `tag`/`untag` invalidate their own.
- **Empty is not zero.** A plugin that counts nothing returns empty, not a record
  of zeros. "No kills" and "nobody is counting" are different answers, and a
  leaderboard that doesn't tell them apart shows a whole server at zero.
- **`ratio()` is computed by the lib.** Plugins don't agree on what to do with
  zero deaths, and a leaderboard mixing two answers is worse than one that picks.
- **Reflection, as with clans**, and for the same reason: the lib loads on
  servers that have neither of them.
- **A `CombatBridge` only writes what its plugin can answer**; the rest are
  defaults, and every default is what a server with nothing installed does.

### Clans — one active provider, per-player cache, no branching

Everything that depends on a clan plugin goes through `net.exylia.lib.clan`.

- **The plugin never asks which clan plugin is installed.** It asks what it wants
  to know (`Clans.areAllied(...)`) and the lib answers with the data it has.
- **A provider is a class implementing `ClanProvider`.** Each one references its
  plugin through reflection, via the shared `Reflect` helper (FactionsUUID,
  HuskTowns, ZelTeams, RunithClans, UltimateClans, Kingdoms, SimpleClans,
  ExyliaClans), or adapts an external `ClanBridge`. Nothing is compiled against a
  clan plugin, so its absence is never an error. Adding one touches nothing else.
- **Detection prioritizes external bridges over built-ins.** A bridge registered
  with priority 10 beats any automatic detection.
- **The cache is Caffeine with a 3-second TTL**, because these calls sit in the
  hot path of damage, the kill message, the scoreboard. Invalidated in
  `Clans.invalidate()` and in `forget(player)`.
- **What a plugin doesn't have comes back empty.** UltimateClans has no alliances
  → `alliesOf()` returns `[]`, it doesn't throw. Asking whether two clans are
  allied when one doesn't exist returns `false`.
- **Relations that are a graph are asked about, not listed.** FactionsUUID and
  ExyliaClans store the relation between two clans, not inside each clan: that is
  why `areAllied` / `areRivals` ask a single question and `alliesOf` / `rivalsOf`
  walk every clan. For the same reason the snapshot of those two comes with
  neither allies nor rivals — filling it in would be that walk on every damage
  event.

### Utils — modular, self-contained, with no dependencies between them

Everything that gives plugins utility but has no home in a specific module goes
in `net.exylia.lib.util`.

- **Each class is a self-contained utility.** They don't depend on each other,
  and nothing outside the module knows how they work inside.
- **Effects** parses potion strings in the format
  `SPEED:1:300|JUMP_BOOST:2:120`. Parsing produces `ParsedEffect` (plain Java
  records) with no Bukkit types, with a 30-second Caffeine cache. The resolver
  (`PotionEffectType.getByName`) and the applier (`addPotionEffect`) are
  injectable for tests.
- **Cooldowns** keeps, per player, a map of key → expiry instant. There is no
  task counting down: it is compared on read, so a thousand inactive cooldowns
  cost zero. What has expired is purged by the read that detects it, and when the
  player leaves it is forgotten entirely — the map can't grow unbounded. The
  clock is injectable so tests don't have to sleep. Seconds round **up**: telling
  someone "0 seconds" while you are still denying them the action is lying to
  them.
- **`Cooldowns` is THE base for every cooldown in the ecosystem.** Items, chat
  and whatever comes next are built on top of it, never alongside it. In
  ExyliaCommons there were four different implementations and one (`channel`) had
  `getRemainingSeconds()` always returning `0`: nobody looks at the fourth copy.
- **A cooldown is identified by `CooldownScope` + key.** The scope is its type
  and its id, so `clan:red` and `team:red` are different owners. Player scopes are
  cached, not rebuilt: it is worth 7 ns on the hot path.
- **Persistence by threshold, with nothing to configure: >= 5 minutes goes to
  disk.** The duration decides it, not the caller. Less than that doesn't pay for
  the write and would have expired before being read. It is written async, only
  for the owners whose long cooldowns changed, to a temp file + atomic move.
- **The item layer only handles what is its own**: the Bukkit overlay and the
  per-material key under `item:`. Counting time belongs to the base.
- **Time is shown with decimals.** `remainingSeconds` returns a `double`;
  `remainingWholeSeconds` still rounds up for "wait N seconds" messages;
  `remainingFormatted` gives the ready-made text through `TimeFormats`, the
  library's single formatting implementation (public in `util`, shared with
  `effect`).
- **A display reads a cooldown, it doesn't duplicate it.** `Timer.ofCooldown(...)`
  is a bridge: the cooldown stays the truth, the display only looks. `advance`
  and `extend` do nothing on that timer; giving more time is done through
  `Cooldowns`.
- **Measure before stacking.** The benchmark exists and lives in the repo: ~32 ns
  with an active cooldown, ~8 ns when there is nothing. When I added scopes it
  went up to 89 ns because of a `UUID.toString()` on every call — the same sin I
  criticized commons for. It was fixed by keeping the UUID and caching the scope.
- **Future utilities** (inventories, timestamps, etc.) follow the same pattern:
  their own class, a cache where it makes sense, and an injectable seam (clock,
  resolver, overlay) so they can be tested without a server.

### Rewards — the format is commons', the bugs are not

Everything a player earns goes through `net.exylia.lib.util.reward`. Never a
loose `addItem`, never a hand-rolled `dispatchCommand`, never a command list in a
`List<String>` of your own.

- **The stored format is not a choice.** There are rows written by commons in
  production (`capture_pending_rewards`, `event_pending_rewards`, SurvivalCore's
  power-ups). `RewardCodec` reads and writes exactly that shape: the field names
  are the old Lombok bean's, nulls are omitted, and an empty list is stored as
  `NULL` and not as `[]`. Migrating a plugin is changing imports.
- **A new field is only written if it isn't the default.** That way a reward the
  old module could have written serializes byte for byte to what it used to
  write, and doesn't bloat against the `VARCHAR(8192)` those tables already have.
- **An unknown type costs a reward, not the list.** That is what lets an
  unmigrated plugin read a row written by a migrated one.
- **Nothing is destroyed.** Commons discarded the map `addItem` returns, so an
  item that didn't fit was deleted with no message, no log and no failure. Here
  the policy is `DROP`, `QUEUE` or `FAIL`; none of them reproduces that. And
  `QUEUE` with no store, or with a store that blows up, **drops**: asking to
  queue is asking not to lose it, and a database being down doesn't change what
  was asked for.
- **What gets queued is the overflow, and the dice are not rolled again** — they
  were already rolled. But it keeps its permission and its condition: something
  owed to someone who has since lost the rank that required it is no longer owed.
- **Permission and condition come before the dice.** Who *may* receive something
  doesn't depend on luck. Commons rolled first, so a rare reward reported "it
  didn't come up" when the truth was a misspelled permission.
- **A skip is not a failure.** Losing the roll, lacking permission and blowing up
  are three different outcomes and are reported as three. Counting all three as
  failures made the commons success rate describe the dice, not the config.
- **An unreadable condition delivers the reward.** The opposite of menus, and on
  purpose: the config says who to *exclude*, and a condition nobody can read
  excludes nobody. Hiding a button is invisible; handing out something that
  wasn't due is noisy, and noisy is what gets the typo fixed.
- **`chance` and `weight` are different questions.** The first is "does this
  happen?", the second "which of these happens?". `pick` looks only at the
  weight; `roll` picks by weight and **then** delivers, so the winner still faces
  its own `chance`.
- **Money travels as text.** A decimal that goes through a `double` on its way to
  the database doesn't come back the same.
- **The pending table belongs to the plugin, not to the lib.** Capture and Events
  already have theirs, full of rows somebody is waiting on; a table imposed by
  the lib either ignores them or forces a migration. You pass a `PendingRewards`.
- **Putting the item into the inventory sits behind `ItemGiver`.** It is the only
  part that needs a real `ItemStack`, and pulling it out lets everything else a
  delivery decides be tested without a server: the order, the dice, the rank, the
  overflow and the queue. The rest does call Bukkit (`dispatchCommand`,
  `giveExp`, `hasPermission`), but against a fake player, not against a registry.
- **The editing menu is prepared, not written.** `RewardEntry` is immutable with
  a `toBuilder()` that preserves the id, `copy()` duplicates, `displayName()` and
  `resolvedIcon()` draw without a server, and `RewardCodec` round-trips. An
  editor built on that touches nothing internal.

### Loot — the format is commons', the bugs aren't either

Everything that comes out of a chest, a spawner or a broken block goes through
`net.exylia.lib.util.loot`. Never a hand-made list of ItemStacks and never a
loose `nextDouble` against a weight.

- **The stored format is not a choice.** There are rows written by commons in
  production (`sc_loot_chest_templates`, the spawner tables, every event
  configuration). `LootCodec` reads and writes exactly that shape: the field
  names are the old Lombok bean's in declaration order, nulls are omitted, and an
  empty list is stored as `NULL` and not as `[]`.
- **A row with no `type` is an item.** It was written before command entries
  existed, and that is what it meant. An unknown type is also read as an item and
  reported: it costs the payload, not the table.
- **A half-configured entry is kept and reported.** An item with no item is
  exactly what an editor is there to fix; dropping it would lose it the moment
  the table is saved.
- **Weight means two things and both of them are commons'.** `roll` reads it as a
  percentage and rolls line by line (chest, spawner); `pick` reads it as a share
  of a total and pulls a single one (survival games refill). They are not
  converted between each other: the tables out there already mean one or the
  other depending on who reads them.
- **The forced line when nothing came up is picked uniformly, not by weight.**
  That is what commons did, and changing it would make rare items common in
  exactly the tables where every line is unlikely.
- **Never a stack of zero.** Commons returned `minAmount` as-is, so an entry
  stored with `0` produced a zero-quantity item that vanished on its way to the
  chest. `amountOf` never goes below one, and a reversed range gives the low end
  instead of nothing.
- **The module stores nothing.** No registry, no cache, no per-plugin owner: a
  table is a `List<LootEntry>` belonging to whoever holds it (a chest template, a
  spawner, an event configuration). A registry in the library would be a second
  place to keep in sync.
- **Building the `ItemStack` sits behind `LootItems`.** It is the only part that
  needs a server; the written grammar, the dice, the amounts and the codec are
  tested without one.
- **`LootEntry` is immutable** with a `toBuilder()` that preserves the id and a
  `copy()` that duplicates, so an admin saving the table doesn't change a line
  underneath a chest that is being filled.

### Region selection — the tool is handed over, the result is confirmed

The `region` block selector is product, not a raw API. What an admin sees is
what they saw in ExyliaCommons, without its bugs.

- **A golden axe, not a wooden one.** The wooden axe is WorldEdit's wand and gets
  confused with it. The default is `GOLDEN_AXE`, with a name, lore and glint.
- **It is actually handed over.** Telling someone to select with a tool they
  don't have is telling them nothing. It goes to a **free** slot: to the hand
  only if the hand is empty. Commons did `setItemInMainHand` and destroyed
  whatever was there — that isn't reproduced.
- **It is given back no matter what.** Confirming, cancelling, leaving the server
  and disabling the plugin all go through the same release path.
- **You see it while you choose.** One corner is drawn as the block it is; two,
  as the box they form. The outline sampler is the same one `visualize` uses, so
  the cost was already measured and bounded.
- **Two corners are a proposal, not an answer.** Shift + left click confirms. The
  first version of this module answered on the second click, so an admin who
  missed the block had already created the arena.
- **With no confirmation, only right click closes.** A left click never
  completes, not even if there already was a second corner: fixing the one you
  just placed can't end the selection.
- **Giving the tool back can never leave the session hanging.** It is pulled from
  the registry and the future is completed **before** touching the inventory, and
  that part is guarded. The other way round, a `getInventory()` that blew up left
  the player unable to select with any plugin until they reconnected.
  `LinkageError` is caught as well as `RuntimeException`: building an `ItemStack`
  resolves the item registry and that arrives as an `Error`.
- **No `isAir()` on testable paths.** It is compared against `AIR`/`CAVE_AIR`/
  `VOID_AIR`, as `item/Source` already documents: `isAir()` asks the block
  registry and only a live server has one.
- **The scheduler is ExyliaLib's, not the consumer's.** A plugin is already
  disabled when its selections are released, and a disabled plugin can't schedule
  the return of its own tool. On Spigot and Paper, if the caller is already on the
  main thread, it is written inline: one tick of delay is enough time to click and
  wonder why nothing is happening.

### Editors — one engine, batteries included

Everything an admin edits on screen (rewards, loot, commands, effects, items,
locations) goes through `net.exylia.lib.util.editor`. Never a per-type menu of
your own, never a per-player session map.

- **Commons had five copies of the same screen** — rewards, loot, potions,
  commands, messages — and four of them resolved the row by slot number, so an
  edit landing after the list changed edited a different row. Here there is one
  screen and the rows carry their element.
- **The engine doesn't know what it edits.** The domain knows the editor
  (`PluginRewards.editor`), never the other way round. `EditorIsGenericTest`
  reads the bytecode and fails if `util.editor` names `util.reward`, `util.loot`
  or `util.command`.
- **A generic engine with no editors gets used by nobody.** That is exactly what
  happened with `panel`: it was deleted without a single plugin having touched
  it. The library ships the descriptors for the types that are already its own.
- **Nothing is written until you save.** The list is copied; cancelling is free.
  Five endings (save, cancel, close, quit, disable) and the first one wins; four
  of them are cancel, because a screen taken away from you was never confirmed.
- **The clipboard belongs to the player, not to the screen.** One bucket per
  `typeKey`, holding whatever elements were copied — one or forty — and pasting
  doesn't empty it: pasting the same table into twelve chests is twelve clicks.
- **Editing a row is a dialog, not seven clicks.** Every field at once and
  **always prefilled**: fixing a name is not retyping it from memory. Long fields
  ask for height (`lines`), because a one-line box shows twenty characters and a
  display name is twelve color tokens.
- **The icon is inserted, not held.** Commons read the hand, which forced you to
  close the screen, find the item and come back — and from a menu you couldn't.
  It is a window with a slot, and the item **always comes back**: on confirm, on
  close, on quit and when the plugin is disabled.
- **And there is a single icon picker, in `input`.** Asking for something belongs
  to the input module; the editor already depends on it for `choice`, `search`
  and `form`, so `IconInput` is the only one and `util.editor` has no copy. From
  1.56.0 to 1.58.0 there were two — one with `HELD` and one with `INSERT` — which
  is exactly the duplication this module exists to prevent.
- **State lives in the window.** Never a `Map<UUID, Session>`: a player with a
  chest open on top of an editor is looking at the chest.
- **The pickers read the registry, not `values()`.** Several of those types
  stopped being enums and a data pack can add to any of them.
- **A plugin adds its buttons, but doesn't choose the slot.** The screen decides:
  if there are buttons, the bottom row of rows becomes the band and the page drops
  from 45 to 36; if there are none, the 45 stay. In commons the slot was written
  by the caller, which is how a button ends up on top of the save button when
  somebody changes the screen. Nine fit; the tenth is rejected at construction
  time, not silently left undrawn.
- **A button touches the working copy and nothing else.** Loading a 40-line
  preset is undone by cancelling like any other edit, which is the only thing
  that makes offering a destructive button safe.
- **And if a button needs to ask, `EditorView.ask`.** A dialog, an anvil and a
  search need the screen, so a button can't open them on its own: the close would
  read as the player leaving and the editor would drop its working copy. `ask` is
  the same door `EditorDescriptor.edit` uses — the window steps aside for the
  question and comes back on the page it was on. Since 1.71.0.

### Conditional effects — the payload is a sequence, not forty fields

What in ExyliaCommons was `EffectEntry` is two things here that are already
written.

- **Commons' `EffectEntry` was 40 fields and 8 types**, with a `switch` that grew
  with every new type. Its own javadoc said it was a copy of `RewardEntry`. Here
  the payload is a sequence and the gating is ten fields.
- **The sequence already expresses those 8 types and 5 more**: particles, sounds,
  potions, fireworks, titles, action bar, chat, lightning, explosions, breaking
  blocks, commands, shapes and pauses. And it compiles once instead of re-parsing
  on every play on the region thread, which is what commons did.
- **The audience is a number, not an enum and a number.** `0` or less is the
  player alone, a finite number is the radius in blocks, `WHOLE_WORLD` is the
  entire world. An enum whose meaning is "look at the other field" is two ways of
  saying the same thing and one way of saying something contradictory.
- **Permission and condition come before the dice**, same as in rewards and for
  the same reason: who *may* see something doesn't depend on luck.
- **`delayTicks` is not a `[DELAY]`.** The line's one delays what comes after it;
  the entry's delays that effect and lets the ones beside it go out on time.
- **What commons left written is read.** `EffectCodec.decode` accepts both shapes
  and translates the old one on the fly; nobody re-authorizes a mine's effects.
  The translation is one-way: writing the old shape back would tie every effect
  again to the eight types it knew.
- **A broken condition is warned about once, not once per play.** A mine effect
  fires thousands of times.
- **The notation is the storage format, not the interface.** Since 1.71.0 an
  effect is edited as a *list of lines*: adding one asks what it plays (every
  token, shapes included), then which one — the same search as the icon picker,
  read from the server registry — and only then opens a form with the fields that
  token actually reads. Nobody writes `[CIRCLE] FLAME;radius:1.5` to get a circle
  of flames, and nobody has to remember that in a circle it's called `radius` and
  in a pair of wings it's `span`.
- **Empty things aren't written.** A blank field is omitted instead of stored
  with its default value: the line that comes out of the screen is the same short
  line a person would have written, and a hand-made file and a click-made one are
  the same thing. A token the library doesn't recognize stays editable as text and
  comes back exactly as it was: an editor that deletes what it can't describe eats
  a config that was working.

### Commands — always Lamp, never a hand-rolled executor

Every command is written with **Lamp** (`io.github.revxrsal:lamp.*`), the
ecosystem's baseline: `compileOnly` in Gradle and `libraries:` in the plugin.yml
so the server downloads it from Maven Central. Never `onCommand`, never a
`CommandExecutor` of your own, never a version other than the one the rest of the
plugins use.

### Reload — everyone reloads their own

- **There is no reload system.** `Configs.reloadAll(plugin)` + `onReload` cover
  it; a plugin reloads itself in three lines and never touches the lib.
- **`/exylialib reload` reloads the lib's six files** (`config.yml`,
  `colors.yml`, `formats.yml`, `economy.yml`, `input.yml`, `messages.yml`) and
  nothing belonging to a consumer. The palette alone is enough to recolor the
  whole server: `Colors.apply` → the `TextEngine` cache is dropped →
  `BoardManager` and `HologramRuntime` are resent whole. `reloadPalette()` keeps
  the name from when the palette was the only file.
- **`info` and `stats` add no counters**, they only show what the modules already
  expose (`Effects.active()`, `Databases.registered()`, …) and what Bukkit
  already knows (who declares `ExyliaLib` in their `plugin.yml`). A diagnostic
  that forces you to instrument the lib stops being a diagnostic.
- **"Reload lib → reload plugin" is forbidden**: a plugin needs nothing from the
  lib to reload itself, and reloading the palette from a consumer would resend
  the visuals of EVERY plugin.
- **A plugin declares its reload with `Reloads`**: named steps, in order, and a
  step that blows up **does not abort the following ones** — it is reported by
  name and the run continues. A half-done reload with no warning is worse than a
  failed one.
- **The lib notifies, it doesn't invoke.**
  `Reloads.onLibraryReload(plugin, action)` runs after `/exylialib reload`; it
  is for whatever a plugin parsed once and kept (a GUI from `onEnable`). A
  listener that blows up is reported against its own plugin and doesn't hold up
  the others; they are released on disable.
- **A normal `step` does NOT listen to the lib.** Only
  `stepAlsoOnLibraryReload`. Re-reading a plugin's own files is not what a
  recolor means.
- **Reload is synchronous.** Reading small YAMLs and resending packets needs
  neither futures nor an orchestrator: that was the commons ceremony.
- **A module that keeps a `Component` (or anything derived from the palette)
  beyond a single render MUST expose `invalidateAll()`** and be called from the
  palette listener in `ExyliaLib.loadPalette`. It is a requirement for a new
  module to enter the lib.
  - Already wired: `TextEngine` (via `Colors.apply`), `BoardManager`,
    `HologramRuntime`, `EffectRuntime`, `ItemCache`.
  - The "static text is drawn once" shortcut is exactly what creates this bug: in
    1.16.0 static effects kept the old colors.
  - What a module caches unrelated to the palette (clans, parsed potions,
    cooldowns) is deliberately left alone.
  - The table of what reloads what is in `docs/reload.md` and `PaletteReloadTest`
    covers it.

### Debug — six methods and one toggle

Every console message goes through `net.exylia.lib.debug.Debug`. Never
`System.out`, never hand-written ANSI, never a per-plugin logger.

- **`log`, `success`, `warn`, `error`, `debug`.** There are no categories, no
  numeric levels and no format configuration. Commons had four classification
  axes and forty entry points to say these five things; at 3 in the morning
  nobody picks well among forty options.
- **A line is `[Plugin] [WARN] message`.** The name is drawn as a
  `{secondary}`→`{primary}`→`{secondary}` gradient and the tag is chosen by the
  method, never by the caller: they are the same five things, not four new axes.
- **The color comes from the server palette** and the message is appended
  literally: a stack trace full of `&` and `{}` comes out as-is.
- **The gradient is read on every line, not cached.** That is why this module
  responds to a reload without `invalidateAll()`, and why it isn't in
  `ExyliaLib.loadPalette`. A plugin name is twelve characters and a log is not a
  hot path: caching would buy nothing and would pay for the coupling.
- **Whose line it is comes from the argument, not from the method.** `Debug.of`
  with the consumer's plugin when the problem is theirs (their `database.yml`,
  their unreadable menu), with the lib when it is the lib's. Commons split every
  type into `logPluginX`/`logLibX` with the lib's prefix hardcoded: that asked the
  caller which jar they were in, which is exactly what nobody asks while reading a
  console, and it was chosen wrong silently.
- **`debug()` only prints with `enabled(true)`**; the toggle comes from the
  plugin's config. The rest always print.
- **The banner (`motd()`) is the name in ASCII art**, framed by a blank line on
  each side and closed with the version + debug state + the Exylia link: the
  commons frame, which a banner wedged between the startup noise of two other
  plugins doesn't have. jfiglet is shaded and relocated, out of the POM.
- **It never breaks a startup**: with no font in a broken jar, it prints the name
  in plain text.

### Packets before state

If the effect only has to be seen by the client, it is a packet, not a real
entity. Holograms, display items, glow, previews, cosmetic effects: all of that
as a packet costs the server a fraction of what it costs as a ticking entity.

Rule: if the server doesn't need to simulate that object, the server must not
know about it.

### Database

- Connections through a **bounded pool** (HikariCP). Never open a connection per
  operation.
- Every DB operation off the main thread, with `runAsync`.
- The result comes back to the right thread (`runAtEntity` / `runAtLocation`)
  before touching anything in the game.
- Cache with Caffeine so you don't hit the DB in a hot path. Batch writes
  together rather than one query per event.
- **The `database.yml` is the ExyliaCommons one**: one block per engine under
  `database:`, same key names. A server already running commons plugins keeps its
  credentials without touching the file. **Only** the keys the lib honors are
  declared; `server-id`, `write-behind`, `cache`, `redis` and the rest of
  `settings` are pruned and reported, because a setting that no longer does what
  it says is worse than none.
- **A settings block is not a value.** `Coercions` rejects a section before
  trying to convert it; without that, `String.valueOf` wrote
  `MemorySection[path='database', root='YamlConfiguration']` into the file as if
  the owner had typed it, and the pruning took their MySQL password down with it.
  It wasn't a `database` bug: it affected any `String` field of any config.
- **The flat layout from 1.24–1.30 migrates**, and its connection fields land in
  the block its `type` names, not always in `mysql`.
- **A column the table demands and no record declares stops being demanded.**
  Every commons entity inherited `created_at`/`updated_at` from `Entity`, and its
  `CREATE TABLE` wrote them `NOT NULL` with no default. No record in the lib
  declares them, so the first insert after migrating names fewer columns than the
  table asks for and the row is rejected. It is relaxed, not deleted and not
  filled in: deleting takes away the values that an unmigrated plugin still
  reads, and filling invents a creation date that isn't one. Only the ones with
  no default and no generated value; if the engine rejects the `ALTER`, it is
  left as it was rather than blocking startup.
- **The key comes with the row; the counter is the exception.** A player's `UUID`
  already identifies their row and doesn't need another number. `@Id(generated)`
  is for what has no identity until it exists (a design in a shared library, an
  audit entry), and it forces `insert` instead of `save`: an upsert needs a row
  to merge against, and the key of a row that doesn't exist yet is a placeholder
  — merging against zero overwrites whatever row has that id.
- **But a placeholder stops being one as soon as the row exists.** A row read
  from the database carries the key the engine chose and names exactly one row,
  so it is rewritten with `update`. Without it, a table with a generated key could
  be inserted and then never modified again: `save` rejected it and `insert`
  published a duplicate. `update` **never creates** — a key that finds nothing
  changes nothing, because the row it would create would have a different key
  from the one the caller is holding.
- **The key is read from the same statement that wrote the row**
  (`getGeneratedKeys`). A `SELECT MAX(id)` or a `LAST_INSERT_ID()` afterwards go
  out through another pooled connection, and on a table two servers write to the
  number belongs to whoever inserted last. `count()+1` and `MAX(id)+1` also
  recycle the key of a deleted row: whatever kept the old id starts pointing at
  somebody else's row.
- **Every engine writes it its own way and none of them is invented here**:
  `AUTO_INCREMENT` on H2, MySQL and MariaDB; `GENERATED BY DEFAULT AS IDENTITY`
  on Postgres (never `SERIAL`: it leaves the sequence alive when the column is
  dropped); and Mongo, which has no counter, keeps one per table in
  `exylia_sequences` with an atomic `$inc`.
- **`saveAll` does not accept generated keys.** A batch can't answer with the
  keys it was given, and whoever inserted a hundred rows without learning their
  ids has stored a hundred rows nobody can reference.

### Redis — shared cache, never storage

Everything that depends on Redis goes through `net.exylia.lib.redis`. No plugin
calls anything: it is switched on from `database.yml` and the repositories a
plugin already had start answering from Redis and notifying the other servers.

- **Store first, then notify, never the other way round.** A peer receiving the
  message re-reads immediately; if the message could get ahead of the value, it
  would cache exactly the row it was told to drop. It is the rule the whole
  module depends on, and there is a test that catches it being inverted.
- **The join waits for no message.** A proxy moves a player between servers
  within the same tick. The destination server misses in its memory (the player
  wasn't there) and reads from Redis, where the other one already wrote. Pub/sub
  only saves work for those who already had the row. Making it depend on the
  message turns the handoff into a race that is sometimes lost — that is exactly
  "my killeffect reset when I switched servers".
- **The database is the truth.** Every write completes against it *before*
  anything is cached. Losing Redis costs speed and cross-server freshness, never
  data.
- **Only what has a key is cached.** `find` and `exists` yes; `select` and
  `count` no. A leaderboard changes when anyone changes and no key predicts it.
  Commons cached them and paid for it by dropping the table's entire keyspace on
  every save.
- **Absence is not cached.** A first join writes exactly that row an instant
  later.
- **A `set` that fails is not announced.** Sending peers to look for a value that
  wasn't stored turns one failed write into a network-wide fallback to the DB for
  the whole TTL.
- **The value is encoded the way the DB encodes it**, via `EntityModel`. Commons
  cached with bare Gson while writing with its serializers: the same field had
  two representations.
- **The key carries the table name and the *stored* id.** Half a dozen plugins
  declare a `PlayerData`; and a `UUID` with `toString()` on one side and its
  codec on the other gives you a cache that never hits and looks healthy.
- **`server-id` is the one from the config, not a random UUID.** In commons it
  was regenerated on every startup: one collision left two servers ignoring each
  other forever and no log could name the sender.
- **A Redis that is down fails fast, it doesn't hang.** The pool's `maxWait` is
  bounded; commons left it at the default (infinite) and an outage turned into
  parked threads.
- **Jedis is confined to `JedisClient`.** Verified in bytecode. A server without
  the library doesn't load that class and everything keeps working.
- **Channels are for events, never for state.** `Channels.of(plugin)
  .channel(name)` publishes and subscribes across servers (and within the same
  one, always, with or without Redis). A message can be lost if a server was
  restarting; a row can't. What has to be *known* goes to the repository; what
  has to be *announced* goes to the channel. The handler runs on the subscriber's
  thread: `Tasks` before touching Bukkit.
- **There is no `@PlayerSession` and no flush-on-quit.** In commons it was dead
  code (zero annotated entities across the whole ecosystem) and it wasn't what
  made the handoff work. Here writes are durable on completion.

### Transfer — the file is one line per row, and the result has three values

Moving a plugin's database to another machine or another engine goes through
`net.exylia.lib.database.transfer`. Never a hand-run `mysqldump`, and never a
giant JSON written by the plugin.

- **A gzipped NDJSON file, not a nested object.** Commons wrote
  `{tables:{t:[...]}}`: a parser can only accept it or reject it whole, so a dump
  cut short by a full disk was worth nothing. One line per value also lets you
  **name the line that failed** — which is what an operator can open.
- **The result is three values, not a boolean.** `PARTIAL` exists because the
  commons importer logged a failed batch, carried on, and returned
  `success(true)`: losing a thousand rows and losing none were the same answer.
- **The wipe deletes rows, never the table** (1.76.0). One `DELETE` per table,
  not `TRUNCATE` — on MySQL and MariaDB it is DDL: it commits, it can't be undone,
  and the engine rejects it if another table references it — and never a `DROP`:
  the plugin has to keep working on the empty table exactly as it did on its
  first startup.
- **A table name that doesn't exist cancels the whole wipe before deleting
  anything.** Skipping it and emptying the rest is how a typo in `players` empties
  `kits` and answers `success`.
- **Confirmation is a typed code, not re-running the command.** "Run it again" is
  confirmed with up-arrow and enter, which is exactly the accident it protects
  against. The code is tied to a sender, a plugin and a table, is spent when used
  and expires after 60 seconds.
- **The command exports before deleting and aborts if the export fails.** The API
  (`wipeAll`) doesn't back up on its own: whoever already has their own doesn't
  pay for a dump, and whoever uses the command can't forget about it.
  A skipped table, a column that no longer exists or a rejected row drop it to
  `PARTIAL` and it never returns to `SUCCESS`.
- **Values are written typed, never inferred.** Gson with no type token turns
  every number into a `Double` — that is what commons did — so every `long` above
  2^53 and every decimal came back silently changed. The `BigDecimal` travels as
  a **string**: the text *is* the value, and money is the only reason for a column
  to be one.
- **`force` merges, it doesn't replace**, and the sentence is written out in full
  wherever it is offered. The row whose key is in the dump is overwritten; the one
  that isn't stays. Whoever reads it as "replace" and runs it has merged two
  servers into one table with nothing saying so.
- **Rows are bound by column name**, using the header's layout. A record that
  gained a component since the dump has to be importable; binding by position
  would put the `UUID` into the clan column and report success.
- **After importing explicit ids the counter is moved forward.** H2 and Postgres
  don't advance it on their own, so the next insert asks for a key the imported
  rows already have. It has a test, and the test fails with the real collision
  when the call is removed.
- **It never runs a codec.** Rows travel in storage form, so a serialized
  inventory is Base64 text on both ends and the module is tested without a
  server.
- **A plugin shows up when it asks for its first repository, not before.** One
  that registers late exports fewer tables than it has, and from the outside you
  can't tell: that is why the tables found are **named** and not just counted.
- **Declared debt: `writeRows` doesn't invalidate Redis.** On purpose — one
  message per batch would send every peer to the database for the whole table,
  which is how commons sank. Importing over a **live** table with Redis leaves the
  other servers serving stale rows until the TTL; over a new table (the migration
  case) it doesn't apply. The command warns exactly in that case.

---

## Quality bar for a module

A module enters ExyliaLib only if it meets all of this:

1. **It solves a real, repeated problem** across several plugins. Speculative
   "just in case" infrastructure is not added.
2. **Small, obvious API.** The consumer has to get it right without reading the
   implementation. If you have to explain the order of the calls, the API is
   wrong.
3. **Documented in Javadoc**, in English, with a usage example on the entry class
   and on every non-trivial method. Document the *why* and the contracts
   (threads, nullability, lifecycle), not what the signature already says.
4. **Works the same on Bukkit and Folia**, or explicitly declares that it doesn't
   apply.
5. **No leaks.** Nothing stays alive when the consuming plugin is disabled.
6. **Class isolation.** Platform-specific types are confined to a class that is
   only loaded on that platform. The library must load on plain Spigot.
7. **With behavior tests**, not just compilation ones.
8. **It responds to reload.** If the module keeps anything derived from the
   palette, it exposes `invalidateAll()` and hooks into `ExyliaLib.loadPalette`;
   if it keeps nothing, that is documented as not applicable. See *Reload* and
   `docs/reload.md`.

### Structure

```
net.exylia.lib
├── ExyliaLib          plugin runtime, lifecycle and cleanup only
├── platform/          platform detection
└── <module>/
    ├── public API     interfaces and entry point
    └── internal/      implementations — nothing outside depends on this
```

Anything under `internal` is free to change without notice. Anything outside it
is a public contract: breaking it forces a major version bump.

---

## Documentation and path map

User documentation lives in `docs/`, **one file per module**, with an index in
`docs/README.md`. It is product (the library is open): it is written in English,
like the README and the Javadoc. This AGENTS file and internal communication are
in Spanish.

### Documentation rules (anti-hallucination)

1. **You document against the code, never from memory.** Before writing or
   touching a doc, extract the real signatures:

   ```bash
   grep -n "    public" src/main/java/net/exylia/lib/util/Cooldowns.java
   ```

   If the doc and the code disagree, the doc is wrong and gets fixed in that
   commit.
2. **Every new or changed API updates its doc in the same commit.** A PR that
   changes `Cooldowns.java` and doesn't touch `docs/cooldowns.md` is incomplete.
3. **The doc describes contracts** (what it does, threads, nullability,
   lifecycle, measured cost), not the implementation. What changes freely in
   `internal/` is not promised in a doc.
4. **Every module carries its `@since`** in the index and in the Javadoc. Version
   map below.
5. **Any performance number you claim comes from a benchmark in the repo.** If
   there is no measurement, the number isn't claimed; you write the design ("it
   is compared on read, there is no task") without a figure.
6. **The paths below are the source for locating code.** Read them before
   grepping blindly; they exist so that documenting or changing a module doesn't
   require re-exploring the repo.

### Module map

Code root: `src/main/java/net/exylia/lib/`. Test root:
`src/test/java/net/exylia/lib/` (same package structure).

| Module | Public API | Internal | Doc | Since |
| --- | --- | --- | --- | --- |
| task | `task/Tasks`, `TaskScheduler`, `TaskHandle`; `platform/Platform` | `task/internal/` | [docs/task.md](docs/task.md) | 1.0.0 |
| config | `config/Configs`, `ConfigFile`, `MutableConfig`, `Key`, `Comment`, `Migration`, `ConfigIssue`, `Schema` (1.50.0); `Map<String, V>` sections (1.63.0) | `config/internal/` (+ `SchemaProjection`) | [docs/config.md](docs/config.md) | 1.1.0 |
| text | `text/Text`, `Colors`, `Palette`, `Lines` (1.48.0), `LibraryMessages` (1.67.0) | `text/internal/` | [docs/text.md](docs/text.md) | 1.2.0 |
| gradients over Components and character maps | `text/Gradients` (`blend`, `at`, `wrap`, `paint`, `apply`, `length`), `text/CharMaps.transform` | `SmallText` is only the table and delegates to `CharMaps`; `Debug.gradientName` uses `Gradients.blend` | [docs/text.md](docs/text.md) | 1.102.0 |
| placeholder | `placeholder/Placeholders`, `Template`, `Resolver`, `Request` | `placeholder/internal/` | [docs/placeholders.md](docs/placeholders.md) | 1.3.0 |
| effect | `effect/Effects`, `Timer`, `Ticks`, `Display`, `EffectConfig` | `effect/internal/` | [docs/effects.md](docs/effects.md) | 1.4.0 |
| scoreboard | `scoreboard/Scoreboards`, `Board`, `SidebarConfig` | `scoreboard/internal/` | [docs/scoreboard.md](docs/scoreboard.md) | 1.5.0 |
| hologram | `hologram/Holograms`, `Hologram`, `HologramConfig` | `hologram/internal/` | [docs/hologram.md](docs/hologram.md) | 1.6.0 |
| client | `client/Clients`, `PluginClients`, `Waypoint`, `Cooldown`, `ClientBrand`, `ClientTeam`, `PluginTeams` | `client/internal/` (+ `TeamRegistry`) | [docs/client.md](docs/client.md) | 1.7.0 (teams 1.36.0, ownership 1.48.0) |
| clan | `clan/Clans`, `Clan`, `ClanBridge` | `clan/internal/` | [docs/clan.md](docs/clan.md) | 1.8.0 |
| util (potions) | `util/Effects` | — | [docs/util.md](docs/util.md) | 1.9.0 |
| util (cooldowns) | `util/Cooldowns`, `CooldownScope`, `PluginCooldowns`, `ItemCooldowns` | `util/internal/CooldownStore` | [docs/cooldowns.md](docs/cooldowns.md) | 1.10.0 |
| scopes + persistence + items | (same files) | `ExyliaLib` (join/quit/shutdown/timer) | docs/cooldowns.md | 1.11.0 |
| decimals + `TimeFormats` + `Timer.ofCooldown` | `util/TimeFormats`; `effect/Timer` | `effect/internal/CooldownTimer` | docs/util.md, docs/effects.md | 1.12.0 |
| chat | `chat/Chats`, `ChatRule` | `chat/internal/` (`ChatRuntime`, `ChatListener`) | [docs/chat.md](docs/chat.md) | 1.89.0 |
| debug | `debug/Debug` | shaded jfiglet (`internal/jfiglet`) | [docs/debug.md](docs/debug.md) | 1.13.0 |
| `/exylialib` command | — | `internal/ReloadCommand`, `internal/Commands` (Lamp confined) | [docs/reload.md](docs/reload.md) | 1.14.0 |
| reload | `reload/Reloads` (+ `Reloads.Report`) | triggered in `ExyliaLib.loadPalette`; released in `onPluginDisable`/`onDisable` | [docs/reload.md](docs/reload.md) | 1.15.0 |
| effects in messages + centering | `text/Centering` | `text/internal/EffectTag`, `EffectTagPlayer`, `text/FontWidths` | [docs/text.md](docs/text.md) | 1.17.0 |
| per-plugin prefix | `text/Prefixes` | substitution in `Text.build`; cleanup in `ExyliaLib.onPluginDisable` | [docs/text.md](docs/text.md) | 1.17.2 |
| per-plugin effect ownership | `effect/Effects.of`, `PluginEffects` | `effect/internal/EffectRuntime` (per-plugin registry) | [docs/effects.md](docs/effects.md) | 1.18.3 |
| skull | `skull/Skulls`, `SkullSource`, `SkullBuilder`, `SkullHandle` | `skull/internal/` | [docs/skulls.md](docs/skulls.md) | 1.19.0 |
| action | `action/Actions`, `PluginActions`, `ActionCall`, `ActionContext`, `ActionSequence` and helper types | `action/internal/` | [docs/actions.md](docs/actions.md) | 1.20.0 |
| region | `region/Regions`, `PluginRegions`, `RegionSnapshot`, `RegionShape` and shapes, `PolicyKey`/`PolicySet`, `RegionData`/`RegionCodec`, `PlayerRegionChangeEvent` (owner filter 1.48.0), selection and visualization | `region/internal/` | [docs/regions.md](docs/regions.md) | 1.23.0 |
| block | `block/Blocks`, `PluginBlocks`, `ClickableBlock`, `BlockClick`, `BlockButton` | `block/internal/` | [docs/blocks.md](docs/blocks.md) | 1.110.0 |
| what a player must see when they come back | `client/Clients.Waypoints.restoreWith` | `client/internal/ClientRuntime.restore` (per-owner RESTORERS) | [docs/client.md](docs/client.md) | 1.58.0 |
| writing into a menu's editable slots | `ui/UiSession.input`, `inputs(Map)` | `ui/internal/Session.requireInput` | [docs/menus.md](docs/menus.md) | 1.58.0 |
| drawing a stored icon | `item/Items.icon` | `item/internal/ItemRenderer.icon` (a single copy: `util/editor/internal/Icons.base` delegates) | [docs/items.md](docs/items.md) | 1.58.0 |
| clearing the entities in a region | `region/PluginRegions.clearEntities` (with and without a predicate) | `region/internal/RegionEntities` | [docs/regions.md](docs/regions.md) | 1.58.0 |
| commons-style selector | `region/SelectionOptions` (builder), `SelectionState.AWAITING_CONFIRMATION`, `SelectionSession.confirm` | `region/internal/SelectorWand`, `SelectionPreview`, `SelectionRuntime`, `SelectionListener` | [docs/regions.md](docs/regions.md) | 1.56.0 |
| database | `database/Databases`, `PluginDatabase`, `Repository`, `Query`, `Table`, `Column`, `Id`, `Indexed`, `Index`, `Codec`, `DatabaseException`, `DatabaseSettings` | `database/internal/` | [docs/database.md](docs/database.md) | 1.24.0 |
| format | `format/Formats`, `Numbers`, `Amounts`, `Dates`, `FormatSettings`; `util/TimeFormats` | `format/internal/` | [docs/formats.md](docs/formats.md) | 1.25.0 |
| economy | `economy/Economy`, `CurrencyProvider`, `EconomyResponse`, `TransferResult`, `EconomySettings`, `EconomyException` | `economy/internal/` | [docs/economy.md](docs/economy.md) | 1.26.0 |
| input | `input/Inputs`, `PluginInputs`, `InputRequest` and per-value types, `ChoiceInput`, `SearchInput`, `FormInput`, `FormField`, `FormKey`, `FormValues`, `InputResult`, `InputOutcome`, `Validation`, `InputParser`, `InputException`, `InputSettings` | `input/internal/` | [docs/input.md](docs/input.md) | 1.31.0 |
| command | `command/Commands`, `PluginCommands`, `CommandLine`, `CommandActor`, `CommandResult` | — | — | 1.21.0 |
| item | `item/Items`, `PluginItems`, `Item`, `Source`, `Appearance`, `Traits`, `Potion`, `Trim`, `Banner`, `Consumable`, `Modifier`, `Problems` | `item/internal/` | [docs/items.md](docs/items.md) | 1.22.0 |
| values on a live item | `item/ItemValues`, `PluginItems.values()` | — | [docs/items.md](docs/items.md) | 1.63.0 |
| util (expressions) | `util/Expressions` | — | [docs/util.md](docs/util.md) | 1.63.0 |
| ui | `ui/Menus`, `PluginMenus`, `UiSession`, `UiDefinition`, `UiSection`, `UiEntry`, `UiItem`, `UiKeys`, `UiFillers`, `UiRefresh`, `UiSounds`, `UiAnimationSpec`, `ClickBindings`, `ClickKind`, `ClickPolicy`, `Pages`, `Slots` | `ui/internal/` | [docs/menus.md](docs/menus.md) | 1.22.0 |
| formatted row values | `ui/UiEntry.Builder.withFormatted`; `item/PluginItems.render(item, viewer, values, formatted)` | `item/internal/ItemRenderer.text` | [docs/menus.md](docs/menus.md), [docs/items.md](docs/items.md) | 1.28.0 |
| small text | `small-text` in `internal/LibrarySettings`; measuring in `text/Centering` | `text/internal/SmallText`, `TextEngine.smallText` | [docs/text.md](docs/text.md) | 1.29.0 |
| util (sequence) | `util/sequence/Sequences`, `PluginSequences`, `Sequence`, `SequenceTarget`, `SequenceRun`, `SequenceStep`, `Shape` | `util/sequence/internal/` | [docs/sequences.md](docs/sequences.md) | 1.30.0 |
| effects with dice, condition and audience | `util/sequence/EffectEntry`, `EffectCodec`, `PluginSequences.play(List, target)`/`editor` | `util/sequence/internal/EffectPlayer`, `util/sequence/EffectDescriptor`; `[MESSAGE]` in `SequenceCompiler` | [docs/sequences.md](docs/sequences.md) | 1.57.0 |
| editing an effect line by line (pick what it plays, search for it, and a form with that token's fields) | — | `util/sequence/SequenceLine`, `util/sequence/LineDescriptor` | [docs/sequences.md](docs/sequences.md) | 1.71.0 |
| shared conditions | — | `util/internal/Conditions` (moved from `util/reward/internal`) | [docs/rewards.md](docs/rewards.md) | 1.57.0 |
| util (preview) | `util/preview/Previews`, `PluginPreviews`, `Preview`, `PreviewSettings` | `util/preview/internal/` | [docs/previews.md](docs/previews.md) | 1.30.0 |
| redis | `redis/Redis`, `RedisSettings` | `redis/internal/` (Jedis confined to `JedisClient`) | [docs/redis.md](docs/redis.md) | 1.31.0 |
| cross-server pub/sub channels | `redis/Channels`, `PluginChannels`, `Channel`, `Message`, `Redis.serverId(plugin)` | `Channel` frames `<server-id>` + pipe + `<payload>` over `RedisRuntime.client`; local bus without Redis | [docs/redis.md](docs/redis.md) | 1.75.0 |
| proxy (bridge with ExyliaProxyUtils) | `proxy/Proxy`, `ProxyReply`; default transport for `command/PluginCommands.proxy()` | `proxy/internal/ProxyRuntime` (`exylia:bridge` channel, in-flight request map, ping on the first join), `Wire`, `BridgeCommands`; `CrossServer.connect`/`connectOther` use the `connect` module when the bridge answered and the `BungeeCord` channel when it didn't | [docs/proxy.md](docs/proxy.md), [docs/teleport.md](docs/teleport.md) | 1.101.0, 1.102.0 |
| auto-update poll | `update-check-minutes` in `internal/LibrarySettings` | `internal/ExyliaLibUpdater` (ETag), timer in `ExyliaLib.startUpdateCheck` | [docs/reload.md](docs/reload.md) | 1.30.0 |
| generated keys | `database/Id.generated`, `Repository.insert`/`insertReturning` | `Dialect.insertGenerated`, `SqlBackend.insert` (`getGeneratedKeys`), `MongoBackend.insert` (`$inc`), `EntityModel.withId` | [docs/database.md](docs/database.md) | 1.32.0 |
| a click that redraws everything that can change | — | `ui/internal/Session.refreshAfterClick`, `redrawChangeable` | [docs/menus.md](docs/menus.md) | 1.44.0 |
| the block the item type writes by itself | `hide-attributes` (same file) | `item/internal/ItemComponents` (registry + `TooltipDisplay` through reflection), `ItemRenderer.hideAdditionalTooltip` | [docs/items.md](docs/items.md) | 1.46.0, 1.47.0 |
| modifying a row with a generated key | `database/Repository.update` | `Dialect.update`, `SqlBackend.update`, `MongoBackend.update` (no upsert), `EntityModel.hasPlaceholderId`, `CachedStorage.update` | [docs/database.md](docs/database.md) | 1.43.0 |
| util (rewards) | `util/reward/Rewards`, `PluginRewards`, `RewardEntry`, `RewardType`, `RewardCodec`, `RewardResult`, `RewardDelivery`, `RewardOutcome`, `OverflowPolicy`, `PendingRewards` | `util/reward/internal/` (`Providers`, `ItemGiver`, `Conditions`, `Rolls`), `util/reward/Previews` | [docs/rewards.md](docs/rewards.md) | 1.34.0 |
| util (snapshots) | `util/snapshot/Snapshots`, `PluginSnapshots`, `Snapshot`, `SnapshotPart`, `SnapshotCodec`, `SnapshotSettings` | `util/snapshot/internal/` (`PlayerState`, `SnapshotRow`, `LegacyRow`, `LegacyImport`, `SnapshotRuntime`) | [docs/snapshots.md](docs/snapshots.md) | 1.34.0 |
| util (teleport) | `util/teleport/Teleports`, `PluginTeleports`, `TeleportRequest`, `TeleportHandle`, `TeleportResult`, `TeleportCause`, `TeleportSettings`, `ExyliaLocation`, `ExyliaTeleportEvent`, `RandomArea`, `TeleportDirection`, `TeleportRequestTicket`, `TpaAcceptance`, `TpaOutcome` | `util/teleport/internal/` (`TeleportRuntime`, `RunningTeleport`, `TeleportPlan`, `Teleporter`, `SafeLocations`, `RandomLocations`, `BackHistory`, `TpaBook`, `CrossServer`) | [docs/teleport.md](docs/teleport.md) | 1.34.0 |
| util (wizard) | `util/wizard/Wizards`, `PluginWizards`, `Wizard`, `WizardBuilder` (+ `Branch`), `WizardStep` (+ `Prompt`), `WizardKey`, `WizardValues`, `WizardRun`, `WizardOutcome`, `WizardResult`, `WizardSettings`, `WizardException` | `util/wizard/internal/` (`WizardRuntime`, `WizardSession`, `WizardListener`); `init`/`forget`/`release` in `ExyliaLib` | [docs/wizard.md](docs/wizard.md) | 1.34.0 |
| console with the commons look | `debug/Debug` (gradient, per-type tag, `motd` frame) | `gradientName`/`blend` in `Debug` | [docs/debug.md](docs/debug.md) | 1.35.0 |
| util (world) | `util/world/Worlds` | `util/world/internal/` (`WorldsBackend`, `WorldsBackendDetector`, `WorldsReflection`, `Worlds3Backend`, `Worlds4Backend`) | [docs/world.md](docs/world.md) | 1.36.0 |
| nametag | `nametag/Nametags`, `PluginNametags`, `NametagStyle` | `nametag/internal/` (`NametagRuntime`, `State`, `NametagSink`; PacketEvents confined to `NametagPackets`) | [docs/nametags.md](docs/nametags.md) | 1.36.0 |
| packet | `packet/Packets`, `PluginPackets`, `Visibility`, `VisibilityRule`, `FakeBlocks`, `Movement`, `FakeGameMode`, `SilentContainer` | `packet/internal/` (`PacketRuntime`, `PacketSink`, `SectionGroups`, `Mirrors`; PacketEvents confined to `PacketHooks`) | [docs/packets.md](docs/packets.md) | 1.75.0 |
| overlay | `overlay/Overlays`, `PluginOverlays`, `OverlayDefinition`, `OverlayLock`, `OverlaySlots`, `OverlayKeys` | `overlay/internal/` (`OverlayRuntime`, `OverlayView`, `OverlayClicks`, `OverlayLoader`, `OverlayListener`, `OverlaySink`; PacketEvents confined to `OverlayPackets`) | [docs/overlays.md](docs/overlays.md) | 1.79.0 |
| util (combat) | `util/combat/Combat`, `CombatBridge`, `CombatStats` | `util/combat/internal/` (`CombatRuntime`, `CombatProvider`, `DeluxeCombatProvider`, `PvpManagerProvider`) | [docs/combat.md](docs/combat.md) | 1.36.0 |
| transfer | `database/transfer/Transfers`, `PluginTransfers`, `TransferReport`, `TableTransfer`, `TransferOutcome` | `database/transfer/internal/` (`DumpFormat`, `DumpWriter`, `DumpReader`, `DumpException`, `TransferRuntime`, `DumpFormatAccess`); command in `internal/ReloadCommand` on top of `internal/TransferAccess` | [docs/transfer.md](docs/transfer.md) | 1.36.0 |
| wipe | `database/transfer/PluginTransfers.wipeAll`, `wipe(String, String...)` | `TransferRuntime.wipe`; `Storage.deleteAll` (+ `SqlBackend.deleteAll`, `Dialect.deleteAll`, `MongoStorage`, `CachedStorage`, `GatedStorage`); `wipe` command in `internal/ReloadCommand` | [docs/transfer.md](docs/transfer.md) | 1.76.0 |
| `/exylialib info` and `stats` | — | `internal/ReloadCommand` (`dependentsOf`, `hologramsLine`) | [docs/reload.md](docs/reload.md) | 1.35.0 |
| `/exylialib export` and `import` | — | `internal/ReloadCommand` (`export`, `importDump`, `reportPanel`, `importPanel`, `safeName`, `KnownPlugins`) | [docs/transfer.md](docs/transfer.md) | 1.36.0 |
| `/exylialib wipe` | — | `internal/ReloadCommand` (`wipe`, `wipePreview`, `wipePanel`, `wipeAborted`, `badConfirmation`, `unknownTable`, `PendingWipe`, `WipeTargets`) | [docs/reload.md](docs/reload.md) | 1.76.0 |
| per-player banner | `item/Banner.template`, `Banner.isDynamic` | `item/internal/ItemReader.banner`, `TraitApplier.resolved` | [docs/items.md](docs/items.md) | 1.37.0 |
| parsed context and paginated title | — | `ui/internal/Session.parsed`, `merged`, `filledTitle` | [docs/menus.md](docs/menus.md) | 1.39.0 |
| title that follows the page | — | `ui/internal/Session.retitle`, `Titles`, `TitlePackets` (PacketEvents confined) | [docs/menus.md](docs/menus.md) | 1.40.0 |
| a `navigation` arrow that paginates on its own | — | `ui/internal/MenuLoader.placed` (per-section fallback) | [docs/menus.md](docs/menus.md) | 1.41.0 |
| a commons-inherited column the table demands | — | `database/internal/SqlSchema.relaxOrphanedColumns`, `Dialect.dropNotNull`, `SchemaReport.relaxedColumns` | [docs/database.md](docs/database.md) | 1.42.0 |
| multiline row value | `<nl>` in a `UiEntry`/`PluginItems.render` value | `item/internal/ItemRenderer.lore`, `spans`, `segment` | [docs/menus.md](docs/menus.md), [docs/items.md](docs/items.md) | 1.38.0 |
| schematic | `schematic/Schematics`, `PluginSchematics`, `SchematicResult`, `SchematicOutcome`, `RegenerateOptions` | `schematic/internal/` (`SchematicRuntime`, `SchematicStore`, `SchematicNames`, `Bounds`, `Engines`, `SchematicEngine`; FAWE confined to `FaweEngine`) | [docs/schematics.md](docs/schematics.md) | 1.48.0 |
| util (loot) | `util/loot/Loot`, `LootEntry`, `LootType`, `LootCodec` (importing a chest and editing properties, 1.77.0) | `util/loot/internal/` (`LootLines`, `LootRolls`, `LootItems`) | [docs/loot.md](docs/loot.md) | 1.56.0 |
| util (editor) | `util/editor/Editors`, `PluginEditors`, `ListEditor`, `EditorDescriptor` (`createAll` 1.77.0), `EditorForm`, `Clipboard`, `Pickers` | `util/editor/internal/` (`EditorRuntime`, `EditorHolder`, `EditorListener`, `Icons`) | [docs/editors.md](docs/editors.md) | 1.56.0 |
| icon by insertion, not from the hand | `input/IconInput.Way.INSERT` (replaces `HELD`) | `input/internal/InsertWindow`, routing in `input/internal/InputListener` | [docs/input.md](docs/input.md) | 1.59.0 |
| custom buttons in an editor | `util/editor/EditorButton`, `EditorView`, `ListEditor.button` (`EditorView.ask` 1.71.0) | `util/editor/internal/EditorHolder` (the band, the page size and the `ask` door) | [docs/editors.md](docs/editors.md) | 1.58.0 |
| util (named commands) | `util/command/NamedCommand`, `NamedCommands` | `util/command/NamedCommandDescriptor` | [docs/editors.md](docs/editors.md) | 1.56.0 |
| bundled editors | `PluginRewards.editor`, `Loot.editor`, `NamedCommands.editor`, `Effects.editor`, `PluginEditors.items`/`locations`/`pick`/`icon` | `util/reward/RewardDescriptor`, `util/loot/LootDescriptor`, `util/PotionEffectDescriptor`, `util/editor/ItemListEditor`, `LocationDescriptor` | [docs/editors.md](docs/editors.md) | 1.56.0 |
| tall, prefilled dialog | `input/TextInput.lines`, `FormField.lines` | `input/internal/DialogPackets.multiline` | [docs/input.md](docs/input.md) | 1.56.0 |
| util (heads) | `util/head/Heads`, `util/head/Head` | `util/head/internal/HeadDb` (headdb.net, one page per search; cache of the latest pages) | [docs/heads.md](docs/heads.md) | 1.82.0 |
| externally paginated search | `input/SearchInput.source(Pages)`, `SearchInput.Page`, `iconItem(fn)`, `PluginInputs.search(player, prompt)` | `input/internal/SearchView` (paginated mode, generation per fetch), `SearchTransport.refresh` | [docs/input.md](docs/input.md) | 1.82.0 |
| icon found in the catalog | `input/IconInput.Way.BROWSE` | `util/head/internal/HeadDb` | [docs/input.md](docs/input.md), [docs/heads.md](docs/heads.md) | 1.82.0 |
| cleanup | — | `internal/cleanup/` (`CleanupRuntime`, `LogCleaner`, `CleanupSettings`); `init` in `ExyliaLib.onEnable`, `reload` in `reloadPalette` | [docs/cleanup.md](docs/cleanup.md) | 1.90.0 |
| normalized time: `FULL` rolls up to days, and the parser reads back everything that is written (`ms w mo y` and decimals) | `util/TimeFormats.Style.FULL`; `input/InputParser.duration()` | — | [docs/util.md](docs/util.md), [docs/input.md](docs/input.md) | 1.87.0 |

Root classes that are not a module: `ExyliaLib.java` (lifecycle and cleanup),
`platform/Platform.java`, `internal/LibrarySettings`, `internal/ExyliaLibUpdater`.

### Injectable seams for tests (don't remove them)

They are package-private on purpose; the tests live in the same package:

| Class | Seam |
| --- | --- |
| `util/Cooldowns` | `setClock/resetClock` (the clock), `installStore/removeStore` (persistence), `trackedOwners/dirtyCount` (observation) |
| `util/ItemCooldowns` | `setOverlay/resetOverlay` (Bukkit's `setCooldown`) |
| `util/Effects` | `setResolver/setApplier`, `resetCache` |
| `debug/Debug` | `setSink/resetSink` (where the lines go) |
| `reload/Reloads` | `listenerCount()` (leak observation) |
| `item/internal/ItemRenderer` | `components(...)` (who writes the data components: `DataComponentTypes` demands a live server just by being named) |
| `item/internal/ItemComponents` | `forgetReportedForTests` (the warning already given, which is once per server) |
| `skull/internal/SkullRuntime` | `installForTests` (lookup and store), `seed` (a texture with no network) |
| `skull/internal/Lookup` | the interface that replaces Mojang in tests |
| `util/snapshot/SnapshotCodec` | `setItems/resetItems` (`ItemIo`: how an item becomes text — a real `ItemStack` can't be built without a server) |
| `util/snapshot/internal/SnapshotRuntime` | `forgetReportedForTests` (the warnings already given) |
| `database/transfer/internal/DumpFormatAccess` | `extension()`, `observeBatches` (the batches the reader hands over: the import's memory bound, observable) |
| `internal/TransferAccess` | the interface the command uses to export and import; `live()` is the real one, a fake replaces it with no database and no file |
| `schematic/internal/Engines` | `install(...)` (the engine: a fake replaces FastAsyncWorldEdit, so **everything** the module decides — name, folder, stage order, what happens when one blows up — is tested without FAWE and without a server) |
| `schematic/internal/SchematicEngine` | the interface installed there; its only real implementation is the one that names FAWE |
| `util/sequence/internal/EffectPlayer` | `forgetReportedForTests` (the warnings already given and the compiled sequences: "once" can only be asserted twice if it can be forgotten) |
| `util/editor/internal/EditorHolder` | package-private in its entirety: the working copy, the pages and the single ending are tested without a window, which is the only part that demands a server |
| `region/internal/SelectionRuntime` | `installWand/resetWand` (how the selector reaches the player: building an `ItemStack` resolves the item registry, which no test environment has) |
| `util/loot/internal/LootRolls` | `Dice` (the dice: roll, range and shuffle). The rest of the module decides over strings and numbers, so with this seam all the logic of a loot table is tested without randomness |
| `util/loot/internal/LootItems` | the interface that builds the `ItemStack` — the only part of the module that needs a server; a double replaces it and the whole written grammar is tested without a registry |
| shared tests | `src/test/java/net/exylia/lib/FakeServer.java`, `FakePlayer.java`, `debug/DebugCapture.java`; `FakeServer.runAsyncForReal()` runs the async work on a real thread |

**Fakes are not free, and a benchmark that calls them measures itself.**
`FakePlayer` is a `java.lang.reflect.Proxy` and `FakeServer.newWorld` recomputes
its UUID with an MD5 on **every** `getUID()`. Measuring through them inflated the
first `RegionBenchmark` by ~460 bytes per move that weren't the lib's. A
benchmark takes out of the measured loop everything the real server doesn't
recompute, and prints the harness floor so the number can be read as "this plus
that" instead of blaming the lib.

### Release protocol (summary; details are in *Verification*)

1. Run `./gradlew clean build` and require a green build with zero warnings.
2. Run tests and sabotage checks: deliberately break the logic and verify that
   the relevant test fails.
3. `publishToMavenLocal` is local-only validation. Use it to compile an external
   consumer against the local artifact; it does not publish to GitHub.
4. Update the module documentation (see the rules) and README when applicable.
5. Contributors and agents must not manually create GitHub tags or releases,
   edit or publish `lib-manifest.json` for a release, or run release commands.
   Commit and push only the files belonging to their own completed change.
6. Changing `version` in `build.gradle` is intentional release input. Coordinate
   before changing it: a push to `main` with a new strict `X.Y.Z` version signals
   `.github/workflows/release.yml` to build and test, create the `v<version>`
   GitHub release with the JAR, update `lib-manifest.json`, and push the manifest
   with the bot account. The workflow rejects duplicate, downgrade, and existing
   tag versions.
7. Keep local release-readiness checks separate from GitHub publication. Verify
   the generated JAR and, when relevant, its POM and downloaded release checksum
   only after the workflow has completed.
8. In consumer plugins: update `compileOnly("net.exylia:ExyliaLib:1.x.y")`, adapt
   code for API changes, run `./gradlew build`, commit, and **deploy the plugin
   JAR manually** — consumer plugins have no auto-updater. If a plugin uses a new
   API, the library JAR must reach the server **before or together with**
   the plugin JAR (`NoSuchMethodError` otherwise).

---

## Verification before calling something done

Compiling is not enough.

- `./gradlew build` — clean, no warnings and no notes.
- Tests that **actually catch failures**: break the logic on purpose and check
  that the corresponding test fails. A test that never fails proves nothing.
- **Real consumption**: publish with `publishToMavenLocal` and compile a test
  plugin against the library using the new API. That is where an awkward API
  shows.
- **Artifact inspection** whenever you touch something platform-sensitive:

  ```bash
  # no class but the specific one may reference Folia types
  for f in $(find . -name "*.class"); do
    javap -c -p "$f" | grep -q threadedregions && echo "$f"
  done
  ```

- API that only exists on Paper (`getPluginMeta()`, etc.) can't be on paths that
  run on Spigot. You use the portable API even if it is deprecated, and you
  document why.

---

## Settled decisions

Documented so we don't argue about them again without new data.

### We don't add our own thread pool for tasks

`runAsync` **is already a pool**, verified in the server's code:

```java
// CraftAsyncScheduler — Paper/Spigot
new ThreadPoolExecutor(4, Integer.MAX_VALUE, 30L, SECONDS, new SynchronousQueue<>(), ...)

// FoliaAsyncScheduler — Folia
new ThreadPoolExecutor(Math.max(4, availableProcessors() / 2), Integer.MAX_VALUE, ...)
```

Adding another pool on top takes no work off the server: it adds a layer and
loses us the automatic cancellation when the plugin is disabled.

The real risk in that design (unbounded `maximumPoolSize` with a
`SynchronousQueue`, i.e. threads with no cap) **is not fixed with more threads**,
it is fixed by bounding the scarce resource at its own point: a *connection pool*
(HikariCP) for the database. Limit connections, not tasks.

### ExyliaLib isn't shaded, but it does shade

They are two different things and it is worth not confusing them:

- **Nobody shades ExyliaLib.** It lives on the server once as a plugin and
  plugins consume it with `compileOnly`. That doesn't change.
- **ExyliaLib does bundle its non-installable dependencies**, relocated.
  scoreboard-library is the first case: `net.megavex.scoreboardlibrary` travels
  as `net.exylia.lib.internal.scoreboardlibrary`.

The criterion for deciding which of the two applies is whether the dependency is
**shared server infrastructure** or **an implementation detail of a module**:

| | Example | How it comes in |
| --- | --- | --- |
| Installed on the server and used by several plugins | PacketEvents, Caffeine, PlaceholderAPI | `compileOnly`, one single version across every plugin |
| Only ExyliaLib uses it internally | scoreboard-library | `shade` + relocate |

Shaded and relocated avoids the problem commons has today: every plugin carries
its own copy under its own package, so there are as many instances of the library
as there are plugins. Here there is one.

It is declared in the `shade` configuration (not in `implementation`) so it meets
all three conditions at once: it compiles, it gets packaged, and it **doesn't
appear in the published POM** — nobody should be resolving a relocated copy.

### Versioning is immutable

A tag published on JitPack is cached forever. A change to an already published
version demands a new version; moving the tag doesn't work.

---

## Style

- Code, names and Javadoc **in English**. This document and team communication,
  in Spanish.
- Comments that explain **why**, never what. If the what isn't clear, the problem
  is the name or the structure, not the missing comment.
- No new dependencies without a demonstrated need. Every one of them gets
  installed on every server.
- No speculative abstraction. It is added when there is a second real case, not
  when one is imagined.
