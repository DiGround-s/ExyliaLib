package net.exylia.lib.util;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.internal.NetworkCooldownRow;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Cooldowns that follow a player across relogs and servers, whatever their
 * length.
 *
 * <p>{@link Cooldowns} lives in one server's memory and keeps only what runs
 * five minutes or more: a player who quits, or moves to another server, starts
 * a thirty-second cooldown over. That is fine for a pearl and wrong for
 * anything worth abusing — a repair, a heal, a reward for a kill. These are
 * written to the plugin's database, so every server that shares it sees the
 * same cooldown.
 *
 * <pre>{@code
 * private final NetworkCooldowns cooldowns = NetworkCooldowns.of(this);
 *
 * UUID id = player.getUniqueId();
 * if (!cooldowns.tryStart(id, "repair", Duration.ofMinutes(10))) {
 *     if (!cooldowns.isLoaded(id)) { say(player, "still loading"); return; }
 *     say(player, "wait " + TimeFormats.render(cooldowns.remaining(id, "repair").toMillis() / 1000.0,
 *             TimeFormats.Style.AUTO));
 *     return;
 * }
 * }</pre>
 *
 * <h2>Reads are memory, writes are the database</h2>
 * A player's cooldowns are read into memory when they join, and every question
 * is answered from there, synchronously and from any thread. A start or a clear
 * changes memory at once and is written asynchronously, in the order it was
 * made.
 *
 * <h2>Before they are read, the answer is "wait"</h2>
 * Between a join and the read finishing — a few milliseconds, or longer when
 * the database is slow — this server does not know. {@link #isActive} answers
 * {@code true} and {@link #tryStart} {@code false} until {@link #isLoaded}
 * does, so a player cannot slip through the gap a server hop opens. A read that
 * fails is tried again while the player stays.
 *
 * <h2>Expiry</h2>
 * A row is deleted when it is cleared, when its owner next joins after it
 * ended, or overwritten when the same key starts again.
 *
 * <h2>What reaches whom</h2>
 * A player is on one server at a time, and that server owns their cooldowns.
 * A cooldown started for somebody who is not here is written for whichever
 * server they join next. One started for somebody online on <em>another</em>
 * server reaches it when they next join a server, not before.
 *
 * <h2>Threads</h2>
 * Safe from any thread.
 *
 * @since 1.184.0
 */
public final class NetworkCooldowns {

    /** How long a failed read waits before it is tried again. */
    private static final long RETRY_TICKS = 100L;

    /** By plugin name; the load that owns each is compared by identity, see {@link #release}. */
    private static final Map<String, NetworkCooldowns> OPEN = new ConcurrentHashMap<>();

    /** One player's cooldowns on this server. */
    private static final class Session {
        volatile boolean loaded;
        final Map<String, Long> expiries = new ConcurrentHashMap<>();
        /** The last write, so the next one waits for it: a clear must not land before its start. */
        CompletableFuture<?> writes = CompletableFuture.completedFuture(null);
    }

    private final Plugin plugin;
    private final String namespace;
    private final Repository<NetworkCooldownRow> rows;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Logger logger;
    private volatile LongSupplier clock = System::currentTimeMillis;

    private NetworkCooldowns(Plugin plugin) {
        this.plugin = plugin;
        this.namespace = plugin.getName().toLowerCase(java.util.Locale.ROOT);
        this.rows = Databases.of(plugin).repository(NetworkCooldownRow.class);
        this.logger = plugin.getLogger();
    }

    /**
     * A plugin's network cooldowns, kept in its database under its own name.
     *
     * <p>Call it once, in {@code onEnable}: the players already online are read
     * at once, and everybody who joins from then on. Released with the plugin.
     *
     * @param plugin the plugin whose {@code database.yml} holds the table
     * @return the same instance for the same plugin
     */
    public static @NotNull NetworkCooldowns of(@NotNull Plugin plugin) {
        // Reloaded in place: an instance owned by a different Plugin object
        // belongs to the previous load, whose release has not run yet.
        OPEN.computeIfPresent(plugin.getName(), (name, open) -> open.plugin == plugin ? open : null);
        NetworkCooldowns[] created = {null};
        NetworkCooldowns cooldowns = OPEN.computeIfAbsent(plugin.getName(), name -> created[0] = new NetworkCooldowns(plugin));
        if (created[0] != null) {
            try {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    cooldowns.open(online.getUniqueId());
                }
            } catch (RuntimeException noServer) {
                // No server behind this call — a test — and nobody to read.
            }
        }
        return cooldowns;
    }

    // ------------------------------------------------------------------
    // Asking
    // ------------------------------------------------------------------

    /**
     * Whether this server has read a player's cooldowns.
     *
     * <p>{@code false} for a player who is not online here, and for one whose
     * read has not come back yet. For the wording of a refusal: "still loading"
     * rather than a remaining time of zero.
     */
    public boolean isLoaded(@NotNull UUID player) {
        Session session = sessions.get(player);
        return session != null && session.loaded;
    }

    /**
     * Whether a key is on cooldown — and {@code true} while this server does
     * not know yet, see {@link #isLoaded}.
     */
    public boolean isActive(@NotNull UUID player, @NotNull String key) {
        Session session = sessions.get(player);
        return session == null || !session.loaded || left(session, key) > 0;
    }

    /**
     * What is left of a cooldown, or {@link Duration#ZERO} when nothing is —
     * including while it is not known yet, which {@link #isLoaded} tells apart.
     */
    public @NotNull Duration remaining(@NotNull UUID player, @NotNull String key) {
        Session session = sessions.get(player);
        return session == null ? Duration.ZERO : Duration.ofMillis(left(session, key));
    }

    /**
     * Starts the cooldown if it is known to be free, and answers whether it did.
     *
     * @return {@code false} when it is running or not known yet
     */
    public boolean tryStart(@NotNull UUID player, @NotNull String key, @NotNull Duration duration) {
        Session session = sessions.get(player);
        if (session == null || !session.loaded) {
            return false;
        }
        synchronized (session) {
            if (left(session, key) > 0) {
                return false;
            }
            start(player, key, duration);
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Changing
    // ------------------------------------------------------------------

    /**
     * Puts a key on cooldown, here at once and on the network once written.
     *
     * <p>A zero or negative duration clears it. Works for a player who is not
     * here: the row waits for the server they join next.
     */
    public void start(@NotNull UUID player, @NotNull String key, @NotNull Duration duration) {
        long millis = duration.toMillis();
        if (millis <= 0) {
            clear(player, key);
            return;
        }
        long expiry = clock.getAsLong() + millis;
        NetworkCooldownRow row = new NetworkCooldownRow(id(player, key), player, namespace, key, expiry);
        write(player, session -> session.expiries.put(key, expiry), () -> rows.save(row));
    }

    /** Ends a cooldown early, here and on the network. */
    public void clear(@NotNull UUID player, @NotNull String key) {
        String id = id(player, key);
        write(player, session -> session.expiries.remove(key), () -> rows.delete(id));
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Reads a joining player's cooldowns in every plugin. Called by the library on join. */
    public static void joined(@NotNull UUID player) {
        for (NetworkCooldowns cooldowns : OPEN.values()) {
            cooldowns.open(player);
        }
    }

    /** Forgets a leaving player in every plugin. Called by the library on quit. */
    public static void left(@NotNull UUID player) {
        for (NetworkCooldowns cooldowns : OPEN.values()) {
            cooldowns.sessions.remove(player);
        }
    }

    /**
     * Stops using a plugin's database, when it is disabled — this load only: a
     * plugin reloaded in place has a newer load alive by the time this runs.
     */
    public static void release(@NotNull Plugin plugin) {
        OPEN.computeIfPresent(plugin.getName(), (name, open) -> open.plugin == plugin ? null : open);
    }

    /** Forgets every plugin, on shutdown. */
    public static void releaseAll() {
        OPEN.clear();
    }

    /**
     * Starts reading a player's cooldowns into a fresh session.
     *
     * <p>A read that finishes after they left finds no session and is dropped;
     * one that finishes after they came back fills the new session, which is
     * the same rows.
     */
    void open(UUID player) {
        Session session = new Session();
        sessions.put(player, session);
        load(player, session);
    }

    private void load(UUID player, Session session) {
        rows.where("player", player).where("namespace", namespace).find().whenComplete((found, failure) -> {
            if (sessions.get(player) != session) {
                return;
            }
            if (failure != null) {
                logger.warning("Network cooldowns: could not read those of " + player
                        + ", trying again: " + failure.getMessage());
                try {
                    Tasks.of(plugin).runAsyncLater(RETRY_TICKS, () -> {
                        if (sessions.get(player) == session) {
                            load(player, session);
                        }
                    });
                } catch (RuntimeException stopped) {
                    // The plugin is going away; the player stays refused, which is the safe side.
                }
                return;
            }
            long now = clock.getAsLong();
            for (NetworkCooldownRow row : found) {
                if (row.expiresAt() <= now) {
                    rows.delete(row.id());
                    continue;
                }
                // A start made while the read was out is newer than the row and wins.
                session.expiries.putIfAbsent(row.name(), row.expiresAt());
            }
            session.loaded = true;
        });
    }

    /**
     * Changes memory now and chains the database write behind the player's
     * previous one, so two writes never land out of order.
     */
    private void write(UUID player, Consumer<Session> change, Supplier<CompletableFuture<?>> store) {
        Session session = sessions.get(player);
        if (session == null) {
            store.get();
            return;
        }
        synchronized (session) {
            change.accept(session);
            session.writes = session.writes.handle((ignored, failure) -> null).thenCompose(ignored -> store.get());
        }
    }

    private long left(Session session, String key) {
        Long expiry = session.expiries.get(key);
        if (expiry == null) {
            return 0L;
        }
        long left = expiry - clock.getAsLong();
        if (left <= 0) {
            // The row goes the next time they join, or with the next start.
            session.expiries.remove(key, expiry);
            return 0L;
        }
        return left;
    }

    private String id(UUID player, String key) {
        return namespace + ':' + player + ':' + key;
    }

    // ------------------------------------------------------------------
    // Test seams
    // ------------------------------------------------------------------

    /** For tests: replaces the clock. */
    void clockForTests(LongSupplier replacement) {
        clock = replacement;
    }

    /** For tests: the last write queued for a player, done when everything before it is. */
    CompletableFuture<?> writesForTests(UUID player) {
        Session session = sessions.get(player);
        return session == null ? CompletableFuture.completedFuture(null) : session.writes;
    }
}
