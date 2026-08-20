package net.exylia.lib.region;

import com.sun.management.ThreadMXBean;
import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.region.internal.RegionRuntime;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * What one step of one player actually costs.
 *
 * <p>This runs on every {@code PlayerMoveEvent} that crosses a block boundary,
 * for every player, forever. A server with 200 players walking produces
 * thousands of these per second, so the interesting number is not only the time
 * but the garbage: allocation in this path is paid back later as a GC pause,
 * which is what a player feels as a stutter.
 *
 * <p>Not an assertion-based test: it prints numbers. Run it with
 * {@code ./gradlew test --tests "*RegionBenchmark*" -i} to see them.
 */
class RegionBenchmark {

    private static final int PLAYERS = 200;
    private static final int WARMUP = 100_000;
    private static final int RUNS = 1_000_000;

    private World world;
    /**
     * Hoisted out of the timed loop on purpose: the fake world recomputes its UUID
     * with an MD5 hash on every {@code getUID()} call, which a real CraftWorld does
     * not. Reading it once keeps the harness out of the measurement.
     */
    private java.util.UUID worldId;
    private String worldName;
    private Plugin plugin;
    private PluginRegions regions;
    private final List<FakePlayer> players = new ArrayList<>();

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Regions.releaseAll();
        world = FakeServer.newWorld("arena");
        worldId = world.getUID();
        worldName = world.getName();
        plugin = FakeServer.newPlugin("Bench", null);
        RegionRuntime.init(plugin);
        regions = Regions.of(plugin);
        players.clear();
    }

    /**
     * The common shape of a real server: a handful of regions that matter
     * (spawn, safezone, arenas) rather than one giant one.
     */
    private void registerRegions(int count) {
        for (int i = 0; i < count; i++) {
            int origin = i * 100;
            regions.register(regions.region("area-" + i, WorldIdentity.from(world),
                    Cuboid.blocks(origin, 0, origin, origin + 49, 255, origin + 49),
                    i, PolicySet.empty()));
        }
    }

    private void spawnPlayers(double x, double y, double z) {
        for (int i = 0; i < PLAYERS; i++) {
            FakePlayer player = new FakePlayer("Bench" + i);
            player.at(new Location(world, x, y, z));
            FakeServer.online(player.player());
            RegionRuntime.initialize(player.player());
            players.add(player);
        }
    }

    @Test
    @DisplayName("benchmark: the cost of one player stepping one block")
    void benchmark() {
        // 0. The harness floor. FakePlayer is a reflective Proxy, so every
        //    getUniqueId() the runtime makes costs more here than against a
        //    real CraftPlayer. Measuring it means the numbers below can be read
        //    as "runtime plus this", instead of quietly blaming the library for
        //    the test's own overhead.
        Result floor = harnessFloor();

        // 1. A server that registered no regions at all. Every plugin using the
        //    library pays this whether or not it uses regions.
        Result empty = scenario("no regions registered", 0, 10, 5, 10);

        // 2. Standing outside every region, which is where most players are
        //    most of the time on most servers.
        Result outside = scenario("outside every region", 8, 5_000, 5, 5_000);

        // 3. Inside one region and staying there: walking around spawn. This is
        //    the case the membership diff has to conclude "nothing changed".
        Result inside = scenario("inside one region", 8, 10, 5, 10);

        // 4. Inside two overlapping regions, the worst realistic case.
        Result overlap = overlapScenario();

        System.out.println();
        System.out.println("=== RegionRuntime.update: one player, one block step ===");
        System.out.printf("  %-24s %10s %14s%n", "", "ns/move", "bytes/move");
        floor.report();
        System.out.println("  " + "-".repeat(46));
        empty.report();
        outside.report();
        inside.report();
        overlap.report();
        System.out.println();
        System.out.println("For scale: one tick is 50,000,000 ns.");
        System.out.printf("At 200 players each crossing a block every tick, "
                        + "'inside one region' costs %,.0f ns/tick (%.4f%% of a tick) "
                        + "and produces %,d bytes of garbage per tick.%n",
                inside.nanos * PLAYERS, inside.nanos * PLAYERS / 50_000_000 * 100,
                (long) (inside.bytes * PLAYERS));
    }

    /**
     * Everything the loop does except call the runtime: the proxy dispatch and
     * the list indexing. Whatever this costs is not the library's.
     */
    private Result harnessFloor() {
        setUp();
        spawnPlayers(10, 5, 10);

        Runnable work = () -> {
            long sink = 0;
            for (int i = 0; i < RUNS; i++) {
                FakePlayer player = players.get(i % PLAYERS);
                // Consumed so the JIT cannot delete the proxy call being timed.
                sink += player.player().getUniqueId().getLeastSignificantBits();
            }
            if (sink == Long.MIN_VALUE) System.out.print("");
        };

        for (int i = 0; i < WARMUP; i++) {
            players.get(i % PLAYERS).player().getUniqueId();
        }
        work.run();

        long allocatedBefore = allocatedBytes();
        long start = System.nanoTime();
        work.run();
        long elapsed = System.nanoTime() - start;
        long allocated = allocatedBytes() - allocatedBefore;
        return new Result("harness floor (proxy)", elapsed / (double) RUNS,
                allocated / (double) RUNS);
    }

    /** Walks a player back and forth across one block boundary. */
    private Result scenario(String label, int regionCount, double x, double y, double z) {
        setUp();
        registerRegions(regionCount);
        spawnPlayers(x, y, z);
        return measure(label, x, y, z);
    }

    /** Two regions covering the same point, so membership carries two entries. */
    private Result overlapScenario() {
        setUp();
        regions.register(regions.region("outer", WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 199, 255, 199), 0, PolicySet.empty()));
        regions.register(regions.region("inner", WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 99, 255, 99), 10, PolicySet.empty()));
        spawnPlayers(10, 5, 10);
        return measure("inside two regions", 10, 5, 10);
    }

    /**
     * Alternates between two adjacent blocks so every call does real work: the
     * listener's own "same block" guard would otherwise skip the update, and
     * skipping is not what we are measuring.
     */
    private Result measure(String label, double x, double y, double z) {
        Runnable work = () -> {
            for (int i = 0; i < RUNS; i++) {
                FakePlayer player = players.get(i % PLAYERS);
                double stepX = x + (i & 1);
                RegionRuntime.move(player.player(), worldId, worldName,
                        stepX, y, z, RegionChangeCause.MOVE);
            }
        };

        for (int i = 0; i < WARMUP; i++) {
            FakePlayer player = players.get(i % PLAYERS);
            RegionRuntime.move(player.player(), worldId, worldName,
                    x + (i & 1), y, z, RegionChangeCause.MOVE);
        }
        work.run(); // one untimed pass

        long allocatedBefore = allocatedBytes();
        long start = System.nanoTime();
        work.run();
        long elapsed = System.nanoTime() - start;
        long allocated = allocatedBytes() - allocatedBefore;

        return new Result(label, elapsed / (double) RUNS, allocated / (double) RUNS);
    }

    /**
     * Bytes allocated by this thread. This is the number that matters here:
     * unlike wall time it does not move with CPU noise, so a change in
     * allocation is a real change rather than a lucky run.
     */
    private static long allocatedBytes() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
            return sun.getCurrentThreadAllocatedBytes();
        }
        return 0L;
    }

    private record Result(String label, double nanos, double bytes) {
        void report() {
            System.out.printf("  %-24s %8.1f ns %10.1f B%n", label, nanos, bytes);
        }
    }
}
