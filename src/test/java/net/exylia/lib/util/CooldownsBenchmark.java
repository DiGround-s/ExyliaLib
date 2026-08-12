package net.exylia.lib.util;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a cooldown check actually costs.
 *
 * <p>This is the piece every damage event, item use and chat message will run
 * through once it is the base for the whole ecosystem, so the cost of a single
 * check is worth knowing rather than assuming.
 *
 * <p>Not an assertion-based test: it prints numbers. Run it with
 * {@code ./gradlew test --tests "*CooldownsBenchmark*" -i} to see them.
 */
class CooldownsBenchmark {

    private static final int PLAYERS = 200;
    private static final int WARMUP = 200_000;
    private static final int RUNS = 2_000_000;

    private final List<UUID> ids = new ArrayList<>();

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Cooldowns.clearEverything();

        for (int i = 0; i < PLAYERS; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            // A realistic spread: every player holds a few cooldowns.
            Cooldowns.start(id, "pearl", Duration.ofSeconds(16));
            Cooldowns.start(id, "gapple", Duration.ofSeconds(30));
            Cooldowns.start(id, "ability", Duration.ofMinutes(2));
        }
    }

    @AfterEach
    void tearDown() {
        Cooldowns.clearEverything();
        FakeServer.reset();
    }

    @Test
    @DisplayName("benchmark: the cost of one check")
    void benchmark() {
        // The hot path: an active cooldown being checked.
        long active = measure("isActive, cooldown running", () -> {
            for (int i = 0; i < RUNS; i++) {
                Cooldowns.isActive(ids.get(i % PLAYERS), "pearl");
            }
        });

        // The other hot path: a key that was never set. This is the common
        // case in practice — most abilities are off cooldown most of the time.
        long absent = measure("isActive, nothing running", () -> {
            for (int i = 0; i < RUNS; i++) {
                Cooldowns.isActive(ids.get(i % PLAYERS), "never-set");
            }
        });

        // A player with no cooldowns at all: the first map lookup misses.
        UUID stranger = UUID.randomUUID();
        long unknown = measure("isActive, unknown player", () -> {
            for (int i = 0; i < RUNS; i++) {
                Cooldowns.isActive(stranger, "pearl");
            }
        });

        // Starting one, which is the only path that allocates.
        long start = measure("start", () -> {
            for (int i = 0; i < RUNS; i++) {
                Cooldowns.start(ids.get(i % PLAYERS), "bench", Duration.ofSeconds(30));
            }
        });

        // remaining(), which callers use to build the "wait N seconds" message.
        long remaining = measure("remaining", () -> {
            for (int i = 0; i < RUNS; i++) {
                Cooldowns.remaining(ids.get(i % PLAYERS), "pearl");
            }
        });

        System.out.println();
        System.out.println("=== Cooldowns: nanoseconds per call ===");
        report("isActive, running   ", active);
        report("isActive, absent key", absent);
        report("isActive, unknown   ", unknown);
        report("start               ", start);
        report("remaining           ", remaining);
        System.out.println();
        System.out.println("For scale: one tick is 50,000,000 ns.");
        System.out.println("At 1 check per player per tick with 200 players: "
                + (active / RUNS * 200) + " ns/tick ("
                + String.format("%.5f", (active / (double) RUNS * 200) / 50_000_000 * 100)
                + "% of a tick).");
    }

    private void report(String label, long totalNanos) {
        System.out.printf("  %s %6.2f ns%n", label, totalNanos / (double) RUNS);
    }

    private long measure(String what, Runnable work) {
        // Warm up so the JIT has compiled the path before it is timed.
        for (int i = 0; i < WARMUP; i++) {
            Cooldowns.isActive(ids.get(i % PLAYERS), "pearl");
        }
        work.run(); // one untimed pass

        long start = System.nanoTime();
        work.run();
        return System.nanoTime() - start;
    }
}
