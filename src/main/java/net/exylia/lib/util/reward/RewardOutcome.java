package net.exylia.lib.util.reward;

/**
 * How one reward ended.
 *
 * <p>ExyliaCommons had a boolean {@code success} and three separate
 * {@code skipped*} flags that all reported failure, so a reward that simply lost
 * its dice roll was indistinguishable from one that threw. A server owner asking
 * "why did nobody get this" could not tell the difference, and neither could the
 * statistics.
 *
 * @since 1.33.0
 */
public enum RewardOutcome {

    /** The player got it. */
    GIVEN,

    /** The dice said no. Nothing is wrong. */
    NOT_ROLLED,

    /** The player lacks the permission the reward names. */
    NO_PERMISSION,

    /** The reward's condition did not hold. */
    CONDITION_FAILED,

    /**
     * There was nowhere to put it, and nothing else could be done with it.
     *
     * <p>Reached under {@link OverflowPolicy#FAIL}, which asks for exactly this.
     *
     * @see OverflowPolicy
     */
    NO_ROOM,

    /**
     * The player was not there to receive it, and it was queued for their
     * return.
     *
     * @see PluginRewards#giveLater
     */
    QUEUED,

    /** Something the reward names does not exist, or the delivery threw. */
    FAILED;

    /** Whether the player actually received something. */
    public boolean isGiven() {
        return this == GIVEN;
    }

    /**
     * Whether nothing happened and nothing is wrong.
     *
     * <p>The distinction that matters for a log: a skipped reward is a
     * configured outcome, a failed one is a bug or a typo.
     */
    public boolean isSkipped() {
        return this == NOT_ROLLED || this == NO_PERMISSION || this == CONDITION_FAILED;
    }

    /** Whether somebody should be told. */
    public boolean isFailure() {
        return this == FAILED || this == NO_ROOM;
    }
}
