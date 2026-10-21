package net.exylia.lib.model;

import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A model being worn.
 *
 * <pre>{@code
 * ModelInstance live = models.attach(mob, "stone_golem", ModelOptions.DEFAULT);
 * live.onEvent(event -> {
 *     if (event.equals("impact")) shockwave(mob.getLocation());
 * });
 * live.play(ModelState.cast("slam"));
 * }</pre>
 *
 * <p>It walks, runs and stands still by itself, from how fast its carrier
 * moves; everything else is asked for. It goes away by itself when its carrier
 * does &mdash; killed, removed, unloaded with its chunk &mdash; and when the
 * plugin that attached it is disabled.
 *
 * <p>Every method is safe from any thread: what it changes is read on the
 * carrier's next tick.
 *
 * @since 1.317.0
 */
public interface ModelInstance {

    /** What it wears. */
    @NotNull Model model();

    /** What wears it, or {@code null} once it has let go of it (dying, or removed). */
    @Nullable LivingEntity carrier();

    /** Whether it is still on anybody's screen, or could be. */
    boolean isActive();

    /**
     * Plays an action on top of what it is doing.
     *
     * <p>A {@link ModelState} or an animation's own name. Only the bones the
     * animation moves are taken over, so an attack while walking keeps the legs
     * walking. A one-shot hands the bones back as it ends; a looping or held
     * animation keeps them until {@link #stop} or the next action.
     *
     * @param stateOrAnimation what to play
     * @return whether the model had an animation for it
     */
    boolean play(@NotNull String stateOrAnimation);

    /**
     * Plays an action fitted to a length of time, so it strikes when the time is
     * up: a skill's wind-up and its animation landing on the same frame.
     *
     * <p>The animation strikes on its {@code release} event, else its
     * {@code impact} event, else at its end. Up to that moment it is sped up or
     * slowed down to fit, between a quarter and four times its own pace; after
     * it, it plays at its own pace. A time of zero plays it as {@link #play(String)}.
     *
     * @param stateOrAnimation what to play
     * @param strikeInMillis   when it should strike, from now
     * @return whether the model had an animation for it
     * @since 1.321.0
     */
    default boolean play(@NotNull String stateOrAnimation, long strikeInMillis) {
        return play(stateOrAnimation);
    }

    /** Lets go of the action under way. */
    void stop();

    /**
     * Moves it to a fight phase: from now on every state plays its phase's own
     * animation where the model has one ({@code walk_p2}, see
     * {@link Model#animationFor(String, ModelOptions, int)}), and the others as before.
     *
     * @param phase the phase, {@code 1} at the start
     * @since 1.321.0
     */
    default void phase(int phase) {
    }

    /**
     * Draws it bigger or smaller than its options say, from now on: an enraged
     * boss that grows. Fades over half a second.
     *
     * @param multiplier times the size its options give it, {@code 0.1-4}
     * @since 1.321.0
     */
    default void resize(double multiplier) {
    }

    /**
     * Outlines it in a colour until told otherwise; a {@link #flash} still shows
     * over it and hands back to it.
     *
     * @param rgb {@code 0xRRGGBB}, or {@code -1} for no outline
     * @since 1.321.0
     */
    default void glow(int rgb) {
    }

    /**
     * Listens to what its animations say: the scripts of their timeline
     * keyframes and the names of the sounds and particles they play, at the
     * moment the animation reaches them.
     *
     * <p>That is how a skill lands on the frame the fist hits the ground. Told
     * on the carrier's thread; a listener that throws is logged and kept.
     *
     * @param listener what to tell
     * @return this
     */
    @NotNull ModelInstance onEvent(@NotNull Consumer<String> listener);

    /**
     * Outlines it in a colour for a moment: a hit, a buff, an enrage.
     *
     * @param rgb    {@code 0xRRGGBB}
     * @param millis how long
     */
    void flash(int rgb, long millis);

    /**
     * Lets go of the carrier and dies where it stands.
     *
     * <p>Plays its {@link ModelState#DEATH} animation, held on its last frame,
     * and then either shrinks away or comes apart. Without a death animation it
     * comes apart at once when {@code apart} is set, and shrinks away otherwise.
     * Call it as the carrier dies, before the carrier is removed.
     *
     * @param apart whether it ends by coming apart rather than shrinking away
     */
    void die(boolean apart);

    /**
     * {@link #die(boolean)}, with the blow that killed it: when it comes apart its
     * cubes are thrown that way.
     *
     * @param pushX east, blocks a second
     * @param pushY up
     * @param pushZ south
     * @since 1.321.0
     */
    default void die(boolean apart, double pushX, double pushY, double pushZ) {
        die(apart);
    }

    /**
     * Breaks into its own cubes, which fly out and fall: no other model is
     * involved. Removes it.
     */
    void comeApart();

    /**
     * {@link #comeApart()}, thrown away from a blow: the cubes fly that way as
     * well as out from the middle, bounce off the blocks around them and come to
     * rest before they melt.
     *
     * @param pushX east, blocks a second
     * @param pushY up
     * @param pushZ south
     * @since 1.321.0
     */
    default void comeApart(double pushX, double pushY, double pushZ) {
        comeApart();
    }

    /**
     * Takes it off every client now. The carrier, if it is still alive, is
     * shown again as it is.
     */
    void remove();
}
