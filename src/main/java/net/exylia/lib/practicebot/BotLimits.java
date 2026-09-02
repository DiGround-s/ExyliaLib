package net.exylia.lib.practicebot;

/**
 * How far a bot will go for a fight.
 *
 * <p>The plugin's own figures are tuned for {@code /bot}: a practice dummy stands
 * next to the player who asked for it, starts swinging when they walk up, and is
 * taken away when they wander off. A match is the opposite of all three - the two
 * sides start at opposite ends of an arena, the fight is on from the countdown,
 * and nothing but the match decides when it is over. A bot spawned across an
 * arena under sandbox limits stands still waiting to be approached, and one whose
 * opponent runs the length of the map is quietly removed mid-fight.
 *
 * <p>So an integration that knows its own geometry sends it. Left unsent, the
 * plugin's configured values apply and nothing changes for anybody else.
 *
 * @param engageDistance how close its target has to be before it fights at all,
 *                       in blocks. Zero or less means anywhere: the fight is on
 *                       wherever the two of them are standing
 * @param leashDistance  how far its target may get before the bot is taken off
 *                       the field. Zero or less means it is never taken: only
 *                       dying, or whoever spawned it, ends the fight
 *
 * @since 1.86.0
 */
public record BotLimits(double engageDistance, double leashDistance) {

    /**
     * No distance decides anything.
     *
     * <p>What a match wants: the arena is the boundary, the match is the clock,
     * and neither is the bot plugin's business.
     */
    public static BotLimits unlimited() {
        return new BotLimits(0.0, 0.0);
    }

    /** Whether the bot fights from wherever it happens to be. */
    public boolean engagesAnywhere() {
        return engageDistance <= 0.0;
    }

    /** Whether the bot stays however far its target goes. */
    public boolean leashless() {
        return leashDistance <= 0.0;
    }
}
