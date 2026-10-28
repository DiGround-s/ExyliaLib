package net.exylia.lib.util.arena;

import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.RegionId;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One hold on an arena, from {@link ArenaPool#acquire} until {@link #release()}.
 *
 * <pre>{@code
 * pool.acquire("forest").thenAccept(lease -> lease.ifPresentOrElse(arena -> {
 *     Teleports.of(plugin).to(first, arena.point("a")).warmup(0).start();
 *     Teleports.of(plugin).to(second, arena.point("b")).warmup(0).start();
 *     match.onEnd(arena::release);
 * }, () -> queue.requeue(first, second)));
 * }</pre>
 *
 * <p>Every getter is already moved to this copy, so a plugin never knows whether it
 * got the original or a copy. All of it is safe from any thread.
 *
 * @since 1.346.0
 */
public final class ArenaLease {

    private final ArenaInstance instance;
    private final AtomicReference<CompletableFuture<Boolean>> released = new AtomicReference<>();
    /** The last in-place regeneration; a release waits for it. */
    private CompletableFuture<Boolean> regenerating = CompletableFuture.completedFuture(true);

    ArenaLease(ArenaInstance instance) {
        this.instance = instance;
    }

    /** @return the template this arena was made from */
    public @NotNull ArenaTemplate template() {
        return instance.entry.template;
    }

    /** @return {@code 0} for the original, {@code 1} and up for a copy */
    public int copy() {
        return instance.copy;
    }

    /** @return whether this is a copy in the temporary world */
    public boolean isClone() {
        return instance.copy > 0;
    }

    /** @return the world this arena is in: the template's, or the temporary world for a copy */
    public @NotNull World world() {
        return instance.world;
    }

    /** @return the whole arena, in {@link #world()} */
    public @NotNull Cuboid bounds() {
        return instance.bounds;
    }

    /** @return the region the pool registered over {@link #bounds()} */
    public @NotNull RegionId region() {
        return instance.region;
    }

    /**
     * A named point of the template, moved to this arena.
     *
     * @param name the point's name, such as {@code "a"} or {@code "spectator"}
     * @return a new location, or {@code null} when the template has no such point
     */
    public @Nullable Location point(@NotNull String name) {
        Location point = template().points().get(name);
        return point == null ? null : ArenaInstance.shift(point, instance.world, instance.dx, instance.dz);
    }

    /**
     * A named box of the template, moved to this arena.
     *
     * @param name the box's name
     * @return the box, or {@code null} when the template has no such box
     */
    public @Nullable Cuboid box(@NotNull String name) {
        Cuboid box = template().boxes().get(name);
        return box == null ? null : ArenaInstance.shift(box, instance.dx, instance.dz);
    }

    /**
     * Puts the arena back as the schematic has it while keeping it: between two
     * rounds of one match. Its loose entities are removed, its blocks regenerated
     * and anyone buried lifted out, exactly as {@link #release()} does, and what the
     * region recorded as placed by players is forgotten; the arena stays this
     * lease's.
     *
     * <p>Move the players onto their spawns after it completes, not before: the
     * regeneration works chunk by chunk and does not wait for anyone to stop
     * building. Calls are queued, never run at once, and a {@link #release()} made
     * meanwhile waits for the last one.
     *
     * @return completes {@code true} when the blocks came back, {@code false} when the
     *         regeneration failed (it is logged) or the lease was already released.
     *         Never completes exceptionally.
     * @since 1.388.0
     */
    public synchronized @NotNull CompletableFuture<Boolean> regenerate() {
        if (isReleased()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> next = regenerating
                .thenCompose(ignored -> instance.regenerateInUse())
                .exceptionally(failure -> false);
        regenerating = next;
        return next;
    }

    /**
     * Gives the arena back: its loose entities are removed and its blocks
     * regenerated from the schematic, and only then can it be acquired again.
     *
     * <p>Move the players out first; anyone still inside is lifted out of the
     * blocks, not taken away. Calling it again returns the same future and does
     * nothing else, so a lease is never released twice into somebody else's match.
     * A {@link #regenerate()} still running is waited for first.
     *
     * @return completes once the arena is free: {@code true} when the blocks came
     *         back, {@code false} when regeneration failed (it is logged, and the
     *         arena is freed anyway). Never completes exceptionally.
     */
    public @NotNull CompletableFuture<Boolean> release() {
        CompletableFuture<Boolean> mine = new CompletableFuture<>();
        CompletableFuture<Boolean> before;
        synchronized (this) {
            if (!released.compareAndSet(null, mine)) {
                return released.get();
            }
            before = regenerating;
        }
        before.handle((ignored, failure) -> null)
                .thenCompose(ignored -> instance.release())
                .whenComplete((ok, failure) -> mine.complete(failure == null && Boolean.TRUE.equals(ok)));
        return mine;
    }

    /** @return whether {@link #release()} has been called */
    public boolean isReleased() {
        return released.get() != null;
    }
}
