# Cleanup

Housekeeping the server never does for itself. The library sweeps the folders
the server writes to and never empties, so a box does not run out of disk
because nobody remembered to look. Since 1.90.0.

There is no API: this is the library tidying the server it runs on, configured
by the server owner in `plugins/ExyliaLib/cleanup.yml`. Today it cleans the
server's logs.

```yaml
logs:
  enabled: true
  keep-days: 7
```

## Log cleaner

Deletes the files in the server's `logs/` folder — the one next to `plugins/` —
that were last written to more than `keep-days` days ago.

| | |
| --- | --- |
| Runs | one minute after startup, then every six hours |
| Thread | asynchronous, always; it is file work and never touches the server |
| Deletes | regular files named `*.log`, `*.log.gz` or `*.gz` |
| Never deletes | `latest.log`, folders, and anything that is not named like a log |
| Reports | one line when it deleted something, one warning per file it could not |

## Behavior

- **The active log is never touched.** Deleting `latest.log` frees nothing while
  the server holds it open, and leaves the server appending to a file with no
  name.
- **A retention below one day is read as one day.** Zero would delete the logs
  written today, and the one being written is among them.
- **A file whose age cannot be read is kept.** Age unproven means the file
  stays; the same goes for one that cannot be deleted, which is reported and
  skipped rather than stopping the sweep.
- **Nothing else in `logs/` is touched.** A `.txt` an admin left there, or a
  folder another plugin made, is not the module's business.
- **The timer runs even when the sweep is off**, so `/exylialib reload` turns it
  on without a restart: it reads one boolean and returns.
- **Nothing is deleted while the server starts.** The first pass waits a minute,
  where it never competes with the disk work of a server coming up.

Code: `net.exylia.lib.internal.cleanup` — `CleanupRuntime` (config and timer),
`LogCleaner` (the sweep), `CleanupSettings` (`cleanup.yml`).
