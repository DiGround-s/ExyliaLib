package net.exylia.lib.replay;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * A recording that is currently being watched.
 *
 * <pre>{@code
 * ReplayPlayback watching = replays.play(replay, arena.spawn(), List.of(viewer));
 * watching.speed(0.25);
 * watching.onEnd(() -> lobby.send(viewer));
 * }</pre>
 *
 * <h2>Only the people watching it see it</h2>
 * The bodies are packets, so a playback exists on the screens it was given and
 * nowhere else. Two players can watch two different recordings standing in the
 * same arena, and neither can be hit by what the other is looking at.
 *
 * <h2>It has to be stopped</h2>
 * A body nobody takes away stands there wearing somebody's name until that
 * client relogs. The module takes its own away when the recording ends, when
 * the last viewer leaves, when the plugin is disabled and when the server
 * stops &mdash; but a playback that is being replaced by another should be
 * stopped rather than dropped.
 *
 * @since 1.175.0
 */
public interface ReplayPlayback {

    /** Holds it where it is. The bodies stay, frozen on the current frame. */
    void pause();

    /** Starts it going again. */
    void resume();

    /** Whether it is held. */
    boolean isPaused();

    /**
     * How fast it runs.
     *
     * <p>A quarter is the useful one: it is what turns "he hit me through the
     * wall" into a frame where the hit either landed or did not. Above one the
     * client is being sent fewer frames than it draws, so it interpolates
     * between them &mdash; it stays smooth, it simply skips.
     *
     * @param multiplier between a sixteenth and eight; one is real time
     */
    void speed(double multiplier);

    /** How fast it is running. */
    double speed();

    /**
     * Jumps to a tick.
     *
     * <p>Backwards as freely as forwards: what everybody is wearing is rebuilt
     * from the marks up to that point, so a seek to the middle of a fight puts
     * the right sword in the right hand rather than whatever was last drawn.
     *
     * @param tick which tick, clamped into the recording
     */
    void seek(int tick);

    /** Which tick it is showing. */
    int tick();

    /** How many ticks long the recording is. */
    int frames();

    /**
     * Whether it starts again when it reaches the end.
     *
     * @param loop whether to loop
     */
    void loop(boolean loop);

    /**
     * Where somebody is right now, in the world the playback is in.
     *
     * <p>This is what a first-person camera reads: teleport the viewer here
     * every tick and they are looking out of the recording rather than at it.
     *
     * @param actor whose
     * @return their location, or {@code null} when they are not there on this
     *         tick
     */
    @Nullable Location locationOf(@NotNull UUID actor);

    /**
     * Called for every mark the playback passes that it does not draw itself.
     *
     * <p>Swings, hits, deaths and equipment changes are handled by the module
     * and never arrive here. Everything a plugin wrote does.
     *
     * @param listener what to call, on the server's main thread
     */
    void onMark(@NotNull Consumer<ReplayMark> listener);

    /**
     * Called once when the recording reaches its end, unless it is looping.
     *
     * @param listener what to call, on the server's main thread
     */
    void onEnd(@NotNull Runnable listener);

    /**
     * Whether the replay makes any noise.
     *
     * <p>On by default, and worth leaving on. A recording holds no audio, but
     * a hit, a block breaking and a blast are already in it as marks, and every
     * client already knows what those sound like. Played back in silence a
     * replay reads as broken rather than as quiet.
     *
     * @param audible whether to play sounds and particles
     * @since 1.178.0
     */
    void sounds(boolean audible);

    /** Whether it is making any noise. */
    boolean sounds();

    /** Takes the bodies away and ends it. Safe to call twice. */
    void stop();

    /** Whether it is still going. */
    boolean isPlaying();
}
