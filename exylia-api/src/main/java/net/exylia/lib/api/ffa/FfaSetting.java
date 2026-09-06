package net.exylia.lib.api.ffa;

/**
 * One toggle a player owns.
 *
 * <p>A player's answer is only half of it: an administrator can turn a feature
 * off for the whole server, and then nobody has it no matter what they chose.
 * {@link FfaService#isSettingEnabled(java.util.UUID, FfaSetting)} reports the
 * player's choice and {@link FfaService#isSettingAvailable(FfaSetting)} reports
 * the server's, so anything deciding whether a feature actually applies has to
 * ask both.
 *
 * @since 1.0.0
 */
public enum FfaSetting {

    /** The FFA sidebar. */
    SCOREBOARD,

    /** Waypoint markers pointing at the arena's spawns. */
    SPAWN_WAYPOINTS,

    /** The action bar shown while in combat. */
    COMBAT_ACTION_BAR,

    /** The action bar shown while linked to one opponent. */
    LINKED_ACTION_BAR,

    /** Chat messages about combat. */
    COMBAT_MESSAGES,

    /** Being told about your own kill streak. */
    KILL_STREAK_NOTIFICATIONS,

    /** Being told about everybody else's kill streaks. */
    KILL_STREAK_BROADCASTS,

    /** Being told who killed whom. */
    KILL_DEATH_BROADCASTS,

    /** Making the linked opponent glow. */
    GLOW_LINKED_PLAYER,

    /** The action bar hint shown while spectating. */
    SPECTATOR_ACTIONBAR_HINT
}
