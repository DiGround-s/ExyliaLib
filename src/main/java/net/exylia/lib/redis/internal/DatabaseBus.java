package net.exylia.lib.redis.internal;

import net.exylia.lib.database.internal.SqlSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A {@link RedisClient} kept in the network's own database, for a network
 * without a Redis server ({@code cross-server: database}).
 *
 * <p>Everything above this class is the same code Redis runs: channels, the
 * proxy bridge, cross-server teleports, {@code RedisKeys}. Only the wire is
 * different. Keys and streams are rows; a published message is a row every
 * server reads by polling.
 *
 * <h2>Polling without losing a message</h2>
 * Messages are numbered by the database. A number is handed out when the
 * insert starts and becomes visible when it commits, so two servers publishing
 * at once can commit 11 before 10: a reader that only asked for "after the
 * highest I saw" would skip 10 for good. Each poll therefore reads again from
 * where the reader stood {@link #LOOKBACK_MILLIS} ago and drops what it already
 * delivered. A message is only missed if its insert takes longer than that to
 * commit, which for a single-row autocommit insert is not a thing that happens
 * on a database that is answering at all.
 *
 * <h2>Clocks</h2>
 * Expiry and message age use this machine's clock, like every server on the
 * network writing them. Servers whose clocks disagree by a few seconds are
 * fine; a minute apart is not, which NTP already prevents.
 */
public abstract class DatabaseBus implements RedisClient {

    /** How often the database is asked for new messages. */
    static final long POLL_MILLIS = 250;

    /** How far back each poll reads again, for numbers that committed late. */
    static final long LOOKBACK_MILLIS = 2_000;

    /** How long a message row lives: far past any reader's lookback. */
    static final long KEEP_MILLIS = 60_000;

    /** How often old messages and expired keys are deleted. */
    static final long PURGE_EVERY_MILLIS = 15_000;

    /** How long the poller waits after a failed poll. */
    static final long RETRY_MILLIS = 2_000;

    /** Messages read per query; a longer backlog is paged. */
    static final int PAGE = 500;

    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
    private final String name;
    private volatile boolean running = true;
    private Thread poller;

    // Poller thread only.
    private final ArrayDeque<long[]> marks = new ArrayDeque<>();
    private final Map<Long, Boolean> delivered = new HashMap<>();
    private long newest;
    private long lastPurge;

    /** A published message as read back. */
    protected record Envelope(long id, @NotNull String channel, @NotNull String payload) {
    }

    protected DatabaseBus(@NotNull String name) {
        this.name = name;
    }

    /**
     * Opens the bus for a database: Mongo or SQL.
     *
     * @param settings the database every server of the network shares
     * @param name     a name for the connection, usually the plugin's
     * @return the bus, already polling
     */
    public static @NotNull DatabaseBus open(@NotNull SqlSettings settings, @NotNull String name) {
        String engine = settings.engine().toLowerCase(java.util.Locale.ROOT).trim();
        DatabaseBus bus = engine.equals("mongodb") || engine.equals("mongo")
                ? MongoBus.connect(settings, name)
                : SqlBus.connect(settings, name);
        try {
            bus.start();
        } catch (RuntimeException failure) {
            bus.close();
            throw failure;
        }
        return bus;
    }

    // --------------------------------------------------------- what a store does

    /** The highest message number now, where a fresh reader starts. */
    protected abstract long newestId() throws Exception;

    /** Messages numbered above {@code after}, lowest first, at most {@code limit}. */
    protected abstract @NotNull List<Envelope> readAfter(long after, int limit) throws Exception;

    /** Stores a message for every server to read. */
    protected abstract void insert(@NotNull String channel, @NotNull String message, long now) throws Exception;

    /** Deletes messages older than {@code before} and keys expired at {@code now}. */
    protected abstract void purge(long before, long now) throws Exception;

    /** Releases the connections the store owns. */
    protected abstract void closeStore();

    /**
     * Waits for the next poll. A store that can be woken by a publish (Postgres
     * {@code LISTEN}) returns as soon as one arrives.
     */
    protected void await(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    // ------------------------------------------------------------------ pub/sub

    @Override
    public void publish(@NotNull String channel, @NotNull String message) {
        try {
            insert(channel, message, System.currentTimeMillis());
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not publish on " + channel + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public @NotNull Subscription subscribe(@NotNull String channel, @NotNull Consumer<String> handler) {
        List<Consumer<String>> registered = handlers.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>());
        registered.add(handler);
        return () -> registered.remove(handler);
    }

    /** Reads where new messages start and begins polling. */
    final void start() {
        prime();
        poller = new Thread(this::run, "ExyliaLib-Bus-" + name);
        poller.setDaemon(true);
        poller.start();
    }

    /** Reads where new messages start, without polling; a test drives {@link #poll} itself. */
    final void prime() {
        try {
            newest = newestId();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
        long now = System.currentTimeMillis();
        marks.add(new long[]{now, newest});
        lastPurge = now;
    }

    private void run() {
        while (running) {
            try {
                await(POLL_MILLIS);
                if (!running) {
                    return;
                }
                poll(System.currentTimeMillis());
            } catch (InterruptedException interrupted) {
                return;
            } catch (Throwable failure) {
                // The database is down or refusing: the publisher sees its own
                // failure, and the poll picks up where it stood once it is back.
                try {
                    Thread.sleep(RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    /**
     * One poll: everything above the number this reader stood at
     * {@link #LOOKBACK_MILLIS} ago, minus what it already delivered.
     *
     * <p>Package-private so a test can drive it without the thread.
     */
    final void poll(long now) throws Exception {
        long floor = floor(now);
        long after = floor;
        List<Envelope> page;
        do {
            page = readAfter(after, PAGE);
            for (Envelope envelope : page) {
                after = envelope.id();
                newest = Math.max(newest, envelope.id());
                if (delivered.putIfAbsent(envelope.id(), Boolean.TRUE) == null) {
                    dispatch(envelope);
                }
            }
        } while (page.size() == PAGE && running);
        marks.addLast(new long[]{now, newest});
        // Numbers at or below the floor are never read again, so nothing needs
        // to remember having delivered them.
        for (Iterator<Long> ids = delivered.keySet().iterator(); ids.hasNext(); ) {
            if (ids.next() <= floor) {
                ids.remove();
            }
        }
        if (now - lastPurge >= PURGE_EVERY_MILLIS) {
            lastPurge = now;
            purge(now - KEEP_MILLIS, now);
        }
    }

    /** Where this reader stood {@link #LOOKBACK_MILLIS} ago, dropping older marks. */
    private long floor(long now) {
        long cutoff = now - LOOKBACK_MILLIS;
        while (marks.size() >= 2) {
            Iterator<long[]> iterator = marks.iterator();
            iterator.next();
            if (iterator.next()[0] > cutoff) {
                break;
            }
            marks.removeFirst();
        }
        return marks.peekFirst()[1];
    }

    private void dispatch(Envelope envelope) {
        List<Consumer<String>> registered = handlers.get(envelope.channel());
        if (registered == null) {
            return;
        }
        for (Consumer<String> handler : registered) {
            try {
                handler.accept(envelope.payload());
            } catch (Throwable failure) {
                // Same rule as the Redis subscriber: one unreadable message
                // must not stop every later one from arriving.
            }
        }
    }

    @Override
    public void close() {
        running = false;
        Thread thread = poller;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        handlers.clear();
        closeStore();
    }

    /** Message ids as Redis callers pass them back: digits, maybe with a {@code -seq} tail. */
    static long entryId(@NotNull String id) {
        int dash = id.indexOf('-');
        return Long.parseLong(dash < 0 ? id : id.substring(0, dash));
    }
}
