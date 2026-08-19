package net.exylia.lib.util.teleport;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * A teleport that has been started.
 *
 * <pre>{@code
 * TeleportHandle handle = teleports.to(player, spawn)
 *         .warmup(3.0)
 *         .then(result -> {
 *             if (result.isCancelled()) player.sendMessage("Teleport cancelled.");
 *         })
 *         .start();
 *
 * // The player did something that should call it off.
 * handle.cancel();
 * }</pre>
 *
 * <p>Every handle completes exactly once, whatever happens to it: the player
 * arriving, the countdown being cancelled, the player leaving, the plugin being
 * disabled. Whoever gets there first wins and the rest are ignored, which is
 * what makes {@link #cancel()} safe to call from anywhere and twice.
 *
 * <p>A teleport with no countdown is already finished by the time the handle
 * exists in most cases; {@link #cancel()} on it does nothing, because there is
 * nothing left to call off.
 *
 * @since 1.34.0
 */
public interface TeleportHandle {

    /**
     * How it ended.
     *
     * <p>Never completes exceptionally: a teleport that failed reports
     * {@link TeleportResult#FAILED} and the console says why. A caller should
     * not have to write a {@code exceptionally} branch to find out that a world
     * was missing.
     *
     * @return the future
     */
    @NotNull CompletableFuture<TeleportResult> future();

    /**
     * Calls it off.
     *
     * <p>Safe from any thread and safe to call twice. A countdown that had
     * started a cooldown gives it back: the player never got the teleport, and
     * charging them for one they did not receive is a bug.
     */
    void cancel();

    /** Whether it has already ended, however it ended. */
    boolean isDone();

    /** Who is being teleported. */
    @NotNull Player player();

    /** Why. */
    @NotNull TeleportCause cause();

    /**
     * How much of the countdown is left, in seconds.
     *
     * <p>Zero for a teleport with no countdown, and zero once it has finished
     * or been cancelled.
     *
     * @return the seconds remaining, decimals included
     */
    double remainingWarmupSeconds();
}
