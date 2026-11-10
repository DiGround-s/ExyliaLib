# Leaderboards

Leaderboards that keep themselves: who has the most kills, money or playtime,
of all time and today, this week, this month and this year, offline players
included, with no stats plugin behind them.

A hologram, a menu and a scoreboard all ask the same thing: "who is first, and
where am I". This module answers it once, from memory, so none of them keeps a
top list of its own.

*Since 1.563.0.*

---

## Opening it

```java
private Leaderboards boards;

@Override
public void onEnable() {
    boards = Leaderboards.of(this);
    boards.clock(PeriodClock.of("Europe/Madrid", 5, DayOfWeek.MONDAY));
    boards.track("kills", LeaderboardSource.statistic(Statistic.PLAYER_KILLS));
    boards.track("diamonds", LeaderboardSource.statistic(Statistic.MINE_BLOCK, Material.DIAMOND_ORE));
    boards.track("money", LeaderboardSource.balance());
    boards.track("playtime", LeaderboardSource.playtime());
    boards.track("level", LeaderboardSource.placeholder("%player_level%"));
}
```

There is **one set of boards per server**, shared by every plugin that calls
`of`. The first plugin to open it **hosts** it: the tables live in its database
and its scheduler runs the timers. When the host is disabled, what was sampled
is written (waiting up to ten seconds) and the next enabled plugin that opened
it takes over, reading every board again from its own database. A board belongs
to the plugin that tracked it and is dropped when that plugin is disabled; its
rows stay, so tracking it again picks up where it was.

Point the plugins of every server at one database and the boards are
network-wide.

## Sources

| `LeaderboardSource` | Ranks by | Offline players | Per server |
| --- | --- | --- | --- |
| `statistic(Statistic)` | a vanilla statistic with no sub-type (`PLAYER_KILLS`, `DEATHS`, `JUMP`) | yes | yes |
| `statistic(Statistic, Material)` | blocks mined, items crafted, used, broken, picked up, dropped | yes | yes |
| `statistic(Statistic, EntityType)` | mobs killed, killed by | yes | yes |
| `balance()` / `balance(currency)` | the lib economy's default currency, or one by id | yes | no |
| `playtime()` | [Playtime](playtime.md), in milliseconds | yes | no |
| `placeholder(text)` | any placeholder, the lib's or PlaceholderAPI's | no, players are ranked as they join | no |

A statistic with the wrong sub-type throws `IllegalArgumentException` at the
call. A placeholder ignores colours, spaces and thousands separators and reads
`1.5K` as 1500; a value that is not a number is not sampled.

**One value everywhere, or one per server.** A statistic lives in each
server's world folder, so every server keeps its own row and the board adds
them up: kills on the whole network, and a lobby with no kills changes nothing.
A balance, playtime and a placeholder are taken to read the same everywhere,
so the server a player is on writes their one row. `source.perServer()` makes
any source the first kind: a Vault balance each server keeps apart, a
placeholder from a plugin with no shared database.

## Spans

| `Span` | Value |
| --- | --- |
| `ALL_TIME` | the source's value now |
| `DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY` | what was gained since that period of the clock began |

