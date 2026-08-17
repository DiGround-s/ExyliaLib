package net.exylia.lib.format;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * What one format call actually costs.
 *
 * <p>These run inside placeholders, and a placeholder runs on every tick of
 * every scoreboard line of every player: a twenty-player server with a ten-line
 * sidebar is four thousand calls a second. The design claim of this module is
 * that a call reads an immutable holder and builds a string, and that nothing
 * on the path parses a config or builds a formatter — so the claim is measured
 * rather than asserted in a comment.
 *
 * <p>The comparison is the shape the ecosystem uses today: a
 * {@link DecimalFormat} built per call, which is what "just format it here"
 * turns into once it is copied into forty plugins.
 *
 * <p>Not an assertion-based test: it prints numbers. Run it with
 * {@code ./gradlew test --tests "*FormatsBenchmark*" -i} to see them.
 */
class FormatsBenchmark {

    private static final int WARMUP = 200_000;
    private static final int RUNS = 2_000_000;

    @AfterEach
    void tearDown() {
        Formats.reset();
    }

    @Test
    @DisplayName("benchmark: the cost of one format call")
    void benchmark() {
        BigDecimal exact = new BigDecimal("1250.75");

        long moneyLong = measure(() -> {
            for (int index = 0; index < RUNS; index++) {
                Formats.money(1250L + index % 1000);
            }
        });

        long moneyBig = measure(() -> {
            for (int index = 0; index < RUNS; index++) {
                Formats.money(exact);
            }
        });

        long moneyCompact = measure(() -> {
            for (int index = 0; index < RUNS; index++) {
                Formats.money(2_500_000L + index);
            }
        });

        long compact = measure(() -> {
            for (int index = 0; index < RUNS; index++) {
                Formats.compact(1_500L + index);
            }
        });

        long percent = measure(() -> {
            for (int index = 0; index < RUNS; index++) {
                Formats.percent(index % 100);
            }
        });

        // What this replaces: a formatter built per call, which is how the
        // ecosystem formats a balance today.
        long perCallFormatter = measure(() -> {
            for (int index = 0; index < RUNS; index++) {
                DecimalFormat format = new DecimalFormat("#,##0.00",
                        DecimalFormatSymbols.getInstance(Locale.US));
                format.format(1250L + index % 1000);
            }
        });

        // The reload path, for scale: this is the one that does the work.
        FormatSettings settings = new FormatSettings();
        long apply = measure(() -> {
            for (int index = 0; index < RUNS / 100; index++) {
                Formats.apply(settings);
            }
        }) * 100;

        System.out.println();
        System.out.println("=== Formats: nanoseconds per call ===");
        report("money(long)         ", moneyLong);
        report("money(BigDecimal)   ", moneyBig);
        report("money(long) compact ", moneyCompact);
        report("compact(long)       ", compact);
        report("percent(double)     ", percent);
        System.out.println("  --- for comparison ---");
        report("new DecimalFormat   ", perCallFormatter);
        report("apply() [on reload] ", apply);
        System.out.println();
        System.out.println("For scale: one tick is 50,000,000 ns.");
        System.out.println("20 players x 10 sidebar lines x 20 ticks = 4,000 calls/s at "
                + String.format("%.1f", compact / (double) RUNS) + " ns = "
                + String.format("%.4f", compact / (double) RUNS * 4_000 / 1_000_000)
                + " ms per second of server time.");
    }

    private void report(String label, long totalNanos) {
        System.out.printf("  %s %7.2f ns%n", label, totalNanos / (double) RUNS);
    }

    private long measure(Runnable work) {
        for (int index = 0; index < WARMUP; index++) {
            Formats.compact((long) index);
        }
        work.run(); // one untimed pass, so the JIT has compiled this shape
        long started = System.nanoTime();
        work.run();
        return System.nanoTime() - started;
    }
}
