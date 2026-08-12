# Task module

Scheduling for Spigot, Paper, Purpur and Folia from a single call. One build,
no platform branching in consuming plugins. Since 1.0.0.

Entry point: `net.exylia.lib.task.Tasks` → `net.exylia.lib.task.TaskScheduler`.

## Getting a scheduler

```java
private TaskScheduler tasks;

@Override
public void onEnable() {
    this.tasks = Tasks.of(this);
}
```

`Tasks.of(plugin)` returns a per-plugin scheduler bound to the right
implementation (`BukkitTaskScheduler` or `FoliaTaskScheduler`), cached by
plugin name. `Tasks.release(pluginName)` drops one; `Tasks.releaseAll()`
drops all (used by the library on plugin disable).

## Choosing a method

Pick by **what the task touches** — that is what makes a plugin Folia-safe
without branching:

| Touches | Method |
| --- | --- |
| entity or player | `runAtEntity(...)` |
| blocks, chunks, a position | `runAtLocation(...)` |
| nothing thread-bound (HTTP, DB, files) | `runAsync(...)` |
| global server state | `run(...)` |

On Spigot/Paper every non-async variant lands on the main thread, so choosing
correctly costs nothing.

## API

`TaskScheduler`:

| Method | Contract |
| --- | --- |
| `run(Runnable)` | next tick, global |
| `runLater(delayTicks, Runnable)` | once, after a delay |
| `runTimer(delay, period, Runnable)` / `runTimer(delay, period, Consumer<TaskHandle>)` | repeating; the `Consumer` form can cancel itself |
| `execute(Runnable)` | run now if already on the right thread, else schedule |
| `runAsync`, `runAsyncLater`, `runAsyncTimer(…, Runnable | Consumer<TaskHandle>)` | async variants |
| `runAtEntity(entity, task, retired)` / without `retired` | entity-bound; `retired` runs if the entity is removed before the task |
| `runAtEntityLater`, `runAtEntityTimer(…, Runnable | Consumer<TaskHandle>)` | delayed / repeating; an entity timer stops itself when the entity is removed |
| `runAtLocation`, `runAtLocationLater`, `runAtLocationTimer(…)` | location-bound variants |
| `isGlobalThread()` | whether the current thread is the global/main one |
| `isOwnedBy(Entity)` / `isOwnedBy(Location)` | whether the current thread owns that context |
| `cancelAll()` | cancel everything this plugin scheduled |
| `activeTasks()` | how many are still running |

`TaskHandle`: `cancel()` (safe from any thread), `isCancelled()`,
`isRepeating()`.

## What the module already handles (do not reimplement)

- Cancellation of everything when the consuming plugin disables.
- Exception isolation: a task that throws does not kill the scheduler.
- Tick normalization (delay 0 means "next tick").
- Entity timers stop on their own when the entity is gone.

## Source and tests

- Public: `task/Tasks.java`, `task/TaskScheduler.java`, `task/TaskHandle.java`,
  `platform/Platform.java` (`current()`, `isFolia()`, `isPaper()`).
- Internal: `task/internal/AbstractTaskScheduler`, `BukkitTaskScheduler`,
  `FoliaTaskScheduler`, `TrackedHandle`.
- Tests: `src/test/java/net/exylia/lib/task/`.
