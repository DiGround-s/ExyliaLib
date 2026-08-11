package net.exylia.lib.task.internal;

import net.exylia.lib.task.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Implementation for single-threaded servers: Bukkit, Spigot, Paper and their
 * non-regionised forks.
 *
 * <p>There is only one main thread here, so the entity and location variants all
 * collapse onto it. They still exist, and consumers should still pick the
 * semantically correct one, because that is exactly what makes the same code run
 * unchanged on Folia.
 *
 * <p>This class deliberately references no Folia type, so it loads on any
 * server.
 */
public final class BukkitTaskScheduler extends AbstractTaskScheduler {

    private final BukkitScheduler scheduler;

    public BukkitTaskScheduler(Plugin plugin) {
        super(plugin);
        this.scheduler = Bukkit.getScheduler();
    }

    // ------------------------------------------------------------------
    // Global
    // ------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle run(@NotNull Runnable task) {
        TrackedHandle handle = newHandle(false);
        BukkitTask bukkitTask = scheduler.runTask(plugin, once(handle, task));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    @Override
    public @NotNull TaskHandle runLater(long delayTicks, @NotNull Runnable task) {
        TrackedHandle handle = newHandle(false);
        BukkitTask bukkitTask = scheduler.runTaskLater(plugin, once(handle, task), normalizeTicks(delayTicks));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    @Override
    public @NotNull TaskHandle runTimer(long delayTicks, long periodTicks, @NotNull Runnable task) {
        TrackedHandle handle = newHandle(true);
        BukkitTask bukkitTask = scheduler.runTaskTimer(plugin, repeating(handle, task),
                normalizeTicks(delayTicks), normalizeTicks(periodTicks));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    @Override
    public void execute(@NotNull Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        run(task);
    }

    // ------------------------------------------------------------------
    // Async
    // ------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAsync(@NotNull Runnable task) {
        TrackedHandle handle = newHandle(false);
        BukkitTask bukkitTask = scheduler.runTaskAsynchronously(plugin, once(handle, task));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    @Override
    public @NotNull TaskHandle runAsyncLater(long delayTicks, @NotNull Runnable task) {
        TrackedHandle handle = newHandle(false);
        BukkitTask bukkitTask = scheduler.runTaskLaterAsynchronously(plugin, once(handle, task),
                normalizeTicks(delayTicks));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    @Override
    public @NotNull TaskHandle runAsyncTimer(long delayTicks, long periodTicks, @NotNull Runnable task) {
        TrackedHandle handle = newHandle(true);
        BukkitTask bukkitTask = scheduler.runTaskTimerAsynchronously(plugin, repeating(handle, task),
                normalizeTicks(delayTicks), normalizeTicks(periodTicks));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    // ------------------------------------------------------------------
    // Entity
    // ------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAtEntity(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired) {
        return run(() -> {
            if (entity.isValid()) {
                task.run();
            } else if (retired != null) {
                retired.run();
            }
        });
    }

    @Override
    public @NotNull TaskHandle runAtEntityLater(@NotNull Entity entity, long delayTicks, @NotNull Runnable task) {
        return runLater(delayTicks, () -> {
            if (entity.isValid()) {
                task.run();
            }
        });
    }

    @Override
    public @NotNull TaskHandle runAtEntityTimer(@NotNull Entity entity, long delayTicks, long periodTicks,
                                                @NotNull Runnable task) {
        // Folia stops an entity timer once the entity is gone; mirror that here so
        // behaviour matches on both platforms.
        TrackedHandle handle = newHandle(true);
        BukkitTask bukkitTask = scheduler.runTaskTimer(plugin, repeating(handle, () -> {
            if (entity.isValid()) {
                task.run();
            } else {
                handle.cancel();
            }
        }), normalizeTicks(delayTicks), normalizeTicks(periodTicks));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull Location location, @NotNull Runnable task) {
        return run(task);
    }

    @Override
    public @NotNull TaskHandle runAtLocationLater(@NotNull Location location, long delayTicks, @NotNull Runnable task) {
        return runLater(delayTicks, task);
    }

    @Override
    public @NotNull TaskHandle runAtLocationTimer(@NotNull Location location, long delayTicks, long periodTicks,
                                                  @NotNull Runnable task) {
        return runTimer(delayTicks, periodTicks, task);
    }

    // ------------------------------------------------------------------
    // Thread checks
    // ------------------------------------------------------------------

    @Override
    public boolean isGlobalThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public boolean isOwnedBy(@NotNull Entity entity) {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public boolean isOwnedBy(@NotNull Location location) {
        return Bukkit.isPrimaryThread();
    }
}
