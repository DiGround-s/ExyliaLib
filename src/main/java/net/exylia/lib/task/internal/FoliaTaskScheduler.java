package net.exylia.lib.task.internal;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.exylia.lib.task.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Implementation for Folia and its forks.
 *
 * <p>Folia has no main thread. Work is owned by the thread responsible for a
 * region or an entity, so each variant maps to a different native scheduler:
 * global to {@link GlobalRegionScheduler}, entity to the entity's own scheduler,
 * location to {@link RegionScheduler}, and async to {@link AsyncScheduler}.
 *
 * <p>Two Folia rough edges are smoothed over here:
 * <ul>
 *   <li>delays and periods must be positive, so they are normalised upstream in
 *       {@link AbstractTaskScheduler#normalizeTicks(long)};</li>
 *   <li>the region and entity schedulers do not cancel a disabled plugin's tasks,
 *       which the handle tracking in {@link AbstractTaskScheduler} takes care of.</li>
 * </ul>
 *
 * <p>This class is only ever loaded after {@link net.exylia.lib.platform.Platform}
 * confirms Folia is present.
 */
public final class FoliaTaskScheduler extends AbstractTaskScheduler {

    private final GlobalRegionScheduler globalScheduler;
    private final RegionScheduler regionScheduler;
    private final AsyncScheduler asyncScheduler;

    public FoliaTaskScheduler(Plugin plugin) {
        super(plugin);
        this.globalScheduler = Bukkit.getGlobalRegionScheduler();
        this.regionScheduler = Bukkit.getRegionScheduler();
        this.asyncScheduler = Bukkit.getAsyncScheduler();
    }

    /** Adapts a native task to the canceller shape the handle expects. */
    private static Runnable canceller(ScheduledTask task) {
        return task::cancel;
    }

    // ------------------------------------------------------------------
    // Global
    // ------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle run(@NotNull Runnable task) {
        TaskHandle stopped = runIfStopped(task);
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        Runnable body = once(handle, task);
        bind(handle, canceller(globalScheduler.run(plugin, scheduled -> body.run())));
        return handle;
    }

    @Override
    public @NotNull TaskHandle runLater(long delayTicks, @NotNull Runnable task) {
        TaskHandle stopped = runIfStopped(task);
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        Runnable body = once(handle, task);
        bind(handle, canceller(globalScheduler.runDelayed(plugin, scheduled -> body.run(),
                normalizeTicks(delayTicks))));
        return handle;
    }

    @Override
    public @NotNull TaskHandle runTimer(long delayTicks, long periodTicks, @NotNull Runnable task) {
        TaskHandle dropped = dropIfStopped();
        if (dropped != null) return dropped;
        TrackedHandle handle = newHandle(true);
        Runnable body = repeating(handle, task);
        bind(handle, canceller(globalScheduler.runAtFixedRate(plugin, scheduled -> body.run(),
                normalizeTicks(delayTicks), normalizeTicks(periodTicks))));
        return handle;
    }

    @Override
    public void execute(@NotNull Runnable task) {
        if (Bukkit.isGlobalTickThread() || !plugin.isEnabled()) {
            task.run();
            return;
        }
        globalScheduler.execute(plugin, task);
    }

    // ------------------------------------------------------------------
    // Async
    // ------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAsync(@NotNull Runnable task) {
        TaskHandle stopped = runIfStopped(task);
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        Runnable body = once(handle, task);
        bind(handle, canceller(asyncScheduler.runNow(plugin, scheduled -> body.run())));
        return handle;
    }

    @Override
    public @NotNull TaskHandle runAsyncLater(long delayTicks, @NotNull Runnable task) {
        TaskHandle stopped = runIfStopped(task);
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        Runnable body = once(handle, task);
        bind(handle, canceller(asyncScheduler.runDelayed(plugin, scheduled -> body.run(),
                ticksToMillis(delayTicks), TimeUnit.MILLISECONDS)));
        return handle;
    }

    @Override
    public @NotNull TaskHandle runAsyncTimer(long delayTicks, long periodTicks, @NotNull Runnable task) {
        TaskHandle dropped = dropIfStopped();
        if (dropped != null) return dropped;
        TrackedHandle handle = newHandle(true);
        Runnable body = repeating(handle, task);
        bind(handle, canceller(asyncScheduler.runAtFixedRate(plugin, scheduled -> body.run(),
                ticksToMillis(delayTicks), ticksToMillis(periodTicks), TimeUnit.MILLISECONDS)));
        return handle;
    }

    // ------------------------------------------------------------------
    // Entity
    // ------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAtEntity(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired) {
        TaskHandle stopped = runIfStopped(() -> {
            if (entity.isValid()) {
                task.run();
            } else if (retired != null) {
                retired.run();
            }
        });
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        Runnable body = once(handle, task);
        Runnable onRetired = () -> {
            try {
                if (retired != null) {
                    retired.run();
                }
            } finally {
                handle.complete();
            }
        };

        ScheduledTask scheduled = entity.getScheduler().run(plugin, ignored -> body.run(), onRetired);
        if (scheduled == null) {
            // The entity was already removed; Folia ran the retired callback itself.
            return handle;
        }
        bind(handle, canceller(scheduled));
        return handle;
    }

    @Override
    public @NotNull TaskHandle runAtEntityLater(@NotNull Entity entity, long delayTicks, @NotNull Runnable task) {
        TaskHandle stopped = runIfStopped(() -> {
            if (entity.isValid()) {
                task.run();
            }
        });
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        Runnable body = once(handle, task);

        ScheduledTask scheduled = entity.getScheduler().runDelayed(plugin, ignored -> body.run(),
                handle::complete, normalizeTicks(delayTicks));
        if (scheduled == null) {
            handle.complete();
            return handle;
        }
        bind(handle, canceller(scheduled));
        return handle;
    }

    @Override
    public @NotNull TaskHandle runAtEntityTimer(@NotNull Entity entity, long delayTicks, long periodTicks,
                                                @NotNull Runnable task) {
        TaskHandle dropped = dropIfStopped();
        if (dropped != null) return dropped;
        TrackedHandle handle = newHandle(true);
        Runnable body = repeating(handle, task);

        ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(plugin, ignored -> body.run(),
                handle::cancel, normalizeTicks(delayTicks), normalizeTicks(periodTicks));
        if (scheduled == null) {
            handle.cancel();
            return handle;
        }
        bind(handle, canceller(scheduled));
        return handle;
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull Location location, @NotNull Runnable task) {
        TaskHandle stopped = runIfStopped(task);
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        Runnable body = once(handle, task);
        bind(handle, canceller(regionScheduler.run(plugin, location, scheduled -> body.run())));
        return handle;
    }

    @Override
    public @NotNull TaskHandle runAtLocationLater(@NotNull Location location, long delayTicks, @NotNull Runnable task) {
        TaskHandle stopped = runIfStopped(task);
        if (stopped != null) return stopped;
        TrackedHandle handle = newHandle(false);
        Runnable body = once(handle, task);
        bind(handle, canceller(regionScheduler.runDelayed(plugin, location, scheduled -> body.run(),
                normalizeTicks(delayTicks))));
        return handle;
    }

    @Override
    public @NotNull TaskHandle runAtLocationTimer(@NotNull Location location, long delayTicks, long periodTicks,
                                                  @NotNull Runnable task) {
        TaskHandle dropped = dropIfStopped();
        if (dropped != null) return dropped;
        TrackedHandle handle = newHandle(true);
        Runnable body = repeating(handle, task);
        bind(handle, canceller(regionScheduler.runAtFixedRate(plugin, location, scheduled -> body.run(),
                normalizeTicks(delayTicks), normalizeTicks(periodTicks))));
        return handle;
    }

    // ------------------------------------------------------------------
    // Thread checks
    // ------------------------------------------------------------------

    @Override
    public boolean isGlobalThread() {
        return Bukkit.isGlobalTickThread();
    }

    @Override
    public boolean isOwnedBy(@NotNull Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    @Override
    public boolean isOwnedBy(@NotNull Location location) {
        return Bukkit.isOwnedByCurrentRegion(location);
    }
}
