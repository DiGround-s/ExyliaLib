package net.exylia.lib.database;

/**
 * What a table holds, for the library's wipes and player resets.
 *
 * <p>Declared on {@link Table#data()}. A server owner who starts a new season
 * picks kinds ("progress and world state, not purchases"), never table names,
 * and the kind is what decides whether a table goes. A table nobody classified
 * is {@link #UNSPECIFIED}, and that is the safe answer: no preset and no player
 * reset touches it.
 *
 * <pre>{@code
 * @Table(value = "practice_player_stats", data = DataKind.PROGRESS)
 * public record PlayerStats(@Id @Owner UUID uuid, @Column int elo) { }
 * }</pre>
 *
 * <p>A table that is two things at once takes the kind whose wipe is correct:
 * rewards owed for a season being wiped are {@link #PROGRESS}; purchases owed
 * to a player are {@link #ACCOUNT}.
 *
 * @since 1.439.0
 */
public enum DataKind {

    /** Not classified yet. Only an explicit per-table or whole-plugin wipe empties it. */
    UNSPECIFIED,

    /** Per-player progress a new season starts over: homes, balances, stats, ranks, cooldowns of kits. */
    PROGRESS,

    /** Server-wide state of the current playthrough: listings, claims, graves, clans, the live season. A list of past seasons is {@link #HISTORY}. */
    WORLD,

    /** Per-player data that survives a season: purchases, unlocks, settings, punishments, links. */
    ACCOUNT,

    /** Logs and history: match history, ledgers, audit trails, replays. */
    HISTORY,

    /** Rebuilt by itself: stat caches, leases, outboxes, seen markers. */
    CACHE,

    /** Built by the admin: warps, kits, arenas, crate definitions, shop products, templates. */
    SETUP
}
