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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

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
    /** Entity timers share one ticker; scheduled from any thread, run on the main one. */
    private final Queue<EntityTimer> entityTimers = new ConcurrentLinkedQueue<>();
    private @Nullable BukkitTask entityTicker;
    private volatile long entityTicks;

    public BukkitTaskScheduler(Plugin plugin) {
        super(plugin);
        this.scheduler = Bukkit.getScheduler();
    }

    // ------------------------------------------------------------------
    // Global
    // ------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle run(@NotNull Runnable task) {
        TaskHandle stopped = runIfStopped(task);
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        BukkitTask bukkitTask = scheduler.runTask(plugin, once(handle, task));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    @Override
    public @NotNull TaskHandle runLater(long delayTicks, @NotNull Runnable task) {
        TaskHandle stopped = runIfStopped(task);
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        BukkitTask bukkitTask = scheduler.runTaskLater(plugin, once(handle, task), normalizeTicks(delayTicks));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    @Override
    public @NotNull TaskHandle runTimer(long delayTicks, long periodTicks, @NotNull Runnable task) {
        TaskHandle dropped = dropIfStopped();
        if (dropped != null) return dropped;
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
        TaskHandle stopped = runIfStopped(task);
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        BukkitTask bukkitTask = scheduler.runTaskAsynchronously(plugin, once(handle, task));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    @Override
    public @NotNull TaskHandle runAsyncLater(long delayTicks, @NotNull Runnable task) {
        TaskHandle stopped = runIfStopped(task);
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        BukkitTask bukkitTask = scheduler.runTaskLaterAsynchronously(plugin, once(handle, task),
                normalizeTicks(delayTicks));
        bind(handle, bukkitTask::cancel);
        return handle;
    }

    @Override
    public @NotNull TaskHandle runAsyncTimer(long delayTicks, long periodTicks, @NotNull Runnable task) {
        TaskHandle dropped = dropIfStopped();
        if (dropped != null) return dropped;
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
            if (exists(entity)) {
                task.run();
            } else if (retired != null) {
                retired.run();
            }
        });
    }

    @Override
    public @NotNull TaskHandle runAtEntityLater(@NotNull Entity entity, long delayTicks, @NotNull Runnable task) {
        return runAtEntityLater(entity, delayTicks, task, null);
    }

    @Override
    public @NotNull TaskHandle runAtEntityLater(@NotNull Entity entity, long delayTicks, @NotNull Runnable task,
                                                @Nullable Runnable retired) {
        return runLater(delayTicks, () -> {
            if (exists(entity)) {
                task.run();
            } else if (retired != null) {
                retired.run();
            }
        });
    }

    @Override
    public @NotNull TaskHandle runAtEntityTimer(@NotNull Entity entity, long delayTicks, long periodTicks,
                                                @NotNull Runnable task) {
        TaskHandle dropped = dropIfStopped();
        if (dropped != null) return dropped;
        // One Bukkit timer per player per feature grows with the server: every
        // entity timer of this plugin rides one shared ticker instead.
        TrackedHandle handle = newHandle(true);
        EntityTimer timer = new EntityTimer(entity, repeating(handle, task), handle,
                entityTicks + normalizeTicks(delayTicks), normalizeTicks(periodTicks));
        bind(handle, () -> {
            entityTimers.remove(timer);
            stopEntityTickerIfIdle();
        });
        addEntityTimer(timer);
        return handle;
    }

    private synchronized void addEntityTimer(EntityTimer timer) {
        if (entityTicker != null && entityTicker.isCancelled()) {
            // The server killed the ticker (plugin disabled): its timers died with it.
            entityTimers.forEach(stale -> stale.handle.cancel());
        }
        entityTimers.add(timer);
        if (entityTicker == null || entityTicker.isCancelled()) {
            entityTicker = scheduler.runTaskTimer(plugin, this::tickEntityTimers, 1L, 1L);
        }
    }

    private void tickEntityTimers() {
        long now = ++entityTicks;
        for (EntityTimer timer : entityTimers) {
            if (now < timer.next) continue;
            timer.next = now + timer.period;
            // Folia stops an entity timer once the entity is gone; mirror that here so
            // behaviour matches on both platforms.
            if (exists(timer.entity)) {
                timer.task.run();
            } else {
                timer.handle.cancel();
            }
        }
    }

    /** An idle plugin keeps no task at all. */
    private synchronized void stopEntityTickerIfIdle() {
        if (entityTimers.isEmpty() && entityTicker != null) {
            entityTicker.cancel();
            entityTicker = null;
        }
    }

    private static final class EntityTimer {
        final Entity entity;
        final Runnable task;
        final TrackedHandle handle;
        final long period;
        long next;

        EntityTimer(Entity entity, Runnable task, TrackedHandle handle, long next, long period) {
            this.entity = entity;
            this.task = task;
            this.handle = handle;
            this.next = next;
            this.period = period;
        }
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
