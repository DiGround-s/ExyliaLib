package net.exylia.lib.replay;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A recorder that is always running, and keeps only the last stretch.
 *
 * <p>Nothing has to be started when something happens, because by then it has
 * already happened. The black box keeps the last minute or so of everything
 * around every player &mdash; where they were, what they held, who hit whom,
 * every block that changed &mdash; and a capture cuts a recording out of it
 * after the fact:
 *
 * <pre>{@code
 * ReplayBlackBox box = Replays.of(this).blackBox(BlackBoxSettings.defaults());
 *
 * // in a death listener
 * box.capture(event.getEntity()).thenAccept(replay -> store.save(replay.toBytes(chunks)));
 * }</pre>
 *
 * <h2>It follows the person, not a place</h2>
 * A capture of a player covers everything within the radius of anywhere they
 * were during the window, so a chase across the map, a pearl or an elytra
 * flight is all in it. A teleport too far to bridge, or a change of world, cuts
 * the recording into scenes, and the playback cuts between them the way a
 * broadcast cuts between cameras.
 *
 * <h2>And the ground they were on</h2>
 * With terrain on, a capture keeps every chunk section along the way as it was
 * when the window started: read now, then walked back through every change the
 * box saw since. Played back on a stage, it does not matter whether the place
 * has been rebuilt, griefed or is on another server entirely.
 *
 * <h2>What it costs</h2>
 * A frame per player per tick, and one per other entity only when it moves.
 * Nothing is written anywhere until a capture is asked for. One box serves the
 * whole server: two plugins that ask for one share it, each keeping at least as
 * much as it asked for.
 *
 * @since 1.241.0
 */
public interface ReplayBlackBox {

    /**
     * Cuts the default window out around a player.
     *
     * @param focus who the recording is about
     * @return the recording, completed off the server's thread
     */
    @NotNull CompletableFuture<Replay> capture(@NotNull Player focus);

    /**
     * Cuts a window out around a player, who need not be online any more.
     *
     * @param focus   who
     * @param seconds how far back, at most what the box keeps
     * @return the recording; completed exceptionally when the box has nothing of
     *         them in that window
     */
    @NotNull CompletableFuture<Replay> capture(@NotNull UUID focus, int seconds);

    /**
     * Cuts a window out around a player that carries on for a few seconds
     * after now.
     *
     * <p>For a death: the body falling, turning red and vanishing is the
     * second after the killing blow, and a replay cut on the blow ends before
     * anybody can see it. The player is followed up to now; whatever happens
     * around that place in the seconds after is in it too, and a respawn
     * somewhere else is not.
     *
     * @param focus        who
     * @param seconds      how far back from now
     * @param afterSeconds how long to carry on; the future completes after it
     * @return the recording
     */
    @NotNull CompletableFuture<Replay> capture(@NotNull UUID focus, int seconds, int afterSeconds);

    /**
     * Cuts a window out around a place rather than a person.
     *
     * @param center  where
     * @param seconds how far back
     * @return the recording
     */
    @NotNull CompletableFuture<Replay> capture(@NotNull Location center, int seconds);

    /**
     * Writes something down against a player, for a plugin's own reasons: a
     * freeze, a report, a flag. It travels with any capture that includes them.
     *
     * @param kind  the plugin's own name for it
     * @param actor who
     * @param text  what to carry, or {@code null}
     */
    void mark(@NotNull String kind, @NotNull Player actor, @Nullable String text);

    /** How far back a capture can reach, in seconds. */
    int seconds();

    /** Whether it is still recording. */
    boolean isRunning();

    /** Stops this plugin's use of it; the box stops when nobody uses it. */
    void close();
}
