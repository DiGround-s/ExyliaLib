# Schedules

Timetables: what starts by itself, when, and what has to be true for it to
happen.

```java
PluginSchedules schedules = Schedules.of(this);

// The gate only this plugin can answer.
schedules.condition("event-inactive", s -> !events.isActive(s.target()));
// What a fire does.
schedules.onFire(s -> events.start(s.target()));

// Whenever the configuration is (re)loaded:
schedules.set(configs.stream().flatMap(c -> c.schedules().stream()).toList());
```

## What this replaces

Every plugin that started something on a clock wrote the same scheduler: a
repeating task, a list of entries parsed out of YAML, a comparison of the current
hour and minute against each one, and a map of the last minute each entry fired
in so the second tick of the same minute would not fire it twice.

There were at least three copies. They had drifted into disagreeing about the key
for the minimum player count — one wrote `min-players`, another
`min-players-online` — and none of them could express a condition beyond that
count, so "do not start an event that is already running" was a hard-coded `if`
inside each scheduler.

## The cost

One asynchronous task for the whole server, not one per plugin. Each pass reads
one `long` per plugin and compares it to the clock. Nothing is walked, parsed or
compared until a schedule is actually due, and the calendar arithmetic happens
once per fire rather than once per second per entry.

The moment something *is* due, the work moves onto the owning plugin's own
scheduler before a single gate is evaluated — so a condition asking whether an
event is running, or how many players are in a world, may touch the server, and
so may the handler.

## A schedule

`Schedule` is an immutable record, the same shape as every other configured thing
in this library.

| Field | What it means |
| --- | --- |
| `name` | What an admin calls the line. Blank shows the times instead. |
| `target` | What it starts. The meaning is the owning plugin's — normally a configuration id. |
| `enabled` | Whether it may fire at all. |
| `days` | Which days. Empty means every day. |
| `times` | The clock times it fires at. |
| `every` | Repeat this often instead of using fixed times. |
| `from` / `to` | The window the repeat runs inside. |
| `minPlayers` / `maxPlayers` | How many players must be, and may be, online. |
| `condition` | An Exylia comparison, such as `%server_tps% >= 18`. |
| `requires` | Named gates the owning plugin registered. |
| `cooldown` | The shortest gap between two fires of this line. |

There is no cron string. A cron string is unreadable in a menu and unwritable in
a form, and every schedule any Exylia plugin has needed is "these times, these
days, if these things hold".

### Two ways of saying when

Either fixed clock times:

```java
Schedule.at("koth_desert", LocalTime.of(20, 0), LocalTime.of(22, 30))
        .withDays(Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY));
```

or a repeat inside a window:

```java
Schedule.every("koth_desert", Duration.ofHours(2));   // then set from/to
```

A repeat is anchored to the window's own opening, not to the moment the plugin
loaded. An every-two-hours schedule that opens at 10:00 fires at 10:00, 12:00 and
14:00 whether the server restarted at 13:07 or not.

## Named gates

The thing configuration cannot express:

```java
schedules.condition("event-inactive", s -> !events.isActive(s.target()));
```

A schedule that lists `event-inactive` in its `requires` fires only when the test
passes. The names a plugin offers are shown to the admin in the edit form, so
nobody has to guess them.

A schedule requiring a name the plugin never registered **does not fire**, and
says so loudly in the console. This is the deliberate opposite of how rewards and
effects treat an unreadable condition: withholding a reward is invisible, so
those fail open; starting an event the admin asked not to start is loud, so this
fails closed.

## A blocked fire is not retried

A schedule stopped by a gate is skipped, and the next fire is the next one the
timetable names. Retrying would mean an event blocked at eight o'clock starting
at eight minutes past, which is not what the timetable said.

A fire more than two minutes late — a frozen server, a suspended host — is
skipped rather than run. Firing a day of missed schedules all at once on the way
back up is worse than missing them.

## Storing them

`ScheduleCodec` writes a JSON array, so a timetable lives in a `TEXT` column
beside the rewards and commands of the same row rather than in a file of its own:

```java
String stored = ScheduleCodec.encode(schedules);
List<Schedule> schedules = ScheduleCodec.decode(stored);
```

Only what carries meaning is written, and an empty list stores as `null` rather
than `[]` — the same rule the reward and command codecs follow.

### Reading what was written by hand

`ScheduleCodec.fromMapList` reads the block form every plugin had before this
module, both spellings of the player key included:

```yaml
- name: 'Friday night'
  target: 'koth_desert'      # or the id of the config the block is under
  time: '20:00'              # or times: ['20:00', '22:30']
  days: [FRIDAY, SATURDAY]   # or ['*'] for every day
  min-players: 10            # also read as min-players-online
```

Migrating a plugin is a read through that method rather than an admin retyping
their timetable.

## Editing them

```java
schedules.editor(config.schedules(), config.id())
         .title("{primary}&lSCHEDULES")
         .onSave(edited -> configs.save(config.withSchedules(edited)))
         .open(player);
```

The [list editor](editors.md), over schedules: pagination, add, edit, delete,
copy and paste, none of which is written again. A row is edited as one form with
every field prefilled.

Passing the target means the screen already knows what it starts, so the admin is
not asked for an id they can only get wrong. Passing `null` makes it a field.

## Reading the clock

```java
schedules.nextFireOf("koth_desert");        // Optional<Instant>
schedules.millisUntilNext("koth_desert");   // OptionalLong, for a placeholder
schedules.isScheduled("koth_desert");
```

`fireNow(schedule)` starts one early, for a command. Every gate is still checked:
"start it now" is a request about the time, not about the conditions.

## Timezone

One setting, in the library's own `config.yml`:

```yaml
timezone: 'Europe/Madrid'
```

Empty means the host's own zone, which is right until the host and the players
are in different countries. A plugin that genuinely runs on a different clock
calls `schedules.zone(...)`; nothing else should.
