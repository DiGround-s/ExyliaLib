package net.exylia.lib.util.teleport;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;

/**
 * How a server owner wants teleports to behave by default.
 *
 * <p>Nests inside a plugin's own configuration record like any other section,
 * so the countdown length and whether moving cancels it are decisions the owner
 * makes rather than the plugin author:
 *
 * <pre>{@code
 * public record MySettings(TeleportSettings teleport) {
 *     public MySettings() {
 *         this(new TeleportSettings());
 *     }
 * }
 * }</pre>
 *
 * <p>A request may still override any of these — a death respawn should not sit
 * through a warmup because warps do — but what the owner writes here is what
 * applies when nobody says otherwise.
 *
 * @param warmupSeconds            how long a player waits before being moved
 * @param cancelOnMove             whether moving calls it off
 * @param cancelOnDamage           whether being hit calls it off
 * @param safeSearchRadius         how far to look for somewhere safe to land
 * @param safeMaxAttempts          how many blocks that search may check
 * @param crossServerTtlSeconds    how long a queued cross-server destination lasts
 * @param crossServerSettleSeconds how long to wait after the handover before moving
 * @param backHistorySize          how many places a player may walk back through
 * @param backHistoryMinutes       how long one of those places stays offered
 * @param tpaExpirySeconds         how long an unanswered request stays askable
 * @param tpaMaxPending            how many requests one player may be sitting on
 * @param randomMaxAttempts        how many places a random teleport may try
 * @since 1.34.0
 */
