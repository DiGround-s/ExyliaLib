package net.exylia.lib.api.clans;

/**
 * Where a clan stands in the deaths-till-raidable cycle.
 *
 * @since 1.0.0
 */
public enum DtrState {

    /** Above zero and not regenerating: the ordinary state. */
    NORMAL,

    /** Recently lost a member, so DTR is held still before it recovers. */
    FROZEN,

    /** Recovering towards its maximum. */
    REGENERATING,

    /** At or below zero: the clan's land can be raided. */
    RAIDABLE
}
