package net.exylia.lib.util.wizard;

/**
 * Why a wizard run ended.
 *
 * <p>{@code EventConfigWizard} in ExyliaEvents chained chat prompts by hand and
 * had exactly two endings: an answer, or nothing at all. Everything that was
 * not an answer &mdash; a timeout, a disconnect, a second command starting the
 * flow again &mdash; left the half-built state in a {@code static Map} and left
 * the menu that opened it closed forever, because the branch that reopened it
 * only existed on the success path.
 *
 * <p>Every ending is named here, and exactly one of them is delivered exactly
 * once per run. Only {@link #COMPLETED} carries answers, and only
 * {@link #COMPLETED} runs the finish callback: the whole reason for the summary
 * step is that a run which ended any other way must leave nothing behind.
 *
 * @since 1.34.0
 */
public enum WizardOutcome {

    /**
     * Every step was answered and the player confirmed the summary.
     *
     * <p>The only outcome that applies anything.
     */
    COMPLETED,

    /**
     * The player chose to stop.
     *
     * <p>The cancel word, the cancel button, closing the window they were asked
     * in, or exceeding the redo limit on the summary &mdash; a player who has
     * gone round the review loop that many times is no longer answering the
     * question, and looping forever is worse than stopping.
     */
    CANCELLED,

    /** Nobody answered before the run's own timeout ran out. */
    TIMED_OUT,

    /** The player left the server. */
    DISCONNECTED,

    /**
     * A newer wizard for the same player took over.
     *
     * <p>A player has one wizard at a time across every plugin, mirroring the
     * one active input the {@code input} module already enforces. Also the
     * outcome when a region step cannot claim the player's block selector
     * because another plugin already owns it: from this run's point of view
     * somebody else has the player, which is exactly what a replacement means.
     *
     * <p>Its own outcome rather than a cancel because the two mean different
     * things to a caller: a cancel is the player saying no, a replace is a
     * plugin changing its mind, and reopening a menu on a replace is how two
     * screens end up fighting each other.
     */
    REPLACED,

    /**
     * The owning plugin was disabled, or the server is stopping.
     *
     * <p>Distinct from a cancel so a caller does not try to save what it was
     * collecting while its own plugin is being torn down.
     */
    SHUT_DOWN,

    /**
     * Something in the definition threw.
     *
     * <p>A branch predicate, a validator, or the finish callback. Reported to
     * the console against the owning plugin and ended here rather than left
     * hanging: a wizard that silently stops is a player standing still with no
     * idea why, and no line in the log to find it by.
     */
    FAILED;

    /**
     * Whether answers are available.
     *
     * @return {@code true} only for {@link #COMPLETED}
     */
    public boolean hasValues() {
        return this == COMPLETED;
    }

    /**
     * Whether the player is the reason this ended.
     *
     * <p>True for a cancel and for a disconnect. Useful for deciding what to
     * say: a player who cancelled already knows why, a run that failed deserves
     * an apology, and a run that was replaced should leave the screen alone
     * because whatever replaced it now owns it.
     *
     * @return {@code true} when the player ended it
     */
    public boolean byPlayer() {
        return this == CANCELLED || this == DISCONNECTED;
    }
}
