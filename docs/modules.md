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
| `onReload()` | on `reloadConfigs`, after `registerConfigs`, while enabled | the switch is read at start; change it live with `setEnabled` |
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
  `pendingRestart()` lists the modules a restart would start or stop: the
  config's switches, the installed plugins and the requirements, against what
  runs (1.557.0: a module kept off by a requirement that is off is no longer
  listed).
- **Lifecycle.** Driven from the main thread; nothing outlives `disableAll()`.
  Palette-derived state: none kept here.

## Switching a module live (1.557.0)

```java
ModuleToggle toggle = modules.setEnabled("auctions", false);
if (toggle.succeeded()) settings.writeSwitch("auctions", false);   // persisting is the plugin's
menus.load(toggle.menus());                                         // menus of modules that came on
```

| Status | Meaning |
| --- | --- |
| `ENABLED` | it runs now; `changed()` holds it and the dependants that came back with it |
| `DISABLED` | it is off; `changed()` holds the dependants that went off first, then it |
| `UNCHANGED` | it already was in that state |
| `BLOCKED` | it stays off: `missing()` names the plugins or modules it needs that are not running |
| `FAILED` | it threw while enabling, was released (as at start) and stays off |
| `UNKNOWN` | no module has that id |

- **Off.** Every running module that needs it, directly or through another,
  goes off first, the last registered first. For each: the listeners it
  registered in `onEnable` are unregistered, its timers stop, `onDisable` runs
  and the placeholders it registered answer nothing (they cannot be removed).
  Listeners registered later than `onEnable` are the module's to remove.
- **On.** With its requirements running: `registerConfigs`, `onEnable`, its
  actions (registered once per run), the players online, its timers. Then the
  modules that need it and whose switch is on come back, in registration
  order. A module that failed to enable is not brought back along.
- **The switch asked for wins for the rest of the run**, so a dependant turned
  off by hand stays off when its requirement returns. Write it to the config so
  the next start agrees and `pendingRestart()` stops listing it.
- **Actions** of a module stay registered while it is off and answer
  `stop("switched off")` without running: `registerActions` hands each module
  `actions.onlyWhile(module::isEnabled)` (`PluginActions#onlyWhile`), so menus
  compiled against them keep working once it is back.
- **Commands** cannot be unregistered cleanly in Lamp, so they stay registered
  and the plugin refuses them with one condition on its own Lamp builder (no
  Lamp type crosses into the library). Register `modules.allCommandHandlers()`
  instead of `commandHandlers()` so a module that was off at start has its
  commands too; its `commandHandlers()` must then not touch what `onEnable`
  builds.

```java
Lamp<BukkitCommandActor> lamp = BukkitLamp.builder(plugin)
        .commandCondition(context -> modules.offModuleOf(context.command().function().method())
                .ifPresent(id -> {
                    messages.send(context.actor().sender(), "module-off");
                    throw new SendableException() { public void sendTo(CommandActor actor) { } };
                }))
        .build();
lamp.register(modules.allCommandHandlers().toArray());
```

`offModuleOf(method)` names the module that owns the command's handler class (a
nested class counts for the handler around it) when it is not running.
Called from the thread the registry is driven from: the main thread, or the
global region on Folia.

## Where the code is

`src/main/java/net/exylia/lib/module/` — `PluginModule`, `PluginModules`, `ModuleToggle`.
Tests: `src/test/java/net/exylia/lib/module/PluginModulesTest.java`.
