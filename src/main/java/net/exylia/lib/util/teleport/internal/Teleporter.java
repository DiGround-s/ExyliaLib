package net.exylia.lib.util.teleport.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.teleport.ExyliaLocation;
import net.exylia.lib.util.teleport.ExyliaTeleportEvent;
import net.exylia.lib.util.teleport.TeleportCause;
import net.exylia.lib.util.teleport.TeleportResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place a player is actually moved.
 *
 * <h2>Why everything goes through here</h2>
 * A teleport has three parts that are easy to get wrong separately: it must run
 * on the thread that owns the player, it must announce itself so other plugins
 * can object, and it must answer whoever asked even when it does not happen.
 * Written once, all three are always true; written per caller, the third is the
 * one that gets forgotten and leaves a future nobody completes.
 *
 * <h2>Why there is no Folia branch</h2>
 * {@link TaskScheduler#runAtEntity} already lands on whichever thread owns the
 * player on whichever platform this is. ExyliaCommons asked
 * {@code Platform.isFolia()} first and then picked between two paths that did
 * the same thing — a branch whose only effect was two code paths to keep in
 * step.
 */
@ApiStatus.Internal
public final class Teleporter {

    /**
     * The move each player is in, so the next one waits for it.
     *
     * <p>Folia refuses a teleport while another is still handing the player
     * between regions, and it refuses it inside its own task, where nothing can
     * catch it: the whole region dies. Two plugins moving the same player at
     * once (a join that sends somebody out of a deleted world, and an event
     * restoring them) is exactly that.
     */
    private static final Map<UUID, CompletableFuture<TeleportResult>> MOVING = new ConcurrentHashMap<>();

    /**
     * Players whose join has not finished. Folia refuses to teleport them
     * ("Cannot teleport async during login"), so their moves wait a tick.
     */
    private static final Set<UUID> JOINING = ConcurrentHashMap.newKeySet();

    private Teleporter() {
        throw new AssertionError("No instances.");
    }

    /**
     * Moves a player, announcing it first.
     *
     * <p>The hop onto the player's thread happens here rather than at the call
     * site, so a caller may start a teleport from anywhere: a countdown timer,
     * an async database callback, a command.
     *
     * <h2>Why the back history is recorded here</h2>
     * This is the only place that holds both halves of the answer at once: the
     * location the player was standing in before the move, and whether the move
     * actually happened. Recording it from the countdown instead would mean
     * reading the player's location a second time, and by the time a teleport
     * reports success that reading is the destination — a {@code /back} that
     * sends the player where they already are.
     *
     * @param plugin      whoever asked, carried on the event
     * @param player      who to move
     * @param destination where to
     * @param cause       why
     * @param tasks       the asking plugin's scheduler
     * @param debug       where to report a failure
     * @param historySize how many places to remember for this player, or zero
     *                    not to remember this move at all
     * @return how it ended; never fails, and never left uncompleted
     */
    public static @NotNull CompletableFuture<TeleportResult> teleport(
            @NotNull Plugin plugin, @NotNull Player player, @NotNull Location destination,
            @NotNull TeleportCause cause, @NotNull TaskScheduler tasks, @NotNull Debug debug,
            int historySize) {

        CompletableFuture<TeleportResult> result = new CompletableFuture<>();
        if (!player.isOnline()) {
            // Asking the scheduler about a player who already left would leave
            // this future hanging: runAtEntity drops the task and the retired
            // callback below is the only thing that would complete it.
            result.complete(TeleportResult.PLAYER_LEFT);
            return result;
        }

        UUID id = player.getUniqueId();
        CompletableFuture<TeleportResult> before = MOVING.put(id, result);
        result.whenComplete((ended, failure) -> MOVING.remove(id, result));
        Runnable go = () -> {
            if (!player.isOnline()) {
                result.complete(TeleportResult.PLAYER_LEFT);
                return;
            }
            tasks.runAtEntity(player,
                    () -> move(plugin, player, destination, cause, tasks, debug, historySize, result),
                    () -> result.complete(TeleportResult.PLAYER_LEFT));
        };
        if (before == null) {
            go.run();
        } else {
            before.whenComplete((ended, failure) -> go.run());
        }
        return result;
    }

    /** Called by the library at the start of every join. */
    static void joining(@NotNull Plugin library, @NotNull Player player) {
        UUID id = player.getUniqueId();
        JOINING.add(id);
        // A quit before this runs is cleared by left().
        net.exylia.lib.task.Tasks.of(library).runAtEntityLater(player, 1L, () -> JOINING.remove(id));
    }

    /** Called by the library when a player leaves: whatever they waited on is over. */
    static void left(@NotNull UUID id) {
        JOINING.remove(id);
        CompletableFuture<TeleportResult> moving = MOVING.remove(id);
        if (moving != null) {
            moving.complete(TeleportResult.PLAYER_LEFT);
        }
    }

    /**
     * The part that runs on the player's own thread.
     *
     * <p>The event is fired here, one statement before the move, so a listener
     * reads the world as it is at the moment of the teleport rather than as it
     * was when somebody queued one.
     */
    private static void move(Plugin plugin, Player player, Location destination,
                             TeleportCause cause, TaskScheduler tasks, Debug debug, int historySize,
                             CompletableFuture<TeleportResult> result) {
        if (!player.isOnline()) {
            result.complete(TeleportResult.PLAYER_LEFT);
            return;
        }
        if (JOINING.contains(player.getUniqueId())) {
            // A quit inside this tick is answered by left(), so nothing hangs.
            tasks.runAtEntityLater(player, 1L,
                    () -> move(plugin, player, destination, cause, tasks, debug, historySize, result));
            return;
        }
        Location from = player.getLocation();
        ExyliaTeleportEvent event = new ExyliaTeleportEvent(
                player, from == null ? destination : from, destination, cause, plugin);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            result.complete(TeleportResult.CANCELLED_BY_EVENT);
            return;
        }

        // Whatever a listener redirected it to, not what we were handed.
        Location target = event.to();
        if (target.getWorld() == null) {
            debug.warn("A teleport was aimed at a location with no world; ignoring it.");
            result.complete(TeleportResult.WORLD_NOT_FOUND);
            return;
        }

        try {
            player.teleportAsync(target).whenComplete((moved, failure) -> {
                if (failure != null) {
                    debug.error("Could not teleport " + player.getName(), failure);
                    result.complete(TeleportResult.FAILED);
                    return;
                }
                if (!Boolean.TRUE.equals(moved)) {
                    result.complete(TeleportResult.FAILED);
                    return;
                }
                remember(player, from, historySize, debug);
                result.complete(TeleportResult.SUCCESS);
            });
        } catch (RuntimeException refused) {
            // A teleport can throw outright — a player leaving in the same tick,
            // a world unloaded underneath us. Reported rather than swallowed,
            // and the future is still answered so nobody waits forever.
            debug.error("Could not teleport " + player.getName(), refused);
            result.complete(TeleportResult.FAILED);
        }
    }

    /**
     * Records where the player was, for a later {@code /back}.
     *
     * <p>Only on the way out of a move that actually happened. A teleport that
     * was vetoed, refused or failed left the player exactly where they were, so
     * recording it would give them an undo for a move they never made — and
     * push the place they genuinely came from off the end of a bounded stack.
     *
     * <p>Nothing here can stop a teleport that has already happened. A place
     * with no world cannot be stored, which is not worth a line in the console
     * every time somebody is moved out of one.
     */
    private static void remember(Player player, @Nullable Location from, int historySize,
                                 Debug debug) {
        if (historySize <= 0 || from == null || from.getWorld() == null) {
            return;
        }
        try {
            BackHistory.push(player.getUniqueId(), ExyliaLocation.of(from), historySize);
        } catch (RuntimeException unstorable) {
            debug.error("Could not record where " + player.getName() + " came from", unstorable);
        }
    }
}
