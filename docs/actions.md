# Actions

Small, compiled actions shared by menus, items and any other event boundary.

```java
PluginActions actions = Actions.of(this, "practice");

actions.registerSync("join_queue", (context, args) -> {
    queues.join(context.player(), args.string(0));
    return ActionResult.success();
});

// Compile while loading YAML, not when the player clicks.
ActionCall click = actions.compile("practice:join_queue boxing");

// The hot path is now one direct handler call.
click.execute(ActionContext.forPlayer(player).origin("menu").build());
```

## What Actions does — and does not do
Actions owns:

- a registry namespaced by plugin;
- compilation of an action string into a direct call;
- typed context shared by UI, Items and other adapters;
- synchronous and genuinely asynchronous handlers;
- sequential execution, delays and shared variables;
- cleanup when an owning plugin disables.

Actions does **not** know about clicks, hands, slots, inventories, projectiles,
permissions or cooldowns. UI parses `left:` and contributes UI keys; Items
parses triggers and contributes item keys. The core stays reusable rather than
becoming a second event framework.

## Registration and namespaces
```java
PluginActions actions = Actions.of(plugin, "practice");

actions.registerSync("open_settings", (context, args) -> {
    menus.openSettings(context.player());
    return ActionResult.success();
});
```

Public YAML should always use the full id:

```yaml
actions:
  - "practice:open_settings"
```

A plugin may compile its own local id (`actions.compile("open_settings")`), but
`Actions.compile("open_settings")` rejects it. This prevents the old behaviour
where a simple id worked until another plugin registered the same one and both
became ambiguous.

Duplicate full ids are rejected. Registrations are automatically removed when
the owner disables. A compiled call retained by an old menu is deactivated too:
it cannot invoke code from a dead classloader.

Empty strings, `none` and `noop` compile into a real no-op. Dynamic menu
placeholders no longer turn `none` into an unknown-action error.

## Compile once
```java
ActionCall call = actions.compile("practice:adjust_priority -10");
```

Compilation does the expensive work once:

1. parse and validate the namespaced id;
2. resolve the registration;
3. tokenise quoted arguments and escapes;
4. retain the direct registered handler.

Execution does none of those things. Menu and item modules should hold the
`ActionCall` on the loaded definition, not compile on every interaction.

Arguments preserve negative numbers and quoted strings:

```java
ActionArguments args = actions.compile(
        "practice:set_name 'Ranked Boxing' -0.5 true").arguments();

args.string(0);          // Ranked Boxing
args.decimal(1);         // -0.5
args.bool(2, false);     // true
```

There is deliberately no flag syntax: treating every nonnumeric `-value` as a
flag made ordinary hyphenated arguments surprising and bought no real usage.

## Typed contexts
The common context only contains a player, an origin, typed adapter data and a
shared scope.

```java
public static final ActionKey<Integer> SLOT =
        ActionKey.of("ui.slot", Integer.class);
public static final ActionKey<ClickType> CLICK =
        ActionKey.of("ui.click", ClickType.class);

ActionContext context = ActionContext.forPlayer(player)
        .origin("menu")
        .put(SLOT, slot)
        .put(CLICK, click)
        .build();
```

Handlers then use:

```java
int slot = context.require(SLOT);
ClickType click = context.require(CLICK);
```

Not:

```java
context.getData("slot", Integer.class);
```

UI and Items define their keys in their own modules. Actions does not import
their types.

## Results and sequence control
```java
ActionResult.success();              // continue
ActionResult.stop();                 // intentional end
ActionResult.denied("permission");  // requirement failed
ActionResult.failed("bad value");   // invalid input or defect
```

Only `SUCCESS` advances a sequence. `STOP`, `DENIED` and `FAILED` finish it with
that result. Exceptions become `FAILED`; they do not escape into an inventory
click listener.

There is no metadata map, execution UUID, audit record or nanosecond timer
allocated for every click. A consumer that needs metrics can measure at its
own boundary.

## Synchronous and asynchronous work
Ordinary actions run directly:

```java
actions.registerSync("close", (context, args) -> {
    context.player().closeInventory();
    return ActionResult.success();
});
```

No scheduler task and no worker thread are created.

Blocking I/O is explicit:

```java
actions.registerAsync("load_stats", (context, args) -> {
    repository.load(context.player().getUniqueId());
    return ActionResult.success();
});
```

