package net.exylia.lib.model.internal;

import net.exylia.lib.util.internal.KeyTrack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One animation of a model: per bone, nine channels keyed over time, in the
 * numbers Blockbench writes.
 *
 * <p>Rotations are degrees in the Bedrock convention, positions pixels and
 * scales multipliers, all as offsets from the rest pose: a channel nobody keyed
 * is absent and reads as no change. {@link Poser} turns them into the game's
 * axes.
 *
 * @param name   its name, as the file has it
 * @param length how long one pass lasts, in milliseconds
 * @param loop   what happens at its end
 * @param bones  per bone index, its nine channels (rotation, position, scale;
 *               X, Y, Z each), any of them {@code null}; or a whole bone {@code null}
 * @param events what it says at given moments: the timeline's scripts and the
 *               names of the sounds and particles it plays
 */
@ApiStatus.Internal
public record Clip(String name, long length, Loop loop, @Nullable KeyTrack[][] bones, List<Event> events) {

    public static final int ROT_X = 0;
    public static final int ROT_Y = 1;
    public static final int ROT_Z = 2;
    public static final int POS_X = 3;
    public static final int POS_Y = 4;
    public static final int POS_Z = 5;
    public static final int SCALE_X = 6;
    public static final int SCALE_Y = 7;
    public static final int SCALE_Z = 8;
    public static final int CHANNELS = 9;

    /** What happens when an animation reaches its end. */
    public enum Loop {
        /** It lets go, and whatever was underneath shows again. */
        ONCE,
        /** It starts over. */
        LOOP,
        /** It stays on its last frame until something else plays. */
        HOLD
    }

    /**
     * Something an animation says at a moment.
     *
     * @param millis when
     * @param name   what: a script line, or a sound or particle's name
     */
    public record Event(long millis, String name) {
    }

    /** What a channel reads when no key moves it. */
    public static double rest(int channel) {
        return channel >= SCALE_X ? 1 : 0;
    }

    /** Whether this animation moves a bone at all. */
    public boolean moves(int bone) {
        return bone < bones.length && bones[bone] != null;
    }

    /**
     * One bone's nine channels at a moment, or {@code false} when this
     * animation leaves the bone alone.
     *
     * @param bone   which
     * @param millis how far into one pass
     * @param out    nine numbers, written only where a channel is keyed
     * @param keyed  nine flags, set where a channel is keyed
     */
    public boolean sample(int bone, long millis, double[] out, boolean[] keyed) {
        if (!moves(bone)) {
            return false;
        }
        KeyTrack[] channels = bones[bone];
        for (int channel = 0; channel < CHANNELS; channel++) {
            KeyTrack track = channels[channel];
            if (track != null) {
                out[channel] = track.value(millis);
                keyed[channel] = true;
            }
        }
        return true;
    }

    /** Where a clock that started {@code elapsed} ago is in one pass, given how it ends. */
    public long at(long elapsed) {
        if (elapsed <= 0) {
            return 0;
        }
        if (loop == Loop.LOOP && length > 0) {
            return elapsed % length;
        }
        return Math.min(elapsed, length);
    }
}
