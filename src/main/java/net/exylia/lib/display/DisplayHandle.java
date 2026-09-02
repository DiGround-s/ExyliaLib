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
}
