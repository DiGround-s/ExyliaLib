package net.exylia.lib.api.practice;

/**
 * How a match was put together.
 *
 * <p>The two bot modes record nothing: no statistics, no ELO movement and no
 * history row. An integration that counts matches should skip them rather than
 * wonder why the numbers never move.
 *
 * @since 1.0.0
 */
public enum MatchMode {

    /** One player against another, from the queue or from a duel request. */
    DUEL_1V1,
    /** Everybody in a party against everybody else in it. */
    PARTY_FFA,
    /** One party split into two teams. */
    PARTY_SPLIT,
    /** Two parties against each other. */
    PARTY_DUEL,
    /** One player against a bot. Nothing is recorded. */
    BOT_DUEL,
    /** A party against a squad of bots. Nothing is recorded. */
    BOT_PARTY;

    /**
     * Whether the opponents in this mode are bots rather than players.
     *
     * @return {@code true} for the two bot modes
     */
    public boolean againstBots() {
        return this == BOT_DUEL || this == BOT_PARTY;
    }
}
