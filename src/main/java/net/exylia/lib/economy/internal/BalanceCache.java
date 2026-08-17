package net.exylia.lib.economy.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.economy.EconomySettings;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Remembers balances for a few hundred milliseconds so a scoreboard does not
 * ask the economy on every tick.
 *
 * <h2>Why this exists</h2>
 * Balances are read on hot paths — a scoreboard refresh, a placeholder, a
 * shop preview — several times per tick per player, and the underlying
 * economy answers from its own cache at best and a database at worst. Asking
 * it on every read would turn this thin wrapper into the bottleneck it was
 * meant to avoid, so a balance once read is reused for
 * {@link EconomySettings#balanceCacheMillis()} milliseconds.
 *
 * <h2>Why the TTL is short, and why the library invalidates its own writes</h2>
 * The default window is 500&nbsp;ms, and the library calls
 * {@link #invalidate(String, UUID)} on every balance change it makes itself.
 * Both serve the same fear: a scoreboard that still shows the old balance
 * right after a purchase is a "my money disappeared" ticket, even when the
 * database is correct. Writes that go through the library are visible at
 * once, so the only staleness a player can ever see is a change made by
 * another plugin directly against the economy — and half a second of that is
 * the price deliberately paid for not hitting the database per tick.
 *
 * <h2>Threading</h2>
 * Safe from any thread. The cache reference is {@code volatile} so
 * {@link #apply(EconomySettings)} can swap it atomically on reload; a reader
 * mid-lookup finishes against the old cache and simply misses once.
 *
 * @since 1.26.0
 */
public final class BalanceCache {

    /** A few thousand entries: one per (currency, player) actually looked up. */
    private static final long MAXIMUM_SIZE = 4096;

    /** The smallest TTL honoured, so a misconfigured zero or negative cannot
     * turn the cache into a per-call miss that reads as a library slowdown. */
    private static final long MINIMUM_TTL_MILLIS = 1L;

    /**
     * What a balance is remembered under. A record rather than a concatenated
     * string so equality is structural and the two halves can never bleed
     * into each other across the separator.
     */
    private record Key(@NotNull String currencyId, @NotNull UUID player) {
    }

    private static volatile Cache<Key, BigDecimal> cache = build(new EconomySettings());

    private BalanceCache() {
    }

    /**
     * Reads a balance, serving the cached value when one is fresh enough and
     * calling {@code loader} otherwise.
     *
     * @param currencyId the currency the balance is in
     * @param player     the player
     * @param loader     reads the balance from the provider; called at most
     *                   once per expiry window per key
     * @return the balance
     */
    public static @NotNull BigDecimal balance(
            @NotNull String currencyId, @NotNull UUID player,
            @NotNull Supplier<@NotNull BigDecimal> loader) {
        return cache.get(new Key(currencyId, player), key -> loader.get());
    }

    /**
     * Drops one cached balance.
     *
     * <p>The library calls this after every deposit, withdraw or set it makes,
     * so its own writes are instantly visible to the next read — see the class
     * Javadoc for why that matters more than the TTL.
     */
    public static void invalidate(@NotNull String currencyId, @NotNull UUID player) {
        cache.invalidate(new Key(currencyId, player));
    }

    /**
     * Drops every cached balance. Called when the set of providers changes or
     * the module shuts down: a currency id that points at a different economy
     * must not keep serving the previous economy's numbers.
     */
    public static void invalidateAll() {
        cache.invalidateAll();
    }

    /**
     * Rebuilds the cache from new settings.
     *
     * <p>Caffeine fixes the expiry at build time, so a new
     * {@link EconomySettings#balanceCacheMillis()} means a new cache rather
     * than a mutated one. The swap is atomic and the old entries are dropped
     * with it — acceptable on a reload, which is rare, and simpler than
     * carrying two expiry regimes across the change.
     */
    public static void apply(@NotNull EconomySettings settings) {
        cache = build(settings);
    }

    /** Forgets everything, on module shutdown. */
    public static void shutdown() {
        cache.invalidateAll();
    }

    private static @NotNull Cache<Key, BigDecimal> build(@NotNull EconomySettings settings) {
        long ttl = Math.max(MINIMUM_TTL_MILLIS, settings.balanceCacheMillis());
        return Caffeine.newBuilder()
                .maximumSize(MAXIMUM_SIZE)
                .expireAfterWrite(Duration.ofMillis(ttl))
                .build();
    }
}
