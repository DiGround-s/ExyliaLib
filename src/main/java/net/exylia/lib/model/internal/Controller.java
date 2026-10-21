package net.exylia.lib.model.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Decides what a model plays, and blends it.
 *
 * <h2>Two layers</h2>
 * The base is what the carrier is doing with its legs: idle, walk or run,
 * picked from how fast it moves, looping, sped up and slowed down with it so
 * the feet do not slide. On top of it an action plays &mdash; an attack, a hit,
 * a cast &mdash; and only the bones the action keys are taken from it: a swing
 * that moves the arms leaves the legs walking.
 *
 * <h2>Nothing jumps</h2>
 * A new base fades in over the old one; an action fades in, and a one-shot
 * fades out as it ends, so the arms are handed back to the walk rather than
 * snapped there. A held animation stays on its last frame until something
 * replaces it.
 *
 * <p>Pure and single-threaded: the clock is handed in, so every transition can
 * be asserted without a server. One per live model, on the carrier's thread.
 */
@ApiStatus.Internal
public final class Controller {

    /** How long one base takes to hand over to the next. */
    static final long BASE_FADE_MS = 250;
    /** How long an action takes to take over the bones it moves. */
    static final long ACTION_IN_MS = 120;
    /** How long before its end a one-shot starts handing the bones back. */
    static final long ACTION_OUT_MS = 180;

    /** Blocks a second at which a still model starts to walk, and below which a walking one stops. */
    static final double WALK_FROM = 0.6;
    static final double WALK_UNTIL = 0.35;
    /** Blocks a second at which a walk becomes a run, and below which it walks again. */
    static final double RUN_FROM = 4.8;
    static final double RUN_UNTIL = 4.0;
    /** The speeds a walk and a run were animated at: calibration knobs, the feet's. */
    static final double WALK_PACE = 2.2;
    static final double RUN_PACE = 5.6;

    private final int bones;
    private final Function<String, Clip> clips;

    private String baseState = "idle";
    private @Nullable Clip base;
    private double baseHead;
    private @Nullable Clip previous;
    private double previousHead;
    private long fadeStarted = Long.MIN_VALUE;

    private @Nullable Clip action;
    private @Nullable String actionState;
    private double actionHead;
    private long actionStarted;
    private boolean dying;

    private long last = Long.MIN_VALUE;
    private double tempo = 1;
    private final List<String> fired = new ArrayList<>();

    private final double[] scratch = new double[Clip.CHANNELS];
    private final boolean[] keyed = new boolean[Clip.CHANNELS];
    private final double[] under = new double[Clip.CHANNELS];

    /**
     * @param bones how many bones the model has
     * @param clips the clip a state or animation name resolves to, or {@code null}
     */
    public Controller(int bones, Function<String, Clip> clips) {
        this.bones = bones;
        this.clips = clips;
        this.base = clips.apply("idle");
    }

    /** The base state it is in: idle, walk or run. */
    public String baseState() {
        return baseState;
    }

    /** The action under way, or {@code null}. */
    public @Nullable String action() {
        return action == null ? null : actionState;
    }

    /** Whether a death animation has been started. */
    public boolean dying() {
        return dying;
    }

    /**
     * Moves the clock on and picks the base from how fast the carrier goes.
     *
     * @param now   the clock, in milliseconds
     * @param speed horizontal speed, in blocks a second
     */
    public void advance(long now, double speed) {
        if (last == Long.MIN_VALUE) {
            last = now;
        }
        long elapsed = Math.max(0, now - last);
        last = now;
        if (!dying) {
            String next = switch (baseState) {
                case "run" -> speed < RUN_UNTIL ? (speed < WALK_UNTIL ? "idle" : "walk") : "run";
                case "walk" -> speed >= RUN_FROM ? "run" : speed < WALK_UNTIL ? "idle" : "walk";
                default -> speed >= RUN_FROM ? "run" : speed >= WALK_FROM ? "walk" : "idle";
            };
            switchBase(next, now);
            double pace = baseState.equals("run") ? RUN_PACE : WALK_PACE;
            tempo = baseState.equals("idle") ? 1 : Math.clamp(speed / pace, 0.6, 1.8);
        } else {
            tempo = 1;
        }
        baseHead = step(base, baseHead, elapsed * tempo);
        previousHead = step(previous, previousHead, elapsed * tempo);
        if (action != null) {
            double before = actionHead;
            actionHead += elapsed;
            events(action, before, actionHead);
            if (action.loop() == Clip.Loop.ONCE && actionHead >= action.length() && !dying) {
                action = null;
                actionState = null;
            }
        }
    }

    private void switchBase(String state, long now) {
        if (state.equals(baseState)) {
            return;
        }
        Clip next = clips.apply(state);
        if (next == null && state.equals("run")) {
            next = clips.apply("walk");
        }
        baseState = state;
        if (next == base) {
            return;
        }
        previous = base;
        previousHead = baseHead;
        base = next;
        baseHead = 0;
        fadeStarted = now;
    }

