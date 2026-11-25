package net.exylia.lib.util.crate.internal;

import net.exylia.lib.cosmetic.CosmeticScopes;
import net.exylia.lib.database.Atomic;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.crate.CrateImport;
import net.exylia.lib.util.reward.PendingRewardRow;
import net.exylia.lib.util.reward.RewardCodec;
import net.exylia.lib.util.internal.RetryBackoff;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Every player's crate row: the database holds it, this server holds a copy to draw from.
 *
 * <h2>The database decides, memory only shows</h2>
 * Every change is worked out on the row as the database holds it right now
 * ({@code findFresh}) and written with a compare-and-set on its version
 * ({@code updated_at}, which every write moves forward): a write worked out on a
 * copy somebody changed since loses, reads again and works it out again. So no
 * server's copy, however stale, is ever written over the table, and a key spent
 * on one server while the player is already on the next is spent once. The
 * copy in memory is what the menus and placeholders read; it is only ever
 * replaced by a row the database answered, never edited by hand.
 *
 * <h2>Operations happen once</h2>
 * An opening spends its keys, unlocks what it won and refunds its duplicates in
 * one write, which also records it in the row's {@link CrateJournal} under its
 * id. Asked again with the same id — a retry after the database went quiet, a
 * replay — it finds itself there and answers what it did. A grant carrying an id
 * is the same. An opening given up on is written down as aborted, so a write
 * still in flight can never land after the refusal was shown.
 *
 * <h2>Scoped</h2>
 * When {@link CosmeticScopes#home} names a scope, a player has two rows: the
 * global one ({@code plugin:uuid}, which takes the start keys and the import)
 * and this scope's ({@code plugin@scope:uuid}). They read as one: unlocks are
 * both rows', keys are both rows' added up. What changes here goes on the
 * scope's row, a key is spent from it first, and only {@link #editShared}
 * writes the global row. An opening that needs some of the global keys first
 * moves them onto the scope's row as an operation of its own, so the opening
 * itself is still one write on one row.
 */
public final class CrateStore implements Listener {

    /** Writes lost to another writer before one gives up; each loss is a fresh read and a retry. */
    private static final int ATTEMPTS = 16;

    /** Paces the retries of failed reads, and their warnings, across every player. */
    private final RetryBackoff retries = new RetryBackoff();

    /** One online player's row on this server. */
    private static final class Session {
        volatile @Nullable CrateRow row;
        /** Completes once the row is read, or once the player left before it was. */
        final CompletableFuture<Void> ready = new CompletableFuture<>();
    }

    /** How an opening's write went. */
    public enum Result {
        /** Written, now or by an earlier try of the same operation. */
        APPLIED,
        /** Not enough keys: nothing written. */
        NO_KEYS,
        /** Given up on: it is not written, and never will be. */
        ABORTED
    }

    /**
     * @param result how it went
     * @param entry  what was written, for {@link Result#APPLIED}
     */
    public record Commit(@NotNull Result result, @Nullable CrateJournal.Entry entry) {
    }

    /** What an opening does to a row the keys were just taken off. */
    @FunctionalInterface
    public interface Settler {
        /**
         * Asked on every attempt, off the server thread, so it must not touch the game.
         *
         * @param paid           the row as the database holds it, the keys already taken off
         * @param ownedElsewhere unlocked on the player's other row
         */
        @NotNull Settled settle(@NotNull CrateRow paid, @NotNull Predicate<String> ownedElsewhere);
    }

    /**
     * @param row   the row with the prizes unlocked and the duplicates refunded
     * @param reels what each reel came to
     */
    public record Settled(@NotNull CrateRow row, @NotNull List<CrateJournal.Reel> reels) {
    }

    private final Plugin plugin;
    private final String owner;
    private final String scope;
    private final Repository<CrateRow> rows;
    /** One row per operation that landed or was given up on: what makes each happen once. */
    private final Repository<CrateOperation> ledger;
    /** The plugin's pending rewards, where a token waits from the opening until it is handed over. */
    private final Repository<PendingRewardRow> pending;
    private final java.util.Set<String> tamperWarned = ConcurrentHashMap.newKeySet();
    private final IntSupplier startKeys;
    private final Consumer<UUID> changed;
    private final Function<UUID, CompletableFuture<CrateImport>> importer;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** The last write in flight per row, so this server's own writes to a row queue instead of racing. */
    private final Map<String, CompletableFuture<?>> tails = new ConcurrentHashMap<>();
    /** The scope's rows, or {@code null} when this store's rows are the only ones. */
    private final @Nullable CrateStore home;
    private volatile Consumer<UUID> loaded = uuid -> { };

    /**
     * @param plugin    whose database holds the table, and whose rows these are
     * @param startKeys keys a row is created with
     * @param changed   told the id of an online player whose row changed, on
     *                  whichever thread learned it
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
        this(plugin, plugin.getName(), startKeys, changed, importer, CosmeticScopes.home(plugin));
    }

    private CrateStore(Plugin plugin, String owner, IntSupplier startKeys, Consumer<UUID> changed,
                       Function<UUID, CompletableFuture<CrateImport>> importer, String scope) {
        this.plugin = plugin;
        this.owner = owner;
        this.scope = scope;
        this.rows = Databases.of(plugin).repository(CrateRow.class);
        this.ledger = Databases.of(plugin).repository(CrateOperation.class);
        this.pending = Databases.of(plugin).repository(PendingRewardRow.class);
        this.startKeys = startKeys;
        this.changed = changed;
        this.importer = importer;
        this.home = scope.isEmpty() ? null
                : new CrateStore(plugin, owner + '@' + scope, () -> 0, changed, uuid -> null, "");
        // Only what is shown: another server's write reaches this copy here. Without Redis this hears
        // nothing, and the copy catches up on the player's next write or join; no decision reads it.
        try {
            Databases.of(plugin).onRemoteChange(CrateRow.class, change -> {
                if (change.wholeTable()) {
                    sessions.keySet().forEach(this::reread);
                    return;
                }
                for (UUID uuid : sessions.keySet()) {
                    if (CrateRow.key(this.owner, uuid).equals(change.id())) {
                        reread(uuid);
                        return;
                    }
                }
            });
        } catch (RuntimeException noDatabase) {
            // A test without a database: no other server either.
        }
    }

    /** For tests: a store scoped as {@code scope}, whatever ExyliaLib's config says. */
    public static @NotNull CrateStore scopedForTests(@NotNull Plugin plugin, @NotNull IntSupplier startKeys,
                                                     @NotNull String scope) {
        return new CrateStore(plugin, plugin.getName(), startKeys, uuid -> { }, uuid -> null, scope);
    }

    /** For tests: one plugin's rows as another server sees them, through {@code plugin}'s database. */
    public static @NotNull CrateStore sharedForTests(@NotNull Plugin plugin, @NotNull String owner,
                                                     @NotNull IntSupplier startKeys, @NotNull String scope) {
        return new CrateStore(plugin, owner, startKeys, uuid -> { }, uuid -> null, scope);
    }

    /** Whether this server keeps its own rows beside the global ones. */
    public boolean scoped() {
        return home != null;
    }

    /** Told the id of a player once their rows are in memory after a join, on a database thread. */
    public void onLoaded(@NotNull Consumer<UUID> listener) {
        this.loaded = listener;
    }

    // ------------------------------------------------------------------
    // Joining and leaving
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        open(event.getPlayer().getUniqueId());
    }

    /** Last, so whatever pays out on the way out still has the row to read. */
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
        if (session == created) load(uuid, session, 0);
        CompletableFuture<Void> all = home == null ? session.ready : CompletableFuture.allOf(session.ready, home.open(uuid));
        if (session == created) {
            all.thenRun(() -> {
                if (sessions.get(uuid) != session || !isLoaded(uuid)) return;
                try {
                    loaded.accept(uuid);
                } catch (RuntimeException failure) {
                    Debug.of(plugin).error("Crate: settling what " + uuid + " was owed failed", failure);
                }
            });
        }
        return all;
    }

    /** Forgets a player who left. Nothing is left to write: every change went to the database. */
    public void close(@NotNull UUID uuid) {
        Session session = sessions.remove(uuid);
        // Anybody waiting on a read that will never be used goes on without it.
        if (session != null) session.ready.complete(null);
        if (home != null) home.close(uuid);
    }

    /** Forgets everybody, when the plugin goes away. */
    public void closeAll() {
        for (UUID uuid : Map.copyOf(sessions).keySet()) close(uuid);
    }

    private void load(UUID uuid, Session session, int failures) {
        created(uuid, startKeys.getAsInt()).whenComplete((row, failure) -> {
            if (sessions.get(uuid) != session) return;
            if (failure != null) {
                long delay = RetryBackoff.delayTicks(failures + 1);
                retries.failed("Crate: could not read the keys of " + uuid + ", trying again in "
                        + delay / 20L + "s: " + failure.getMessage(), Debug.of(plugin)::warn);
                try {
                    Tasks.of(plugin).runAsyncLater(delay, () -> {
                        if (sessions.get(uuid) == session) load(uuid, session, failures + 1);
                    });
                } catch (RuntimeException stopped) {
                    // The plugin is going away; nobody needs the row any more.
                }
                return;
            }
            retries.succeeded();
            remember(uuid, row);
            session.ready.complete(null);
        });
    }

    /**
     * The row as the database holds it, created first when there is none: with
     * the start keys, or with what the plugin imports. The start keys and the
     * import are written by the write that creates the row, and two servers
     * creating it at once leave one row, never two grants: the loser reads the
     * winner's.
     */
    private CompletableFuture<CrateRow> created(UUID uuid, int seedKeys) {
        String key = CrateRow.key(owner, uuid);
        return rows.findFresh(key).thenCompose(found -> found.isPresent()
                ? CompletableFuture.completedFuture(found.get())
                : seed(uuid, seedKeys).thenCompose(seed -> rows.insertIfAbsent(seed.row()))
                        .thenCompose(ignored -> rows.findFresh(key))
                        .thenApply(again -> again.orElseThrow(() -> new IllegalStateException(
                                "The crate row " + key + " was deleted as it was created"))));
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Whether this server has an online player's row in memory. */
    public boolean isLoaded(@NotNull UUID uuid) {
        return row(uuid) != null;
    }

    /** An online player's row as last read, or {@code null} when it is not in memory. For showing only. */
    public @Nullable CrateRow row(@NotNull UUID uuid) {
        CrateRow own = ownRow(uuid);
        if (home == null || own == null) return own;
        CrateRow scoped = home.row(uuid);
        return scoped == null ? null : merged(own, scoped);
    }

    private @Nullable CrateRow ownRow(UUID uuid) {
        Session session = sessions.get(uuid);
        return session == null ? null : session.row;
    }

    /** The scope's row seen with the global one: both rows' unlocks, both rows' keys. */
    private static CrateRow merged(CrateRow global, CrateRow scoped) {
        CrateRow view = scoped.withKeys(scoped.keys() + global.keys());
        for (String id : global.unlockedIds()) view = view.withUnlocked(id);
        return view;
    }

    /**
     * A player's row as the database holds it, whether or not they are here.
     *
     * <p>A row that does not exist yet reads as empty, with no start keys:
     * those belong to the row a join creates. When the plugin imports, it reads
     * as what was imported instead, and that row is created here.
     */
    public @NotNull CompletableFuture<CrateRow> fetch(@NotNull UUID uuid) {
        if (home != null) return fetchOwn(uuid).thenCombine(home.fetch(uuid), CrateStore::merged);
        return fetchOwn(uuid);
    }

    private CompletableFuture<CrateRow> fetchOwn(UUID uuid) {
        String key = CrateRow.key(owner, uuid);
        return rows.findFresh(key).thenCompose(found -> {
            if (found.isPresent()) {
                remember(uuid, found.get());
                return CompletableFuture.completedFuture(found.get());
            }
            // Something imported is written now, as the row it creates:
            // otherwise every read would ask the plugin's table again,
            // and would answer keys nobody can spend.
            return seed(uuid, 0).thenCompose(seed -> seed.imported()
                    ? rows.insertIfAbsent(seed.row()).thenCompose(ignored -> rows.findFresh(key))
                            .thenApply(again -> again.orElse(seed.row()))
                    : CompletableFuture.completedFuture(seed.row()));
        });
    }

    /** Reads an online player's rows again, for a copy that may be behind. */
    public void refresh(@NotNull UUID uuid) {
        reread(uuid);
        if (home != null) home.refresh(uuid);
    }

    private void reread(UUID uuid) {
        if (!sessions.containsKey(uuid)) return;
        rows.findFresh(CrateRow.key(owner, uuid))
                .thenAccept(found -> found.ifPresent(row -> remember(uuid, row)))
                .exceptionally(failure -> null);
    }

    /** Keeps a row the database answered, when it is newer than the copy in memory. */
    private void remember(UUID uuid, CrateRow row) {
        if (!row.id().equals(CrateRow.key(owner, uuid))) return;
        Session session = sessions.get(uuid);
        if (session == null) return;
        synchronized (session) {
            CrateRow mine = session.row;
            // Versions only move forward, so an equal one is the same row and an older one is a read
            // that was overtaken by a write.
            if (mine != null && row.updatedAt() <= mine.updatedAt()) return;
            session.row = row;
        }
        changed.accept(uuid);
    }

    // ------------------------------------------------------------------
    // Changing
    // ------------------------------------------------------------------

    /**
     * Changes a player's row, here or not: this server's scope's row when it has
     * one, the global row otherwise.
     *
     * @param change what to do to the row as the database holds it; answers the
     *               row itself (or {@code null}) for no change. Asked again on
     *               every attempt, off the server thread
     * @return the row as it is afterwards
     */
    public @NotNull CompletableFuture<CrateRow> edit(@NotNull UUID uuid, @NotNull UnaryOperator<CrateRow> change) {
        return home != null ? home.editShared(uuid, change) : editShared(uuid, change);
    }

    /**
     * Changes a player's global row, the one every server reads. The same as
     * {@link #edit} when this server is not {@link #scoped}.
     *
     * @return the global row as it is afterwards
     */
    public @NotNull CompletableFuture<CrateRow> editShared(@NotNull UUID uuid,
                                                           @NotNull UnaryOperator<CrateRow> change) {
        return write(CrateRow.key(owner, uuid), uuid, change);
    }

    /**
     * Spends keys and settles an opening in one transaction, once per operation id:
     * the keys, the unlocks, the refunds, the ledger row and a pending delivery for
     * every token item all land together or not at all.
     *
     * <p>The same {@code operation} asked again finds its ledger row and answers
     * what the first one wrote, so a caller that lost the answer to a timeout asks
     * again instead of guessing.
     *
     * @param operation unique per opening
     * @param spend     keys it costs from the account; {@code 0} for one paid with an item
     * @param tokens    each reel's token, encoded as {@link RewardCodec} stores it; {@code null} for none
     * @return {@link Result#APPLIED} with what it wrote, {@link Result#NO_KEYS}, or
     *         {@link Result#ABORTED} for an operation {@link #abort}ed before; fails
     *         when the database could not be asked, and then it may or may not have landed
     */
    public @NotNull CompletableFuture<Commit> commit(@NotNull UUID uuid, @NotNull String operation, int spend,
                                                     @NotNull List<@Nullable String> tokens,
                                                     @NotNull Settler settler) {
        return transact(uuid, operation, known -> CompletableFuture.completedFuture(answered(known)), (global, scoped) -> {
            int have = global.keys() + (scoped == null ? 0 : scoped.keys());
            if (have < spend) return Change.none(new Commit(Result.NO_KEYS, null));
            int fromScope = scoped == null ? 0 : Math.min(scoped.keys(), spend);
            CrateRow paidGlobal = global.withKeys(global.keys() - (spend - fromScope));
            Settled settled;
            CrateRow newGlobal;
            CrateRow newScoped;
            if (scoped == null) {
                settled = settler.settle(paidGlobal, id -> false);
                newGlobal = settled.row();
                newScoped = null;
            } else {
                settled = settler.settle(scoped.withKeys(scoped.keys() - fromScope), paidGlobal::isUnlocked);
                newGlobal = paidGlobal;
                newScoped = settled.row();
            }
            List<CrateJournal.Reel> reels = settled.reels();
            List<PendingRewardRow> deliveries = new ArrayList<>();
            for (int i = 0; i < reels.size(); i++) {
                String token = i < tokens.size() ? tokens.get(i) : null;
                if (reels.get(i).tokenOwed() && token != null) {
                    deliveries.add(new PendingRewardRow(deliveryId(operation, i), plugin.getName(), uuid.toString(),
                            token, System.currentTimeMillis()));
                }
            }
            CrateJournal.Entry entry = new CrateJournal.Entry(operation, CrateJournal.State.DONE,
                    System.currentTimeMillis(), spend, "", reels);
            return new Change<>(newGlobal, newScoped, entry, deliveries, new Commit(Result.APPLIED, entry));
        });
    }

    /**
     * Gives up on an opening: written down in the ledger as aborted unless it
     * already landed, in which case that is what it answers. Either way the
     * opening's fate is settled for good once this answers: the ledger key lets
     * only one of the two in.
     */
    public @NotNull CompletableFuture<Commit> abort(@NotNull UUID uuid, @NotNull String operation) {
        CrateJournal.Entry tomb = new CrateJournal.Entry(operation, CrateJournal.State.ABORTED,
                System.currentTimeMillis(), 0, "", List.of());
        return ledger.insertIfAbsent(CrateOperation.of(owner, uuid, tomb)).thenCompose(won -> won
                ? CompletableFuture.completedFuture(new Commit(Result.ABORTED, tomb))
                : ledger.findFresh(CrateOperation.key(owner, uuid, operation)).thenApply(found -> {
                    CrateJournal.Entry known = found.map(CrateOperation::read).orElse(null);
                    if (known == null) throw new IllegalStateException("The crate operation " + operation
                            + " is in the ledger and cannot be read");
                    return answered(known);
                }));
    }

    private static Commit answered(CrateJournal.Entry known) {
        return new Commit(known.state() == CrateJournal.State.ABORTED ? Result.ABORTED : Result.APPLIED, known);
    }

    /** The pending reward row that holds one reel's token until it is handed over. */
    public static @NotNull String deliveryId(@NotNull String operation, int index) {
        String id = "crate:" + operation + ':' + index;
        return id.length() <= 64 ? id : "crate:" + UUID.nameUUIDFromBytes(operation.getBytes(
                java.nio.charset.StandardCharsets.UTF_8)) + ':' + index;
    }

    /**
     * Takes one reel's token off the pending rewards, so exactly one caller on any
     * server hands it over; a player who joins before it is taken gets it from
     * the pending rewards instead.
     *
     * @return whether this call took it
     */
    public @NotNull CompletableFuture<Boolean> takeDelivery(@NotNull String operation, int index) {
        return pending.delete(deliveryId(operation, index));
    }

    /**
     * Adds keys, or takes them with a negative number, never below zero. Added to
     * this server's scope's row when it has one, the global row otherwise; taken
     * off the scope's row first and the rest off the global one. Both rows and the
     * ledger row are one transaction.
     *
     * <p>With an id the ledger remembers it network-wide, so the same purchase or
     * vote delivered twice, to one server or two, counts once.
     *
     * @param operation the grant's id; {@code null} for none
     * @return the keys afterwards
     */
    public @NotNull CompletableFuture<Integer> addKeys(@NotNull UUID uuid, int amount, @Nullable String operation) {
        return transact(uuid, operation, known -> fetch(uuid).thenApply(CrateRow::keys), (global, scoped) -> {
            CrateRow newGlobal = null;
            CrateRow newScoped = null;
            int applied;
            if (amount >= 0) {
                applied = amount;
                if (scoped != null) newScoped = scoped.withKeys(scoped.keys() + amount);
                else newGlobal = global.withKeys(global.keys() + amount);
            } else {
                int take = Math.min(-amount, global.keys() + (scoped == null ? 0 : scoped.keys()));
                int fromScope = scoped == null ? 0 : Math.min(scoped.keys(), take);
                applied = -take;
                if (fromScope > 0) newScoped = scoped.withKeys(scoped.keys() - fromScope);
                if (take > fromScope) newGlobal = global.withKeys(global.keys() - (take - fromScope));
            }
            int after = (newGlobal == null ? global : newGlobal).keys()
                    + (scoped == null ? 0 : (newScoped == null ? scoped : newScoped).keys());
            CrateJournal.Entry entry = operation == null ? null : new CrateJournal.Entry(operation,
                    CrateJournal.State.DONE, System.currentTimeMillis(), applied, "", List.of());
            return new Change<>(newGlobal, newScoped, entry, List.of(), after);
        });
    }

    /**
     * Deletes ledger rows older than {@code days}, a day at a time over the two
     * months before that, so a server that was off for weeks still catches up.
     */
    public @NotNull CompletableFuture<Void> pruneLedger(int days) {
        int last = CrateOperation.today() - Math.max(1, days);
        CompletableFuture<?> all = CompletableFuture.completedFuture(null);
        for (int day = last - 60; day < last; day++) {
            int which = day;
            all = all.thenCompose(ignored -> ledger.where("day", which).delete());
        }
        return all.thenApply(ignored -> null);
    }

    /** What one attempt of an operation writes, and what it answers. */
    private record Change<R>(@Nullable CrateRow global, @Nullable CrateRow scoped,
                             @Nullable CrateJournal.Entry entry, @NotNull List<PendingRewardRow> deliveries,
                             R answer) {
        static <R> Change<R> none(R answer) {
            return new Change<>(null, null, null, List.of(), answer);
        }
    }

    /** Works an operation out on the rows as the database holds them; off the server thread. */
    @FunctionalInterface
    private interface Plan<R> {
        /** @param scoped this server's scope's row, {@code null} when it is not scoped */
        @NotNull Change<R> apply(@NotNull CrateRow global, @Nullable CrateRow scoped);
    }

    /** A row as read, and whether it exists or is what a new one would start as. */
    private record Read(CrateRow row, boolean exists, boolean imported) {
    }

    /**
     * Runs an operation as one transaction on the player's global row, this
     * server's scope's row, the ledger and the pending rewards: the rows are
     * read fresh, the plan worked out on them, and every write lands only while
     * each row is still the version read and the operation id is still absent
     * from the ledger. Losing reads again, finds the ledger row when it was the
     * operation itself that landed, and otherwise works it out again.
     */
    private <R> CompletableFuture<R> transact(UUID uuid, @Nullable String operation,
                                              Function<CrateJournal.Entry, CompletableFuture<R>> known, Plan<R> plan) {
        return serial(CrateRow.key(owner, uuid), () -> transactAttempt(uuid, operation, known, plan, ATTEMPTS));
    }

    private <R> CompletableFuture<R> transactAttempt(UUID uuid, @Nullable String operation,
                                                     Function<CrateJournal.Entry, CompletableFuture<R>> known,
                                                     Plan<R> plan, int left) {
        CompletableFuture<Optional<CrateOperation>> done = operation == null
                ? CompletableFuture.completedFuture(Optional.empty())
                : ledger.findFresh(CrateOperation.key(owner, uuid, operation));
        return done.thenCompose(found -> {
            if (found.isPresent()) {
                CrateJournal.Entry entry = found.get().read();
                if (entry == null) return CompletableFuture.failedFuture(new IllegalStateException(
                        "The crate operation " + operation + " is in the ledger and cannot be read"));
                return known.apply(entry);
            }
            CompletableFuture<Read> scopedRead = home == null ? CompletableFuture.completedFuture(null)
                    : current(uuid, home.owner);
            return current(uuid, owner).thenCombine(scopedRead, (global, scoped) -> new Read[]{global, scoped})
                    .thenCompose(read -> {
                        Read global = read[0];
                        Read scoped = read[1];
                        Change<R> change = plan.apply(global.row(), scoped == null ? null : scoped.row());
                        Atomic unit = Databases.of(plugin).atomic();
                        CrateRow writtenGlobal = stage(unit, global, change.global());
                        CrateRow writtenScoped = scoped == null ? null : stage(unit, scoped, change.scoped());
                        if (change.entry() != null) unit.insert(ledger, CrateOperation.of(owner, uuid, change.entry()));
                        for (PendingRewardRow delivery : change.deliveries()) unit.insert(pending, delivery);
                        if (writtenGlobal == null && writtenScoped == null && change.entry() == null) {
                            return CompletableFuture.completedFuture(change.answer());
                        }
                        return unit.commit().thenCompose(landed -> {
                            if (landed) {
                                if (writtenGlobal != null) remember(uuid, writtenGlobal);
                                if (writtenScoped != null && home != null) home.remember(uuid, writtenScoped);
                                return CompletableFuture.completedFuture(change.answer());
                            }
                            if (left <= 1) return CompletableFuture.<R>failedFuture(new IllegalStateException(
                                    "The crate rows of " + uuid + " kept changing under " + ATTEMPTS + " writes"));
                            return transactAttempt(uuid, operation, known, plan, left - 1);
                        });
                    });
        });
    }

    /** Adds a row's write to a unit: a compare-and-set on the version read, or the insert that creates it. */
    private @Nullable CrateRow stage(Atomic unit, Read read, @Nullable CrateRow changed) {
        CrateRow after = changed == null || changed == read.row() ? null : changed;
        if (after == null && (read.exists() || !read.imported())) return null;
        if (after == null) after = read.row();
        if (!read.exists()) {
            CrateRow created = after.sealed();
            unit.insert(rows, created);
            return created;
        }
        CrateRow stamped = after.stampedAfter(read.row()).sealed();
        unit.updateIf(rows, stamped, "updatedAt", read.row().updatedAt());
        return stamped;
    }

    /** A row as the database holds it; one that does not exist yet reads as what it would be created as. */
    private CompletableFuture<Read> current(UUID uuid, String rowOwner) {
        String key = CrateRow.key(rowOwner, uuid);
        return rows.findFresh(key).thenCompose(found -> {
            if (found.isPresent()) return CompletableFuture.completedFuture(new Read(checked(found.get()), true, false));
            return seedFor(key, uuid).thenApply(seed -> new Read(seed.row(), false, seed.imported()));
        });
    }

    /** Says so, once per row, when a server on an older library wrote a row over a newer one's write. */
    private CrateRow checked(CrateRow row) {
        if (row.tampered() && tamperWarned.add(row.id())) {
            Debug.of(plugin).error("Crate: the row " + row.id() + " was written by a server that runs an ExyliaLib"
                    + " older than 1.663.0 and shares this database. Its keys and unlocks may be wrong: update"
                    + " every server of the network to the same ExyliaLib.", null);
        }
        return row;
    }

    /**
     * A compare-and-set write of one row, worked out on what the database holds:
     * read fresh, changed, written only while the version is still the one read.
     * Losing to another writer reads again and works the change out again.
     */
    private CompletableFuture<CrateRow> write(String key, UUID uuid, UnaryOperator<CrateRow> change) {
        return serial(key, () -> attempt(key, uuid, change, ATTEMPTS)).thenApply(row -> {
            remember(uuid, row);
            return row;
        });
    }

    private CompletableFuture<CrateRow> attempt(String key, UUID uuid, UnaryOperator<CrateRow> change, int left) {
        return rows.findFresh(key).thenCompose(found -> {
            if (found.isEmpty()) {
                if (left <= 0) return CompletableFuture.failedFuture(new IllegalStateException("No crate row " + key));
                // Created by an insert that never overwrites, so a row another server just created wins
                // and the change is worked out again on it.
                return seedFor(key, uuid).thenCompose(seed -> {
                    CrateRow after = change.apply(seed.row());
                    boolean unchanged = after == null || after == seed.row();
                    // Nothing to write, and nothing imported to keep: no row, so the join that creates
                    // it still hands out the start keys.
                    if (unchanged && !seed.imported()) return CompletableFuture.completedFuture(seed.row());
                    CrateRow created = (unchanged ? seed.row() : after).sealed();
                    return rows.insertIfAbsent(created).thenCompose(won -> won
                            ? CompletableFuture.completedFuture(created)
                            : attempt(key, uuid, change, left - 1));
                });
            }
            CrateRow before = checked(found.get());
            CrateRow after = change.apply(before);
            if (after == null || after == before) return CompletableFuture.completedFuture(before);
            CrateRow stamped = after.stampedAfter(before).sealed();
            return rows.updateIf(stamped, "updatedAt", before.updatedAt()).thenCompose(won -> {
                if (won) return CompletableFuture.completedFuture(stamped);
                if (left <= 1) {
                    return CompletableFuture.<CrateRow>failedFuture(new IllegalStateException(
                            "The crate row " + key + " kept changing under " + ATTEMPTS + " writes"));
                }
                return attempt(key, uuid, change, left - 1);
            });
        });
    }

    /** What a row an edit is about to create starts as: an import for this store's own rows, empty otherwise. */
    private CompletableFuture<Seed> seedFor(String key, UUID uuid) {
        if (key.equals(CrateRow.key(owner, uuid))) return seed(uuid, 0);
        String rowOwner = key.substring(0, key.length() - uuid.toString().length() - 1);
        return CompletableFuture.completedFuture(new Seed(CrateRow.fresh(rowOwner, uuid, 0), false));
    }

    /** Runs a write once this server's previous write of the same row is done, whatever became of it. */
    private <R> CompletableFuture<R> serial(String key, Supplier<CompletableFuture<R>> work) {
        AtomicReference<CompletableFuture<R>> mine = new AtomicReference<>();
        tails.compute(key, (ignored, tail) -> {
            CompletableFuture<?> previous = tail == null ? CompletableFuture.completedFuture(null) : tail;
            CompletableFuture<R> next = previous.handle((value, failure) -> null).thenCompose(value -> work.get());
            mine.set(next);
            return next;
        });
        CompletableFuture<R> next = mine.get();
        next.whenComplete((value, failure) -> tails.remove(key, next));
        return next;
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

    /**
     * Completes after a delay, off the server thread; fails once the plugin is
     * going away, so a retry loop ends with it.
     */
    public @NotNull CompletableFuture<Void> after(long ticks) {
        CompletableFuture<Void> waited = new CompletableFuture<>();
        try {
            Tasks.of(plugin).runAsyncLater(Math.max(1L, ticks), () -> waited.complete(null));
        } catch (RuntimeException stopped) {
            waited.completeExceptionally(stopped);
        }
        return waited;
    }

    /** For tests: done once every write this server queued for the player's rows is. */
    public @NotNull CompletableFuture<?> writesForTests(@NotNull UUID uuid) {
        CompletableFuture<?> own = tails.getOrDefault(CrateRow.key(owner, uuid), CompletableFuture.completedFuture(null));
        CompletableFuture<?> scoped = home == null ? CompletableFuture.completedFuture(null) : home.writesForTests(uuid);
        return CompletableFuture.allOf(own.handle((v, f) -> null), scoped.handle((v, f) -> null));
    }
}
