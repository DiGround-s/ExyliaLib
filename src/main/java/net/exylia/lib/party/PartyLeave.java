package net.exylia.lib.party;

/**
 * Why a member is no longer in a party.
 *
 * @since 1.621.0
 */
public enum PartyLeave {
    /** They left on their own. */
    LEFT,
    /** The leader removed them. */
    KICKED,
    /** They stayed off the server longer than the rejoin grace period. */
    TIMED_OUT,
    /** They accepted another party's invitation. */
    SWITCHED
}
