package net.exylia.lib.guide;

/**
 * One piece of guidance on one player's screen.
 *
 * <p>Every piece goes by itself when the player leaves or its plugin is
 * disabled, so a caller with nothing to take down early may drop the handle.
 * Pieces survive a death and a change of world: they are drawn again on the
 * player's next tick, because a journey does not stop when somebody dies.
 *
 * @since 1.391.0
 */
public interface GuideHandle {

    /** Takes it off the player's screen now. Safe from any thread and safe to call twice. */
    void remove();

    /** Whether it is still in place: not removed, and its player still here. */
    boolean isShowing();
}
