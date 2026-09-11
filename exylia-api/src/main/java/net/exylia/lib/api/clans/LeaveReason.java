package net.exylia.lib.api.clans;

/**
 * Why a player stopped being a member of a clan.
 *
 * <p>A clan disbanding is not here: every member goes at once, and
 * {@link net.exylia.lib.api.clans.event.ClanDisbandEvent} says so in one event
 * rather than one per member.
 *
 * @since 1.3.0
 */
public enum LeaveReason {

    /** They left on their own. */
    LEFT,

    /** Somebody removed them: a member allowed to kick, or an administrator. */
    KICKED,

    /** They were banned from the clan, which removes them first. */
    BANNED
}
