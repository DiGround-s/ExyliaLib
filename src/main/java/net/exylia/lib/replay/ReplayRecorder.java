package net.exylia.lib.replay;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
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
 * <p>The arena and everything else in it are the plugin's to offer, because
 * only the plugin knows which of the server's events happened inside the match:
 * {@link #follow(Entity)} for an arrow, a crystal or a block of TNT,
 * {@link #block} for a block that changed, {@link #explosion} for a blast.
 * Given those, a replay of a crystal or TNT fight shows the craters appearing
 * where they appeared.
 *
 * <p>Everything else is the plugin's too, through {@link #mark}: who won, when
 * a round started, a combo counter, whatever that plugin wants to draw on top
 * of the playback later.
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
     * Starts recording something that is not a player.
     *
     * <p>An arrow, an ender pearl, a splash potion, an end crystal, a block of
     * TNT counting down. Its type and its position, tick by tick, for as long
     * as it exists &mdash; which for most of them is a couple of seconds, so a
     * recording holds only the ticks it was actually there for.
     *
     * <p>It stops being recorded by itself when it is gone, so nothing has to
     * remember to let go of an arrow.
     *
     * <p>A recording follows a few hundred things at most. Past that the newest
     * are not recorded: a replay missing some of its debris is a better failure
     * than a server running out of memory during a crystal fight.
     *
     * @param entity what to record; a player given here is followed as one
     * @since 1.176.0
     */
    void follow(@NotNull Entity entity);

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

    /**
     * Writes down that a block changed.
     *
     * <p>Placed, broken, blown up, burnt: whatever the block is now. The
     * playback shows it to the viewer alone, so the arena a replay is watched
     * in is never really changed and can be fought in a minute later.
     *
     * <p>A recording holds tens of thousands of these. Past that the arena
     * stops being recorded and the fight does not, which is the right half to
     * keep.
     *
     * <p>Both sides of the change are kept. What it became is what the
     * playback draws; what it <em>was</em> is what the playback hears and
     * shatters, because a block that turned to air took its own sound and its
     * own colour with it. Pass the old one and a break sounds like the block
     * that broke; leave it out and every break in the replay is silent.
     *
     * @param at     where, in the world the recording is being made in
     * @param became what is there now, or {@code null} for air
     * @param was    what was there a moment ago, or {@code null}
     * @since 1.176.0
     */
    void block(@NotNull Location at, @Nullable BlockData became, @Nullable BlockData was);

    /**
     * Writes down that something went off.
     *
     * <p>Only the flash, the smoke and the bang. What it took out of the arena
     * is its own {@link #block} calls, because the server decides which blocks
     * an explosion actually removes and a replay that guessed would disagree
     * with the crater everybody remembers.
     *
     * @param at    where it was centred
     * @param power how big, as the server measures it
     * @since 1.176.0
     */
    void explosion(@NotNull Location at, float power);

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
