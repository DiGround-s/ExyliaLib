package net.exylia.lib.util.crate.internal;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.crate.CrateImport;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.UnaryOperator;

/**
 * Every online player's crate row, in memory, and the database behind it.
 *
 * <h2>Reads are memory, writes go through</h2>
 * A row is read when its player joins and forgotten when they leave; every
 * question about an online player is a map lookup. Every change is applied in
 * memory at once and written behind the player's previous write, so two
 * changes never land out of order and nothing is left to flush on a quit.
 *
 * <h2>Somebody who is not here</h2>
 * Read, changed and written, and never cached: the row of somebody on another
 * server would go stale in this one's memory the moment they changed it.
 */
public final class CrateStore implements Listener {

    /** How long a failed read waits before it is tried again. */
    private static final long RETRY_TICKS = 100L;

    /** One online player's row on this server. */
    private static final class Session {
        volatile @Nullable CrateRow row;
        /** Completes once the row is read, or once the player left before it was. */
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        /** The last write, so the next one waits for it. */
        CompletableFuture<?> writes = CompletableFuture.completedFuture(null);
    }

    private final Plugin plugin;
    private final String owner;
    private final Repository<CrateRow> rows;
    private final IntSupplier startKeys;
    private final Consumer<UUID> changed;
    private final Function<UUID, CompletableFuture<CrateImport>> importer;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * @param plugin    whose database holds the table, and whose rows these are
     * @param startKeys keys a row is created with
     * @param changed   told the id of an online player whose row changed, on
     *                  the thread that changed it
     */
    public CrateStore(@NotNull Plugin plugin, @NotNull IntSupplier startKeys, @NotNull Consumer<UUID> changed) {
        this(plugin, startKeys, changed, uuid -> null);
    }

    /**
     * @param importer what a player had before, asked only when their row is
     *                 about to be created; a {@code null} future or a
     *                 {@code null} answer means nothing, and the row starts
     *                 as a new one does
     */
    public CrateStore(@NotNull Plugin plugin, @NotNull IntSupplier startKeys, @NotNull Consumer<UUID> changed,
                      @NotNull Function<UUID, CompletableFuture<CrateImport>> importer) {
        this.plugin = plugin;
        this.owner = plugin.getName();
        this.rows = Databases.of(plugin).repository(CrateRow.class);
        this.startKeys = startKeys;
        this.changed = changed;
        this.importer = importer;
    }

