package net.exylia.lib.api.events;

/**
 * Where a running event is in its life.
 *
 * @since 1.0.0
 */
public enum GameState {

    /** Open, filling up, waiting for enough players to start. */
    WAITING,

    /** Full enough and counting down; still joinable until it reaches zero. */
    STARTING,

    /** Being played. */
    PLAYING,

    /** Over, showing the winner and paying out before it cleans itself up. */
    ENDING,

    /** Stopped by an admin or by a failure, and about to be removed. */
    DISABLED;

    /**
     * Whether a player may still be let in.
     *
     * @return {@code true} while the event is filling or counting down
     */
    public boolean isJoinable() {
        return this == WAITING || this == STARTING;
    }

    /**
     * Whether the game itself is running.
     *
     * @return {@code true} only while it is being played
     */
    public boolean isActive() {
        return this == PLAYING;
    }
}
