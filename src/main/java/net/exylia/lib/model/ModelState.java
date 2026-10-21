package net.exylia.lib.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * The moments a model is animated for, by name.
 *
 * <p>A state is not an animation: it is what the carrier is doing, and the
 * model picks the animation it plays for it. {@link ModelOptions#states()}
 * names one per state; a state it leaves out is matched by name against the
 * model's own animations (see {@link Model#animationFor}).
 *
 * <pre>{@code
 * live.play(ModelState.ATTACK);
 * live.play(ModelState.cast("slam"));
 * }</pre>
 *
 * @since 1.317.0
 */
public final class ModelState {

    /** Standing still; the base the others return to. */
    public static final String IDLE = "idle";
    /** Moving at a walk. Chosen by itself from how fast the carrier goes. */
    public static final String WALK = "walk";
    /** Moving fast. Chosen by itself; a model with no run animation walks. */
    public static final String RUN = "run";
    /** A blow that landed. */
    public static final String ATTACK = "attack";
    /** Taking a hit. */
    public static final String HURT = "hurt";
    /** Dying. Played once and held; see {@link ModelInstance#die}. */
    public static final String DEATH = "death";
    /** Appearing. */
    public static final String SPAWN = "spawn";

    /** Every state but casts, in the order an editor lists them. */
    public static final List<String> ALL = List.of(IDLE, WALK, RUN, ATTACK, HURT, DEATH, SPAWN);

    /** The prefix of a cast's state. */
    public static final String CAST = "cast:";

    private ModelState() {
        throw new AssertionError("No instances.");
    }

    /**
     * The state of casting one skill.
     *
     * @param skill the cast's name, in any case
     * @return {@code cast:<skill>}, lower case
     */
    public static @NotNull String cast(@NotNull String skill) {
        return CAST + skill.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether a state is a cast's. */
    public static boolean isCast(@NotNull String state) {
        return state.startsWith(CAST);
    }
}
