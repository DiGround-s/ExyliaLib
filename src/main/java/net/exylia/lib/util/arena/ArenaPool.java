package net.exylia.lib.util.arena;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.PluginRegions;
import net.exylia.lib.region.PolicySet;
import net.exylia.lib.region.RegionId;
import net.exylia.lib.region.RegionSnapshot;
import net.exylia.lib.region.Regions;
import net.exylia.lib.region.WorldIdentity;
import net.exylia.lib.schematic.PluginSchematics;
import net.exylia.lib.schematic.RegenerateOptions;
import net.exylia.lib.schematic.Schematics;
import net.exylia.lib.util.world.TemporaryWorld;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Arenas handed out one match at a time, copied into the temporary world when
 * every one is taken.
 *
 * <pre>{@code
 * ArenaPool arenas = ArenaPool.of(this, "duels_arena_");   // once, in onEnable
 * arenas.register(ArenaTemplate.of("forest", world, bounds, "duels_forest", points, 8));
 *
 * arenas.acquireAny(kit.arenaIds()).thenAccept(lease -> lease.ifPresentOrElse(
 *         arena -> startMatch(arena),          // arena.point("a"), arena.release()
 *         () -> tellBoth("No arena is free.")));
 * }</pre>
 *
 * <p>An acquire takes the original if it is free, else a free copy, else makes a
 * new copy — a {@link TemporaryWorld} plot with the schematic pasted on it — while
 * the template has fewer than {@link ArenaTemplate#maxCopies()}. Copies are kept and
 * reused: temporary plots are never given back, so a pool that threw its copies
 * away would eat the world.
 *
 * <p>Every instance gets a region of this plugin's over its bounds, keyed
 * {@code <prefix><id>} for the original and {@code <prefix><id>_<n>} for copy
 * {@code n}, declaring the template's policies — so {@code enforce()},
 * {@code player_build_only} and placed-block records work on copies too.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread and none blocks. Futures complete on
 * whichever thread finished the work (a world thread, often), and never
 * exceptionally; hop through {@code Tasks.of(plugin)} before touching the game.
 *
 * <h2>Lifecycle</h2>
 * Hold one pool per plugin in a field. Nothing outlives the plugin: its regions are
 * released with it, the temporary world is deleted with the library.
 *
 * @since 1.346.0
 */
public final class ArenaPool {

    /** What the pool does to the world; replaced in tests. */
    interface Placement {
        /** Reserves a plot and pastes the template; the paste origin, or {@code null}. */
        CompletableFuture<Location> copy(ArenaTemplate template);

        CompletableFuture<Boolean> regenerate(String schematic, Cuboid bounds, World world);

        RegionId register(String key, World world, Cuboid bounds, PolicySet policies);

        void unregister(RegionId id);
    }

    /** A registered template and the instances it has. */
    static final class Entry {
        final ArenaPool pool;
        final ArenaTemplate template;
        final List<ArenaInstance> instances = new CopyOnWriteArrayList<>();
        final AtomicInteger copies = new AtomicInteger();
        volatile boolean closed;

        Entry(ArenaPool pool, ArenaTemplate template) {
            this.pool = pool;
            this.template = template;
        }

        boolean reserveCopy() {
            int current;
            do {
                current = copies.get();
                if (closed || current >= template.maxCopies()) {
                    return false;
                }
            } while (!copies.compareAndSet(current, current + 1));
            return true;
        }

        /** No more acquires; free copies go now, busy ones on release. */
        void close() {
            closed = true;
            instances.forEach(ArenaInstance::retire);
        }
    }

    final Placement placement;
    private final String prefix;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /** Copy numbers per id, never reset: a replaced template's busy copies keep theirs. */
    private final Map<String, AtomicInteger> numbers = new ConcurrentHashMap<>();

    ArenaPool(Placement placement, String prefix) {
        this.placement = placement;
        this.prefix = prefix;
    }

    /**
     * Creates a plugin's pool.
     *
     * @param plugin       the owning plugin; its schematics and regions are used
     * @param regionPrefix prepended to every region key, such as {@code "duels_arena_"}
     * @return a new, empty pool
     */
    public static @NotNull ArenaPool of(@NotNull Plugin plugin, @NotNull String regionPrefix) {
        Objects.requireNonNull(regionPrefix, "regionPrefix");
        return new ArenaPool(new Live(plugin), regionPrefix);
    }

    /**
     * Adds a template, or replaces the one with the same id (a reload).
     *
     * <p>A replaced template's copies are dropped — the ones in a match once it
     * releases them — and new copies are made for the new one. Its original keeps
     * its state: an original in a match stays taken until that match releases it.
     *
     * @param template the template
     */
    public void register(@NotNull ArenaTemplate template) {
        RegionId region = placement.register(key(template.id(), 0), template.world(),
                template.bounds(), template.policies());
        Entry[] replaced = new Entry[1];
        entries.compute(template.id(), (id, old) -> {
            Entry entry = new Entry(this, template);
            // The original is the same blocks either way: a match in it keeps it taken.
            AtomicReference<ArenaInstance.State> state = old != null
                    ? old.instances.getFirst().state
                    : new AtomicReference<>(ArenaInstance.State.FREE);
            entry.instances.add(new ArenaInstance(entry, 0, template.world(), 0, 0, region, state));
            replaced[0] = old;
            return entry;
        });
        if (replaced[0] != null) {
            replaced[0].close();
        }
    }

    /**
     * Removes a template and the regions of its original and its free copies.
     * Leases already out still work and still regenerate when released.
     *
     * @param id the template's id
     * @return whether there was one
     */
    public boolean unregister(@NotNull String id) {
        Entry entry = entries.remove(id);
        if (entry == null) {
            return false;
        }
        entry.close();
        placement.unregister(entry.instances.getFirst().region);
        return true;
    }

    /** @return the template registered under {@code id} */
    public @NotNull Optional<ArenaTemplate> template(@NotNull String id) {
        Entry entry = entries.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.template);
    }

    /** @return every registered template */
    public @NotNull List<ArenaTemplate> templates() {
        return entries.values().stream().map(entry -> entry.template).toList();
    }

    /**
     * Takes one arena of a template.
     *
     * @param id the template's id
     * @return the lease, or empty when the template is unknown, every instance is
     *         taken and no copy could be made
     */
    public @NotNull CompletableFuture<Optional<ArenaLease>> acquire(@NotNull String id) {
        return acquireAny(List.of(id));
    }

    /**
     * Takes one arena of any of these templates, in the order given: first a free
     * instance of any of them, then a new copy of the first that can make one.
     * Shuffle the ids first for a random arena.
     *
     * @param ids the templates' ids; unknown ones are skipped
     * @return the lease, or empty when nothing is free and no copy could be made
     */
    public @NotNull CompletableFuture<Optional<ArenaLease>> acquireAny(@NotNull Collection<String> ids) {
        List<Entry> candidates = new ArrayList<>();
        for (String id : ids) {
            Entry entry = entries.get(id);
            if (entry == null) {
                continue;
            }
            for (ArenaInstance instance : entry.instances) {
                if (instance.tryAcquire()) {
                    return CompletableFuture.completedFuture(Optional.of(new ArenaLease(instance)));
                }
            }
            candidates.add(entry);
        }
        return copyFrom(candidates, 0);
    }

    private CompletableFuture<Optional<ArenaLease>> copyFrom(List<Entry> candidates, int index) {
        if (index >= candidates.size()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Entry entry = candidates.get(index);
        if (!entry.reserveCopy()) {
            return copyFrom(candidates, index + 1);
        }
        ArenaTemplate template = entry.template;
        CompletableFuture<Location> pasted;
        try {
            pasted = placement.copy(template);
        } catch (RuntimeException failure) {
            pasted = CompletableFuture.failedFuture(failure);
        }
        return pasted.handle((origin, failure) -> {
            if (failure != null || origin == null || origin.getWorld() == null) {
                entry.copies.decrementAndGet();
                return null;
            }
            try {
                int n = numbers.computeIfAbsent(template.id(), id -> new AtomicInteger()).incrementAndGet();
                int dx = origin.getBlockX() - (int) Math.floor(template.bounds().minX());
                int dz = origin.getBlockZ() - (int) Math.floor(template.bounds().minZ());
                Cuboid bounds = ArenaInstance.shift(template.bounds(), dx, dz);
                RegionId region = placement.register(key(template.id(), n), origin.getWorld(),
                        bounds, template.policies());
                ArenaInstance instance = new ArenaInstance(entry, n, origin.getWorld(), dx, dz, region,
                        new AtomicReference<>(ArenaInstance.State.IN_USE));
                entry.instances.add(instance);
                return new ArenaLease(instance);
            } catch (RuntimeException registerFailed) {
                entry.copies.decrementAndGet();
                return null;
            }
        }).thenCompose(lease -> lease != null
                ? CompletableFuture.completedFuture(Optional.of(lease))
                : copyFrom(candidates, index + 1));
    }

    private String key(String id, int copy) {
        String key = prefix + id + (copy == 0 ? "" : "_" + copy);
        return RegionId.sanitize(key);
    }

    /** The real world: temporary plots, this plugin's schematics and regions. */
    private static final class Live implements Placement {
        private final PluginSchematics schematics;
        private final PluginRegions regions;
        private final Debug debug;

        Live(Plugin plugin) {
            this.schematics = Schematics.of(plugin);
            this.regions = Regions.of(plugin);
            this.debug = Debug.of(plugin);
        }

        @Override
        public CompletableFuture<Location> copy(ArenaTemplate template) {
            Cuboid bounds = template.bounds();
            int sizeX = (int) Math.ceil(bounds.maxX() - Math.floor(bounds.minX()));
            int sizeZ = (int) Math.ceil(bounds.maxZ() - Math.floor(bounds.minZ()));
            return TemporaryWorld.reserve(sizeX, sizeZ).thenCompose(plot -> {
                if (plot == null) {
                    debug.error("Arena '" + template.id() + "' cannot be copied: there is no temporary world");
                    return CompletableFuture.completedFuture(null);
                }
                Location origin = plot.origin(Math.floor(bounds.minY()));
                return schematics.paste(template.schematic(), origin).thenApply(result -> {
                    if (result.isSuccess()) {
                        return origin;
                    }
                    debug.error("Arena '" + template.id() + "' could not be copied: " + result.outcome()
                            + (result.reason() == null ? "" : " (" + result.reason() + ")"));
                    return null;
                });
            });
        }

        @Override
        public CompletableFuture<Boolean> regenerate(String schematic, Cuboid bounds, World world) {
            return schematics.regenerate(schematic, bounds, WorldIdentity.from(world),
                    RegenerateOptions.defaults()).thenApply(result -> {
                if (!result.isSuccess()) {
                    debug.error("Arena schematic '" + schematic + "' could not be regenerated: "
                            + result.outcome() + (result.reason() == null ? "" : " (" + result.reason() + ")"));
                }
                return result.isSuccess();
            });
        }

        @Override
        public RegionId register(String key, World world, Cuboid bounds, PolicySet policies) {
            RegionSnapshot region = regions.region(key, WorldIdentity.from(world), bounds, 0, policies);
            if (regions.get(region.id()).isPresent()) {
                regions.replace(region);
            } else {
                regions.register(region);
            }
            return region.id();
        }

        @Override
        public void unregister(RegionId id) {
            regions.unregister(id);
        }
    }
}
