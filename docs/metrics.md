# Metrics

What the library tells the Exylia developers about the servers it runs on.
Since 1.160.0.

There is no API. The library reports, on its own, to
`https://stats.exylia.net/api/v1/report`, and the server owner turns it off in
`plugins/ExyliaLib/config.yml`:

```yaml
metrics:
  enabled: true
```

## What is sent

| | |
| --- | --- |
| Server | a random id generated once (`plugins/ExyliaLib/metrics-id.txt`), software and its version, Minecraft, Java, OS, architecture, cores, memory, player count and limit, online mode, proxy (`velocity`, `bungeecord` or none) |
| Plugins | name and version of each production Exylia plugin (the payload's `plugin.yml`, not the loader's placeholder), and of ExyliaLib |
| Errors | exceptions those plugins threw: type, message, stack trace, phase (`enable`, `disable`, `runtime`) and how many times |
| Never | player names or ids, IP addresses, configuration, anything a plugin did not throw |

## When

| | |
| --- | --- |
| First report | between one and five minutes after startup |
| Then | every `interval` seconds the stats server answers (5 minutes to a day; 30 minutes until it says otherwise) |
| Errors | within a minute of happening, only when there are some |
| Thread | asynchronous, always |
| Failure | a debug line; the next cycle simply tries again with what is new |

## Behavior

- **Only production plugins count.** A plugin counts when it runs through a
  Lukittu loader jar whose generated `LUKITTU_BRANCH` is not `dev`. A local jar,
  or a dev loader, is neither listed nor are its errors kept. A server with no
  production Exylia plugin sends nothing at all.
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
- **The stats server can switch it off.** An answer of `enabled: false` stops
  reporting until the next restart.
- **Nothing here can throw into the server.** Every hook contains its own
  failures.