    private double step(@Nullable Clip clip, double head, double by) {
        if (clip == null) {
            return head;
        }
        double after = head + by;
        events(clip, head, after);
        if (clip.loop() == Clip.Loop.LOOP && clip.length() > 0) {
            return after % clip.length();
        }
        return Math.min(after, clip.length());
    }

    /** Every event a clip passes between two moments of its playhead, a loop's wrap included. */
    private void events(Clip clip, double from, double to) {
        if (clip.events().isEmpty() || to <= from) {
            return;
        }
        long length = clip.length();
        for (Clip.Event event : clip.events()) {
            long at = event.millis();
            boolean passed = clip.loop() == Clip.Loop.LOOP && length > 0
                    ? crossed(from % length, from % length + (to - from), at, length)
                    : at > from && at <= to || from == 0 && at == 0;
            if (passed) {
                fired.add(event.name());
            }
        }
    }

    private static boolean crossed(double from, double to, long at, long length) {
        for (double cycle = 0; cycle <= to; cycle += length) {
            double moment = cycle + at;
            if (moment > from && moment <= to || from == 0 && moment == 0) {
                return true;
            }
        }
        return false;
    }

    /** The events fired since the last time this was asked, oldest first. */
    public List<String> drainEvents() {
        if (fired.isEmpty()) {
            return List.of();
        }
        List<String> copy = List.copyOf(fired);
        fired.clear();
        return copy;
    }

    /**
     * Starts an action.
     *
     * @param state a state or an animation's name
     * @param now   the clock
     * @return whether there was an animation to play
     */
    public boolean play(String state, long now) {
        if (dying) {
            return false;
        }
        Clip clip = clips.apply(state);
        if (clip == null) {
            return false;
        }
        action = clip;
        actionState = state;
        actionHead = 0;
        actionStarted = now;
        events(clip, -1, 0);
        return true;
    }

    /** Lets go of whatever action is playing; the base takes the bones back. */
    public void stop() {
        if (!dying) {
            action = null;
            actionState = null;
        }
    }

    /**
     * Plays the death animation, which nothing replaces.
     *
     * @return how long it lasts, or {@code 0} when there is none
     */
    public long die(long now) {
        Clip clip = clips.apply("death");
        dying = true;
        if (clip == null) {
            return 0;
        }
        action = new Clip(clip.name(), clip.length(), clip.loop() == Clip.Loop.LOOP ? Clip.Loop.LOOP : Clip.Loop.HOLD,
                clip.bones(), clip.events());
        actionState = "death";
        actionHead = 0;
        actionStarted = now;
        return clip.length();
    }

    /**
     * Every bone's nine channels right now, blended.
     *
     * @param now the clock
     * @param out per bone, nine numbers; every one written
     */
    public void sample(long now, double[][] out) {
        double baseWeight = previous == null || fadeStarted == Long.MIN_VALUE ? 1
                : Math.clamp((now - fadeStarted) / (double) BASE_FADE_MS, 0, 1);
        if (baseWeight >= 1) {
            previous = null;
        }
        double actionWeight = 0;
        if (action != null) {
            actionWeight = Math.clamp((now - actionStarted) / (double) ACTION_IN_MS, 0, 1);
            if (action.loop() == Clip.Loop.ONCE) {
                actionWeight = Math.min(actionWeight,
                        Math.clamp((action.length() - actionHead) / ACTION_OUT_MS, 0, 1));
            }
        }
        for (int bone = 0; bone < bones; bone++) {
            double[] row = out[bone];
            rest(row);
            if (base != null) {
                blend(base, bone, (long) baseHead, row, 1);
            }
            if (previous != null && baseWeight < 1) {
                // The old base, fading out under the new one.
                rest(under);
                blend(previous, bone, (long) previousHead, under, 1);
                for (int channel = 0; channel < Clip.CHANNELS; channel++) {
                    row[channel] = under[channel] + (row[channel] - under[channel]) * baseWeight;
                }
            }
            if (action != null && actionWeight > 0) {
                blend(action, bone, (long) action.at((long) actionHead), row, actionWeight);
            }
        }
    }

    /** Pulls a bone's keyed channels towards a clip's, by a weight; channels the clip leaves alone stay. */
    private void blend(Clip clip, int bone, long millis, double[] row, double weight) {
        Arrays.fill(keyed, false);
        if (!clip.sample(bone, clip.loop() == Clip.Loop.LOOP ? millis : Math.min(millis, clip.length()), scratch, keyed)) {
            return;
        }
        for (int channel = 0; channel < Clip.CHANNELS; channel++) {
            if (keyed[channel]) {
                row[channel] = row[channel] + (scratch[channel] - row[channel]) * weight;
            }
        }
    }

    private static void rest(double[] row) {
        for (int channel = 0; channel < Clip.CHANNELS; channel++) {
            row[channel] = Clip.rest(channel);
        }
    }
}