@Comment("How teleports behave.")
@Comment("")
@Comment("These are the defaults. A plugin may still ask for something else for")
@Comment("one particular teleport — a respawn has no reason to sit through the")
@Comment("countdown that a warp does — but anything that does not ask uses this.")
public record TeleportSettings(

        @Key("warmup-seconds")
        @Comment("How long a player waits before being moved, in seconds.")
        @Comment("Decimals are allowed: 2.5 is two and a half seconds.")
        @Comment("")
        @Comment("Zero by default, so nothing waits unless it was asked to. A")
        @Comment("countdown is a fairness rule for warps in a survival world; on a")
        @Comment("lobby it is only a delay the player did not ask for.")
        double warmupSeconds,

        @Key("cancel-on-move")
        @Comment("Whether walking during the countdown calls the teleport off.")
        @Comment("")
        @Comment("On by default: this is the whole point of a countdown, since it")
        @Comment("is what stops a player escaping a fight by warping out of it.")
        @Comment("Looking around does not count, only leaving the block they")
        @Comment("started on — a player nudged by a mob should not lose their warp.")
        boolean cancelOnMove,

        @Key("cancel-on-damage")
        @Comment("Whether taking damage during the countdown calls it off.")
        @Comment("")
        @Comment("On by default, for the same reason as movement. Turn it off on a")
        @Comment("server where fall damage in a parkour lobby would otherwise")
        @Comment("cancel every teleport out of it.")
        boolean cancelOnDamage,

        @Key("safe-search-radius")
        @Comment("How far from the destination to look for somewhere safe to land,")
        @Comment("in blocks. Only used when the teleport asked to be made safe.")
        @Comment("")
        @Comment("Five blocks by default: far enough to step out of a wall, close")
        @Comment("enough that the player still lands where they were sent. A large")
        @Comment("radius quietly turns a warp into a lottery.")
        @Comment("Allowed range: 0 to 32.")
        int safeSearchRadius,

        @Key("safe-max-attempts")
        @Comment("How many blocks that search may check before giving up.")
        @Comment("")
        @Comment("This is the cost ceiling, not the search shape. The search runs on")
        @Comment("the thread owning the destination, so an unbounded scan would be")
        @Comment("a stall the whole server feels. Reaching the limit reports that")
        @Comment("no safe spot was found rather than teleporting anywhere.")
        @Comment("Allowed range: 1 to 256.")
        int safeMaxAttempts,

        @Key("cross-server-ttl-seconds")
        @Comment("How long a destination queued for another server stays valid,")
        @Comment("in seconds.")
        @Comment("")
        @Comment("Five minutes by default: long enough to cover a slow handover or")
        @Comment("a player reconnecting by hand, short enough that a destination")
        @Comment("nobody claimed does not move them on some later login.")
        @Comment("Allowed range: 30 to 3600.")
        int crossServerTtlSeconds,

        @Key("cross-server-settle-seconds")
        @Comment("How long the destination server waits after a player arrives")
        @Comment("before moving them, in seconds.")
        @Comment("")
        @Comment("Half a second by default. A player who joins is still loading the")
        @Comment("world they logged into, and moving them in the same tick is how")
        @Comment("a client ends up in an empty grey void until it is nudged.")
        @Comment("Raise it on a server with slow chunk loading.")
        @Comment("Allowed range: 0.05 to 5.")
        double crossServerSettleSeconds,

        @Key("back-history-size")
        @Comment("How many places a player may walk back through.")
        @Comment("")
        @Comment("Three by default. This is an undo, not a travel log: a player")
        @Comment("who wants the fourth place back wants a home, and a home is")
        @Comment("something they set on purpose rather than something we guessed.")
        @Comment("Every one of these is held in memory only, so a large number")
        @Comment("here is memory spent per online player for nothing.")
        @Comment("Allowed range: 1 to 16.")
        int backHistorySize,

        @Key("back-history-minutes")
        @Comment("How long one of those places stays offered, in minutes.")
        @Comment("")
        @Comment("Half an hour by default. A place a player left an hour ago is")
        @Comment("not somewhere they meant to come back to, and offering it turns")
        @Comment("an undo into a surprise: they type the command expecting the")
        @Comment("arena they just left and land in a mine they forgot about.")
        @Comment("Allowed range: 1 to 1440.")
        int backHistoryMinutes,

        @Key("tpa-expiry-seconds")
        @Comment("How long an unanswered teleport request stays askable,")
        @Comment("in seconds.")
        @Comment("")
        @Comment("A minute by default. Long enough for somebody mid-fight to")
        @Comment("finish and answer, short enough that accepting one does not")
        @Comment("drag a player out of wherever they got to since.")
        @Comment("Allowed range: 5 to 3600.")
        int tpaExpirySeconds,

        @Key("tpa-max-pending")
        @Comment("How many requests one player may be sitting on at once.")
        @Comment("")
        @Comment("Eight by default. This is the anti-spam limit: without it one")
        @Comment("player can bury another's list under requests until the one they")
        @Comment("actually wanted is unfindable, at no cost to the sender.")
        @Comment("Allowed range: 1 to 64.")
        int tpaMaxPending,

        @Key("random-max-attempts")
        @Comment("How many places a random teleport may try before giving up.")
        @Comment("")
        @Comment("Sixteen by default. Each attempt loads a chunk, so this is the")
        @Comment("cost ceiling of the whole feature: on a world that is mostly")
        @Comment("ocean a generous number here is a lot of chunk generation for")
        @Comment("one player. Running out reports that nowhere safe was found")
        @Comment("rather than dropping them in the water.")
        @Comment("Allowed range: 1 to 64.")
        int randomMaxAttempts
) {

    /** The defaults: no countdown, and one that is cancelled if asked for. */
    public TeleportSettings() {
        this(0.0, true, true, 5, 32, 300, 0.5, 3, 30, 60, 8, 16);
    }

    public TeleportSettings {
        // A negative countdown is not a countdown, and a NaN one would make
        // every comparison in the timer false and never finish.
        if (!Double.isFinite(warmupSeconds) || warmupSeconds < 0) {
            warmupSeconds = 0.0;
        }
        // A radius past a chunk or two stops being "somewhere near" and starts
        // being "somewhere else"; a zero one means the exact block only.
        safeSearchRadius = Math.clamp(safeSearchRadius, 0, 32);
        // At least one block has to be checked or the search cannot answer, and
        // the ceiling is what keeps it off the list of things that stall a tick.
        safeMaxAttempts = Math.clamp(safeMaxAttempts, 1, 256);
        crossServerTtlSeconds = Math.clamp(crossServerTtlSeconds, 30, 3600);
        if (!Double.isFinite(crossServerSettleSeconds)) {
            crossServerSettleSeconds = 0.5;
        }
        // Below one tick the wait does not exist; above a few seconds the player
        // is just standing still wondering whether the server hung.
        crossServerSettleSeconds = Math.clamp(crossServerSettleSeconds, 0.05, 5.0);
        // At least one place, or "back" has nothing to mean. The ceiling is
        // memory: every entry is held per online player for as long as it lasts.
        backHistorySize = Math.clamp(backHistorySize, 1, 16);
        // A day at most. Past that the entry has outlived the session that
        // created it and is a place the player no longer remembers leaving.
        backHistoryMinutes = Math.clamp(backHistoryMinutes, 1, 1440);
        // Under five seconds nobody can answer in time; an hour is already far
        // longer than the fight the target was in when they were asked.
        tpaExpirySeconds = Math.clamp(tpaExpirySeconds, 5, 3600);
        tpaMaxPending = Math.clamp(tpaMaxPending, 1, 64);
        // Same reasoning as the safe search: this is a cost ceiling, and each
        // attempt here is a chunk load rather than a block read.
        randomMaxAttempts = Math.clamp(randomMaxAttempts, 1, 64);
    }
}
