# Reloading

There is no reload *system*, because none is needed: each side reloads what
it owns, and nothing else. Since 1.14.0 the library's half of that is
reachable from in-game.

## The library: `/exylialib reload`

Registered through Lamp (`io.github.revxrsal:lamp.*:4.0.0-rc.17`, the
framework the whole ecosystem standardises on), permission
`exylialib.admin`. It reloads `colors.yml` — the only state of the library a
server owner edits.

One command recolours the whole server. The chain, verified in code:

```
/exylialib reload
  → palette.reload()
      → Colors.apply(new palette)              palette tokens mean new colours
      → TextEngine cache dropped               nothing stale is re-shown
      → BoardManager.invalidateAll()           scoreboards re-send themselves
      → HologramRuntime.invalidateAll()        holograms too
```

`/exylialib` alone shows the version and the subcommand.

## A plugin: its own `/plugin reload`

A consumer reloads **itself** and never touches the library — the library
keeps no state derived from consumer configs that needs refreshing first.
Three lines cover it:

```java
@Subcommand("reload")
@CommandPermission("myplugin.admin")
public void reload(CommandSender sender) {
    List<ConfigIssue> issues = Configs.reloadAll(this);   // re-reads every file
    // onReload listeners re-apply: re-show boards, debug.enabled(...), etc.
}
```

Placeholders need no re-registration: resolvers read live state. Boards and
holograms keep showing; if a config section changed shape, the plugin
re-shows from its `onReload`.

## Changing colours: which command?

**Only `/exylialib reload`.** Everything the library renders — scoreboards,
holograms, effects, and any `Text` built on the fly — picks up the new
palette, because `Text` re-parses after the cache drop.

The honest exception: what a plugin parsed *once* and kept as state (a GUI
built in `onEnable`, say). That is plugin state with old colours baked in;
the convention is that such things are rebuilt in the plugin's `onReload`.

## Why not "reload lib → reload plugin"

That order is backwards and harmful: the plugin needs nothing from the
library to reload, and the palette is everyone's — reloading it from one
consumer's command would re-send every scoreboard and hologram of every
plugin on the server. `/exyliaffa reload` recolouring the whole server would
be a bug with good intentions.
