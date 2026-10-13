# Logs

A staff audit log every plugin records into: who did what to whom, where, and
which items moved. Each plugin keeps its entries in its own database; staff read
them all together with `/exylialib logs`. *Since 1.296.0.*

```java
private Logs logs;

@Override
public void onEnable() {
    logs = Logs.of(this)
            .action("market-buy", "EMERALD")   // optional: icon + tab completion
            .action("trade", "CHEST");
}

// any thread, once the action really happened
logs.record("market-buy", buyer, seller, "16x Diamond for " + Logs.money("dollars", price),
        List.of(Logs.Group.of("Bought", stack)));
logs.record("crate-keys-give", sender, target.id(), target.name(), amount + "x " + crate);
```

## The surface

| Method | What it does |
| --- | --- |
| `Logs.of(plugin)` | the plugin's log; same instance for the same plugin load. The first call starts its timers |
| `action(action, icon)` | declares an action: its menu icon (a material name) and a tab completion before it was ever recorded. Optional; undeclared actions draw as `PAPER` |
| `record(action, CommandSender actor, String detail)` | something done with nobody on the other end |
| `record(action, CommandSender actor, ExyliaPlayer target, String detail[, List<Group> items])` | something a sender did to a player, who may be offline |
| `record(action, CommandSender actor, UUID targetId, String targetName, String detail[, List<Group> items])` | the same when only the id and name are at hand |
| `record(action, UUID actorId, String actorName, UUID targetId, String targetName, String detail, Location at[, List<Group> items])` | the full form, for a caller that holds only ids |
| `Logs.Group(label, items)`, `Group.of(label, Collection)`, `Group.of(label, ItemStack)`, `Group.of(label, ItemStack template, int amount)` | stacks kept under a label such as `Steve gave`; copied on creation, air skipped; the last one cuts any amount into real stack sizes |
| `Logs.snapshot(ItemStack[])` / `Logs.changed(before, after)` | a copy of a container, then what left (`Removed`) and what came in (`Added`), by item rather than by slot |
| `Logs.money(currency, BigDecimal)`, `money(Map)`, `items(Collection)`, `item(ItemStack)`, `item(template, amount)`, `text(List<Group>)` | how a detail writes amounts and stacks: `1500 dollars`, `64x Diamond, 1x Dirt`, `Removed: 64x Diamond \| Added: 1x Dirt` |
| `Logs.CONSOLE`, `Logs.MAX_STACKS` | what a non-player actor is stored as; most stacks one entry keeps (256) |

A `CommandSender` actor that is a player is stored with their id, name and
location; anything else is stored as `CONSOLE`. The location is only read when
this thread owns the player (`Bukkit.isOwnedByCurrentRegion`), so an entry
recorded from a database callback has none.

Actions are kebab-case keys (`crate-keys-give`): stored lower case, spaces and
underscores turned into dashes. They are what the action filter matches and
what the menu shows, upper-cased, as the entry's name.

## What is kept

`record` only queues the entry — the strings cut (action 48 characters, names
32, detail 4000), the groups copied and capped at 256 stacks in all. The queue
is written in one batch every `logs.flush-seconds`, off the main thread, so no
action ever waits on the database. Reading writes every queue first, so staff
never miss what just happened. A plugin's queue is written one last time when it
disables, before its database is released.

Table `exylia_logs` in the recording plugin's database: `id`, `plugin`,
`server` (the network server id), `log_action`, `actor`, `actorName`, `target`,
`targetName`, `detail`, `world`, `x`, `y`, `z`, `created_day`, `created_at`,
`item_groups`, `items`; indexed on `(plugin, actor, created_at desc)`,
`(plugin, target, created_at desc)` and `(plugin, created_at desc)`. Plugins
sharing one database share the table; every read and purge is filtered by
plugin.

## Reading: `/exylialib logs`

```
/exylialib logs <player|*> [plugin] [action]
```

Permission `exylialib.logs` (default: op). The player is resolved like any
other library command, online, offline or elsewhere on the network; `*` reads
everybody's newest entries. `*` or `all` for the plugin or the action means no
filter. A player's read is what they did merged with what was done to them, an
entry done to themselves shown once; at most `logs.max-rows`, newest first,
across every plugin keeping a log.

- **In game:** the list screen (`lang/<code>/menus/logs/logs.yml`): entries in
  slots 0-35, the plugin filter at 45 (left-click next plugin, right-click all),
  the action filter at 46 (cycles the chosen plugin's actions), page arrows at
  48/50 and the filter summary at 49. A command opens it, so it has no BACK.
  Clicking an entry opens `entry.yml`: its whole detail at the top and every
  stack it kept below, each group starting a row with its label; BACK returns
  to the list with the same filters. Both are admin screens, rewritten from
  the jar on every start.
- **In the console:** the newest `logs.console-rows` lines, then how many more
  there are.

Row placeholders: `%log_plugin%` `%log_action%` `%log_actor%` `%log_target%`
`%log_detail%` `%log_server%` `%log_location%` `%log_when%` `%log_date%`
`%log_view%` `%log_icon%`; list values `%logs_subject%` `%logs_plugin%`
`%logs_action%` `%logs_count%`. The words (unavailable, empty, header, entry,
more, everybody, all, none, detail-line, view-items, view-entry) are under
`logs:` in ExyliaLib's `messages.yml`.

## Configuration

```yaml
logs:
  # Days an entry is kept before it is deleted. 0 keeps them for ever.
  retention-days: 30
  # Seconds between writes of what was recorded. Reading always writes first.
  # Changing it needs a restart.
  flush-seconds: 10
  # Most entries one lookup reads, newest first.
  max-rows: 540
  # Most entries the console prints for one lookup.
  console-rows: 20
  # What is never recorded: a plugin name such as ExyliaMarket, or one of its
  # actions such as ExyliaMarket:trade.
  disabled: []
```

Old entries are purged a minute after start and every six hours after, one
`created_day` at a time from the plugin's oldest.

## Threads and lifecycle

Every method is safe from any thread. Recording never touches the database;
writing, reading and purging run asynchronously. Released with the plugin, after
its last write; nothing is cached from the palette, so reload needs nothing.

## Where the code lives

`log/Logs` (public, `Logs.Group` inside); `log/internal/LogRow` (the table),
`log/internal/LogsMenu` (the screens and the console listing). The command is
`logs` in `internal/ReloadCommand`. Tests: `log/LogsTest`.
