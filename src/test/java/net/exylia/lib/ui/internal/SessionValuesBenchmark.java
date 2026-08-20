package net.exylia.lib.ui.internal;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What deciding a slot's values costs.
 *
 * <p>A menu on a {@code SMART} refresh timer runs this once per drawn slot per
 * tick, so a 54-slot menu open for one player is up to 54 of these a tick. The
 * decision itself is cheap; what matters is whether it allocates while making
 * it, because that garbage is paid back later as a GC pause.
 *
 * <p>Not an assertion-based test: it prints numbers. Run it with
 * {@code ./gradlew test --tests "*SessionValuesBenchmark*" -i} to see them.
 */
class SessionValuesBenchmark {

    private static final int WARMUP = 200_000;
    private static final int RUNS = 2_000_000;

    /** A menu context of the size a real screen carries. */
    private static Map<String, Object> context() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("kit_name", "Boxing");
        context.put("arena", "Arena 3");
        context.put("players", 12);
        context.put("state", "waiting");
        return context;
    }

    @Test
    @DisplayName("benchmark: deciding which values are parsed")
    void benchmark() {
        Map<String, Object> context = context();
        Map<String, String> rowValues = Map.of("player_name", "Steve", "rank", "MVP");

        // A fixed slot: a decoration, a button, a title bar. No row values and
        // nothing asking to be formatted, which is most of what a menu draws.
        Result fixed = measure("fixed slot (no row values)",
                () -> Session.parsed(context, Map.of(), Set.of()));

        // A row in a list: carries its own values, so the sets really do differ.
        Result row = measure("list row (2 row values)",
                () -> Session.parsed(context, rowValues, Set.of()));

        // A row that opted into formatting for one of its values.
        Result formatted = measure("list row (1 formatted)",
                () -> Session.parsed(context, rowValues, Set.of("rank")));

        System.out.println();
        System.out.println("=== Session.parsed: one drawn slot ===");
        System.out.printf("  %-28s %10s %14s%n", "", "ns/call", "bytes/call");
        fixed.report();
        row.report();
        formatted.report();
        System.out.println();
        System.out.printf("A 54-slot menu of fixed slots on a 20-tick SMART timer, "
                        + "open for 20 players: %,d bytes per refresh.%n",
                (long) (fixed.bytes * 54 * 20));
    }

    private Result measure(String label, java.util.function.Supplier<Set<String>> work) {
        // Consumed so the JIT cannot delete the call being measured.
        long sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            sink += work.get().size();
        }

        long allocatedBefore = allocatedBytes();
        long start = System.nanoTime();
        for (int i = 0; i < RUNS; i++) {
            sink += work.get().size();
        }
        long elapsed = System.nanoTime() - start;
        long allocated = allocatedBytes() - allocatedBefore;

        if (sink == Long.MIN_VALUE) System.out.print("");
        return new Result(label, elapsed / (double) RUNS, allocated / (double) RUNS);
    }

    private static long allocatedBytes() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
            return sun.getCurrentThreadAllocatedBytes();
        }
        return 0L;
    }

    private record Result(String label, double nanos, double bytes) {
        void report() {
            System.out.printf("  %-28s %8.1f ns %10.1f B%n", label, nanos, bytes);
        }
    }
}
