package net.exylia.lib.task;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Unified scheduling API that behaves identically on Bukkit, Spigot, Paper and
 * Folia.
 *
 * <p>Obtain one with {@link Tasks#of(org.bukkit.plugin.Plugin)}, typically once,
 * stored in a field:
 *
 * <pre>{@code
 * public final class MyPlugin extends JavaPlugin {
 *     private TaskScheduler tasks;
 *
 *     @Override
 *     public void onEnable() {
 *         this.tasks = Tasks.of(this);
 *     }
 * }
 * }</pre>
 *
 * <h2>Choosing a method</h2>
 * The only thing you have to decide is <em>what the task touches</em>. That
 * choice is what makes your plugin Folia-compatible:
 *
 * <table border="1">
 *   <caption>Method selection</caption>
 *   <tr><th>The task touches...</th><th>Use</th></tr>
 *   <tr><td>a specific entity or player</td><td>{@link #runAtEntity}</td></tr>
 *   <tr><td>blocks, chunks or a world position</td><td>{@link #runAtLocation}</td></tr>
 *   <tr><td>nothing thread-bound (I/O, HTTP, database)</td><td>{@link #runAsync}</td></tr>
 *   <tr><td>global server state (weather, whitelist, plugin state)</td><td>{@link #run}</td></tr>
 * </table>
 *
 * <p>On non-Folia servers every non-async method runs on the main thread, so the
 * distinction costs nothing and your code stays correct on both platforms.
 *
 * <h2>Time units</h2>
 * Every delay and period is expressed in <strong>ticks</strong> (20 ticks =
 * 1 second), including the async methods, which convert internally. A delay or
 * period below {@code 1} is raised to {@code 1}, because Folia rejects
 * non-positive values.
 *
 * <h2>Lifecycle</h2>
 * Every task scheduled through this API is tracked and cancelled automatically
 * when the owning plugin is disabled, on <em>all</em> platforms. This closes a
 * real Folia footgun: its region and entity schedulers do not clean up after a
 * plugin on their own.
 *
 * <h2>Thread safety</h2>
 * Every method is safe to call from any thread.
 *
 * @since 1.0.0
 */
public interface TaskScheduler {

    // ------------------------------------------------------------------
    // Global
    // ------------------------------------------------------------------

    /**
     * Runs a task on the global thread as soon as possible.
     *
     * <p>Use this only for state that belongs to the server as a whole (weather,
     * game rules, whitelist) or to your own plugin. If the task touches an entity
     * or a block, use {@link #runAtEntity} or {@link #runAtLocation} instead, or
     * it will not work on Folia.
     *
     * <p>On Folia this targets the global region thread; elsewhere, the main
     * thread. If you need to skip the queue when already on the right thread,
     * see {@link #execute(Runnable)}.
     *
     * @param task the work to run
     * @return a handle that can cancel the task before it starts
     */
    @NotNull TaskHandle run(@NotNull Runnable task);

    /**
     * Runs a task on the global thread after a delay.
     *
     * @param delayTicks ticks to wait before running; values below 1 are treated as 1
     * @param task       the work to run
     * @return a handle that can cancel the task before it starts
     * @see #run(Runnable)
     */
    @NotNull TaskHandle runLater(long delayTicks, @NotNull Runnable task);

    /**
     * Runs a repeating task on the global thread.
     *
     * <p>The task repeats until cancelled through the returned handle or until
     * the plugin is disabled.
     *
     * @param delayTicks  ticks to wait before the first run; values below 1 are treated as 1
     * @param periodTicks ticks between runs; values below 1 are treated as 1
     * @param task        the work to run
     * @return a handle that cancels the timer
     * @see #run(Runnable)
     */
    @NotNull TaskHandle runTimer(long delayTicks, long periodTicks, @NotNull Runnable task);

    /**
     * Runs a repeating task on the global thread that can cancel itself.
     *
     * <p>The handle passed to the callback is the same one this method returns,
     * so a task can stop its own timer:
     *
     * <pre>{@code
     * tasks.runTimer(0L, 20L, handle -> {
     *     if (--countdown <= 0) handle.cancel();
     * });
     * }</pre>
     *
     * @param delayTicks  ticks to wait before the first run; values below 1 are treated as 1
     * @param periodTicks ticks between runs; values below 1 are treated as 1
     * @param task        the work to run, receiving its own handle
     * @return a handle that cancels the timer
     */
    @NotNull TaskHandle runTimer(long delayTicks, long periodTicks, @NotNull Consumer<TaskHandle> task);

    /**
     * Runs a task on the global thread, immediately and inline if the caller is
     * already on that thread.
     *
     * <p>Use this when a task might be triggered from either an event handler or
     * a background thread and you want to avoid a needless one-tick delay. Be
     * aware that the task may therefore run <em>before</em> this method returns.
     *
     * @param task the work to run
     */
    void execute(@NotNull Runnable task);

    // ------------------------------------------------------------------
    // Async
    // ------------------------------------------------------------------

    /**
     * Runs a task off the server threads as soon as possible.
     *
     * <p>Intended for work that never touches the game state: HTTP requests,
     * database queries, file I/O, heavy computation. Calling the Bukkit API from
     * here is unsafe; hop back with {@link #runAtEntity} or {@link #run} first.
     *
     * @param task the work to run
     * @return a handle that can cancel the task before it starts
     */
    @NotNull TaskHandle runAsync(@NotNull Runnable task);

    /**
     * Runs a task off the server threads after a delay.
     *
     * @param delayTicks ticks to wait before running; values below 1 are treated as 1
     * @param task       the work to run
     * @return a handle that can cancel the task before it starts
     * @see #runAsync(Runnable)
     */
    @NotNull TaskHandle runAsyncLater(long delayTicks, @NotNull Runnable task);

    /**
     * Runs a repeating task off the server threads.
     *
     * @param delayTicks  ticks to wait before the first run; values below 1 are treated as 1
     * @param periodTicks ticks between runs; values below 1 are treated as 1
     * @param task        the work to run
     * @return a handle that cancels the timer
     * @see #runAsync(Runnable)
     */
    @NotNull TaskHandle runAsyncTimer(long delayTicks, long periodTicks, @NotNull Runnable task);

    /**
     * Runs a repeating task off the server threads that can cancel itself.
     *
     * @param delayTicks  ticks to wait before the first run; values below 1 are treated as 1
     * @param periodTicks ticks between runs; values below 1 are treated as 1
     * @param task        the work to run, receiving its own handle
     * @return a handle that cancels the timer
     * @see #runTimer(long, long, Consumer)
     */
    @NotNull TaskHandle runAsyncTimer(long delayTicks, long periodTicks, @NotNull Consumer<TaskHandle> task);

    // ------------------------------------------------------------------
    // Entity
    // ------------------------------------------------------------------

    /**
     * Runs a task on the thread that owns an entity.
     *
     * <p>This is the correct way to touch a player or entity: send a message,
     * change inventory, teleport, apply effects. On Folia the task runs on
     * whichever thread currently owns the entity, following it across regions;
     * elsewhere it runs on the main thread.
     *
     * <p>If the entity is removed before the task runs, the task is discarded and
     * {@code retired} is run instead, on the same thread.
     *
     * @param entity  the entity the task acts on
     * @param task    the work to run
     * @param retired run instead of {@code task} if the entity no longer exists,
     *                or {@code null} to simply drop the task
     * @return a handle that can cancel the task before it starts
     */
    @NotNull TaskHandle runAtEntity(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired);

    /**
     * Runs a task on the thread that owns an entity, dropping it if the entity
     * is gone.
     *
     * @param entity the entity the task acts on
     * @param task   the work to run
     * @return a handle that can cancel the task before it starts
     * @see #runAtEntity(Entity, Runnable, Runnable)
     */
    @NotNull TaskHandle runAtEntity(@NotNull Entity entity, @NotNull Runnable task);

    /**
     * Runs a task on the thread that owns an entity, after a delay.
     *
     * @param entity     the entity the task acts on
     * @param delayTicks ticks to wait before running; values below 1 are treated as 1
     * @param task       the work to run
     * @return a handle that can cancel the task before it starts
     * @see #runAtEntity(Entity, Runnable, Runnable)
     */
    @NotNull TaskHandle runAtEntityLater(@NotNull Entity entity, long delayTicks, @NotNull Runnable task);

    /**
     * Runs a repeating task on the thread that owns an entity.
     *
     * <p>The timer stops on its own when the entity is removed, so this is the
     * safe way to attach a recurring effect to a player or mob.
     *
     * @param entity      the entity the task acts on
     * @param delayTicks  ticks to wait before the first run; values below 1 are treated as 1
     * @param periodTicks ticks between runs; values below 1 are treated as 1
     * @param task        the work to run
     * @return a handle that cancels the timer
     * @see #runAtEntity(Entity, Runnable, Runnable)
     */
    @NotNull TaskHandle runAtEntityTimer(@NotNull Entity entity, long delayTicks, long periodTicks, @NotNull Runnable task);

    /**
     * Runs a repeating task on the thread that owns an entity, able to cancel
     * itself.
     *
     * @param entity      the entity the task acts on
     * @param delayTicks  ticks to wait before the first run; values below 1 are treated as 1
     * @param periodTicks ticks between runs; values below 1 are treated as 1
     * @param task        the work to run, receiving its own handle
     * @return a handle that cancels the timer
     * @see #runTimer(long, long, Consumer)
     */
    @NotNull TaskHandle runAtEntityTimer(@NotNull Entity entity, long delayTicks, long periodTicks, @NotNull Consumer<TaskHandle> task);

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    /**
     * Runs a task on the thread that owns a location.
     *
     * <p>This is the correct way to touch blocks, chunks, or to spawn things into
     * the world. On Folia the task runs on the thread owning that region;
     * elsewhere it runs on the main thread.
     *
     * @param location where the task acts
     * @param task     the work to run
     * @return a handle that can cancel the task before it starts
     */
    @NotNull TaskHandle runAtLocation(@NotNull Location location, @NotNull Runnable task);

    /**
     * Runs a task on the thread that owns a location, after a delay.
     *
     * @param location   where the task acts
     * @param delayTicks ticks to wait before running; values below 1 are treated as 1
     * @param task       the work to run
     * @return a handle that can cancel the task before it starts
     * @see #runAtLocation(Location, Runnable)
     */
    @NotNull TaskHandle runAtLocationLater(@NotNull Location location, long delayTicks, @NotNull Runnable task);

    /**
     * Runs a repeating task on the thread that owns a location.
     *
     * @param location    where the task acts
     * @param delayTicks  ticks to wait before the first run; values below 1 are treated as 1
     * @param periodTicks ticks between runs; values below 1 are treated as 1
     * @param task        the work to run
     * @return a handle that cancels the timer
     * @see #runAtLocation(Location, Runnable)
     */
    @NotNull TaskHandle runAtLocationTimer(@NotNull Location location, long delayTicks, long periodTicks, @NotNull Runnable task);

    /**
     * Runs a repeating task on the thread that owns a location, able to cancel
     * itself.
     *
     * @param location    where the task acts
     * @param delayTicks  ticks to wait before the first run; values below 1 are treated as 1
     * @param periodTicks ticks between runs; values below 1 are treated as 1
     * @param task        the work to run, receiving its own handle
     * @return a handle that cancels the timer
     * @see #runTimer(long, long, Consumer)
     */
    @NotNull TaskHandle runAtLocationTimer(@NotNull Location location, long delayTicks, long periodTicks, @NotNull Consumer<TaskHandle> task);

    // ------------------------------------------------------------------
    // Thread checks
    // ------------------------------------------------------------------

    /**
     * Returns whether the calling thread may act on global server state.
     *
     * @return {@code true} on Folia's global region thread, or on the main thread
     *         elsewhere
     */
    boolean isGlobalThread();

    /**
     * Returns whether the calling thread currently owns an entity.
     *
     * <p>Always {@code true} on the main thread of a non-Folia server.
     *
     * @param entity the entity to check
     * @return {@code true} if the caller may act on that entity right now
     */
    boolean isOwnedBy(@NotNull Entity entity);

    /**
     * Returns whether the calling thread currently owns a location's region.
     *
     * <p>Always {@code true} on the main thread of a non-Folia server.
     *
     * @param location the location to check
     * @return {@code true} if the caller may act on that location right now
     */
    boolean isOwnedBy(@NotNull Location location);

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Cancels every task scheduled through this scheduler.
     *
     * <p>This happens automatically when the plugin is disabled, so calling it
     * manually is only needed when you want to reset state, for example on a
     * reload command.
     */
    void cancelAll();

    /**
     * Returns how many scheduled tasks are still active.
     *
     * <p>Intended for diagnostics; a number that only grows usually means timers
     * are being created and never cancelled.
     *
     * @return the number of tracked tasks that have not finished or been cancelled
     */
    int activeTasks();
}