    // ------------------------------------------------------------------
    // Joining and leaving
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        open(event.getPlayer().getUniqueId());
    }

    /** Last, so whatever pays out on the way out still has the row to write to. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        close(event.getPlayer().getUniqueId());
    }

    /**
     * Reads a player's row, creating it with the start keys the first time
     * they are ever seen.
     *
     * @return done once the row is in memory; the same future for a read already in flight
     */
    public @NotNull CompletableFuture<Void> open(@NotNull UUID uuid) {
        Session created = new Session();
        Session session = sessions.computeIfAbsent(uuid, ignored -> created);
        if (session == created) load(uuid, session);
        return session.ready;
    }

    /** Forgets a player who left. Their row was already written, change by change. */
    public void close(@NotNull UUID uuid) {
        Session session = sessions.remove(uuid);
        // Anybody waiting on a read that will never be used goes on without it.
        if (session != null) session.ready.complete(null);
    }

    /** Forgets everybody, when the plugin goes away. */
    public void closeAll() {
        for (UUID uuid : Map.copyOf(sessions).keySet()) close(uuid);
    }

    private void load(UUID uuid, Session session) {
        rows.find(CrateRow.key(owner, uuid))
                .thenCompose(found -> {
                    if (found.isPresent()) return CompletableFuture.completedFuture(found.get());
                    // The start keys are written on the row that is created, not
                    // on every read, so they are handed out once ever. So is an
                    // import: once the row exists it is never asked again, and a
                    // failed one fails the read, which is tried again later
                    // rather than creating a row that would forget it.
                    return seed(uuid, startKeys.getAsInt())
                            .thenCompose(seed -> rows.save(seed.row()).thenApply(ignored -> seed.row()));
                })
                .whenComplete((row, failure) -> {
                    if (sessions.get(uuid) != session) return;
                    if (failure != null) {
                        Debug.of(plugin).warn("Crate: could not read the keys of " + uuid
                                + ", trying again: " + failure.getMessage());
                        try {
                            Tasks.of(plugin).runAsyncLater(RETRY_TICKS, () -> {
                                if (sessions.get(uuid) == session) load(uuid, session);
                            });
                        } catch (RuntimeException stopped) {
                            // The plugin is going away; nobody needs the row any more.
                        }
                        return;
                    }
                    session.row = row;
                    session.ready.complete(null);
                    changed.accept(uuid);
                });
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Whether this server has an online player's row in memory. */
    public boolean isLoaded(@NotNull UUID uuid) {
        return row(uuid) != null;
    }

    /** An online player's row, or {@code null} when it is not in memory. */
    public @Nullable CrateRow row(@NotNull UUID uuid) {
        Session session = sessions.get(uuid);
        return session == null ? null : session.row;
    }

    /**
     * A player's row whether or not they are here, without caching it.
     *
     * <p>A row that does not exist yet reads as empty, with no start keys:
     * those belong to the row a join creates. When the plugin imports, it reads
     * as what was imported instead, and that row is created here.
     */
    public @NotNull CompletableFuture<CrateRow> fetch(@NotNull UUID uuid) {
        CrateRow cached = row(uuid);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return rows.find(CrateRow.key(owner, uuid))
                .thenCompose(found -> {
                    if (found.isPresent()) return CompletableFuture.completedFuture(found.get());
                    // Something imported is written now, as the row it creates:
                    // otherwise every read would ask the plugin's table again,
                    // and would answer keys nobody can spend.
                    return seed(uuid, 0).thenCompose(seed -> seed.imported()
                            ? rows.save(seed.row()).thenApply(ignored -> seed.row())
                            : CompletableFuture.completedFuture(seed.row()));
                });
    }

    // ------------------------------------------------------------------
    // Changing
    // ------------------------------------------------------------------

    /**
     * Takes one key off an online player's row, if it has one.
     *
     * <p>Checked and taken under the session's lock, so two clicks in one tick
     * cannot both spend the last key.
     *
     * @return whether a key was spent; {@code false} too when the row is not in memory
     */
    public boolean spendKey(@NotNull UUID uuid) {
        Session session = sessions.get(uuid);
        if (session == null) return false;
        synchronized (session) {
            CrateRow row = session.row;
            if (row == null || row.keys() <= 0) return false;
            apply(uuid, session, current -> current.withKeys(current.keys() - 1));
            return true;
        }
    }

    /**
     * Unlocks a reward on an online player's row.
     *
     * @return whether it was new to them; {@code false} too when the row is not in memory
     */
    public boolean unlockNow(@NotNull UUID uuid, @NotNull String rewardId) {
        Session session = sessions.get(uuid);
        if (session == null) return false;
        synchronized (session) {
            CrateRow row = session.row;
            if (row == null || row.isUnlocked(rewardId)) return false;
            apply(uuid, session, current -> current.withUnlocked(rewardId));
            return true;
        }
    }

    /**
     * Changes a player's row, here or not.
     *
     * <p>In memory it is applied at once, on the caller's thread. A row still
     * being read is changed as soon as it arrives. Out of memory it is a read,
     * the change and a write.
     *
     * @param change what to do to the row; answers the row itself for no change
     * @return the row as it is afterwards
     */
    public @NotNull CompletableFuture<CrateRow> edit(@NotNull UUID uuid, @NotNull UnaryOperator<CrateRow> change) {
        Session session = sessions.get(uuid);
        if (session != null) {
            synchronized (session) {
                if (session.row != null) {
                    return CompletableFuture.completedFuture(apply(uuid, session, change));
                }
            }
            // Still being read: once it is, this is either a row in memory or,
            // when they left first, a row that is not.
            return session.ready.thenCompose(ignored -> edit(uuid, change));
        }

        return rows.find(CrateRow.key(owner, uuid))
                .thenCompose(found -> found.isPresent()
                        ? CompletableFuture.completedFuture(new Seed(found.get(), false))
                        : seed(uuid, 0))
                .thenCompose(seed -> {
                    CrateRow before = seed.row();
                    CrateRow after = change.apply(before);
                    // An imported row is written even when the change did
                    // nothing: it is being created, and not writing it would
                    // ask the plugin's table again next time.
                    if (after == before && !seed.imported()) return CompletableFuture.completedFuture(before);
                    CrateRow stamped = after.touched();
                    return rows.save(stamped).thenApply(ignored -> stamped);
                })
                .thenCompose(after -> {
                    // They joined while this was in flight, and their read may
                    // have come back before this write landed. What is written
                    // here is the newer row either way, so it is what they get.
                    Session joined = sessions.get(uuid);
                    if (joined == null) return CompletableFuture.completedFuture(after);
                    return joined.ready.thenApply(ignored -> {
                        synchronized (joined) {
                            if (sessions.get(uuid) == joined && joined.row != null && !joined.row.equals(after)) {
                                apply(uuid, joined, current -> after);
                            }
                        }
                        return after;
                    });
                });
    }

    /**
     * A row about to be created, and whether it carries an import.
     *
     * @param row      what to write
     * @param imported whether the plugin had something for this player
     */
    private record Seed(CrateRow row, boolean imported) {
    }

    /**
     * What a row that does not exist yet starts as: what the plugin had for the
     * player, or the start keys when it had nothing. Nothing is written here.
     *
     * @return fails when the import fails, so no row is created without it
     */
    private CompletableFuture<Seed> seed(UUID uuid, int startKeys) {
        CompletableFuture<CrateImport> asked;
        try {
            asked = importer.apply(uuid);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (asked == null) asked = CompletableFuture.completedFuture(null);
        return asked.thenApply(imported -> imported == null
                ? new Seed(CrateRow.fresh(owner, uuid, startKeys), false)
                : new Seed(CrateRow.imported(owner, uuid, imported.keys(), imported.unlocked()), true));
    }

    /** Applies a change in memory and queues its write. The caller holds the session. */
    private CrateRow apply(UUID uuid, Session session, UnaryOperator<CrateRow> change) {
        CrateRow before = session.row;
        CrateRow after = change.apply(before);
        if (after == before) return before;
        CrateRow stamped = after.touched();
        session.row = stamped;
        session.writes = session.writes.handle((ignored, failure) -> null)
                .thenCompose(ignored -> rows.save(stamped));
        changed.accept(uuid);
        return stamped;
    }

    /** For tests: the last write queued for a player, done when every one before it is. */
    public @NotNull CompletableFuture<?> writesForTests(@NotNull UUID uuid) {
        Session session = sessions.get(uuid);
        return session == null ? CompletableFuture.completedFuture(null) : session.writes;
    }
}
