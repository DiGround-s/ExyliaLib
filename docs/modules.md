# Modules

A plugin made of parts a server owner switches on and off: each part has its
own files, listeners, actions, menus, commands and timers, and a part that is
off costs nothing. ExyliaSurvivalCore's module system, made generic.

```java
modules = PluginModules.of(plugin, id -> MarketConfig.get().modules().switchOf(id));
modules.register(new MarketModule());
modules.register(new AuctionsModule());   // requiredModules() returns ["market"]
modules.registerActions(actions);         // before the menus are compiled
menus.load(modules.menuIds());
modules.registerCommands(lamp);
modules.loadOnlinePlayers(Bukkit.getOnlinePlayers());
modules.startTimers();

// reload:  Configs.reloadAll(plugin); modules.reloadConfigs();
// disable: modules.disableAll();
```

```java
public final class AuctionsModule extends PluginModule {
    public AuctionsModule() { super("auctions"); }

    @Override protected List<String> requiredModules() { return List.of("market"); }
    @Override protected void registerConfigs() {
        AuctionsDefaults.install(config("modules/auctions/config", AuctionsDefaults.class).load());
    }
    @Override protected void onEnable() { manager = new AuctionManager(); }
    @Override protected void onDisable() { if (manager != null) manager.shutdown(); }
    @Override protected void timers() { timer(20L, 20L, () -> manager.sweep()); }
}
```

## Lifecycle

| Step | When | Notes |
| --- | --- | --- |
| `registerConfigs()` | before `onEnable`, and on every reload | must be safe to repeat |
| `onEnable()` | at `register`, when the switch is on and every `requiredPlugins()` / `requiredModules()` is running | the module counts as enabled only once it returns |
| `actions`, `menus`, `commands` | once, through `registerActions`, `menuIds`, `registerCommands` | only for running modules |
| `loadOnlinePlayers` | once, at start | not on reload |
| `timers()` | at `startTimers`, and after every reload | the previous timers are cancelled first |
| `onReload()` | on `reloadConfigs`, after `registerConfigs`, while enabled | the switch itself is read only at start |
| `onDisable()` | on `disableAll`, in reverse registration order, after the timers stop; also after a failed `onEnable` | must tolerate managers that were never built |

## Contracts

- **Switches.** The function handed to `of` answers each module's switch;
  empty means there is no switch for that id, which is logged as an error and
  leaves the module off, so a misspelt key never fails silently.
- **Requirements.** `requiredModules()` names modules of the same plugin; they
  must be registered first. A module whose requirement is off stays off with
  one warning naming it. `requiredPlugins()` does the same for plugins.
- **Isolation.** A module that throws while enabling has the listeners it
  registered removed, its new placeholders emptied (they cannot be removed),
  its `onDisable` called, and stays off; the next modules still start. The
  same holds for each registration step and for disabling.
- **Lookups.** `getModule(id[, type])` and `isEnabled(id)` answer only for
  running modules, from any thread.
- **Reload.** `reloadConfigs` reloads each module on its own and throws, naming
  the ones that failed, after the rest reloaded. `reload(id)` (1.284.0) does
  the same for one running module, for an in-game editor that changed only its
  settings; it answers `false` when that module is not running.
  `pendingRestart()` lists the modules whose switch changed since start.
- **Lifecycle.** Driven from the main thread; nothing outlives `disableAll()`.
  Palette-derived state: none kept here.

## Where the code is

`src/main/java/net/exylia/lib/module/` — `PluginModule`, `PluginModules`.
Tests: `src/test/java/net/exylia/lib/module/PluginModulesTest.java`.
