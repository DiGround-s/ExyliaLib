package net.exylia.lib.util.arena;

import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.RegionId;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One place a template stands — the original or a copy — and whether it is taken.
 *
 * <p>{@code FREE → IN_USE → REGENERATING → FREE}; a copy of a template that was
 * unregistered or replaced ends in {@code CLOSED} instead, once it is free.
 */
final class ArenaInstance {

    enum State { FREE, IN_USE, REGENERATING, CLOSED }

    final ArenaPool.Entry entry;
    final int copy;
    final World world;
    final int dx;
    final int dz;
    final Cuboid bounds;
    final RegionId region;
    final AtomicReference<State> state;

    ArenaInstance(ArenaPool.Entry entry, int copy, World world, int dx, int dz,
                  RegionId region, AtomicReference<State> state) {
        this.entry = entry;
        this.copy = copy;
        this.world = world;
        this.dx = dx;
        this.dz = dz;
        this.bounds = shift(entry.template.bounds(), dx, dz);
        this.region = region;
        this.state = state;
    }

    boolean tryAcquire() {
        return !entry.closed && state.compareAndSet(State.FREE, State.IN_USE);
    }

    /** Regenerates, then frees; completes {@code true} when the blocks came back. */
    CompletableFuture<Boolean> release() {
        return regenerateFrom(State.IN_USE);
    }

    /** Regenerates a free instance nobody is using; {@code false} when it was not free. */
    CompletableFuture<Boolean> regenerateIdle() {
        return regenerateFrom(State.FREE);
    }

    private CompletableFuture<Boolean> regenerateFrom(State from) {
        if (!state.compareAndSet(from, State.REGENERATING)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> regenerated;
        try {
            regenerated = entry.pool.placement.regenerate(entry.template.schematic(), bounds, world);
        } catch (RuntimeException failure) {
            regenerated = CompletableFuture.failedFuture(failure);
        }
        return regenerated.handle((ok, failure) -> {
            // FREE first, then the closed check: close() does the opposite, so
            // whichever runs second sees the other and the region is dropped once.
            state.set(State.FREE);
            if (entry.closed) {
                retire();
            }
            return failure == null && Boolean.TRUE.equals(ok);
        });
    }

    /** Takes a free copy of a closed template out for good. */
    void retire() {
        if (copy > 0 && state.compareAndSet(State.FREE, State.CLOSED)) {
            entry.pool.placement.unregister(region);
        }
    }

    static Cuboid shift(Cuboid box, int dx, int dz) {
        return new Cuboid(box.minX() + dx, box.minY(), box.minZ() + dz,
                box.maxX() + dx, box.maxY(), box.maxZ() + dz);
    }

    static Location shift(Location point, World world, int dx, int dz) {
        return new Location(world, point.getX() + dx, point.getY(), point.getZ() + dz,
                point.getYaw(), point.getPitch());
    }
}
