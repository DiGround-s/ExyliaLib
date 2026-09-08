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
import net.exylia.lib.util.teleport.ExyliaLocation;
import net.exylia.lib.util.teleport.PluginTeleports;
import net.exylia.lib.util.teleport.TeleportCause;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

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
                teleports().here(player), SnapshotRuntime.stamp());
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
        SnapshotRow row = SnapshotRow.of(uuid, contextId, snapshot,
                where == null ? null : teleports().here(where), SnapshotRuntime.stamp());
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
     * <h2>When the player is about to change world</h2>
     * Clear them on the far side of the teleport, not this one. A per-world
     * inventory plugin writes whatever a player is holding into the profile of
     * the world they are leaving, so a player emptied here arrives having
     * donated an empty inventory to the world they came from, and gets that
     * emptiness back when they return. {@link #save} and then {@link #clear}
     * once they have arrived is the same two steps in the order that survives
     * it.
     *
     * @param player    the player
     * @param contextId why the snapshot is being taken
     * @return completes once the row is durable and the inventory is empty
     */
    public @NotNull CompletableFuture<Void> saveAndClear(@NotNull Player player,
                                                         @NotNull String contextId) {
        return save(player, contextId).thenCompose(ignored -> clear(player));
    }

    /**
     * Empties a player's inventory, armour, off hand and cursor.
     *
     * <p>The second half of {@link #saveAndClear}, on its own, for a caller
     * that has to put a teleport between the two halves. It stores nothing and
     * checks nothing: whoever calls it has already made the state durable, or
     * has decided it does not need to be.
     *
     * <p>Runs on the thread that owns the player, whichever thread it is called
     * from, and does nothing at all if they have left.
     *
     * @param player the player
     * @return completes once the inventory is empty
     * @since 1.118.0
     */
    public @NotNull CompletableFuture<Void> clear(@NotNull Player player) {
        CompletableFuture<Void> cleared = new CompletableFuture<>();
        // Back on the player's own thread: an inventory cannot be touched from
        // the one a write completed on. If they left in the meantime, there is
        // nothing to clear and the snapshot is safe.
        tasks.runAtEntity(player,
                () -> {
                    PlayerState.clear(player);
                    cleared.complete(null);
                },
                () -> cleared.complete(null));
        return cleared;
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
     * <h2>When the way back crosses a world</h2>
     * This restores first and tells the caller afterwards, so the player is
     * dressed in the world they are leaving and moved out of it after. That is
     * the wrong order next to a per-world inventory plugin &mdash;
     * Multiverse-Inventories, PerWorldInventory &mdash; which on the world
     * change writes whatever the player is holding into the profile of the
     * world they left and then loads the profile of the world they entered over
     * the top. The restore becomes the second-to-last write and loses.
     * {@link #returnAndRestore(Player, String, Function, Set)} is the same call
     * in the order that survives it: move, then restore.
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
                    SnapshotRow row = readable(found);
                    if (row == null) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return applyAndDelete(player, repository, row, parts, wentBack);
                }));
    }

    /**
     * Puts the player back where they were and restores them once they are
     * there, in that order.
     *
     * <p>Same work as {@link #restore(Player, String, Consumer, Set)} and the
     * opposite order: the way home runs first and the snapshot is applied when
     * it has finished. Which is the order to use whenever home may be another
     * world, because a per-world inventory plugin swaps the player's things on
     * the world change and everything written before that swap is thrown away
     * by it. Restoring after the move makes this library the last writer, which
     * is the only position that wins.
     *
     * <p>{@code goHome} is handed the stored location, runs on the player's own
     * thread and answers with a future that completes when the player has
     * arrived &mdash; which is exactly what {@code teleports().to(...).then(...)}
     * already reports. Where the player actually goes is still the game's
     * decision: a lobby, a spawn, or the stored spot itself. A {@code null}
     * {@code goHome}, a snapshot with no stored place, or a place on another
     * server means nothing is moved and the snapshot is applied where the
     * player stands.
     *
     * <p>The row is deleted only after the snapshot has been applied, so a
     * player who disconnects mid-flight keeps it and gets it on their next
     * join.
     *
     * @param player    the player
     * @param contextId which snapshot
     * @param goHome    moves the player and answers when they have arrived, or
     *                  {@code null} to restore them where they stand
     * @param parts     which parts to put back
     * @return whether there was one to restore
     * @since 1.118.0
     */
    public @NotNull CompletableFuture<Boolean> returnAndRestore(@NotNull Player player,
                                                                @NotNull String contextId,
                                                                @Nullable Function<Location, CompletableFuture<?>> goHome,
                                                                @NotNull Set<SnapshotPart> parts) {
        UUID uuid = player.getUniqueId();
        return store().thenCompose(repository -> repository.find(SnapshotRow.key(uuid, contextId))
                .thenCompose(found -> {
                    SnapshotRow row = readable(found);
                    if (row == null) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return goneHome(player, liveHere(row.lastLocation()), goHome)
                            .thenCompose(ignored -> applyAndDelete(player, repository, row, parts, null));
                }));
    }

    /**
     * The same, putting every part back.
     *
     * @param player    the player
     * @param contextId which snapshot
     * @param goHome    moves the player and answers when they have arrived, or
     *                  {@code null} to restore them where they stand
     * @return whether there was one to restore
     * @since 1.118.0
     */
    public @NotNull CompletableFuture<Boolean> returnAndRestore(@NotNull Player player,
                                                                @NotNull String contextId,
                                                                @Nullable Function<Location, CompletableFuture<?>> goHome) {
        return returnAndRestore(player, contextId, goHome, SnapshotPart.ALL);
    }

    /**
     * The readable snapshot in a lookup, or {@code null} when there is nothing
     * to restore from.
     *
     * <p>Absent, a migration marker, and present but unreadable all mean the
     * same thing to a caller and are told apart nowhere else. An unreadable row
     * is kept rather than deleted: the codec has already reported it, a fixed
     * server can still read it, and deleting it here would destroy the only
     * copy of somebody's inventory to tidy up a log line.
     */
    private @Nullable SnapshotRow readable(@NotNull Optional<SnapshotRow> found) {
        if (found.isEmpty() || LegacyImport.isMarker(found)) {
            return null;
        }
        SnapshotRow row = found.get();
        return row.snapshot() == null ? null : row;
    }

    /**
     * Applies a row on the player's own thread and removes it once it is on.
     *
     * <p>The row is deleted only after the player has actually been restored,
     * so a player who leaves mid-restore keeps their snapshot.
     */
    private @NotNull CompletableFuture<Boolean> applyAndDelete(@NotNull Player player,
                                                               @NotNull Repository<SnapshotRow> repository,
                                                               @NotNull SnapshotRow row,
                                                               @NotNull Set<SnapshotPart> parts,
                                                               @Nullable Consumer<Location> wentBack) {
        Snapshot snapshot = Objects.requireNonNull(row.snapshot(), "snapshot");
        CompletableFuture<Boolean> applied = new CompletableFuture<>();
        tasks.runAtEntity(player,
                () -> {
                    if (!player.isOnline()) {
                        applied.complete(false);
                        return;
                    }
                    PlayerState.apply(snapshot, player, parts, SnapshotRuntime::report);
                    Location back = liveHere(row.lastLocation());
                    if (wentBack != null && back != null) {
                        wentBack.accept(back);
                    }
                    applied.complete(true);
                },
                // The player went away between the read and the apply. Their
                // snapshot stays where it is.
                () -> applied.complete(false));
        return applied.thenCompose(restored -> restored
                ? repository.delete(row.key()).thenApply(ignored -> true)
                : CompletableFuture.completedFuture(false));
    }

    /**
     * Runs a caller's way home and answers when it is over, however it went.
     *
     * <p>Never fails and never hangs on the caller's behalf: a mover that threw,
     * answered {@code null} or failed still lets the restore happen, because a
     * player who could not be moved must still get their things back. Without
     * that, a teleport refused by another plugin would strand the snapshot in
     * the table and the player in a kit.
     */
    private @NotNull CompletableFuture<?> goneHome(@NotNull Player player,
                                                   @Nullable Location back,
                                                   @Nullable Function<Location, CompletableFuture<?>> goHome) {
        if (back == null || goHome == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Object> arrived = new CompletableFuture<>();
        tasks.runAtEntity(player,
                () -> {
                    if (!player.isOnline()) {
                        arrived.complete(null);
                        return;
                    }
                    CompletableFuture<?> moving;
                    try {
                        moving = goHome.apply(back);
                    } catch (RuntimeException failure) {
                        debug.error("A snapshot's way home threw; restoring where they stand", failure);
                        arrived.complete(null);
                        return;
                    }
                    if (moving == null) {
                        arrived.complete(null);
                        return;
                    }
                    moving.whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            debug.error("A snapshot's way home failed; restoring where they stand", failure);
                        }
                        arrived.complete(null);
                    });
                },
                () -> arrived.complete(null));
        return arrived;
    }

    /**
     * Restores the parts named and puts the player back where they were, on
     * whichever server that was.
     *
     * <p>The one to use when "back" may be another server of the network: a
     * staff member who went on duty in the lobby and comes off it in an arena,
     * a match that started elsewhere. On the same server it is a plain
     * teleport; elsewhere it is a handover through the proxy, with the exact
     * spot carried along. {@link #restore(Player, String, Consumer, Set)} only
     * knows how to hand back a location on this server.
     *
     * <p>On this server the teleport happens <em>first</em> and the snapshot is
     * applied on arrival, so a per-world inventory plugin cannot swap the
     * player's things out from under the restore on the way across. The name is
     * the old one and the order is the safe one; a handover to another server
     * still restores first, because the player leaves before there is anything
     * to arrive at.
     *
     * @param player    the player
     * @param contextId which snapshot
     * @param parts     which parts to put back
     * @return whether there was one to restore; the teleport's own result is
     *         reported by the teleport module
     * @since 1.109.0
     */
    public @NotNull CompletableFuture<Boolean> restoreAndReturn(@NotNull Player player,
                                                                @NotNull String contextId,
                                                                @NotNull Set<SnapshotPart> parts) {
        UUID uuid = player.getUniqueId();
        return store().thenCompose(repository -> repository.find(SnapshotRow.key(uuid, contextId))
                .thenCompose(found -> {
                    ExyliaLocation back = found.filter(row -> !LegacyImport.isMarker(row))
                            .map(SnapshotRow::lastLocation).orElse(null);
                    if (back != null && !back.isSameServer(teleports().serverId())) {
                        // A handover, not a teleport: the player stops existing
                        // here the moment it starts, so there is no "on arrival"
                        // on this server to restore in.
                        return restore(player, contextId, null, parts).thenApply(restored -> {
                            if (restored) {
                                teleports().to(player, back).cause(TeleportCause.PLUGIN).start();
                            }
                            return restored;
                        });
                    }
                    return returnAndRestore(player, contextId,
                            here -> moved(player, here), parts);
                }));
    }

    /**
     * Moves a player with this plugin's teleport and answers when it is over.
     *
     * <p>The way home {@link #restoreAndReturn} hands to
     * {@link #returnAndRestore}, and the shape any caller writing their own
     * ends up with.
     */
    private @NotNull CompletableFuture<?> moved(@NotNull Player player, @NotNull Location destination) {
        CompletableFuture<Object> arrived = new CompletableFuture<>();
        teleports().to(player, destination)
                .cause(TeleportCause.PLUGIN)
                .then(result -> arrived.complete(null))
                .start();
        return arrived;
    }

    /**
     * Where a player was when a snapshot was taken, as a place that knows
     * its server.
     *
     * <p>{@link #pending} answers with a live location only when that place
     * is on this server; this answers wherever it is.
     *
     * @param uuid      the player
     * @param contextId which snapshot
     * @return the place, when there is a snapshot to come back to
     * @since 1.109.0
     */
    public @NotNull CompletableFuture<Optional<ExyliaLocation>> pendingPlace(@NotNull UUID uuid,
                                                                             @NotNull String contextId) {
        return store().thenCompose(repository ->
                repository.find(SnapshotRow.key(uuid, contextId))
                        .thenApply(found -> found
                                .filter(row -> !LegacyImport.isMarker(row))
                                .map(SnapshotRow::lastLocation)));
    }

    /**
     * Restores a player from every snapshot they have, whatever the context.
     *
     * <p>What a join handler wants: a player who was in an arena when the server
     * died has one row, does not know it, and should simply get their things
     * back. Contexts are applied oldest first, so the snapshot taken before all
     * the others is the state they end up in.
     *
     * <p>Restores first and reports the location after, for the same reason and
     * with the same cost as {@link #restore(Player, String, Consumer, Set)}:
     * next to a per-world inventory plugin, a way home that crosses a world
     * undoes it. {@link #returnAndRestoreAll} is the order that survives that.
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
     * Puts the player back where the oldest snapshot says they were and then
     * restores every snapshot they have, in that order.
     *
     * <p>What a join handler wants when home is another world. A player who was
     * in an event arena when the server died logs back into that arena; moving
     * them out of it and restoring them there is the only order a per-world
     * inventory plugin does not undo, because the swap it does on the world
     * change happens before the restore rather than after it.
     *
     * <p>The move runs once, before anything is applied, and is given the place
     * the oldest snapshot remembers &mdash; the spot the player was in before
     * any of these contexts started. The snapshots are then applied newest
     * first, so the oldest is applied last and wins, exactly as
     * {@link #restoreAll} does.
     *
     * @param player the player
     * @param goHome moves the player and answers when they have arrived, or
     *               {@code null} to restore them where they stand
     * @return how many snapshots were restored
     * @since 1.118.0
     */
    public @NotNull CompletableFuture<Integer> returnAndRestoreAll(@NotNull Player player,
                                                                   @Nullable Function<Location, CompletableFuture<?>> goHome) {
        UUID uuid = player.getUniqueId();
        return contexts(uuid).thenCompose(rows -> {
            if (rows.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            List<String> newestFirst = new ArrayList<>(rows);
            String oldest = newestFirst.get(newestFirst.size() - 1);
            return pending(uuid, oldest).thenCompose(place ->
                    goneHome(player, place.orElse(null), goHome).thenCompose(ignored -> {
                        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
                        for (String contextId : newestFirst) {
                            chain = chain.thenCompose(count -> restore(player, contextId, null, SnapshotPart.ALL)
                                    .thenApply(restored -> restored ? count + 1 : count));
                        }
                        return chain;
                    }));
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
     * location so the caller can decide, and touches nothing. Empty when the
     * place is on another server; {@link #pendingPlace} answers there too.
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
                                .map(row -> liveHere(row.lastLocation()))));
    }

    /** A stored place as a live location, only when it is on this server and its world is loaded. */
    private @Nullable Location liveHere(@Nullable ExyliaLocation place) {
        if (place == null || !place.isSameServer(teleports().serverId())) {
            return null;
        }
        return place.toBukkitLocation();
    }

    private PluginTeleports teleports() {
        return Teleports.of(plugin);
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
    /**
     * Returns whether this store belongs to the given load of its plugin.
     *
     * <p>Identity, not {@code equals}: that one is final on {@code Plugin} and
     * compares names, which is what two loads of the same plugin share.
     */
    boolean ownedBy(Plugin other) {
        return plugin == other;
    }

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
