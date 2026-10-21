# Runtime commands

Commands whose names come from a plugin's data rather than its code: a shop
opened with `/blocks`, a currency with `/gems`. A Lamp command is declared by
annotation, so its name is fixed when the plugin is compiled; these are put
straight into the server's command map and replaced as a set whenever the data
changes.

```java
RuntimeCommands.of(plugin).install(shops.stream()
        .map(shop -> RuntimeCommand.of(shop.command(), (sender, args) -> open(sender, shop.id()))
                .withAliases(shop.aliases())
                .withPermission("myplugin.shop")
                .withCompleter((sender, args) -> List.of()))
        .toList());
```

- `install(commands)` replaces everything the plugin installed before. It may be
  called from any thread; the command map is only touched on the global thread.
- Players get a new command tree only when the names changed, so reinstalling
  the same set on every data change costs nothing visible.
- A name another plugin holds still answers under the plugin's prefix
  (`/myplugin:blocks`), and the console says so.
- `uninstall()` takes them all down. The library also does that when the plugin
  is disabled.
- `installed()` lists the names installed, each command's name first.

`RuntimeCommand` is a record: `name`, `aliases`, `permission` (empty for none,
checked before running and suggesting), `description`, `executor` and
`completer`. Names are trimmed, lower-cased and lose a leading slash.

## Where the code is

`net.exylia.lib.command.RuntimeCommands`, `net.exylia.lib.command.RuntimeCommand`.
