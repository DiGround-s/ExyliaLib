# Settings

Player preferences every Exylia plugin registers into, the announcement
channels players can mute, and the one `/settings` screen that shows them all.
*Since 1.261.0.*

```java
@Override
public void onEnable() {
    Settings.category(this, "requests", "ENDER_PEARL", "Requests", "How other players reach you.");
    Settings.register(this, Setting.toggle("trade-requests", true)
            .category("requests")
            .icon("EMERALD")
            .name("Trade requests")
            .description("Whether players can ask you", "to {highlight}trade{letters}.")
            .build());

    deaths = Broadcasts.channel(this, "death-messages", "SKELETON_SKULL", "Death messages",
            "Messages about players dying", "anywhere on the server.");
}

// anywhere, any thread
if (!Settings.enabled(target, this, "trade-requests")) { refuse(); return; }
Broadcasts.send(deaths, Text.from(this, messages.death()).with("player", victim.getName()));
```

## Kinds

| Builder | Holds | A click |
| --- | --- | --- |
| `Setting.toggle(key, default)` | `true` / `false` | flips it |
| `Setting.choice(key, default, options...)` | one option id; `.label(id, text)` names it on screen | left forward, right back, wrapping |
| `Setting.number(key, default, min, max, step)` | a number, kept between `min` and `max` | left `+step`, right `-step` |

Optional on every builder: `category`, `icon` (anything a menu's `material:`
accepts), `name` (plain text, drawn in capitals), `description` (body lines,
palette tokens allowed), `permission`, `perServer()`, `available(check, reason)`
and `store(Store)`.

- **`permission`** — a player without it sees the setting locked and reads its
  default.
- **`available`** — asked whenever the menu draws; a setting whose feature is
  off is shown disabled with the reason instead of offered.
- **`store`** — the value lives in the plugin's own table (a column it already
  had). `get`/`set` run on the caller's thread and read memory; `loaded` says
  whether a click can be trusted yet.

## Reading and writing

| Call | Answers |
| --- | --- |
| `Settings.enabled(player, plugin, key)` | a toggle; `true` for a key never registered |
| `Settings.value(player, plugin, key)` | the value as text; `null` for a key never registered |
| `Settings.number(player, plugin, key)` | a number setting |
| `Settings.set(player, plugin, key, value)` | `false` when the key is unknown or the value is not one it holds |
| `Settings.set(uuid, plugin, key, value)` | the same for a player who may be offline (imports, admin commands); not for a plugin-kept setting |
| `Settings.toggle` / `Settings.step(…, direction)` | one click; `false` while the player's values are not read yet |
| `Settings.isLoaded(player, plugin)` | whether the join read is back |

- **Reads are memory.** A player's values are read asynchronously when they
  join; every question is answered from memory, synchronously, from any thread.
  Until the read is back the answer is the default. Nothing waits for the
  database, so asking from `onEnable` or a broadcast loop is safe.
- **Writes are memory now, the database after the previous one.** Two quick
  clicks land in order. A value set while the join read is out is newer than the
  row and wins.
- **Only what differs from the default is stored.** Setting a value back to the
  default deletes its row, so a default changed in a later release reaches
  everybody who never touched it.

## Where values live

Table `exylia_player_settings`, in the **registering plugin's own database**
(its `database.yml`), keyed by player, plugin, key and server. A network that
shares a plugin's database shares its players' settings. A setting is
network-wide (server column empty) unless it is `perServer()`, in which case
each server keeps its own under its network id (the same id stored places use).

With Redis on, a write on one server makes the servers sharing the database read
that player again (`onRemoteChange`); without it, a player moving servers is read
on join anyway.

## Announcements

`Broadcasts.channel(plugin, key, icon, name, description...)` registers a toggle
that starts on, under the reserved category `announcements`. The menu lists
channels on their own screen, grouped by plugin, apart from the settings.

`Broadcasts.send(channel, text)` sends to every online player who has the
channel on, and to the console, each on the thread that owns them. The text is
built once. `Broadcasts.send(channel, String)` builds it with `Text.from(plugin, …)`,
so `%prefix%` is the plugin's prefix. A channel whose feature can be off takes
the `available` overload.

## The screen

Root: two cards, **Settings** and **Announcements**. Settings → the plugins that
registered settings → a plugin's hub of category cards → one category's
settings. Announcements → the plugins with channels → their channels. A step
with a single choice is skipped, and BACK skips it the same way.

Every list sits on the suite grid: up to seven cards centred on one row of a hub
(45 slots, subject at 4, back at 40) or sub-page (36, back at 31), more on a
paged list (54, arrows at 48/50, back at 49). States are drawn from lambdas, so
a click, or a change from another server, shows without reopening. The words are
the library's phrase table (`lang/<code>/phrases.yml`).

`Settings.open(player)` and `Settings.openAnnouncements(player)` open it from a
plugin's own command.

## The command

The library adds no player command unless the server asks. In ExyliaLib's
`config.yml`:

```yaml
settings:
  command:
    enabled: false
    name: settings
    aliases: [preferences, options]
```

`enabled: true` registers it (permission `exylialib.settings`, default true).
Read at startup; a change needs a restart. A plugin with its own settings
command skips it when `Settings.commandEnabled()` is `true`.

## Lifecycle

Everything a plugin registered is forgotten one tick after it is disabled
(after its own `onDisable`, so it can still announce its shutdown). Register
from `onEnable`; registering a key again replaces it, so a reload may register
everything again with new text. Players are read on join and forgotten on quit
by the library. Nothing here is derived from the palette beyond a single render,
so palette reload needs nothing (the screens are recompiled with the library's
other menus on `/exylialib reload`).

## Threads and platforms

Reads and `set` are safe from any thread. Menu clicks run on the viewer's
thread. `Broadcasts.send` hops to each recipient's own thread on Folia and
sends in place on Bukkit when already there.

## Where the code is

- `settings/Settings`, `Setting`, `Broadcasts` — public API.
- `settings/internal/SettingsRuntime` — registry, per-player cache, join/quit,
  remote changes; `StoredSetting` — the row; `SettingsMenu` — the screens.
- `internal/SettingsCommand`, `internal/LibCommands` — the optional command;
  `internal/LibrarySettings.PlayerSettings` — its config.
- Tests: `src/test/java/net/exylia/lib/settings/internal/SettingsTest`.
