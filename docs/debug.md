# Debug module

What a plugin says to the console, in colour. Six methods and one toggle —
no categories, no numeric levels, no configuration format. Since 1.13.0.

Entry point: `net.exylia.lib.debug.Debug`.

## Using

```java
private Debug debug;

@Override
public void onEnable() {
    debug = Debug.of(this);
    debug.enabled(config.settings().debug());
    debug.motd();
    debug.success("Ready in " + millis + "ms");
}
```

| Method | What prints |
| --- | --- |
| `log(message)` | an ordinary line |
| `success(message)` | something went right, in the success colour |
| `warn(message)` | wrong but survivable, in the warning colour |
| `error(message)` / `error(message, throwable)` | an error, with the stack trace after it |
| `debug(message)` | the detail nobody wants in production — prints only when enabled |
| `motd()` | the plugin's name in ASCII art, version underneath |

Every line is prefixed with the plugin's name: `[ExyliaFFA] ready`.

Lifecycle: `Debug.of(plugin)` caches per plugin; `release(pluginName)` /
`releaseAll()` drop instances (the library does this on shutdown).

## The server-wide switch

`plugins/ExyliaLib/config.yml` turns the detail lines of **every** Exylia
plugin on at once. Since 1.27.0.

```yaml
# Whether debug lines print, for every plugin using ExyliaLib.
debug: false
```

Diagnosing a problem across a dozen plugins meant editing a dozen configs and
restarting between each. One value now covers them all, and `/exylialib
reload` applies it without a restart.

It **raises the floor, never lowers it**: a plugin that called
`enabled(true)` keeps printing when the server switch is off. Otherwise the
shared file would silence a plugin that had asked to be heard.

| Server `debug` | Plugin's own toggle | `debug(...)` prints |
| --- | --- | --- |
| `false` | not set | no |
| `false` | `enabled(true)` | yes |
| `true` | not set | yes |
| `true` | `enabled(true)` | yes |

`Debug.all(boolean)` is the same switch in code, and `Debug.isAllEnabled()`
reads it. The library calls it at startup and on reload; a plugin has no
reason to.

## Contracts

- **Colours come from the server's palette** (`primary` for the prefix,
  `success`/`warning`/`error`/`muted` for the body), so a recoloured server
  recolours its logs. Fixed fallbacks match the palette defaults for when
  the palette is not loaded.
- **The message is appended literally, never parsed.** A stack trace or a
  config line full of `&` and `{}` prints as-is.
- **Either toggle gates only `debug`.** Everything else always prints.
- **The banner never breaks a startup.** If the font resource were missing
  from a broken jar, the name prints plainly instead.
- Lines go to the server's console sender, which renders component colours
  as terminal colours and writes plain text to the log file.

## Why so small

ExyliaCommons shipped four axes of classification (categories, levels, types,
sources), a formatter, a config format and forty entry points to say these
same five things. Nobody picks the right combination at 3 a.m., which is when
debug output matters. Here a message is a log, a success, a warning, an
error, or a debug line. That is all.

## Source and tests

- Public: `debug/Debug.java`.
- The ASCII art comes from jfiglet 0.0.9, shaded and relocated to
  `net.exylia.lib.internal.jfiglet` (like scoreboard-library: bundled, absent
  from the published POM).
- Tests: `src/test/java/net/exylia/lib/debug/DebugTest.java`. The output
  destination is an injectable sink (`setSink`/`resetSink`, package-private).
