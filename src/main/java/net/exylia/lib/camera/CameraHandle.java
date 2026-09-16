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
}
