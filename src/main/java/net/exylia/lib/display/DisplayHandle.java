package net.exylia.lib.display;

/**
 * A display that is currently being shown.
 *
 * <p>Held only by code that might want it gone early &mdash; a preview the
 * player closed, an effect on an entity that stopped existing. A display that
 * is left alone removes itself when its motion ends, so most callers throw the
 * handle away.
 *
 * @since 1.85.0
 */
public interface DisplayHandle {

    /**
     * Removes it now, rather than when its life is up.
     *
     * <p>Safe from any thread and safe to call twice.
     */
    void remove();

    /** Whether it is still on somebody's screen. */
    boolean isShowing();

    /**
     * Takes it back to where it started, smoothly, and then removes it.
     *
     * <p>For something cut off in the middle of its motion that should not
     * simply vanish: a body stopped halfway through a dance stands back up in
     * the time given, and is then gone. Safe from any thread; a display that
     * cannot do it is removed at once.
     *
     * @param millis how long the way back takes
     * @since 1.262.0
     */
    default void settle(long millis) {
        remove();
    }
}
