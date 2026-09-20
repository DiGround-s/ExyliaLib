# Custom minigames

Registering a minigame of your own with ExyliaEvents. Since ExyliaLib 1.186.0
(`exylia-api` 1.7.0), package `net.exylia.lib.api.events.custom`.

ExyliaEvents ships fifty-odd minigames. This is how a plugin outside the suite
adds a fifty-first that behaves like the rest: an admin configures arenas of it
in the same menus, it is validated, scheduled, protected, scored and paid out by
the same code, and it shows up in the same player-facing screens.

## Why it is a declaration rather than a subclass

Every built-in minigame is a subclass of an internal `BaseEvent` living in
ExyliaEvents' own classloader — which under the Lukittu loader is not reachable
from any other plugin. Nothing outside ExyliaEvents can extend it, import it, or
see that it exists.

What both sides *can* see is `exylia-api`, because ExyliaLib loads it once for
the whole server. So a minigame crosses as a `MinigameDefinition` — data
describing it — plus a `MinigameHandler` — an interface holding its rules.
ExyliaEvents turns the declaration into one of its own event types and calls the
handler to play it.

## The two halves

| You write | ExyliaEvents does |
| --- | --- |
| `MinigameDefinition` — id, name, icon, settings, markers, scoreboard | the admin setup flow, the arena menus, the stored defaults, the validation |
| `MinigameHandler` — start, tick, eliminate, win | the lobby, the countdown, the teleports, the inventories, the kits, the spectator seat, the arena protection, the statistics, the rewards, the cross-server announcement |

## Getting in

```gradle
repositories { maven { url 'https://jitpack.io' } }

dependencies {
    compileOnly 'com.github.DiGround-s.ExyliaLib:exylia-api:1.7.0'
}
```

```yaml
# plugin.yml
depend: [ ExyliaLib, ExyliaEvents ]
```

`depend` rather than `softdepend`: your plugin must enable *after* ExyliaEvents,
so the service is there to register with.

## Registering

```java
@Override
public void onEnable() {
    ExyliaAPI.get(EventsService.class).ifPresent(events ->
            events.registerMinigame(this, SkyfallMinigame.DEFINITION));
}
```

That is the whole of the lifecycle. There is no unregister to write: a plugin
disabling takes its minigames with it, and any run still going is ended first.
The arenas an admin configured stay in ExyliaEvents' database and come back
with the minigame.

Registering is refused, with the reason in the console, when the id is already
taken — by a built-in minigame or by another add-on. Prefix the id with your
plugin's name.

## Declaring one

```java
public final class SkyfallMinigame {

    public static final MinigameDefinition DEFINITION = MinigameDefinition
            .of("myplugin_skyfall", SkyfallHandler::new)
            .displayName("Skyfall")
            .description("The floor falls away beneath you.", "Be the last one standing.")
            .category("Survival")
            .icon("SAND", "<#FFAA00>")
            .players(2, 16)
            .countdown(30)
            .duration(300)
            .settings(
                    MinigameSetting.duration("grace-period", 5)
                            .label("{info}&lGRACE PERIOD")
                            .lore("Nobody can be hit while it lasts")
                            .icon("CLOCK"),
                    MinigameSetting.integer("lives", 1)
                            .label("{warning}&lLIVES")
                            .icon("TOTEM_OF_UNDYING"))
            .markers(MinigameMarker.area("safe-zone", "Safe zone", "BARRIER").count(0, 4))
            .sidebar(MinigameSidebar.of("{primary}&lSKYFALL", List.of(
                    "",
                    " {muted}❙ {letters}Alive: {success}%alive%{muted}/{letters}%total%",
                    " {muted}❙ {letters}Lives: {highlight}%lives_left%",
                    "",
                    " {muted}❙ {letters}Time: {highlight}%time%",
                    "",
                    " {highlight}ᴇxʏʟɪᴀ.ɴᴇᴛ")))
            .build();

    private SkyfallMinigame() {}
}
```

### The id

Lowercase letters, digits and underscores, at most 32 characters, and never
ending in `_teams` — that suffix means a team variant of a built-in minigame.
The id becomes a menu id, a database value and a placeholder, so it is checked
when the definition is built rather than when something breaks.

### Settings

A `MinigameSetting` is the default a fresh arena is created with, the row the
settings screen draws, the box the click opens, and the value the handler reads
back. One declaration, four uses, which is what stops them drifting.

| Factory | Stored as | The admin sees |
| --- | --- | --- |
| `flag(key, boolean)` | boolean | a switch, clicked to flip |
| `integer(key, int)` | int | click to step, shift-click to type |
| `decimal(key, double)` | double | click to type |
| `duration(key, seconds)` | int, in **seconds** | click to type `30s`, `5m`, `1h30m` |
| `text(key, string)` | string | click to type |

Read them back with `arena.settingInt("lives")` and friends. A setting an arena
was created before still answers: the value falls back to the declared default.

ExyliaEvents adds `countdown`, `duration`, `min-players`, `max-players` and
`bounds-border` to every minigame; do not declare those yourself.

