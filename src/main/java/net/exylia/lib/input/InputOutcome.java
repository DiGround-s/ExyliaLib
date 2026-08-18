package net.exylia.lib.input;

/**
 * Why an input request ended.
 *
 * <p>ExyliaCommons had two callbacks — {@code onResponse} and {@code onCancel} —
 * so every ending that was not an answer looked identical. A player who timed
 * out, a player who disconnected, and a player who pressed cancel all arrived at
 * the same handler, and a caller that wanted to say "you took too long" could
 * not tell them apart. Worse, a request replaced by a newer one ran no callback
 * at all, so a menu waiting to reopen simply never did.
 *
 * <p>Every ending is named here, and exactly one of them is delivered exactly
 * once per request.
 *
 * @since 1.31.0
 */
public enum InputOutcome {

    /** The player answered and the answer parsed and validated. */
    COMPLETED,

    /**
     * The player chose to stop: the cancel word, the cancel button, or closing
     * the window they were asked in.
     */
    CANCELLED,

    /** Nobody answered before the request's timeout ran out. */
    TIMED_OUT,

    /**
     * A newer request for the same player took over.
     *
     * <p>A player has one request at a time, so opening a second one ends the
     * first. This is its own outcome rather than a cancel because the two mean
     * different things to a caller: a cancel is the player saying no, a replace
     * is the plugin changing its mind, and reopening a menu on a replace is how
     * two menus end up fighting over the screen.
     */
    REPLACED,

    /** The player left the server. */
    DISCONNECTED,

    /**
     * Nothing could ask the question.
     *
     * <p>The player was offline when the request opened, or every way of
     * showing it failed. Never a silent nothing: a caller waiting on the stage
     * is told, so a command can answer rather than hang.
     */
    UNAVAILABLE,

    /**
     * The owning plugin was disabled, or the server is stopping.
     *
     * <p>Distinct from a cancel so a caller does not try to save what it was
     * asking about while its own plugin is being torn down.
     */
    SHUT_DOWN;

    /**
     * Whether an answer is available.
     *
     * @return {@code true} only for {@link #COMPLETED}
     */
    public boolean hasValue() {
        return this == COMPLETED;
    }

    /**
     * Whether the player is the reason this ended.
     *
     * <p>True for a cancel and for a disconnect. Useful for deciding whether to
     * reopen the menu the request came from: a player who cancelled wants to go
     * back, a request that timed out or was replaced should leave the screen
     * alone.
     *
     * @return {@code true} when the player ended it
     */
    public boolean byPlayer() {
        return this == CANCELLED || this == DISCONNECTED;
    }
}
