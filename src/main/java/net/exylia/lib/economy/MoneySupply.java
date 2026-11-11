package net.exylia.lib.economy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The latest money supply of each currency: every balance added up and how
 * many players hold some of it.
 *
 * <p>The plugin that stores the balances measures it (ExyliaEconomy does so a
 * minute after start and every ten minutes after) and reports it here; any
 * plugin that steers prices by how much money is around reads it, without a
 * database query of its own. Nothing is persisted: until the first report,
 * {@link #latest} answers {@code null} and the reader keeps its own fallback.
 *
 * @since 1.595.0
 */
public final class MoneySupply {

    /**
     * One measurement.
     *
     * @param currency the currency's id, lower case
     * @param total    every balance added up
     * @param holders  how many balances hold more than nothing
     * @param at       when it was measured, epoch milliseconds
     */
    public record Snapshot(@NotNull String currency, @NotNull BigDecimal total, long holders, long at) {

        /** @return how old this measurement is at {@code now}, in milliseconds */
        public long age(long now) {
            return Math.max(0L, now - at);
        }
    }

    private static final Map<String, Snapshot> LATEST = new ConcurrentHashMap<>();

    private MoneySupply() {
    }

    /** Records a measurement of {@code currency}, replacing the previous one. */
    public static void report(@NotNull String currency, @NotNull BigDecimal total, long holders) {
        Objects.requireNonNull(total, "total");
        String key = key(currency);
        LATEST.put(key, new Snapshot(key, total, Math.max(0L, holders), System.currentTimeMillis()));
    }

    /** @return the latest measurement of {@code currency}, or {@code null} when none was reported */
    public static @Nullable Snapshot latest(@NotNull String currency) {
        return LATEST.get(key(currency));
    }

    /** Forgets {@code currency}'s measurement, when the plugin that reported it stops. */
    public static void forget(@NotNull String currency) {
        LATEST.remove(key(currency));
    }

    private static String key(String currency) {
        return Objects.requireNonNull(currency, "currency").toLowerCase(Locale.ROOT);
    }
}
