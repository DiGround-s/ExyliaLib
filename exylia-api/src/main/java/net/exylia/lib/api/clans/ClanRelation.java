package net.exylia.lib.api.clans;

/**
 * Where two players stand towards each other, as their clans see it.
 *
 * <p>The one question a combat, party or chat plugin asks about two players,
 * answered in a single lookup rather than three. The values never overlap: a
 * clan cannot be allied with and a rival of the same clan at once, and two
 * clanmates are {@link #SAME_CLAN} before anything else.
 *
 * @since 1.3.0
 */
public enum ClanRelation {

    /** Either player is clanless, or their clans have declared nothing. */
    NONE,

    /** Both players belong to the same clan. */
    SAME_CLAN,

    /** Their clans are allied. */
    ALLY,

    /**
     * One of their clans has declared the other a rival.
     *
     * <p>Rivalry is declared by one side and needs no acceptance, so either
     * side declaring it is enough.
     */
    RIVAL
}
