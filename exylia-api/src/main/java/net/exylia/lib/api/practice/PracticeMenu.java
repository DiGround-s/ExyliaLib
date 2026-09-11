package net.exylia.lib.api.practice;

/**
 * The player screens another plugin may open with {@link PracticeService#openMenu}.
 *
 * <p>The ones a player reaches from a command or a lobby item with nothing else
 * to decide first. Screens that need a choice made before they mean anything —
 * one kit's leaderboard, a duel against one particular player — are reached
 * from these, the same way a player reaches them.
 *
 * @since 1.3.0
 */
public enum PracticeMenu {

    /** The queue, starting from its kit categories. */
    QUEUE,
    /** The party hub: create or accept an invitation, or run the party they are in. */
    PARTY,
    /** The kit editor. Refused unless the player is free. */
    KIT_EDITOR,
    /** The player's own practice settings. */
    SETTINGS,
    /** The player's statistics, starting from the kit categories. */
    STATS,
    /** The player's recent matches, starting from the kit categories. */
    MATCH_HISTORY,
    /** The live matches that may be watched. */
    SPECTATE,
    /**
     * Practice against a bot. Refused without the bot practice permission, and
     * on a server that is not running the bot plugin.
     */
    BOT_PVP
}
