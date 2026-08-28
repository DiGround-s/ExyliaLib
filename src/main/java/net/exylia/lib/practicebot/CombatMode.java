package net.exylia.lib.practicebot;

import java.util.Arrays;
import java.util.List;

/**
 * How the bot fights. Together with {@link Difficulty} this is the whole
 * configuration surface a normal user ever touches: the mode decides which
 * techniques the bot knows and which kit it carries, the difficulty decides how
 * well it executes them.
 *
 * @since 1.73.0
 */
public enum CombatMode {

    /** Plain vanilla melee. No techniques, no consumables. */
    NONE,
    /** 1.8-flavoured sword combat: crits, w-taps, strafes, gapples, heal pots. */
    POT_PVP,
    /** Obsidian, end crystals, respawn anchors, totems, traps. */
    CRYSTAL_PVP,
    /** Wind-charge launches into mace smashes. */
    MACE_PVP;

    /**
     * The modes worth offering somebody as a choice.
     *
     * <p>{@link #NONE} is the enum's zero value and a sandbox setting, not a way
     * of fighting anybody would pick from a menu, so it is not in here.
     *
     * <p>A menu built from this list grows on its own the day a mode is added.
     *
     * @return every playable mode, in declaration order
     */
    public static List<CombatMode> playable() {
        return Arrays.stream(values()).filter(mode -> mode != NONE).toList();
    }

    public CombatMode next() {
        CombatMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public CombatMode previous() {
        CombatMode[] values = values();
        return values[(ordinal() - 1 + values.length) % values.length];
    }

    /**
     * Reads a mode from configuration or from a stored row, absorbing the names
     * older versions used ({@code EASY}/{@code MEDIUM}/{@code PRO} were
     * difficulties stored in the same column).
     */
    public static CombatMode parse(String raw) {
        if (raw == null) return NONE;
        return switch (raw.trim().toUpperCase()) {
            case "EASY" -> NONE;
            case "MEDIUM", "PRO", "POTPVP", "POT" -> POT_PVP;
            case "CRYSTAL", "CRYSTALPVP" -> CRYSTAL_PVP;
            case "MACE", "MACEPVP" -> MACE_PVP;
            default -> {
                try {
                    yield valueOf(raw.trim().toUpperCase());
                } catch (IllegalArgumentException unknown) {
                    yield NONE;
                }
            }
        };
    }
}
