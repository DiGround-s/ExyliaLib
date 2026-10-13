package net.exylia.lib.tuner;

/**
 * Why a {@link TunerSession} ended, and whether what was tuned is kept.
 *
 * <p>Only the player saying no, or the consumer pulling the subject out from
 * under the session, throws the changes away. Everything that merely interrupts
 * (leaving, dying, a far teleport, the plugin stopping) keeps them: the player
 * was looking at the result and did not ask for it to be undone.
 *
 * @since 1.298.0
 */
public enum TunerEnd {

    /** The player saved: a click, or the consumer's {@link TunerSession#save()}. */
    SAVED(true),
    /** The player asked for the values back: {@link TunerSession#cancel()}. */
    CANCELLED(false),
    /** The consumer ended it because the subject is gone: {@link TunerSession#abort()}. */
    ABORTED(false),
    /** A new session for the same player took over. */
    REPLACED(true),
    /** The player left the server. */
    QUIT(true),
    /** The player died. */
    DEATH(true),
    /** The player changed world or teleported far away. */
    MOVED_AWAY(true),
    /** The plugin that started it was disabled. */
    DISABLED(true);

    private final boolean kept;

    TunerEnd(boolean kept) {
        this.kept = kept;
    }

    /**
     * Whether the consumer keeps what was tuned.
     *
     * @return {@code true} to persist, {@code false} to put the original back
     */
    public boolean kept() {
        return kept;
    }
}