`registerAsync` goes through ExyliaLib `Tasks`; it creates no private executor.
When an async step finishes, the sequence resumes on the player-owning thread,
which is correct on Folia and the main thread elsewhere.

A handler that already owns a `CompletionStage` can use `register` directly.

## Sequences
For strings with no per-step configuration:

```java
ActionSequence sequence = actions.compile(List.of(
        "specials:damage 3.0",
        "specials:heal 1.8"
));
```

For structured item configuration and delays:

```java
ActionSequence sequence = actions.sequence()
        .then("specials:damage", 0, ITEM_CONFIG, damageConfig)
        .then("specials:heal", 10, ITEM_CONFIG, healConfig)
        .build();
```

Every step is compiled in advance. `delayTicks` means wait before the step;
delayed continuations run at the player via Tasks.

### Shared values
```java
ActionKey<Double> LAST_DAMAGE =
        ActionKey.of("specials.last_damage", Double.class);

actions.registerSync("damage", (context, args) -> {
    context.scope().set(LAST_DAMAGE, actualDamage);
    return ActionResult.success();
});

actions.registerSync("heal", (context, args) -> {
    double dealt = context.scope().require(LAST_DAMAGE);
    return ActionResult.success();
});
```

`ActionScope` is thread-safe because an async step may populate it before a
synchronous continuation resumes.

## Actions that depend on the row they are drawn for
A menu row's button often carries the id of the thing in that row, and
sometimes has nothing to offer at all:

```java
// "practice:party_kick %member_id%", or "none" for a row the viewer cannot kick
ActionTemplate kick = actions.template(config.getString("kick-action"));

kick.resolve(viewer, Map.of("member_id", member.id())).execute(context);
```

- **A template with no placeholders costs nothing.** It is compiled once, when
  the menu loads, and every use returns that same call. A typo is then reported
  at load rather than when somebody presses the button.
- **`resolveOrNoop` survives stale data.** An id that no longer exists leaves a
  dead button instead of stopping the menu from opening.

## Stopping what has not run yet
`execute` returns an `ActionExecution`, not a bare future:

```java
ActionExecution running = sequence.execute(context);
// when the menu closes, or the player logs out:
running.cancel("menu closed");
```

Without this, a sequence with a delayed step outlives the screen that started
it. Cancelling stops the sequence before its next step and cancels any pending
delay outright, so nothing is left scheduled. A step already running is not
interrupted — killing code halfway through is worse than letting it finish —
but nothing after it starts. Cancelling twice, or after the sequence already
finished, does nothing.

## Future UI adapter
UI should compile:

```yaml
actions:
  - "left: practice:set_attribute max_health"
  - "right: practice:reset_attribute max_health"
  - "shift_left: practice:adjust_priority 10"
```

into its own structure:

```java
ClickBinding(Set<ClickKind> clicks, ActionCall action)
```

`left:` belongs to UI, not Actions. Built-ins such as close, next page, previous
page and back also belong to UI and register ordinary actions through this core.

## Future Items adapter
Items should compile its trigger and action separately:

```yaml
trigger: RIGHT_CLICK
actions:
  - action: "specials:effect"
    delay-ticks: 0
    action-config:
      effects: ["SPEED|2|5"]
```

It can contribute typed keys for the item, hand, target, block, location and
trigger without changing this module.

## Intentionally not migrated from Commons
- generic middleware pipeline;
- duplicated cooldown and rate-limit systems;
- execution/audit caches;
- aliases and ambiguous simple-id mappings;
- priority and category metadata;
- parallel SyncAction/AsyncAction class hierarchies;
- ConditionalAction and ChainedAction wrappers;
- a UUID and timing record for every execution;
- repeated parsing and registry lookup on every click.

Use ExyliaLib `Cooldowns` when an action actually needs a cooldown. Conditions
are ordinary handlers returning `STOP` or `DENIED`.

## Source and tests
- Public: `action/Actions`, `PluginActions`, `ActionId`, `ActionCall`,
  `ActionArguments`, `ActionContext`, `ActionKey`, `ActionScope`,
  `ActionHandler`, `ActionResult`, `ActionStep`, `ActionSequence`,
  `ActionTemplate`, `ActionExecution`.
- Internal: `action/internal/ActionRegistry`, `RegisteredAction`.
- Tests: `action/ActionsTest`, `ActionSequenceTest`, `ActionTemplateTest`.
