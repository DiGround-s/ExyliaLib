package net.exylia.lib.task;

import net.exylia.lib.platform.Platform;
import net.exylia.lib.task.internal.BukkitTaskScheduler;
import net.exylia.lib.task.internal.FoliaTaskScheduler;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point of the task module.
 *
 * <p>Hands out the {@link TaskScheduler} for a plugin, already bound to the right
 * implementation for the server it is running on.
 *
 * <pre>{@code
 * public final class MyPlugin extends JavaPlugin {
 *     private TaskScheduler tasks;
 *
 *     @Override
 *     public void onEnable() {
 *         this.tasks = Tasks.of(this);
 *
 *         tasks.runTimer(0L, 20L, this::tick);
 *         tasks.runAsync(this::loadDataFromDatabase);
 *     }
 * }
 * }</pre>
 *
 * <p>Schedulers are cached per plugin, so calling {@link #of(Plugin)} repeatedly
 * is cheap and always returns the same instance. There is no need to release it:
 * ExyliaLib cancels every task and drops the scheduler when the plugin is
 * disabled.
 *
 * @since 1.0.0
 */
public final class Tasks {

    private static final Map<String, TaskScheduler> SCHEDULERS = new ConcurrentHashMap<>();

    private Tasks() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns the scheduler for a plugin.
     *
     * <p>Safe to call from any thread, and cheap enough to call anywhere, though
     * storing the result in a field reads better.
     *
     * @param plugin the plugin that will own the tasks
     * @return the scheduler for that plugin, never {@code null}
     * @throws IllegalArgumentException if {@code plugin} is {@code null}
     */
    public static @NotNull TaskScheduler of(@NotNull Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
        return SCHEDULERS.computeIfAbsent(plugin.getName(), ignored -> create(plugin));
    }

    private static TaskScheduler create(Plugin plugin) {
        return Platform.isFolia()
                ? new FoliaTaskScheduler(plugin)
                : new BukkitTaskScheduler(plugin);
    }

    /**
     * Cancels a plugin's tasks and forgets its scheduler.
     *
     * <p>Called by ExyliaLib when a plugin is disabled. Consumers do not need to
     * call this.
     *
     * @param pluginName the name of the plugin being disabled
     */
    public static void release(@NotNull String pluginName) {
        TaskScheduler scheduler = SCHEDULERS.remove(pluginName);
        if (scheduler != null) {
            scheduler.cancelAll();
        }
    }

    /**
     * Cancels every task of every plugin and clears the cache.
     *
     * <p>Called by ExyliaLib on shutdown. Consumers do not need to call this.
     */
    public static void releaseAll() {
        SCHEDULERS.values().forEach(TaskScheduler::cancelAll);
        SCHEDULERS.clear();
    }
}
