package net.exylia.lib.practicebot;

/**
 * How well the bot plays. One knob instead of thirty: every internal number the
 * AI needs - reaction time, aim accuracy, attack-cooldown discipline, how often
 * a technique is executed at all - is derived from this.
 *
 * <p>The bot turns one of these into the couple of dozen numbers its AI
 * actually runs on. Nothing outside the plugin needs to know which.
 *
 * @since 1.73.0
 */
public enum Difficulty {

    /** Late reactions, sloppy aim, swings early, forgets to reset sprint. */
    EASY,
    /** A decent server regular. Lands most techniques, still makes mistakes. */
    NORMAL,
    /** Practises daily. Tight spacing, clean combos, punishes every heal. */
    HARD,
    /** Frame-perfect. Only sensible with a shield or a totem in hand. */
    INSANE,
    /**
     * Past frame-perfect: it sees the world one tick old, never misses, never
     * blunders, and chains a crystal in the time it takes to blink. Meant to be
     * unfair - it is a wall to measure yourself against, not a fair fight.
     */
    EXTREME;

    public Difficulty next() {
        Difficulty[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public Difficulty previous() {
        Difficulty[] values = values();
        return values[(ordinal() - 1 + values.length) % values.length];
    }

    /** Reads a stored or configured name, including the ones 3.0 used. */
    public static Difficulty parse(String raw) {
        if (raw == null) return NORMAL;
        return switch (raw.trim().toUpperCase()) {
            case "EASY", "NOOB", "ROOKIE" -> EASY;
            case "MEDIUM" -> NORMAL;
            case "HARD", "PRO", "SWEAT" -> HARD;
            case "INSANE", "GOD", "ELITE" -> INSANE;
            case "EXTREME", "IMPOSSIBLE", "NIGHTMARE" -> EXTREME;
            default -> {
                try {
                    yield valueOf(raw.trim().toUpperCase());
                } catch (IllegalArgumentException unknown) {
                    yield NORMAL;
                }
            }
        };
    }
}
