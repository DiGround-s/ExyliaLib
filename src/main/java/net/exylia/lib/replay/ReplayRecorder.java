package net.exylia.lib.replay;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A recording that is currently running.
 *
 * <pre>{@code
 * ReplayRecorder recorder = replays.record(arena.spawn());
 * recorder.follow(red);
 * recorder.follow(blue);
 *
 * // ... the duel happens ...
 *
 * Replay replay = recorder.stop();
 * repository.save(duelId, replay.toBytes());
 * }</pre>
 *
 * <h2>What it takes by itself</h2>
 * Every tick, for everybody being followed: where they are, which way they are
 * looking, how they are standing, whether they are sprinting and on the ground,
 * and how much health they have left. It also watches the six equipment slots
 * and writes a mark whenever one of them actually changes, so a sword being put
 * away for a pearl is in the recording without a listener for it. Swings and
 * hits are read off the server's own events for the same reason.
 *
 * <p>Everything else is the plugin's, through {@link #mark}: who won, when a
 * round started, a combo counter, whatever that plugin wants to draw on top of
 * the playback later.
 *
 * <h2>It has to be stopped</h2>
 * A recording holds arrays that grow for as long as it runs. {@link #stop()}
 * gives back what was recorded and {@link #cancel()} throws it away, and one of
 * the two has to happen. One that is forgotten stops itself at half an hour
 * rather than growing forever, and one whose plugin is disabled is cancelled.
 *
 * @since 1.175.0
 */
public interface ReplayRecorder {

    /**
     * Starts recording somebody.
     *
     * <p>Their identity, including the skin they are wearing, is read now. The
     * ticks before this are theirs and empty: somebody followed a minute in
     * simply is not there for the first minute.
     *
     * <p>Following the same player twice does nothing the second time.
     *
     * @param player who to record
     */
    void follow(@NotNull Player player);

    /**
     * Stops recording somebody, leaving what was already recorded of them.
     *
     * <p>Not needed when they quit or die &mdash; a player who is gone is
     * recorded as not being there, and starts being recorded again by himself
     * if he comes back.
     *
     * @param player who to stop recording
     */
    void forget(@NotNull Player player);

    /**
     * Writes something down at this moment.
     *
     * @param kind  what happened; a plugin's own name, or one of the constants
     *              on {@link ReplayMark}
     * @param actor who it happened to, or {@code null} when it belongs to
     *              nobody in particular
     * @param data  whatever the kind carries, or {@code null}
     */
    void mark(@NotNull String kind, @Nullable Player actor, byte @Nullable [] data);

    /**
     * The same, with a line of text behind it.
     *
     * @param kind  what happened
     * @param actor who it happened to, or {@code null}
     * @param text  the line, read back with {@link ReplayMark#text()}
     */
    void mark(@NotNull String kind, @Nullable Player actor, @NotNull String text);

    /** Which tick it is on, from zero. */
    int tick();

    /** Whether it is still running. */
    boolean isRecording();

    /**
     * Ends it and gives back what was recorded.
     *
     * <p>Safe to call twice: the second time gives back the same recording.
     *
     * @return the recording, which is now detached from this server
     */
    @NotNull Replay stop();

    /**
     * Ends it and throws away what was recorded.
     *
     * <p>For the duel that was cancelled, the round that never started, the
     * match a restart interrupted. Nothing is kept and nothing is written.
     */
    void cancel();
}
