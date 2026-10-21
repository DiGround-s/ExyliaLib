package net.exylia.lib.playtime;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.playtime.internal.LegacyPlaytimeRow;
import net.exylia.lib.playtime.internal.PlaytimeRow;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.PlayerStates;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * How long a player has actually played, on every server of the network.
 *
 * <pre>{@code
 * Playtime playtime = Playtime.of(this);           // in onEnable
 * long now = playtime.online(player.getUniqueId()); // -1 until their row is read
 * playtime.total(uuid).thenAccept(ms -> ...);      // anyone, online or not
 * playtime.onMinute(this, player -> checkMilestones(player));
 * }</pre>
 *
 * <h2>One tracker per server</h2>
 * Time is added, so two trackers on one server would count it twice. Every
 * plugin that calls {@link #of} gets the same one. The first plugin hosts it:
 * the rows live in that plugin's database. When the host is disabled, its
 * players' time is written and the next enabled plugin that opened it takes
 * over, reading every online player's row from its own database; with nobody
 * left, tracking stops.
 *
 * <h2>One row, written by every server</h2>
 * {@code exylia_playtime} holds one total per player. Each server adds what it
 * counted since its own last write with an atomic increment, so servers
 * sharing a database never overwrite each other, and the server a player just
 * left can still add its last minute after the next one started counting.
 * Every write reads the row back, which is how time from other servers reaches
 * {@link #online}.
 *
 * <p>The first time the table is empty, the host's ExyliaSurvivalCore
 * {@code playtime_data} totals are copied into it, once; that table is never
 * written.
 *
 * <h2>Only time actually played</h2>
 * A player idle ({@link #active}) at the minute's write or when they leave
 * has that stretch dropped. Time is written every minute, on quit and on
 * disable: a crash costs at most the last minute.
 *
 * <h2>Threads</h2>
 * Safe from any thread. Futures complete off the server thread.
 *
 * @since 1.341.0
 */
public final class Playtime {

    /** How long a player can stand still before their time stops counting, unless {@link #idleAfter(Duration)} says otherwise. */
    public static final Duration DEFAULT_IDLE_AFTER = Duration.ofMinutes(5);

    private static final long MINUTE_TICKS = 1200L;
    /** How long a disable waits for the last writes, all of them together. */
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private static final Object LOCK = new Object();
    /** Every plugin load that opened the tracker, in order; guarded by {@link #LOCK}. */
    private static final List<Plugin> OPENERS = new ArrayList<>();
    private static final List<MinuteListener> MINUTE = new CopyOnWriteArrayList<>();
    private static @Nullable Playtime tracker;
    private static volatile Duration idleAfter = DEFAULT_IDLE_AFTER;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<?>> chains = new ConcurrentHashMap<>();
    private volatile @Nullable Plugin host;
    private volatile Repository<PlaytimeRow> rows;
    /** Completes once the legacy copy is done; every read and write waits for it. */
    private volatile CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);
    private volatile @Nullable TaskHandle timer;
    private volatile Logger logger = Logger.getLogger("ExyliaLib");
    private volatile LongSupplier clock = System::currentTimeMillis;

    private record MinuteListener(Plugin owner, Consumer<Player> action) {
    }

    /** What this server knows about one online player. */
    private static final class Session {
        final long joined;
        volatile boolean loaded;
        /** The total the row held when this server last read or wrote it. */
        private long stored;
        /** Since when this server's time is not in {@link #stored}. */
        private long mark;

        Session(long now) {
            joined = now;
            mark = now;
        }

        synchronized long total(long now) {
            return stored + Math.max(0, now - mark);
        }

        /** Drops the time since the last mark: the player was idle. */
        synchronized void idle(long now) {
            mark = Math.max(mark, now);
        }
    }

    /** For tests: a tracker over one table, hosted by nobody, with no timer. */
    Playtime(Repository<PlaytimeRow> rows) {
        this.rows = rows;
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    /**
     * The server's playtime tracker, hosted by the first plugin that asks.
     * Starts counting every player already online.
     *
     * <p>Call it once, in {@code onEnable}. Released with the plugin.
     *
     * @param plugin the plugin asking; its {@code database.yml} holds the table if it is the first
     * @return the one tracker of this server
     */
    public static @NotNull Playtime of(@NotNull Plugin plugin) {
        synchronized (LOCK) {
            // Reloaded in place: the previous load's release has not run yet.
            OPENERS.removeIf(open -> open != plugin && open.getName().equals(plugin.getName()));
            if (!OPENERS.contains(plugin)) {
                OPENERS.add(plugin);
            }
            if (tracker == null) {
                Playtime created = new Playtime(Databases.of(plugin).repository(PlaytimeRow.class));
                created.hostBy(plugin);
                try {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        created.start(online.getUniqueId());
                    }
                } catch (RuntimeException noServer) {
                    // No server behind this call — a test — and nobody online.
                }
                tracker = created;
            }
            return tracker;
        }
    }

    /**
     * Sets how long a player can stand without moving, looking, typing or
     * clicking before their time stops counting, for every plugin.
     *
     * @param after the threshold; zero or negative turns idle detection off
     */
    public static void idleAfter(@NotNull Duration after) {
        idleAfter = after;
    }

    /** The idle threshold in use; {@link #DEFAULT_IDLE_AFTER} unless set. */
    public static @NotNull Duration idleAfter() {
        return idleAfter;
    }

    /**
     * Whether the player moved, looked, typed or clicked within
     * {@link #idleAfter()}.
     *
     * @param player the player
     * @return whether their time counts right now
     */
    public static boolean active(@NotNull Player player) {
        Duration after = idleAfter;
        return after.isNegative() || after.isZero() || player.getIdleDuration().compareTo(after) < 0;
    }

    /**
     * Whether the player is in the game as a player: alive, in survival or
     * adventure, and not vanished. For rewards for being somewhere, which are
     * not for staff flying about in creative, spectating or hidden.
     *
     * @param player the player
     * @return whether they are playing
     */
    public static boolean playing(@NotNull Player player) {
        if (player.isDead()) return false;
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return false;
        return !PlayerStates.isVanished(player);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Time played by a player online here, including what this server has not
     * written yet. Free: memory only.
     *
     * @param player the player
     * @return milliseconds played on the whole network, or {@code -1} when they
     *         are not online here or their row has not been read yet
     */
    public long online(@NotNull UUID player) {
        Session session = sessions.get(player);
        return session == null || !session.loaded ? -1L : session.total(clock.getAsLong());
    }

    /**
     * Time played by anybody: from memory for a loaded player online here,
     * from the database otherwise.
     *
     * @param player the player
     * @return milliseconds played, {@code 0} for somebody never seen; completes
     *         exceptionally when the database cannot answer
     */
    public @NotNull CompletableFuture<Long> total(@NotNull UUID player) {
        long known = online(player);
        if (known >= 0) {
            return CompletableFuture.completedFuture(known);
        }
        Repository<PlaytimeRow> target = rows;
        return ready.thenCompose(ignored -> target.find(player))
                .thenApply(row -> row.map(PlaytimeRow::totalMs).orElse(0L));
    }

    /**
     * How long a player has been on this server since they joined, idle or not.
     *
     * @param player the player
     * @return milliseconds, {@code 0} when they are not online here
     */
    public long session(@NotNull UUID player) {
        Session session = sessions.get(player);
        return session == null ? 0L : Math.max(0, clock.getAsLong() - session.joined);
    }

    /**
     * Whether a player online here has had their row read, so that
     * {@link #online} answers.
     *
     * @param player the player
     * @return whether their total is known
     */
    public boolean loaded(@NotNull UUID player) {
        Session session = sessions.get(player);
        return session != null && session.loaded;
    }

    /**
     * Runs something for every online player once a minute, right after their
     * time is written, on that player's own thread. For milestones: read
     * {@link #online} inside it. Dropped when the owner is disabled.
     *
     * @param owner  the plugin the task belongs to
     * @param action what to run for each player
     */
    public void onMinute(@NotNull Plugin owner, @NotNull Consumer<Player> action) {
        MINUTE.add(new MinuteListener(owner, action));
    }

    // ------------------------------------------------------------------
    // Lifecycle, driven by the library
    // ------------------------------------------------------------------

    /** Starts counting a player. Called by the library on join. */
    public static void joined(@NotNull Player player) {
        Playtime current = tracker;
        if (current != null) current.start(player.getUniqueId());
    }

    /** Writes a player's last stretch. Called by the library on quit. */
    public static void left(@NotNull Player player) {
        Playtime current = tracker;
        if (current != null) current.end(player.getUniqueId(), active(player));
    }

    /**
     * Lets go of a plugin load, once it is disabled. When it hosted the
     * tracker, its players' time is written (waiting up to ten seconds) and
     * the next enabled plugin that opened it takes over.
     */
    public static void release(@NotNull Plugin plugin) {
        synchronized (LOCK) {
            OPENERS.remove(plugin);
            MINUTE.removeIf(listener -> listener.owner == plugin);
            Playtime current = tracker;
            if (current == null || current.host != plugin) return;
            current.stopTimer();
            current.writeAllAndWait();
            Plugin next = OPENERS.stream().filter(Plugin::isEnabled).findFirst().orElse(null);
            if (next == null) {
                current.sessions.clear();
                tracker = null;
                return;
            }
            current.rows = Databases.of(next).repository(PlaytimeRow.class);
            current.hostBy(next);
            // Each total was the old host's database; this one's may differ.
            current.sessions.forEach((id, session) -> {
                session.loaded = false;
                current.load(id, session);
            });
        }
    }

    /** Writes everybody's time and stops, on shutdown. */
    public static void releaseAll() {
        synchronized (LOCK) {
            Playtime current = tracker;
            if (current != null) {
                current.stopTimer();
                current.writeAllAndWait();
                current.sessions.clear();
            }
            tracker = null;
            OPENERS.clear();
            MINUTE.clear();
        }
    }

    private void hostBy(Plugin plugin) {
        host = plugin;
        logger = plugin.getLogger();
        ready = copyLegacy(plugin, rows).exceptionally(failure -> {
            logger.warning("Playtime: could not copy playtime_data into exylia_playtime: " + failure.getMessage());
            return null;
        });
        try {
            timer = Tasks.of(plugin).runTimer(MINUTE_TICKS, MINUTE_TICKS, this::minute);
        } catch (RuntimeException noServer) {
            // No scheduler behind this call — a test.
        }
    }

    /**
     * Copies ExyliaSurvivalCore's totals the first time {@code exylia_playtime}
     * is empty.
     *
     * <p>ponytail: two servers enabling at the very same moment can both copy,
     * and the second copy can overwrite the first one's first minute; a shared
     * lock if that ever matters.
     */
    private static CompletableFuture<Void> copyLegacy(Plugin plugin, Repository<PlaytimeRow> rows) {
        return rows.count().thenCompose(count -> {
            if (count > 0) return CompletableFuture.<Void>completedFuture(null);
            Repository<LegacyPlaytimeRow> legacy = Databases.of(plugin).repository(LegacyPlaytimeRow.class);
            return legacy.findAll().thenCompose(old -> {
                List<PlaytimeRow> copied = new ArrayList<>();
                for (LegacyPlaytimeRow row : old) {
                    if (row.totalPlaytimeMs() <= 0) continue;
                    try {
                        copied.add(new PlaytimeRow(UUID.fromString(row.playerUuid()), row.totalPlaytimeMs()));
                    } catch (IllegalArgumentException notAnId) {
                        // A row nobody can be matched to.
                    }
                }
                if (copied.isEmpty()) return CompletableFuture.<Void>completedFuture(null);
                plugin.getLogger().info("Playtime: copied " + copied.size() + " totals from playtime_data.");
                return rows.saveAll(copied);
            });
        });
    }

    private void stopTimer() {
        TaskHandle running = timer;
        timer = null;
        if (running != null) running.cancel();
    }

    /** Once a minute: writes each online player's time, then runs the minute listeners. */
    private void minute() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            write(player.getUniqueId(), active(player));
            for (MinuteListener listener : MINUTE) {
                // Disabled, and its release has not run yet: its scheduler is gone.
                if (!listener.owner.isEnabled()) continue;
                Tasks.of(listener.owner).runAtEntity(player, () -> listener.action.accept(player));
            }
        }
    }

    private void writeAllAndWait() {
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        sessions.forEach((id, session) -> writes.add(flush(id, session)));
        try {
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            logger.warning("Playtime: not every write had landed when tracking stopped: " + failure.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Counting and writing
    // ------------------------------------------------------------------

    /** Starts a player's session and reads their row. */
    void start(UUID player) {
        Session session = new Session(clock.getAsLong());
        sessions.put(player, session);
        load(player, session);
    }

    /** Ends a player's session and writes it; idle time since the last write is dropped. */
    CompletableFuture<Void> end(UUID player, boolean active) {
        Session session = sessions.remove(player);
        if (session == null) return CompletableFuture.completedFuture(null);
        if (!active) session.idle(clock.getAsLong());
        return flush(player, session);
    }

    /** The minute's write for one player; idle time since the last write is dropped. */
    CompletableFuture<Void> write(UUID player, boolean active) {
        Session session = sessions.get(player);
        if (session == null) return CompletableFuture.completedFuture(null);
        if (!active) session.idle(clock.getAsLong());
        return flush(player, session);
    }

    private void load(UUID player, Session session) {
        Repository<PlaytimeRow> target = rows;
        inTurn(player, () -> ready.thenCompose(ignored -> target.findFresh(player)).thenAccept(found -> {
            synchronized (session) {
                // A write already brought a fresher row than this read.
                if (session.loaded) return;
                session.stored = found.map(PlaytimeRow::totalMs).orElse(0L);
                session.loaded = true;
            }
        })).exceptionally(failure -> {
            // Left unloaded: the minute's write reads the row and loads it.
            logger.warning("Playtime: could not read the playtime of " + player + ": " + failure.getMessage());
            return null;
        });
    }

    /**
     * Adds the time counted since the last write to the row, then reads it
     * back with every other server's time in it.
     *
     * <p>The mark moves when the write is queued, so the next write never
     * counts the same time; if the add fails, the time goes back onto the
     * session to be written with the next.
     */
    private CompletableFuture<Void> flush(UUID player, Session session) {
        return inTurn(player, () -> {
            long now = clock.getAsLong();
            long delta;
            synchronized (session) {
                delta = Math.max(0, now - session.mark);
                session.mark = now;
                session.stored += delta;
            }
            Repository<PlaytimeRow> target = rows;
            CompletableFuture<Void> add = delta == 0 ? ready
                    : ready.thenCompose(ignored -> target.increment(new PlaytimeRow(player, delta), "total_ms"));
            return add.handle((ignored, failure) -> {
                if (failure == null) return true;
                synchronized (session) {
                    session.mark -= delta;
                    session.stored -= delta;
                }
                String lost = sessions.get(player) == session ? "" : " (" + delta + "ms lost, the player left)";
                logger.warning("Playtime: could not write the playtime of " + player + lost + ": " + failure.getMessage());
                return false;
            }).thenCompose(added -> !added ? CompletableFuture.<Void>completedFuture(null)
                    : target.findFresh(player).thenAccept(found -> found.ifPresent(row -> {
                        synchronized (session) {
                            session.stored = row.totalMs();
                            session.loaded = true;
                        }
                    })));
        });
    }

    /** Runs one player's reads and writes one after another, in the order they were asked for. */
    private <T> CompletableFuture<T> inTurn(UUID player, Supplier<CompletableFuture<T>> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<?> before = chains.put(player, result);
        (before == null ? CompletableFuture.completedFuture(null) : before)
                .handle((ignored, failure) -> null)
                .thenCompose(ignored -> {
                    try {
                        return work.get();
                    } catch (RuntimeException failure) {
                        return CompletableFuture.<T>failedFuture(failure);
                    }
                })
                .whenComplete((value, failure) -> {
                    chains.remove(player, result);
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(value);
                    }
                });
        return result;
    }

    // ------------------------------------------------------------------
    // Test seams
    // ------------------------------------------------------------------

    /** For tests: replaces the clock. */
    void clockForTests(LongSupplier replacement) {
        clock = replacement;
    }
}
