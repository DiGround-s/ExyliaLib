package net.exylia.lib.camera;

/**
 * A shot that is currently playing.
 *
 * <p>Held by whoever might want it over early &mdash; an emote the player
 * cancelled by moving, a cutscene interrupted by a hit. A shot left alone ends
 * itself and gives every eye back, so a caller with nothing to interrupt it
 * may throw the handle away.
 *
 * @since 1.172.0
 */
public interface CameraHandle {

    /**
     * Ends it now, rather than when the shot runs out.
     *
     * <p>Gives every viewer their own eyes back and takes the camera off their
     * clients. Safe from any thread and safe to call twice.
     */
    void stop();

    /** Whether anybody is still looking through it. */
    boolean isRunning();

    /**
     * Holds the shot at a moment of it: the camera stops there, whatever is on screen stays and the shot's
     * clock waits until {@link #resume()}. Nothing ends a held shot but {@code resume} and what ends any shot
     * (the viewer leaving, dying or changing world, a stop), so whoever holds one owns a way out of it.
     *
     * @param millis from the start of the shot; a moment already past holds it now
     * @since 1.528.0
     */
    default void pauseAt(long millis) {
    }

    /**
     * Lets a held shot go on from where it stopped.
     *
     * @since 1.528.0
     */
    default void resume() {
    }

    /**
     * Whether the shot is standing at a {@link #pauseAt} moment now.
     *
     * @since 1.528.0
     */
    default boolean isPaused() {
        return false;
    }

    /**
     * Where the shot's clock is: a held shot's stands still.
     *
     * @return milliseconds from the start of the shot
     * @since 1.528.0
     */
    default long elapsedMillis() {
        return 0;
    }

    /**
     * Hears what a viewer presses while looking through this shot, once it is held: a left or right click
     * (through an invisible box round the still camera), a swing, a sneak or a jump. A press within a quarter
     * of a second of the last one is the same press.
     *
     * @param press run for each press, on a network thread: hop to the player's thread before touching them;
     *              {@code null} stops listening
     * @since 1.528.0
     */
    default void onPress(@org.jetbrains.annotations.Nullable java.util.function.Consumer<org.bukkit.entity.Player> press) {
    }

    /**
     * Shows every line on screen whole at once, on a shot with {@link WorldShot.Builder#reveal(double)}.
     *
     * @since 1.528.0
     */
    default void revealAll() {
    }

    /**
     * Whether a line is still being written out.
     *
     * @since 1.528.0
     */
    default boolean isRevealing() {
        return false;
    }

    /**
     * Sets the small line at the foot of the view, on a shot with {@link WorldShot.Builder#caption()}: what a
     * held shot is waiting for. {@code null} takes it away.
     *
     * @since 1.528.0
     */
    default void caption(@org.jetbrains.annotations.Nullable net.kyori.adventure.text.Component line) {
    }
}