Periods follow the [`PeriodClock`](util.md) set with `boards.clock(...)`
(default: midnight in the server's zone, weeks from Monday). A day starts at
the reset hour, a week on its start day, a month on the 1st and a year on the
1st of January, all at the reset hour. Give every server of a network the same
clock. Setting another clock is a reload: moving the hour, zone or week start
renames the current periods, which then start over.

Players who count zero in a span are not ranked: the weekly board lists only
those who gained something this week.

## Reading

`boards.view(id, span)` returns a `LeaderboardView`. Every call reads the last
sort from memory: safe from any thread, every tick. A view of a board nobody
tracks yet is empty and fills once it is tracked, so a hologram can keep the
view it was given.

| Member | Answers |
| --- | --- |
| `List<Entry> top(int count)` | the first players, best first |
| `Optional<Entry> at(int rank)` | the player in a place, from 1 |
| `Optional<Entry> entryOf(UUID)` | a player's entry |
| `OptionalInt rankOf(UUID)` | a player's place: each viewer sees their own |
| `OptionalDouble valueOf(UUID)` | a player's value in the span |
| `int size()` | how many are ranked |
| `Optional<Duration> remaining()` | until the span starts over; empty for all time |
| `board()`, `span()` | what the view is of |

`Entry(int rank, UUID id, String name, double value)`. Places are never
shared: a tie goes to whoever reached the value first (the oldest last change),
then to the lower id, so the order never flickers between two sorts. The name
is the one the player had when last seen.

`entry.formatted(style)` and `LeaderboardView.format(value, style)` write a
value for players:

| Style | Gives |
| --- | --- |
| blank or `null` | `12,345`, two decimals when it has some |
| `compact` | `1.2K`, following `formats.yml` |
| `money` | the server's money format |
| `duration` | milliseconds as `1d 4h 12m`, for `playtime()` |
| anything else | a `DecimalFormat` pattern such as `#,##0.##`, the same on every host |

```java
LeaderboardView weekly = boards.view("kills", Span.WEEKLY);
String first = weekly.at(1).map(e -> e.name() + " " + e.formatted("compact")).orElse("—");
String mine = weekly.rankOf(viewer.getUniqueId()).stream().mapToObj(r -> "#" + r).findFirst().orElse("—");
```

## Boards

| Member | Does |
| --- | --- |
| `track(id, source)` | starts keeping a board, or changes what an existing one ranks by. Ids are 1-48 of `A-Z a-z 0-9 _ . : -`, the same on every server |
| `untrack(id)` | stops keeping it here, writing what was sampled; the rows stay |
| `tracked()` | the ids tracked on this server, by any plugin |
| `reset(id)` | starts a board over on every server: every row deleted, every period started again. All time follows the source, so it comes back as players are read (and from offline players, when the source can): a rebuild, not a zeroing |
| `reset(id, span)` | starts one span over on every server, within a sort; all time and the other spans are untouched |
| `sampleEvery(Duration)` | how often online players are read; default `DEFAULT_SAMPLE_EVERY`, a minute; at least a second |
| `sortEvery(Duration)` | how often boards are reloaded and sorted; default `DEFAULT_SORT_EVERY`, 30 seconds; at least a second |
| `clock(PeriodClock)` / `clock()` | the clock every board follows |

The settings are shared by every plugin: the last call wins. Changing a board
to a source that reads something else mixes old rows with new ones: reset it.

## How it works

- **Sampling.** Every sample interval, each player online here is read on their
  own thread (statistics and placeholders need it on Folia), and once more when
  they leave. A row is written only when it changed, all changed rows in one
  batch at the next interval. A player is sampled only once their rows were
  read on join, so a sample never overwrites what another server wrote.
- **Periods.** Each row keeps, per period, the key of the period and the value
  it started from. A sample whose period key moved on starts that period from
  the value last stored, so money paid to somebody offline over the reset
  counts for the new period. Keys come from the wall clock, so a server that
  was down over a reset needs no catch-up, and a row from an earlier period
  simply counts zero.
- **Resets.** A span's reset count is part of its key (`2026-11-09#1`). A reset
  writes one row; every row of the board then names an older period, on every
  server at its next sort, and nothing else is rewritten.
- **Sorting.** Every sort interval each board's rows are read from the
  database (so other servers' players appear), this server's own fresher rows
  kept, and every span sorted, off the server thread. Views read the result.
- **Offline players.** A board tracked with no rows of this server's yet, whose
  source can read offline players, is filled from everybody who ever played
  here: ten statistic files a second on the server thread, one every two ticks,
  or twenty balance or playtime reads a second, the next batch only once the
  last one finished. Players at zero are not stored. A placeholder cannot be
  read offline: its board fills as players join.
- **Cost.** One timer a second that compares two clocks; one entity task per
  online player per sample interval; one query per board per sort; nothing on
  a view call.

## Storage

`exylia_leaderboard_values`: one row per board, player and scope (`id` is
`board|scope|player`; `board`, `scope` (the server id, or `*` for a source that
reads the same everywhere), `player`, `name`, `value`, and `day_key`/`day_base`,
`week_key`/`week_base`, `month_key`/`month_base`, `year_key`/`year_base`,
`updated`). `exylia_leaderboards`: one row per board that was ever reset
(`id`, `day_resets`, `week_resets`, `month_resets`, `year_resets`).

Both are `PROGRESS`; values are owned by `player`, so `/exylialib wipe` and
`/exylialib resetplayer` cover them and a reset player leaves every board at
once. Neither goes through the Redis row cache: they are read by query.

## Threads

Safe from any thread. Views never touch the database. `reset` completes off
the server thread.

## Where it lives

- `net.exylia.lib.leaderboard.Leaderboards` — the API and the tracker.
- `LeaderboardSource`, `LeaderboardView` (and `Entry`), `Span`.
- `leaderboard.internal.LeaderboardRow`, `LeaderboardMeta` — rows;
  `Ranking` — sample and sort arithmetic; `Seeding` — offline reads in batches.
- `ExyliaLib.onPlayerJoin` / `onPlayerQuit` call `joined` / `left` (before
  Playtime's, which drops the session a playtime board reads);
  `releaseAfterDisable` calls `release`; `onDisable` calls `releaseAll` before
  the databases close.
- Tests: `RankingTest`, `SeedingTest`, `LeaderboardSourceTest`,
  `LeaderboardsTest`, `PeriodClockTest`.