### The settings screen

Generated from the settings, and registered under the id the admin menus
already open. Seven rows to a screen row, centred, with the value, your lore and
the click hint under each name — the same shape as a built-in minigame's.

A server owner who wants to restyle it writes
`menus/admin/<your-id>_settings.yml` in ExyliaEvents' folder, and that file wins
over the generated screen. Twenty-eight settings fit; any beyond that still
store and are still read, they simply have no row.

### Markers

An arena is a box, a lobby spawn, a spectator spawn and a list of spawn points.
Anything else the game plays around is a marker: declare it, and it appears on
the arena setup screen, an arena missing a required one cannot be enabled, and
the handler is told when a player walks into or out of it.

```java
MinigameMarker.point("finish", "Finish line", "CHECKERED_FLAG").count(1, 4).radius(2.0)
MinigameMarker.area("capture", "Capture zone", "BEACON")
```

Read them back with `arena.places("finish")`, which answers a `MinigamePlace`
carrying a centre and a box whichever kind the marker is.

### The scoreboard

`MinigameSidebar` is the board shown while the game is played. The lobby board
belongs to the server owner and is drawn from ExyliaEvents' own
`scoreboards.yml` for every minigame, yours included.

These are always available: `%event_name%`, `%event_type%`, `%players%`,
`%total%`, `%alive%`, `%time%`. Anything else comes from your handler's
`placeholders(arena, viewer)`. Palette tokens (`{primary}`, `{letters}`) and
PlaceholderAPI both work.

## Writing the rules

```java
public final class SkyfallHandler implements MinigameHandler {

    private final Map<UUID, Integer> lives = new HashMap<>();

    @Override
    public void onGameStart(MinigameArena arena) {
        arena.flag(MinigameFlag.PVP, false);
        arena.flag(MinigameFlag.BUILD, false);
        arena.players().forEach(p -> lives.put(p.getUniqueId(), arena.settingInt("lives")));
        arena.broadcastTitle("{primary}&lSKYFALL", "{letters}Stay on the floor");
    }

    @Override
    public void onTick(MinigameArena arena) {
        if (arena.remainingSeconds() == 10) arena.broadcast("{warning}Ten seconds left!");
    }

    @Override
    public void onEliminated(MinigameArena arena, Player player) {
        arena.broadcast("{error}" + player.getName() + " fell.");
    }

    @Override
    public Map<String, String> placeholders(MinigameArena arena, Player viewer) {
        return Map.of("lives_left", String.valueOf(lives.getOrDefault(viewer.getUniqueId(), 0)));
    }

    @Override
    public Listener listener(MinigameArena arena) {
        return new SkyfallListener(arena, lives);
    }
}
```

A fresh handler is built for every run, so it may keep state in its own fields
without clearing it — the object is thrown away when the run ends. That is also
what lets several arenas of the same minigame play at once.

Every method has a default, so implement only what your game has.

| Callback | When |
| --- | --- |
| `onLobbyJoin` | a player arrived in the lobby, before the game starts |
| `onPrepare` | a player is being put into the arena, after their kit |
| `onGameStart` | everybody is in and the clock is running |
| `onTick` | once a second while it is being played |
| `onMarkerEnter` / `onMarkerExit` | a player crossed one of your markers |
| `onEliminated` | a player was taken out, and is already spectating |
| `onTimeUp` | the clock ran out; the default ends the run |
| `onGameEnd` | it is over, before the winners are announced |
| `winners` | who won; the default is everybody still alive |
| `listener` | a Bukkit listener registered for the run and only the run |
| `onCleanup` | tear down whatever you started |

### The verbs

- `arena.eliminate(player)` — they lost. Counts a death, seats them as a
  spectator, ends the run when one is left.
- `arena.finish(player)` — they are *done*: crossed the line, filled the board.
  Same spectator seat, no death, still eligible to win.
- `arena.end()` — end the run now and announce `winners`.
- `arena.remainingSeconds(n)` — put time on the clock. Called from `onTimeUp`,
  it keeps the game going instead of ending it.
- `arena.flag(MinigameFlag.PVP, true)` — change what the arena allows, for
  everybody, immediately. There is no reason to listen for block breaks or hits
  yourself to enforce a rule ExyliaEvents already enforces.
- `arena.refreshScoreboard()` — redraw now rather than on the next refresh.

### What not to do

Do not teleport players in or out, save or restore inventories, put anybody into
spectator mode, protect the arena, count kills and deaths, pay rewards, or clean
up after a run. All of it has already happened by the time your callback runs,
and doing it again is how a player ends up stuck in an arena that no longer
exists.

An exception thrown by a callback is caught, reported against your plugin by
name, and the run carries on — an add-on cannot leave a server full of players
locked inside an event.

## What is not supported yet

**Teams.** A definition describes a free-for-all. A minigame that wants sides
has to keep them itself; the team selector, the per-team spawns and the per-team
markers the built-in team variants use are not reachable through this API.
