package net.exylia.lib.ledger;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Query;
import net.exylia.lib.database.Repository;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.ledger.internal.LedgerPlayerRow;
import net.exylia.lib.ledger.internal.LedgerRow;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.internal.CrossServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * How much money the server's plugins created and took out of the economy,
 * per hour, plugin and source.
 *
 * <pre>{@code
 * // onEnable of a plugin that reads it (a dashboard): opens the ledger
 * Ledger ledger = Ledger.of(this);
 *
 * // money a plugin printed is recorded on its own when paid through Modifiers.pay;
 * // anything else is told explicitly
 * Ledger.created(this, "daily-reward", player, amount);
 * Ledger.sunk(this, "order-tax", player, tax);
 *
 * ledger.day(Ledger.currentDay()).thenAccept(entries -> ...);       // every source today
 * ledger.top(Ledger.Flow.CREATED, null, from, to, 10).thenAccept(...); // top earners
 * }</pre>
 *
 * <h2>Created and sunk</h2>
 * Money <em>created</em> enters the economy from nothing: a job payout, a
 * mission reward, a sell to the server. Money <em>sunk</em> leaves it: a tax,
 * a fee, a buy from the server. A transfer between players is neither and is
 * never recorded. Every {@link net.exylia.lib.modifier.Modifiers#pay} that
 * deposits is recorded as created, under the calling plugin and the source it
 * was given; sinks have no such single door, so a plugin tells them with
 * {@link #sunk}.
 *
 * <h2>Cost</h2>
 * Nothing is recorded until some plugin opens the ledger with {@link #of}:
 * before that a record is one volatile read. Once open, a record adds to a
 * counter in memory (per hour, server, plugin, source, currency, and per
 * player); every minute the counters are written with atomic increments. A
 * write that fails is reported and dropped: this is a statistic, not a
 * balance. Rows older than 90 days are purged.
 *
 * <h2>One ledger per server, network-wide rows</h2>
 * The first plugin that opens it hosts it: the tables live in its database.
 * When the host disables, its counters are written and the next plugin that
 * opened it takes over. Servers sharing that database write into the same
 * tables, each row carrying its {@code server-id}, so a dashboard sees the
 * whole network and can still split it per server. Player totals add every
 * server together.
 *
 * <h2>Time</h2>
 * Hours and days are UTC, counted from the epoch: {@link #hourOf(long)} and
 * {@link #dayOf(long)}. Amounts are kept to two decimals.
 *
 * <h2>Threads</h2>
 * Every method is safe from any thread. Futures complete off the server thread.
 *
 * @since 1.478.0
 */
public final class Ledger {

    /** Which way money moved. */
    public enum Flow {
        /** Entered the economy from nothing. */
        CREATED,
        /** Left the economy. */
        SUNK
    }

    /**
     * Money that moved one way in one hour through one source.
     *
     * @param hour     the UTC hour since the epoch
     * @param server   the {@code server-id} that recorded it
     * @param plugin   the plugin that recorded it
     * @param source   what the plugin called it ({@code jobs}, {@code order-tax})
     * @param currency the currency id
     * @param flow     created or sunk
     * @param amount   how much
     * @param times    how many records made it up
     */
    public record Entry(long hour, @NotNull String server, @NotNull String plugin, @NotNull String source,
                        @NotNull String currency, @NotNull Flow flow, @NotNull BigDecimal amount, long times) {
    }

    /**
     * One player's total over a span.
     *
     * @param player the player
     * @param amount how much they received (created) or spent (sunk)
     */
    public record PlayerTotal(@NotNull UUID player, @NotNull BigDecimal amount) {
    }

    private static final long HOUR_MILLIS = 3_600_000L;
    private static final int KEEP_DAYS = 90;
    /** The widest span {@link #top} reads, in hours: 31 days. */
    private static final int MAX_TOP_HOURS = 24 * 31;
    private static final long FLUSH_TICKS = 20L * 60;
    private static final long PURGE_TICKS = 20L * 60 * 60 * 6;
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private static final Object LOCK = new Object();
    /** Every plugin load that opened it, in order; guarded by {@link #LOCK}. */
    private static final List<Plugin> OPENERS = new ArrayList<>();
    private static volatile @Nullable Ledger open;
    /** The plugin a calling class belongs to, by class; {@code ""} for none. */
    private static final ClassValue<String> PLUGIN_OF = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return pluginOf(type);
        }
    };

    record SourceKey(long hour, String plugin, String source, String currency, Flow flow) {
    }

    record PlayerKey(long hour, UUID player, String plugin, String currency, Flow flow) {
    }

    final ConcurrentMap<SourceKey, long[]> sources = new ConcurrentHashMap<>();
    final ConcurrentMap<PlayerKey, long[]> players = new ConcurrentHashMap<>();
    private volatile @Nullable Repository<LedgerRow> rows;
    private volatile @Nullable Repository<LedgerPlayerRow> playerRows;
    private volatile @Nullable Plugin host;
    private volatile @Nullable String server;
    private volatile @Nullable TaskHandle flushTimer;
    private volatile @Nullable TaskHandle purgeTimer;
    private final LongSupplier clock;
    private final Logger logger = Logger.getLogger("ExyliaLib");

    Ledger(LongSupplier clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------ opening

    /**
     * The server's ledger, hosted by the first plugin that asks. Recording
     * starts with the first call. Released with the plugin.
     *
     * @param plugin the plugin asking; its {@code database.yml} holds the tables if it is the first
     * @return the one ledger of this server
     */
    public static @NotNull Ledger of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (LOCK) {
            OPENERS.removeIf(opener -> opener != plugin && opener.getName().equals(plugin.getName()));
            if (!OPENERS.contains(plugin)) OPENERS.add(plugin);
            Ledger ledger = open;
            if (ledger == null) {
                ledger = new Ledger(System::currentTimeMillis);
                ledger.hostBy(plugin);
                open = ledger;
            }
            return ledger;
        }
    }

    /** Whether some plugin opened the ledger, so records are kept. */
    public static boolean isOpen() {
        return open != null;
    }

    private void hostBy(Plugin plugin) {
        host = plugin;
        server = null;
        rows = Databases.of(plugin).repository(LedgerRow.class);
        playerRows = Databases.of(plugin).repository(LedgerPlayerRow.class);
        try {
            flushTimer = Tasks.of(plugin).runAsyncTimer(FLUSH_TICKS, FLUSH_TICKS, () -> flush());
            purgeTimer = Tasks.of(plugin).runAsyncTimer(20L * 60, PURGE_TICKS, () -> purge());
        } catch (RuntimeException noServer) {
            // No server behind this call — a test — and nothing to time.
        }
    }

    // ------------------------------------------------------------------ recording

    /**
     * Records money a plugin created, in the default currency.
     *
     * @param plugin the plugin that paid it
     * @param source what it was for, lower case ({@code jobs}, {@code daily-reward})
     * @param player who received it; {@code null} for nobody in particular
     * @param amount how much; zero, negative or {@code null} records nothing
     */
    public static void created(@NotNull Plugin plugin, @NotNull String source, @Nullable UUID player,
                               @Nullable BigDecimal amount) {
        created(plugin, source, player, null, amount);
    }

    /**
     * {@link #created(Plugin, String, UUID, BigDecimal)} in a named currency.
     *
     * @param currency the currency id; {@code null} or blank for the default one
     */
    public static void created(@NotNull Plugin plugin, @NotNull String source, @Nullable UUID player,
                               @Nullable String currency, @Nullable BigDecimal amount) {
        record(plugin.getName(), source, Flow.CREATED, player, currency, amount);
    }

    /**
     * Records money a plugin took out of the economy, in the default currency:
     * a tax, a fee, a purchase from the server.
     *
     * @param plugin the plugin that took it
     * @param source what it was for, lower case ({@code order-tax}, {@code perk-swap})
     * @param player who paid it; {@code null} for nobody in particular
     * @param amount how much; zero, negative or {@code null} records nothing
     */
    public static void sunk(@NotNull Plugin plugin, @NotNull String source, @Nullable UUID player,
                            @Nullable BigDecimal amount) {
        sunk(plugin, source, player, null, amount);
    }

    /**
     * {@link #sunk(Plugin, String, UUID, BigDecimal)} in a named currency.
     *
     * @param currency the currency id; {@code null} or blank for the default one
     */
    public static void sunk(@NotNull Plugin plugin, @NotNull String source, @Nullable UUID player,
                            @Nullable String currency, @Nullable BigDecimal amount) {
        record(plugin.getName(), source, Flow.SUNK, player, currency, amount);
    }

    /**
     * What {@code Modifiers.pay} calls after a deposit: recorded as created
     * under the plugin that called it, found from the call stack.
     */
    @ApiStatus.Internal
    public static void paid(@NotNull String source, @NotNull UUID player, @Nullable String currency,
                            @NotNull BigDecimal amount) {
        if (open == null) return;
        String plugin = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(type -> !type.getName().startsWith("net.exylia.lib."))
                        .map(PLUGIN_OF::get)
                        .filter(name -> !name.isEmpty())
                        .findFirst()
                        .orElse("unknown"));
        record(plugin, source, Flow.CREATED, player, currency, amount);
    }

    private static void record(String plugin, String source, Flow flow, @Nullable UUID player,
                               @Nullable String currency, @Nullable BigDecimal amount) {
        Ledger ledger = open;
        if (ledger == null || amount == null || amount.signum() <= 0) return;
        ledger.add(plugin, Objects.requireNonNull(source, "source"), flow, player, currencyOf(currency), amount);
    }

    void add(String plugin, String source, Flow flow, @Nullable UUID player, String currency, BigDecimal amount) {
        long cents = cents(amount);
        if (cents <= 0) return;
        long hour = hourOf(clock.getAsLong());
        sources.merge(new SourceKey(hour, cut(plugin, 64), cut(source.trim().toLowerCase(java.util.Locale.ROOT), 64),
                        cut(currency, 32), flow), new long[]{cents, 1},
                (old, more) -> {
                    old[0] += more[0];
                    old[1] += more[1];
                    return old;
                });
        if (player != null) {
            players.merge(new PlayerKey(hour, player, cut(plugin, 64), cut(currency, 32), flow), new long[]{cents},
                    (old, more) -> {
                        old[0] += more[0];
                        return old;
                    });
        }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes what is counted in memory. Runs every minute on its own, and
     * before every read.
     *
     * @return completes once written (or reported as failed)
     */
    public @NotNull CompletableFuture<Void> flush() {
        Repository<LedgerRow> sourceRepo = rows;
        Repository<LedgerPlayerRow> playerRepo = playerRows;
        if (sourceRepo == null || playerRepo == null) return CompletableFuture.completedFuture(null);
        String serverId = server();
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (SourceKey key : sources.keySet()) {
            long[] counted = sources.remove(key);
            if (counted == null) continue;
            String id = cut(key.hour() + "|" + serverId + "|" + key.plugin() + "|" + key.source() + "|"
                    + key.currency() + "|" + key.flow().name(), 255);
            // Each increment carries zero for the other column, so whichever creates the row is right.
            writes.add(sourceRepo.increment(new LedgerRow(id, key.hour(), key.hour() / 24, serverId, key.plugin(),
                    key.source(), key.currency(), key.flow().name(), counted[0], 0), "cents"));
            writes.add(sourceRepo.increment(new LedgerRow(id, key.hour(), key.hour() / 24, serverId, key.plugin(),
                    key.source(), key.currency(), key.flow().name(), 0, counted[1]), "times"));
        }
        for (PlayerKey key : players.keySet()) {
            long[] counted = players.remove(key);
            if (counted == null) continue;
            String id = key.hour() + "|" + key.player() + "|" + key.currency() + "|" + key.flow().name()
                    + "|" + key.plugin();
            writes.add(playerRepo.increment(new LedgerPlayerRow(cut(id, 128), key.hour(), key.hour() / 24,
                    key.player().toString(), key.currency(), key.flow().name(), counted[0], key.plugin()), "cents"));
        }
        if (writes.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).exceptionally(failure -> {
            logger.log(Level.WARNING, "Some economy ledger counts could not be written and were dropped.", failure);
            return null;
        });
    }

    private String server() {
        String known = server;
        if (known == null) {
            Plugin current = host;
            try {
                known = current == null ? "server" : cut(CrossServer.serverId(current), 64);
            } catch (RuntimeException | LinkageError unreadable) {
                known = "server";
            }
            server = known;
        }
        return known;
    }

    private CompletableFuture<Void> purge() {
        Repository<LedgerRow> sourceRepo = rows;
        Repository<LedgerPlayerRow> playerRepo = playerRows;
        if (sourceRepo == null || playerRepo == null) return CompletableFuture.completedFuture(null);
        long cutoff = dayOf(clock.getAsLong()) - KEEP_DAYS;
        CompletableFuture<Void> sourcesDone = sourceRepo.all().orderBy("day").limit(1).findFirst()
                .thenCompose(oldest -> oldest.isEmpty() ? CompletableFuture.completedFuture(null)
                        : purgeDays(oldest.get().day(), cutoff, day -> sourceRepo.where("day", day).delete()));
        CompletableFuture<Void> playersDone = playerRepo.all().orderBy("day").limit(1).findFirst()
                .thenCompose(oldest -> oldest.isEmpty() ? CompletableFuture.completedFuture(null)
                        : purgeDays(oldest.get().day(), cutoff, day -> playerRepo.where("day", day).delete()));
        return CompletableFuture.allOf(sourcesDone, playersDone).exceptionally(failure -> {
            logger.log(Level.WARNING, "Old economy ledger rows could not be purged.", failure);
            return null;
        });
    }

    private static CompletableFuture<Void> purgeDays(long from, long cutoff,
                                                     java.util.function.LongFunction<CompletableFuture<Integer>> delete) {
        CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
        // Bounded, so a clock that jumped years cannot start an endless loop.
        for (long day = Math.max(from, cutoff - 3650); day < cutoff; day++) {
            long expired = day;
            chain = chain.thenCompose(done -> delete.apply(expired));
        }
        return chain.thenApply(done -> null);
    }

    // ------------------------------------------------------------------ reading

    /**
     * Every entry of one UTC day, all servers, plugins and sources, the hours
     * apart: group and sum them as the screen needs.
     *
     * @param day the UTC day since the epoch ({@link #currentDay()})
     */
    public @NotNull CompletableFuture<List<Entry>> day(long day) {
        Repository<LedgerRow> repo = rows;
        if (repo == null) return CompletableFuture.completedFuture(List.of());
        return flush().thenCompose(done -> repo.where("day", day).find()).thenApply(Ledger::entries);
    }

    /**
     * Every entry of one UTC hour.
     *
     * @param hour the UTC hour since the epoch ({@link #currentHour()})
     */
    public @NotNull CompletableFuture<List<Entry>> hour(long hour) {
        Repository<LedgerRow> repo = rows;
        if (repo == null) return CompletableFuture.completedFuture(List.of());
        return flush().thenCompose(done -> repo.where("hour", hour).find()).thenApply(Ledger::entries);
    }

    /**
     * The players who received (created) or spent (sunk) the most over a span,
     * every server and source together.
     *
     * @param flow     which way
     * @param currency the currency id; {@code null} for the default one
     * @param fromHour first UTC hour, inclusive
     * @param toHour   last UTC hour, exclusive; at most 31 days after {@code fromHour}
     * @param limit    how many to return
     * @return highest first
     */
    public @NotNull CompletableFuture<List<PlayerTotal>> top(@NotNull Flow flow, @Nullable String currency,
                                                             long fromHour, long toHour, int limit) {
        Repository<LedgerPlayerRow> repo = playerRows;
        if (repo == null || toHour <= fromHour || limit <= 0) return CompletableFuture.completedFuture(List.of());
        String wanted = currencyOf(currency);
        long end = Math.min(toHour, fromHour + MAX_TOP_HOURS);
        return flush().thenCompose(done -> {
            List<CompletableFuture<List<LedgerPlayerRow>>> parts = new ArrayList<>();
            long hour = fromHour;
            while (hour < end) {
                Query<LedgerPlayerRow> query;
                if (Math.floorMod(hour, 24) == 0 && hour + 24 <= end) {
                    query = repo.where("day", hour / 24);
                    hour += 24;
                } else {
                    query = repo.where("hour", hour);
                    hour++;
                }
                parts.add(query.where("flow", flow.name()).where("currency", wanted).find());
            }
            return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(all -> {
                List<LedgerPlayerRow> found = new ArrayList<>();
                parts.forEach(part -> found.addAll(part.join()));
                return top(found, limit);
            });
        });
    }

    /**
     * How much one player received (created) or spent (sunk) over a span,
     * every server and source together.
     *
     * <pre>{@code
     * long now = Ledger.currentHour();
     * ledger.player(uuid, Ledger.Flow.CREATED, null, now - 24, now + 1)
     *       .thenAccept(earned -> ...); // last day's earnings, default currency
     * }</pre>
     *
     * @param player          the player
     * @param flow            which way
     * @param currency        the currency id; {@code null} for the default one
     * @param fromHour        first UTC hour, inclusive
     * @param toHourExclusive last UTC hour, exclusive; no span limit, since one player's rows are few
     * @return the total, {@code 0.00} when nothing was recorded; completes off the server thread
     * @since 1.480.0
     */
    public @NotNull CompletableFuture<BigDecimal> player(@NotNull UUID player, @NotNull Flow flow,
                                                         @Nullable String currency, long fromHour,
                                                         long toHourExclusive) {
        Repository<LedgerPlayerRow> repo = playerRows;
        if (repo == null || toHourExclusive <= fromHour) return CompletableFuture.completedFuture(amount(0));
        String wanted = currencyOf(currency);
        return flush().thenCompose(done -> repo.where("player", player.toString())
                        .where("flow", flow.name()).where("currency", wanted).find())
                .thenApply(found -> sum(found, fromHour, toHourExclusive));
    }

    /**
     * How much one player received (created) or spent (sunk) over a span
     * through one plugin, every server and source together: a plugin capping
     * its own payouts reads this, not the total from every plugin.
     *
     * <pre>{@code
     * long now = Ledger.currentHour();
     * ledger.player(uuid, plugin, Ledger.Flow.CREATED, null, now, now + 1)
     *       .thenAccept(earned -> ...); // what this plugin paid them this hour
     * }</pre>
     *
     * <p>Rows written before 1.482.0 carry no plugin and are not counted here.
     *
     * @param player          the player
     * @param plugin          the plugin that recorded it
     * @param flow            which way
     * @param currency        the currency id; {@code null} for the default one
     * @param fromHour        first UTC hour, inclusive
     * @param toHourExclusive last UTC hour, exclusive
     * @return the total, {@code 0.00} when nothing was recorded; completes off the server thread
     * @since 1.482.0
     */
    public @NotNull CompletableFuture<BigDecimal> player(@NotNull UUID player, @NotNull Plugin plugin,
                                                         @NotNull Flow flow, @Nullable String currency,
                                                         long fromHour, long toHourExclusive) {
        Repository<LedgerPlayerRow> repo = playerRows;
        if (repo == null || toHourExclusive <= fromHour) return CompletableFuture.completedFuture(amount(0));
        String wanted = currencyOf(currency);
        String name = cut(plugin.getName(), 64);
        return flush().thenCompose(done -> repo.where("player", player.toString())
                        .where("flow", flow.name()).where("currency", wanted).where("plugin", name).find())
                .thenApply(found -> sum(found, fromHour, toHourExclusive));
    }

    static BigDecimal sum(List<LedgerPlayerRow> rows, long fromHour, long toHourExclusive) {
        long cents = 0;
        for (LedgerPlayerRow row : rows) {
            if (row.hour() >= fromHour && row.hour() < toHourExclusive) cents += row.cents();
        }
        return amount(cents);
    }

    static List<PlayerTotal> top(List<LedgerPlayerRow> rows, int limit) {
        Map<String, Long> byPlayer = new HashMap<>();
        rows.forEach(row -> byPlayer.merge(row.player(), row.cents(), Long::sum));
        List<PlayerTotal> totals = new ArrayList<>();
        byPlayer.forEach((player, cents) -> {
            try {
                totals.add(new PlayerTotal(UUID.fromString(player), amount(cents)));
            } catch (IllegalArgumentException malformed) {
                // a row not written by this module; skip it
            }
        });
        totals.sort(Comparator.comparing(PlayerTotal::amount).reversed());
        return totals.size() > limit ? List.copyOf(totals.subList(0, limit)) : totals;
    }

    private static List<Entry> entries(List<LedgerRow> rows) {
        List<Entry> entries = new ArrayList<>(rows.size());
        for (LedgerRow row : rows) {
            Flow flow;
            try {
                flow = Flow.valueOf(row.flow());
            } catch (IllegalArgumentException | NullPointerException malformed) {
                continue;
            }
            entries.add(new Entry(row.hour(), row.server(), row.plugin(), row.source(), row.currency(), flow,
                    amount(row.cents()), row.times()));
        }
        return entries;
    }

    // ------------------------------------------------------------------ time and amounts

    /** The UTC hour since the epoch an instant falls in. */
    public static long hourOf(long epochMillis) {
        return Math.floorDiv(epochMillis, HOUR_MILLIS);
    }

    /** The UTC day since the epoch an instant falls in. */
    public static long dayOf(long epochMillis) {
        return Math.floorDiv(epochMillis, HOUR_MILLIS * 24);
    }

    /** The UTC hour since the epoch now. */
    public static long currentHour() {
        return hourOf(System.currentTimeMillis());
    }

    /** The UTC day since the epoch now. */
    public static long currentDay() {
        return dayOf(System.currentTimeMillis());
    }

    static long cents(BigDecimal amount) {
        BigDecimal scaled = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP);
        return scaled.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : scaled.longValue();
    }

    static BigDecimal amount(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    private static String currencyOf(@Nullable String currency) {
        try {
            return currency == null || currency.isBlank() ? Economy.defaultId() : Economy.canonical(currency);
        } catch (RuntimeException | LinkageError noEconomy) {
            return currency == null || currency.isBlank() ? "default" : currency.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String pluginOf(Class<?> type) {
        try {
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            // Parents count: a Lukittu-loaded plugin's classes come from a child of the loader plugin's.
            for (ClassLoader loader = type.getClassLoader(); loader != null; loader = loader.getParent()) {
                for (Plugin plugin : plugins) {
                    if (plugin.getClass().getClassLoader() == loader) return plugin.getName();
                }
            }
        } catch (RuntimeException | LinkageError noServer) {
            // No plugin manager: a test.
        }
        return "";
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Writes what the host had counted and hands hosting to the next plugin
     * that opened the ledger, when a plugin is disabled. Called by the library.
     */
    @ApiStatus.Internal
    public static void release(@NotNull Plugin plugin) {
        Ledger ledger;
        Plugin next = null;
        synchronized (LOCK) {
            if (!OPENERS.remove(plugin)) return;
            ledger = open;
            if (ledger == null || ledger.host != plugin) return;
            for (Plugin candidate : OPENERS) {
                if (candidate.isEnabled()) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) open = null;
        }
        ledger.stopTimers();
        ledger.flushAndWait();
        if (next != null) {
            synchronized (LOCK) {
                if (open == ledger) ledger.hostBy(next);
            }
        }
    }

    /** Writes everything and forgets every opener, on shutdown. Called by the library. */
    @ApiStatus.Internal
    public static void releaseAll() {
        Ledger ledger;
        synchronized (LOCK) {
            ledger = open;
            open = null;
            OPENERS.clear();
        }
        if (ledger != null) {
            ledger.stopTimers();
            ledger.flushAndWait();
        }
    }

    private void stopTimers() {
        TaskHandle flushing = flushTimer;
        if (flushing != null) flushing.cancel();
        TaskHandle purging = purgeTimer;
        if (purging != null) purging.cancel();
    }

    private void flushAndWait() {
        try {
            flush().get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception lost) {
            logger.log(Level.WARNING, "The last economy ledger counts could not be written.", lost);
        }
    }
}
