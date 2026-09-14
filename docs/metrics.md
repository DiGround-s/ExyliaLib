# Metrics

What the library tells the Exylia developers about the servers it runs on.
Since 1.160.0.

The library reports, on its own, to `https://stats.exylia.net/api/v1/report`,
and the server owner turns it off in `plugins/ExyliaLib/config.yml`:

```yaml
metrics:
  enabled: true
```

## What is sent

| | |
| --- | --- |
| Server | a random id generated once (`plugins/ExyliaLib/metrics-id.txt`), software and its version, Minecraft, Java, OS, architecture, cores, maximum heap, heap in use, live threads, machine memory (the container's limit inside one), player count and limit, online mode, proxy (`velocity`, `bungeecord` or none) |
| Exylia plugins | name and version of each production Exylia plugin (the payload's `plugin.yml`, not the loader's placeholder), and of ExyliaLib |
| Installed plugins | name, version and whether it is enabled, for every plugin on the server (since 1.163.0) |
| Setup | what a production Exylia plugin chose to describe with `Metrics.describe` (since 1.163.0) |
| Errors | exceptions those plugins threw: type, message, stack trace, phase (`enable`, `disable`, `runtime`) and how many times |
| Never | player names or ids, IP addresses, configuration files, anything a plugin did not throw or describe |

## When

| | |
| --- | --- |
| First report | between one and five minutes after startup |
| Then | every `interval` seconds the stats server answers (5 minutes to a day; 30 minutes until it says otherwise) |
| Installed plugins and setup | on the first report that delivers them, then only when they change |
| Errors | within a minute of happening, only when there are some |
| A new setup description | within a minute, once the first report went out |
| Thread | asynchronous, always |
| Failure | a debug line; the next cycle simply tries again with what is new |

## Describing a plugin's setup

```java
Metrics.describe(plugin, Map.of(
        "events", configurations.size(),
        "largestRegionBlocks", largest));
```

`Metrics.describe(Plugin, Map<String, ?>)` replaces what that plugin reports. Use
it for the shape of the setup an error could depend on — how many arenas, how
large their regions are — never for players.

- **Copied on the call, safe from any thread.** Compute the values where they
  are safe to read (a region on its own thread, a database result wherever it
  arrived) and hand them over; the map can change afterwards.
- **Values** are strings, numbers, booleans, enums, `null`, maps and iterables of
  those, nested at most eight levels. A non-finite number is sent as `null`.
- **At most 32 KB** once written as JSON. Anything larger, or a value of another
  type, keeps the previous description and prints a debug line.
- **Sent once, not every heartbeat.** It rides the next report and is only resent
  after it changes, so describing again on reload with the same result costs
  nothing on the wire.
- **Forgotten on disable.** Describe on enable and after a reload.
- **Free when nothing is reported**: with metrics off, or on a server whose
  plugin is not a production build, the call returns without copying.

## Behavior

- **Only production plugins count.** A plugin counts when it runs through a
  Lukittu loader jar whose generated `LUKITTU_BRANCH` is not `dev`. A local jar,
  or a dev loader, is neither listed nor are its errors or setup kept. A server
  with no production Exylia plugin sends nothing at all.
- **Errors come from the hooks that know who threw.** Paper's
  `ServerExceptionEvent` (listeners, commands, Bukkit tasks, plugin messages,
  enable and disable), tasks scheduled through `Tasks`,
  `Debug.error(message, throwable)`, and the line a Lukittu loader logs when the
  inner plugin fails to start (the loader catches it, so Paper never sees it).
  A task scheduled through `Tasks` is caught by the library before Paper sees
  it, so it is counted once.
- **Identical errors are one entry.** Same plugin, type and stack trace between
  two reports add to a count. At most 50 different errors wait for a report;
  new ones past that are dropped while the ones held keep counting.
- **A report over 256 KB drops errors first and the inventory last**; the
  inventory then goes out with the next report.
- **The stats server can switch it off.** An answer of `enabled: false` stops
  reporting until the next restart.
- **Nothing here can throw into the server.** Every hook contains its own
  failures.
