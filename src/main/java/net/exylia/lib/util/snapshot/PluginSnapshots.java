package net.exylia.lib.util.snapshot;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.snapshot.internal.LegacyImport;
import net.exylia.lib.util.snapshot.internal.PlayerState;
import net.exylia.lib.util.snapshot.internal.SnapshotRow;
import net.exylia.lib.util.snapshot.internal.SnapshotRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * One plugin's view of the snapshot module.
 *
 * <pre>{@code
 * private PluginSnapshots snapshots;
 *
 * @Override
 * public void onEnable() {
 *     snapshots = Snapshots.of(this).using(config.get().snapshots());
 * }
 *
 * // Joining an arena: keep what they own, then hand out the kit.
 * snapshots.saveAndClear(player, "ffa").thenRun(() -> giveKit(player));
 *
 * // Leaving it, whenever that happens — this tick, or three restarts later.
 * snapshots.restore(player, "ffa", lobby -> teleport(player, lobby));
 * }</pre>
 *
 * <h2>Two lifetimes, one type</h2>
 * A snapshot held in a field lives as long as the field. A snapshot passed to
 * {@link #save} survives a disconnect, a restart and a crash, because it is a
 * row. The difference is which method is called, not which type is used, and
 * nothing here needs to be initialised, registered or shut down.
 *
 * <h2>Everything is a future, and nothing blocks</h2>
 * A snapshot is read from and written to a database, so every method that
 * touches one answers with a {@link CompletableFuture}. Capturing and restoring
 * happen on the thread that owns the player, scheduled by the library; a caller
 * never has to think about which thread it is on.
 *
 * <h2>Context</h2>
 * A context id is a short name for the reason a snapshot was taken:
 * {@code "ffa"}, {@code "event"}, {@code "sandbox"}, {@code "kit-editor"}. It
 * is part of the identity of the row, so the same player can have one of each
 * at the same time and restoring one leaves the others alone. ExyliaCommons
 * keyed on the player alone, which meant a player who joined an event while in
 * an arena lost the inventory they actually owned.
 *
 * @since 1.34.0
 */
public final class PluginSnapshots {

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;
    private volatile SnapshotSettings settings = new SnapshotSettings();

    /** Built on the first call that needs it: opening is the database's job. */
    private volatile @Nullable Repository<SnapshotRow> rows;

    /** Whether the one-time import has been started by this plugin. */
    private volatile @Nullable CompletableFuture<Integer> imported;

    private final Object lock = new Object();

    PluginSnapshots(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
    }

    /** The plugin these belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Uses this plugin's own configured behaviour.
     *
     * @param settings what to do on the way up
     * @return this
     */
    public @NotNull PluginSnapshots using(@NotNull SnapshotSettings settings) {
        this.settings = settings;
        return this;
    }

    /** The settings in force. */
    public @NotNull SnapshotSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------- capturing

    /**
     * Captures a player without storing anything.
     *
     * <p>The in-memory lifetime: hold the result in a field, restore it when
     * the menu closes, and let it be collected. Must be called on the thread
     * that owns the player, which an event handler already is.
     *
     * @param player the player
     * @return their state
     */
    public @NotNull Snapshot capture(@NotNull Player player) {
        return Snapshot.of(player);
    }

    /**
     * Captures a player and stores it under a context.
     *
     * <p>The capture happens on the calling thread &mdash; it must, since it
     * reads a live inventory &mdash; and the write happens in the background.
     * An existing snapshot for the same player <em>and the same context</em> is
     * replaced; one taken for a different context is untouched.
     *
     * @param player    the player
     * @param contextId why the snapshot is being taken
     * @return completes when the row is durable
     */
    public @NotNull CompletableFuture<Void> save(@NotNull Player player,
                                                 @NotNull String contextId) {
        SnapshotRow row = SnapshotRow.of(player.getUniqueId(), contextId, Snapshot.of(player),
                player.getLocation(), SnapshotRuntime.stamp());
        return store().thenCompose(repository -> repository.save(row));
    }

    /**
     * Stores a snapshot that was captured earlier.
     *
     * <p>The bridge between the two lifetimes: something held in memory becomes
     * something that survives a restart, without being re-captured from a player
     * who has since changed.
     *
     * @param uuid      whose snapshot it is
     * @param contextId why it was taken
     * @param snapshot  the state
     * @param where     where they were, or {@code null} if it does not matter
     * @return completes when the row is durable
     */
    public @NotNull CompletableFuture<Void> save(@NotNull UUID uuid, @NotNull String contextId,
                                                 @NotNull Snapshot snapshot,
                                                 @Nullable Location where) {
        SnapshotRow row = SnapshotRow.of(uuid, contextId, snapshot, where,
                SnapshotRuntime.stamp());
        return store().thenCompose(repository -> repository.save(row));
    }

    /**
     * Stores a player's state and then empties their inventory.
     *
     * <p><b>In that order, and this is the whole point of the method.</b>
     * ExyliaCommons cleared first and wrote afterwards, so a write that failed
     * &mdash; a database that had gone away, a column too small, a connection
     * pool exhausted at the exact moment fifty players joined an event &mdash;
     * left the player with neither their inventory nor a snapshot of it. Here
     * the clearing waits for the row to be durable, and happens back on the
     * thread that owns the player.
     *
     * <p>A failed write therefore leaves the player holding everything they
     * owned, which is the correct outcome: the caller's own
     * {@code thenRun(() -> giveKit(player))} never runs either, so nobody is
     * handed a kit on top of their own gear.
     *
     * <p>Only the inventory, armour and off hand are cleared. Health, hunger,
     * experience and game mode are the caller's to change if the mode calls for
     * it.
     *
     * @param player    the player
     * @param contextId why the snapshot is being taken
     * @return completes once the row is durable and the inventory is empty
     */
    public @NotNull CompletableFuture<Void> saveAndClear(@NotNull Player player,
                                                         @NotNull String contextId) {
        return save(player, contextId).thenCompose(ignored -> {
            CompletableFuture<Void> cleared = new CompletableFuture<>();
            // Back on the player's own thread: an inventory cannot be touched
            // from the one the write completed on. If they left in the
            // meantime, there is nothing to clear and the snapshot is safe.
            tasks.runAtEntity(player,
                    () -> {
                        PlayerState.clear(player);
                        cleared.complete(null);
                    },
                    () -> cleared.complete(null));
            return cleared;
        });
    }

    // ------------------------------------------------------------- restoring

    /**
     * Restores a player from a stored snapshot and removes the row.
     *
     * <p>The row is deleted only after the player has actually been restored,
     * so a player who leaves mid-restore keeps their snapshot and gets it on
     * their next join. Nothing happens at all if there is no such row, which is
     * why this doubles as the "restore if there is anything to restore" call
     * every quit and join handler wants.
     *
     * @param player    the player
     * @param contextId which snapshot
     * @return whether there was one to restore
     */
    public @NotNull CompletableFuture<Boolean> restore(@NotNull Player player,
                                                       @NotNull String contextId) {
        return restore(player, contextId, null, SnapshotPart.ALL);
    }

    /**
     * The same, told where the player was when the snapshot was taken.
     *
     * <p>The location is handed to the callback rather than applied, because
     * where a player goes after a round is the game's decision: a lobby, a
     * spawn, or exactly where they were. It runs on the player's own thread.
     * The callback is not called when there was no snapshot.
     *
     * @param player    the player
     * @param contextId which snapshot
     * @param wentBack  told where they were, or {@code null} to ignore it
     * @return whether there was one to restore
     */
    public @NotNull CompletableFuture<Boolean> restore(@NotNull Player player,
                                                       @NotNull String contextId,
                                                       @Nullable Consumer<Location> wentBack) {
        return restore(player, contextId, wentBack, SnapshotPart.ALL);
    }

    /**
     * The same, restoring only the parts named.
     *
     * <p>The row is still removed: a partial restore is a decision about what to
     * put back, not about whether the snapshot has been used. A caller that
     * wants to keep it reads it with {@link #find} and applies it by hand.
     *
     * @param player    the player
     * @param contextId which snapshot
     * @param wentBack  told where they were, or {@code null} to ignore it
     * @param parts     which parts to put back
     * @return whether there was one to restore
     */
    public @NotNull CompletableFuture<Boolean> restore(@NotNull Player player,
                                                       @NotNull String contextId,
                                                       @Nullable Consumer<Location> wentBack,
                                                       @NotNull Set<SnapshotPart> parts) {
        UUID uuid = player.getUniqueId();
        return store().thenCompose(repository -> repository.find(SnapshotRow.key(uuid, contextId))
                .thenCompose(found -> {
                    if (found.isEmpty() || LegacyImport.isMarker(found)) {
                        return CompletableFuture.completedFuture(false);
                    }
                    SnapshotRow row = found.get();
                    Snapshot snapshot = row.snapshot();
                    if (snapshot == null) {
                        // Unreadable rather than absent. Already reported by the
                        // codec; keeping the row means a fixed server can still
                        // read it, and deleting it here would destroy the only
                        // copy of somebody's inventory to tidy up a log line.
                        return CompletableFuture.completedFuture(false);
                    }
                    CompletableFuture<Boolean> applied = new CompletableFuture<>();
                    tasks.runAtEntity(player,
                            () -> {
                                if (!player.isOnline()) {
                                    applied.complete(false);
                                    return;
                                }
                                PlayerState.apply(snapshot, player, parts, SnapshotRuntime::report);
                                if (wentBack != null && row.lastLocation() != null) {
                                    wentBack.accept(row.lastLocation());
                                }
                                applied.complete(true);
                            },
                            // The player went away between the read and the
                            // apply. Their snapshot stays where it is.
                            () -> applied.complete(false));
                    return applied.thenCompose(restored -> restored
                            ? repository.delete(row.key()).thenApply(ignored -> true)
                            : CompletableFuture.completedFuture(false));
                }));
    }

    /**
     * Restores a player from every snapshot they have, whatever the context.
     *
     * <p>What a join handler wants: a player who was in an arena when the server
     * died has one row, does not know it, and should simply get their things
     * back. Contexts are applied oldest first, so the snapshot taken before all
     * the others is the state they end up in.
     *
     * @param player   the player
     * @param wentBack told where they were by the oldest snapshot, or {@code null}
     * @return how many snapshots were restored
     */
    public @NotNull CompletableFuture<Integer> restoreAll(@NotNull Player player,
                                                          @Nullable Consumer<Location> wentBack) {
        UUID uuid = player.getUniqueId();
        return contexts(uuid).thenCompose(rows -> {
            if (rows.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            // Newest first, oldest last. The earliest snapshot describes the
            // player before any of this started, so it has to be applied last
            // and win; the list arrives newest first, so it is walked forwards.
            // The other order would leave the player in the state they were in
            // on their way *into* the deepest context, holding a kit.
            List<String> newestFirst = new ArrayList<>(rows);
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            for (int index = 0; index < newestFirst.size(); index++) {
                String contextId = newestFirst.get(index);
                // The oldest snapshot is the one that says where they actually
                // were, which is what the caller is asking to be told.
                boolean oldest = index == newestFirst.size() - 1;
                chain = chain.thenCompose(count -> restore(player, contextId,
                        oldest ? wentBack : null, SnapshotPart.ALL)
                        .thenApply(restored -> restored ? count + 1 : count));
            }
            return chain;
        });
    }

    /**
     * Hands a leaving player's snapshot to whoever can still use it.
     *
     * <p>The answer to {@code restoreSync}, which ExyliaCommons offered for a
     * plugin shutting down or a player quitting and which blocked the main
     * thread on a database read and a delete. There is no synchronous form
     * here, and there should not be: a shutdown that waits on a database is a
     * shutdown that hangs when the database is the thing that went wrong.
     *
     * <p>What actually happens instead is nothing, and that is the point. The
     * snapshot is already durable &mdash; it was written when the player entered
     * the context &mdash; so a player who is disconnecting or whose server is
     * stopping needs no work at all: the row is still there, and the next time
     * they join, {@link #restore} or {@link #restoreAll} gives it back. The
     * blocking call existed to do work that the database had already done.
     *
     * <p>A caller that used {@code restoreSync} to move the player somewhere
     * before they left should stop: teleporting a player during
     * {@code PlayerQuitEvent} does nothing, and teleporting one during
     * {@code onDisable} races the server's own save. This returns their stored
     * location so the caller can decide, and touches nothing.
     *
     * @param uuid      the player
     * @param contextId which snapshot
     * @return where they were, when there is a snapshot to come back to
     */
    public @NotNull CompletableFuture<Optional<Location>> pending(@NotNull UUID uuid,
                                                                  @NotNull String contextId) {
        return store().thenCompose(repository ->
                repository.find(SnapshotRow.key(uuid, contextId))
                        .thenApply(found -> found
                                .filter(row -> !LegacyImport.isMarker(row))
                                .map(SnapshotRow::lastLocation)));
    }

    // ---------------------------------------------------------------- reading

    /**
     * Reads a stored snapshot without restoring or removing it.
     *
     * @param uuid      the player
     * @param contextId which snapshot
     * @return the snapshot, or empty when there is none
     */
    public @NotNull CompletableFuture<Optional<Snapshot>> find(@NotNull UUID uuid,
                                                               @NotNull String contextId) {
        return store().thenCompose(repository ->
                repository.find(SnapshotRow.key(uuid, contextId))
                        .thenApply(found -> found
                                .filter(row -> !LegacyImport.isMarker(row))
                                .map(SnapshotRow::snapshot)));
    }

    /**
     * Whether a player has a snapshot in a context.
     *
     * @param uuid      the player
     * @param contextId which snapshot
     * @return whether there is one
     */
    public @NotNull CompletableFuture<Boolean> has(@NotNull UUID uuid,
                                                   @NotNull String contextId) {
        return store().thenCompose(repository ->
                repository.exists(SnapshotRow.key(uuid, contextId)));
    }

    /**
     * Every context a player has a snapshot in, newest first.
     *
     * @param uuid the player
     * @return the context ids
     */
    public @NotNull CompletableFuture<List<String>> contexts(@NotNull UUID uuid) {
        return store().thenCompose(repository -> repository
                .where("uuid", uuid)
                .orderByDescending("savedAt")
                .find()
                .thenApply(rows -> {
                    List<String> contexts = new ArrayList<>(rows.size());
                    for (SnapshotRow row : rows) {
                        if (!LegacyImport.isMarker(row)) {
                            contexts.add(row.contextId());
                        }
                    }
                    return List.copyOf(contexts);
                }));
    }

    /**
     * Removes a stored snapshot without restoring it.
     *
     * <p>What a plugin calls when the reason for the snapshot went away rather
     * than ended &mdash; an arena deleted, a mode turned off. It throws away
     * somebody's inventory, so it is spelled as its own method rather than
     * being a flag on a restore.
     *
     * @param uuid      the player
     * @param contextId which snapshot
     * @return whether there was one
     */
    public @NotNull CompletableFuture<Boolean> discard(@NotNull UUID uuid,
                                                       @NotNull String contextId) {
        return store().thenCompose(repository ->
                repository.delete(SnapshotRow.key(uuid, contextId)));
    }

    // ------------------------------------------------------------------ store

    /**
     * The repository, opened once, after the legacy import has been attempted.
     *
     * <p>Everything funnels through here so nothing has to remember to wait for
     * the import: the first call starts it, every call chains onto it, and a
     * failed import still yields a usable store because a server whose old
     * table cannot be read must still be able to take new snapshots.
     */
    private CompletableFuture<Repository<SnapshotRow>> store() {
        Repository<SnapshotRow> repository = rows;
        if (repository == null) {
            synchronized (lock) {
                repository = rows;
                if (repository == null) {
                    PluginDatabase database = Databases.of(plugin);
                    // Registering the codec has to happen before the model is
                    // compiled, and compiling happens inside repository().
                    SnapshotRuntime.init(plugin);
                    repository = database.repository(SnapshotRow.class);
                    rows = repository;
                    imported = settings.importLegacy()
                            ? LegacyImport.run(database, repository, debug)
                            : CompletableFuture.completedFuture(0);
                }
            }
        }
        CompletableFuture<Integer> importing = imported;
        Repository<SnapshotRow> opened = repository;
        if (importing == null) {
            return CompletableFuture.completedFuture(opened);
        }
        // The import never fails a caller: it already reported itself, and a
        // player joining an arena should not be refused because a two-year-old
        // table could not be read.
        return importing.handle((moved, failure) -> opened);
    }

    /**
     * Forgets this plugin's repository.
     *
     * <p>Called by the library when the plugin is disabled. Nothing is written
     * and nothing is restored: every snapshot this plugin took is already a row,
     * which is exactly what a plugin being disabled needs it to be.
     */
    void release() {
        synchronized (lock) {
            rows = null;
            imported = null;
        }
    }

    @Override
    public String toString() {
        return "PluginSnapshots[" + plugin.getName() + ']';
    }
}
