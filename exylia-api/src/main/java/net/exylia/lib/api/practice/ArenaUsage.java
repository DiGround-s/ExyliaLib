package net.exylia.lib.api.practice;

/**
 * What an arena may be used for.
 *
 * <p>An arena declares the ones it accepts, so a map built for party fights is
 * never handed to a queue match and a bot arena is never duelled in. An arena
 * that declares none accepts everything, and {@link Arena#usages()} reports the
 * full set in that case rather than an empty one — an integration reading the
 * list should not have to know that empty means "all".
 *
 * @since 1.0.0
 */
public enum ArenaUsage {

    /** Ranked and unranked matchmaking. */
    QUEUE,
    /** Player-sent duels. */
    DUEL,
    /** Party FFA and party team fights. */
    PARTY,
    /** Fights against bots. */
    BOT
}
