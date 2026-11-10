package net.exylia.lib.leaderboard;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.wipe.Wipes;
import net.exylia.lib.leaderboard.internal.LeaderboardMeta;
import net.exylia.lib.leaderboard.internal.LeaderboardRow;
import net.exylia.lib.leaderboard.internal.Ranking;
import net.exylia.lib.leaderboard.internal.Seeding;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.PeriodClock;
import net.exylia.lib.util.teleport.internal.CrossServer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DayOfWeek;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Leaderboards that keep themselves: all time, today, this week, this month and
 * this year, for offline players too, with no stats plugin behind them.
 *
 * <pre>{@code
 * Leaderboards boards = Leaderboards.of(this);                          // in onEnable
 * boards.clock(PeriodClock.of("Europe/Madrid", 5, DayOfWeek.MONDAY));
 * boards.track("kills", LeaderboardSource.statistic(Statistic.PLAYER_KILLS));
 * boards.track("money", LeaderboardSource.balance());
 *
 * LeaderboardView weekly = boards.view("kills", Span.WEEKLY);           // memory only, any thread
 * weekly.top(10);
 * weekly.rankOf(viewer.getUniqueId());
 * }</pre>
 *
 * <h2>One tracker per server</h2>
 * Every plugin that calls {@link #of} gets a handle on the same boards. The
 * first one hosts them: the rows live in its database and its scheduler runs
 * the timers. When it is disabled, what was sampled is written and the next
 * enabled plugin that opened it takes over. A board belongs to the plugin that
 * tracked it and goes with it.
 *
 * <h2>How a value is kept</h2>
 * Every sample interval each player online here is read on their own thread,
 * and a row is written only when it changed, all in one batch. Each row keeps
 * the value every period started from, keyed by the period's name, so what a
 * player gained this week is their value now minus that base, and a server that
 * was down over the reset needs no catch-up. A board tracked for the first time
 * is filled with the players who ever played here, a few a second, in the
 * background.
 *
 * <h2>Networks</h2>
 * Only the server a player is on reads them. Every sort reloads the board's
 * rows, so players on other servers rank too. See {@link LeaderboardSource} for
 * sources that differ per server.
 *
 * <h2>Threads</h2>
 * Safe from any thread. Views never touch the database.
 *
 * @since 1.563.0
 */
public final class Leaderboards {

    /** How often online players are read, unless {@link #sampleEvery(Duration)} says otherwise. */
    public static final Duration DEFAULT_SAMPLE_EVERY = Duration.ofMinutes(1);
    /** How often boards are reloaded and sorted, unless {@link #sortEvery(Duration)} says otherwise. */
    public static final Duration DEFAULT_SORT_EVERY = Duration.ofSeconds(30);

    private static final Pattern BOARD_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,48}");
    /** Offline reads handed out a second while a new board fills: database reads. */
    private static final int SEED_BATCH = 20;
    /** Statistic files read a second, on the server thread, one every two ticks. */
    private static final int SEED_STATISTIC_BATCH = 10;
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private static final Object LOCK = new Object();
    /** Every plugin load that opened the boards, in order; guarded by {@link #LOCK}. */
    private static final List<Plugin> OPENERS = new ArrayList<>();
    private static final Map<String, Board> BOARDS = new ConcurrentHashMap<>();
    /** Players online here whose rows were read: only they are sampled. */
    private static final Set<UUID> LOADED = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> LOADING = ConcurrentHashMap.newKeySet();

    private static volatile PeriodClock clock = PeriodClock.of("", 0, DayOfWeek.MONDAY);
    private static volatile Duration sampleEvery = DEFAULT_SAMPLE_EVERY;
    private static volatile Duration sortEvery = DEFAULT_SORT_EVERY;
    private static volatile @Nullable Plugin host;
    private static volatile @Nullable Repository<LeaderboardRow> rows;
    private static volatile @Nullable Repository<LeaderboardMeta> metas;
    private static volatile String server = "server-1";
    private static volatile @Nullable TaskHandle timer;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");
    private static volatile long lastSample;
    private static volatile long lastSort;

    private final Plugin owner;

    private Leaderboards(Plugin owner) {
        this.owner = owner;
    }

    /** One board as this server knows it. */
    private static final class Board {
        final String id;
        volatile LeaderboardSource source;
        volatile Plugin owner;
        /** Every row of the board, every server's, by row id. */
        final Map<String, LeaderboardRow> rows = new ConcurrentHashMap<>();
        /** Rows changed here and not written yet. */
        final Set<String> dirty = ConcurrentHashMap.newKeySet();
        volatile LeaderboardMeta meta;
        /** Its rows and resets were read once: sampling may start. */
        volatile boolean ready;
        volatile boolean seedChecked;
        volatile @Nullable Seeding<OfflinePlayer> seeding;
        volatile boolean warned;
        /** The reload running now, if any; guarded by the board. */
        @Nullable CompletableFuture<Void> refreshing;
        volatile Ranking[] rankings = empty();

        Board(String id, LeaderboardSource source, Plugin owner) {
            this.id = id;
            this.source = source;
            this.owner = owner;
            this.meta = LeaderboardMeta.fresh(id);
        }

        String scope() {
            return source.isPerServer() ? server : LeaderboardRow.SHARED;
        }

        /** Each period's current key: its name and how often an admin reset it. */
        String[] keys() {
            PeriodClock now = clock;
            Span[] spans = Span.values();
            String[] keys = new String[Ranking.PERIODS];
            for (int period = 0; period < Ranking.PERIODS; period++) {
                keys[period] = now.key(spans[period + 1].period()) + "#" + meta.resets(period);
            }
            return keys;
        }

        /** Whether this server is the one writing a row: its player is here and it is this server's scope. */
        boolean ours(LeaderboardRow row) {
            return LOADED.contains(row.player()) && row.scope().equals(scope());
        }

        static Ranking[] empty() {
            Ranking[] none = new Ranking[Span.values().length];
            Arrays.fill(none, Ranking.EMPTY);
            return none;
        }
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    /**
     * The server's leaderboards, hosted by the first plugin that asks.
     *
     * <p>Call it once, in {@code onEnable}. Boards this plugin tracks are
     * dropped when it is disabled; their rows stay.
     *
     * @param plugin the plugin asking; its {@code database.yml} holds the tables if it is the first
     * @return this plugin's handle on the shared boards
     */
    public static @NotNull Leaderboards of(@NotNull Plugin plugin) {
        synchronized (LOCK) {
            // Reloaded in place: the previous load's release has not run yet.
            OPENERS.removeIf(open -> open != plugin && open.getName().equals(plugin.getName()));
            if (!OPENERS.contains(plugin)) OPENERS.add(plugin);
            Plugin current = host;
            if (current == null || (current != plugin && current.getName().equals(plugin.getName()))) {
                hostBy(plugin);
            }
            return new Leaderboards(plugin);
        }
    }

    /**
     * Sets the clock every board's days, weeks, months and years follow, for
     * every plugin. Reloadable: moving the reset hour, zone or week start
     * renames the current periods, which then start over.
     */
    public @NotNull Leaderboards clock(@NotNull PeriodClock next) {
        clock = next;
        return this;
    }

    /** The clock in use; midnight in the server's zone, weeks from Monday, unless set. */
    public @NotNull PeriodClock clock() {
        return clock;
    }

    /** Sets how often online players are read, for every board. At least a second. */
    public @NotNull Leaderboards sampleEvery(@NotNull Duration every) {
        sampleEvery = every.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : every;
        return this;
    }

    /** Sets how often boards are reloaded and sorted, for every board. At least a second. */
    public @NotNull Leaderboards sortEvery(@NotNull Duration every) {
        sortEvery = every.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : every;
        return this;
    }

    static PeriodClock clockInUse() {
        return clock;
    }

    // ------------------------------------------------------------------
    // Boards
    // ------------------------------------------------------------------

    /**
     * Starts keeping a board, or changes what an existing one ranks by.
     *
     * <p>Its stored rows are read and sorted in the background; a board with no
     * rows of this server's yet is filled from offline players when its source
     * can read them. Changing a board to a source that reads something else
     * mixes old rows with new ones: {@link #reset(String)} it.
     *
     * @param id     the board's name, 1-48 of {@code A-Z a-z 0-9 _ . : -}; the same on every server
     * @param source what it ranks by
     * @return this
     * @throws IllegalArgumentException for an id outside those characters
     */
    public @NotNull Leaderboards track(@NotNull String id, @NotNull LeaderboardSource source) {
        if (!BOARD_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A leaderboard id is 1-48 of A-Z a-z 0-9 _ . : - (got '" + id + "')");
        }
        Board existing = BOARDS.get(id);
        if (existing != null) {
            existing.source = source;
            existing.owner = owner;
            return this;
        }
        Board board = new Board(id, source, owner);
        BOARDS.put(id, board);
        refresh(board);
        return this;
    }

    /**
     * Stops keeping a board here, writing what was sampled. Its rows stay, so
     * tracking it again picks up where it was.
     *
     * @return whether it was tracked
     */
    public boolean untrack(@NotNull String id) {
        Board board = BOARDS.remove(id);
        if (board == null) return false;
        write(List.of(board));
        return true;
    }

    /** The ids of every board tracked on this server, by any plugin. */
    public @NotNull Set<String> tracked() {
        return Set.copyOf(BOARDS.keySet());
    }

    /**
     * A board's standings in a span. Cheap: keep it or ask again, both read the
     * last sort.
     */
    public @NotNull LeaderboardView view(@NotNull String id, @NotNull Span span) {
        return new LeaderboardView(id, span);
    }

    /**
     * Starts a board over, on every server: every row is deleted and every
     * period starts again. All time follows the source, so it comes back as
     * players are read again (and from offline players, when the source can).
     */
    public @NotNull CompletableFuture<Void> reset(@NotNull String id) {
        return reset(id, Span.ALL_TIME);
    }

    /**
     * Starts one span of a board over, on every server, within a sort: what
     * players gained in it counts from now. {@link Span#ALL_TIME} is
     * {@link #reset(String)}. No row is rewritten: the span's reset count is
     * part of its key, so every row names an older period.
     *
     * @return completes once it is stored; fails when the database cannot answer
     */
    public @NotNull CompletableFuture<Void> reset(@NotNull String id, @NotNull Span span) {
        Repository<LeaderboardMeta> metaTable = metas;
        Repository<LeaderboardRow> rowTable = rows;
        if (metaTable == null || rowTable == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No plugin hosts the leaderboards."));
        }
        boolean everything = span == Span.ALL_TIME;
        return metaTable.findFresh(id).thenCompose(found -> {
            LeaderboardMeta next = found.orElse(LeaderboardMeta.fresh(id)).reset(span.index());
            CompletableFuture<Void> saved = metaTable.save(next);
            if (everything) saved = saved.thenCompose(ignored -> rowTable.where("board", id).delete()).thenRun(() -> { });
            return saved.thenRun(() -> {
                Board board = BOARDS.get(id);
                if (board == null) return;
                board.meta = next;
                if (everything) {
                    board.rows.clear();
                    board.dirty.clear();
                    board.seedChecked = false;
                }
                refresh(board);
            });
        });
    }

    static Ranking ranking(String id, Span span) {
        Board board = BOARDS.get(id);
        return board == null ? Ranking.EMPTY : board.rankings[span.ordinal()];
    }

    // ------------------------------------------------------------------
    // Lifecycle, driven by the library
    // ------------------------------------------------------------------

    /** Reads a player's rows so they can be sampled. Called by the library on join. */
    public static void joined(@NotNull Player player) {
        if (host != null) load(player.getUniqueId());
    }

    /**
     * Samples a player one last time and writes it. Called by the library on
     * quit, on that player's thread.
     */
    public static void left(@NotNull Player player) {
        UUID id = player.getUniqueId();
        LOADING.remove(id);
        if (host == null || !LOADED.contains(id)) return;
        sample(player);
        LOADED.remove(id);
        write(BOARDS.values());
    }

    /**
     * Lets go of a plugin load, once it is disabled: its boards are written
     * and dropped, and when it hosted them the next enabled plugin that opened
     * them takes over.
     */
    public static void release(@NotNull Plugin plugin) {
        synchronized (LOCK) {
            OPENERS.remove(plugin);
            List<Board> owned = BOARDS.values().stream().filter(board -> board.owner == plugin).toList();
            if (plugin != host) {
                owned.forEach(board -> BOARDS.remove(board.id, board));
                write(owned);
                return;
            }
            stopTimer();
            writeAndWait();
            owned.forEach(board -> BOARDS.remove(board.id, board));
            Plugin next = OPENERS.stream().filter(Plugin::isEnabled).findFirst().orElse(null);
            if (next == null) {
                clear();
                return;
            }
            hostBy(next);
        }
    }

    /** Writes everything and stops, on shutdown. */
    public static void releaseAll() {
        synchronized (LOCK) {
            stopTimer();
            if (host != null) writeAndWait();
            clear();
            OPENERS.clear();
        }
    }

    private static void clear() {
        BOARDS.clear();
        LOADED.clear();
        LOADING.clear();
        host = null;
        rows = null;
        metas = null;
    }

    /** Hosts the boards in a plugin's database: every board and every online player is read again from it. */
    private static void hostBy(Plugin plugin) {
        stopTimer();
        host = plugin;
        logger = plugin.getLogger();
        rows = Databases.of(plugin).repository(LeaderboardRow.class);
        metas = Databases.of(plugin).repository(LeaderboardMeta.class);
        try {
            server = CrossServer.serverId(plugin);
        } catch (RuntimeException noSettings) {
            server = "server-1";
        }
        LOADED.clear();
        LOADING.clear();
        for (Board board : BOARDS.values()) {
            board.ready = false;
            board.seedChecked = false;
            board.seeding = null;
            board.rows.clear();
            board.dirty.clear();
            refresh(board);
        }
        try {
            Wipes.of(plugin).onPlayerReset(Leaderboards::forget);
            timer = Tasks.of(plugin).runTimer(20L, 20L, Leaderboards::tick);
            for (Player online : Bukkit.getOnlinePlayers()) load(online.getUniqueId());
        } catch (RuntimeException noServer) {
            // No scheduler behind this call: a test.
        }
    }

    private static void stopTimer() {
        TaskHandle running = timer;
        timer = null;
        if (running != null) running.cancel();
    }

    /** A reset player's rows are gone from the database; they go from memory too. */
    private static void forget(UUID player) {
        for (Board board : BOARDS.values()) {
            board.rows.entrySet().removeIf(entry -> {
                if (!entry.getValue().player().equals(player)) return false;
                board.dirty.remove(entry.getKey());
                return true;
            });
        }
    }

    // ------------------------------------------------------------------
    // The timer: once a second, global thread, nothing but clock checks
    // ------------------------------------------------------------------

    private static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastSample >= sampleEvery.toMillis()) {
            lastSample = now;
            write(BOARDS.values());
            Plugin owner = host;
            if (owner != null) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID id = player.getUniqueId();
                    if (!LOADED.contains(id)) {
                        load(id); // the join read failed, or is still running
                        continue;
                    }
                    Tasks.of(owner).runAtEntity(player, () -> sample(player));
                }
            }
        }
        if (now - lastSort >= sortEvery.toMillis()) {
            lastSort = now;
            BOARDS.values().forEach(Leaderboards::refresh);
        }
        BOARDS.values().forEach(Leaderboards::seedStep);
    }

    // ------------------------------------------------------------------
    // Sampling and writing
    // ------------------------------------------------------------------

    /** Reads a player's rows of every board; they are sampled from then on. */
    private static void load(UUID player) {
        Repository<LeaderboardRow> table = rows;
        if (table == null || LOADED.contains(player) || !LOADING.add(player)) return;
        table.where("player", player).find().whenComplete((found, failure) -> {
            if (!LOADING.remove(player)) return; // left meanwhile
            if (failure != null) {
                logger.warning("Leaderboards: could not read the rows of " + player + ": " + failure.getMessage());
                return;
            }
            for (LeaderboardRow row : found) {
                Board board = BOARDS.get(row.board());
                if (board != null && !board.dirty.contains(row.id())) board.rows.put(row.id(), row);
            }
            LOADED.add(player);
        });
    }

    /** Reads every board's value of a player, on that player's thread. */
    static void sample(Player player) {
        Plugin owner = host;
        UUID id = player.getUniqueId();
        if (owner == null || !LOADED.contains(id)) return;
        long now = System.currentTimeMillis();
        String name = player.getName();
        for (Board board : BOARDS.values()) {
            if (!board.ready) continue;
            OptionalDouble read = board.source.read(player, owner);
            if (read.isPresent()) record(board, id, name, read.getAsDouble(), now);
        }
    }

    /** One sampled value into a board's row, marked for writing when it changed. */
    private static void record(Board board, UUID player, String name, double value, long now) {
        if (!Double.isFinite(value)) return;
        String scope = board.scope();
        String rowId = LeaderboardRow.id(board.id, scope, player);
        LeaderboardRow old = board.rows.get(rowId);
        LeaderboardRow next = Ranking.sample(old, board.id, scope, player, name, value, board.keys(), now);
        if (next == old) return;
        board.rows.put(rowId, next);
        // A first row at zero says nothing yet: kept in memory for its bases, written once it moves.
        if (old != null || value != 0) board.dirty.add(rowId);
    }

    /** Writes the changed rows of some boards, in one batch. */
    private static CompletableFuture<Void> write(Iterable<Board> boards) {
        Repository<LeaderboardRow> table = rows;
        if (table == null) return CompletableFuture.completedFuture(null);
        List<LeaderboardRow> batch = new ArrayList<>();
        List<Board> owners = new ArrayList<>();
        for (Board board : boards) {
            for (String rowId : List.copyOf(board.dirty)) {
                // Off the list before it is read: a sample after this marks it again.
                board.dirty.remove(rowId);
                LeaderboardRow row = board.rows.get(rowId);
                if (row != null) {
                    batch.add(row);
                    owners.add(board);
                }
            }
        }
        if (batch.isEmpty()) return CompletableFuture.completedFuture(null);
        return table.saveAll(batch).whenComplete((ignored, failure) -> {
            if (failure == null) return;
            for (int index = 0; index < batch.size(); index++) owners.get(index).dirty.add(batch.get(index).id());
            logger.warning("Leaderboards: could not write " + batch.size() + " rows, retrying with the next: " + failure.getMessage());
        });
    }

    private static void writeAndWait() {
        try {
            write(BOARDS.values()).get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            logger.warning("Leaderboards: not every row had landed when tracking stopped: " + failure.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Reloading and sorting, off the server thread
    // ------------------------------------------------------------------

    /**
     * Reads a board's rows and resets from the database, keeps this server's
     * own fresher rows, and sorts every span. The first time, sampling starts
     * and an empty board is seeded.
     */
    private static CompletableFuture<Void> refresh(Board board) {
        Repository<LeaderboardRow> table = rows;
        Repository<LeaderboardMeta> metaTable = metas;
        if (table == null || metaTable == null) return CompletableFuture.completedFuture(null);
        synchronized (board) {
            CompletableFuture<Void> running = board.refreshing;
            if (running != null && !running.isDone()) return running;
            CompletableFuture<Void> next = reload(board, table, metaTable);
            board.refreshing = next;
            return next;
        }
    }

    private static CompletableFuture<Void> reload(Board board, Repository<LeaderboardRow> table,
                                                  Repository<LeaderboardMeta> metaTable) {
        return table.where("board", board.id).find()
                .thenCombine(metaTable.findFresh(board.id), (stored, meta) -> {
                    board.meta = meta.orElse(LeaderboardMeta.fresh(board.id));
                    merge(board, stored);
                    sort(board);
                    board.ready = true;
                    board.warned = false;
                    return (Void) null;
                })
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        if (!board.warned) logger.warning("Leaderboards: could not read board '" + board.id + "': " + failure.getMessage());
                        board.warned = true;
                        return;
                    }
                    if (!board.seedChecked) {
                        board.seedChecked = true;
                        startSeeding(board);
                    }
                })
                .exceptionally(failure -> null);
    }

    private static void merge(Board board, List<LeaderboardRow> stored) {
        Set<String> seen = new HashSet<>(stored.size() * 2);
        for (LeaderboardRow row : stored) {
            seen.add(row.id());
            board.rows.compute(row.id(), (key, mine) ->
                    mine != null && (board.dirty.contains(key) || board.ours(mine)) ? mine : row);
        }
        board.rows.entrySet().removeIf(entry -> !seen.contains(entry.getKey())
                && !board.dirty.contains(entry.getKey()) && !board.ours(entry.getValue()));
    }

    /** Sorts every span from memory. */
    static void sort(Board board) {
        String[] keys = board.keys();
        List<LeaderboardRow> snapshot = List.copyOf(board.rows.values());
        Span[] spans = Span.values();
        Ranking[] next = new Ranking[spans.length];
        for (Span span : spans) {
            int period = span.index();
            next[span.ordinal()] = Ranking.rank(snapshot, period, period < 0 ? null : keys[period]);
        }
        board.rankings = next;
    }

    // ------------------------------------------------------------------
    // Seeding a new board from offline players
    // ------------------------------------------------------------------

    /** A board with no rows of this server's scope, whose source reads offline players, is filled from them. */
    private static void startSeeding(Board board) {
        Plugin owner = host;
        if (owner == null || !board.source.seedsOffline()) return;
        String scope = board.scope();
        if (board.rows.values().stream().anyMatch(row -> row.scope().equals(scope))) return;
        try {
            // The player list reads the server's player folder: once, on the server thread.
            Tasks.of(owner).run(() -> {
                List<OfflinePlayer> everyone = Arrays.asList(Bukkit.getOfflinePlayers());
                if (everyone.isEmpty()) return;
                int batch = board.source.seedsOnServerThread() ? SEED_STATISTIC_BATCH : SEED_BATCH;
                board.seeding = new Seeding<>(everyone, batch);
                logger.info("Leaderboards: filling '" + board.id + "' from " + everyone.size() + " players who played here.");
            });
        } catch (RuntimeException noServer) {
            // No scheduler: a test.
        }
    }

    /** Once a second: hands out the next few offline reads of a filling board. */
    private static void seedStep(Board board) {
        Seeding<OfflinePlayer> seeding = board.seeding;
        Plugin owner = host;
        if (seeding == null || owner == null) return;
        if (seeding.finished()) {
            board.seeding = null;
            logger.info("Leaderboards: '" + board.id + "' read " + seeding.read() + " offline players.");
            return;
        }
        List<OfflinePlayer> next = seeding.next();
        String scope = board.scope();
        LeaderboardSource source = board.source;
        for (int index = 0; index < next.size(); index++) {
            OfflinePlayer player = next.get(index);
            Runnable read = () -> source.readOffline(player, owner).whenComplete((value, failure) -> {
                try {
                    if (failure == null && value.isPresent()) seeded(board, scope, player, value.getAsDouble());
                } finally {
                    seeding.done();
                }
            });
            if (source.seedsOnServerThread()) {
                Tasks.of(owner).runLater(1L + index * 2L, read);
            } else {
                read.run();
            }
        }
    }

    private static void seeded(Board board, String scope, OfflinePlayer player, double value) {
        String name = player.getName();
        if (name == null || value == 0 || !Double.isFinite(value) || board.seeding == null) return;
        String rowId = LeaderboardRow.id(board.id, scope, player.getUniqueId());
        LeaderboardRow row = Ranking.sample(null, board.id, scope, player.getUniqueId(), name, value, board.keys(),
                System.currentTimeMillis());
        if (board.rows.putIfAbsent(rowId, row) == null) board.dirty.add(rowId);
    }

    // ------------------------------------------------------------------
    // Test seams
    // ------------------------------------------------------------------

    /** For tests: hosts the boards in a plugin's database with no timer, as {@code server}. */
    static Leaderboards hostForTests(Plugin plugin, String serverId) {
        synchronized (LOCK) {
            OPENERS.add(plugin);
            host = plugin;
            rows = Databases.of(plugin).repository(LeaderboardRow.class);
            metas = Databases.of(plugin).repository(LeaderboardMeta.class);
            server = serverId;
            return new Leaderboards(plugin);
        }
    }

    /** For tests: a sampled value, as if read from a player online here. */
    static void sampleForTests(String board, UUID player, String name, double value, long now) {
        LOADED.add(player);
        record(BOARDS.get(board), player, name, value, now);
    }

    /** For tests: a player gone from this server. */
    static void leftForTests(UUID player) {
        LOADED.remove(player);
    }

    /** For tests: writes, reloads and sorts a board now, waiting for it. */
    static void syncForTests(String board) {
        Board target = BOARDS.get(board);
        write(List.of(target)).join();
        refresh(target).join();
    }
}
