package net.exylia.lib.api.totemtrainer;

import org.jetbrains.annotations.NotNull;

/**
 * Everything a session runs under, resolved from configuration once so a
 * running session never re-reads the file.
 *
 * <p>A mode is three independent choices and this is where they land, which is
 * why nothing here is named after a mode: {@code totems} says how the inventory
 * is stocked, a window says the interval is drawn rather than kept, and a
 * speed-up says it shortens as hits add up. A mode may do both of the last two,
 * and one that does neither runs at a flat interval.
 *
 * <p>{@code ticks} is the interval the player chose. A window is shifted so
 * that interval sits at its centre — a 10..30 window on a 25-tick session draws
 * from 15..35 — so a picked speed means the same thing in every mode.
 *
 * @param modeId          the configured mode id
 * @param ticks           the chosen interval between hits, in ticks
 * @param totems          totems kept in random slots, or {@code 0} for a full inventory
 * @param refill          whether a spent full inventory is handed out again
 * @param closeInventory  whether the inventory is shut after every pop
 * @param windowMinTicks  window floor, in ticks
 * @param windowMaxTicks  window ceiling, or {@code 0} when the interval is not drawn
 * @param speedUpEvery    hits between two speed-ups
 * @param speedUpBy       ticks removed by one speed-up, or {@code 0} when it does not
 * @param floorTicks      the fastest interval a speed-up may reach
 * @param seed            the random seed, so both sides of a duel draw the same
 *                        sequence and a session can be replayed exactly
 * @since 1.0.0
 */
public record TrainingRules(
        @NotNull String modeId,
        int ticks,
        int totems,
        boolean refill,
        boolean closeInventory,
        int windowMinTicks,
        int windowMaxTicks,
        int speedUpEvery,
        int speedUpBy,
        int floorTicks,
        long seed) {

    /**
     * Whether totems are kept in random slots rather than filling the inventory.
     *
     * @return {@code true} when the inventory is stocked with a fixed count
     */
    public boolean isStocked() {
        return totems > 0;
    }

    /**
     * Whether every interval is drawn from the window rather than kept flat.
     *
     * @return {@code true} when the interval is random
     */
    public boolean drawsInterval() {
        return windowMaxTicks > 0;
    }

    /**
     * Whether the interval shortens as hits add up.
     *
     * @return {@code true} when the session accelerates
     */
    public boolean speedsUp() {
        return speedUpBy > 0 && speedUpEvery > 0;
    }

    /**
     * The interval a hit count implies, before any draw.
     *
     * <p>What a scoreboard should show: the speed the session is running at,
     * without consuming any of the session's randomness.
     *
     * @param hits how many hits have already landed
     * @return the interval in ticks
     */
    public int intervalAt(int hits) {
        if (!speedsUp()) {
            return ticks;
        }
        return Math.max(floorTicks, ticks - (hits / speedUpEvery) * speedUpBy);
    }
}
