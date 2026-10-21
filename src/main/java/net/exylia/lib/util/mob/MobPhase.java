package net.exylia.lib.util.mob;

import org.jetbrains.annotations.NotNull;

/**
 * One step of a fight: what changes once a mob's health drops below a share.
 *
 * <pre>{@code
 * // Below half health: a lightning suffix, 30% faster, 25% harder hits.
 * MobPhase enraged = new MobPhase(0.5, "", "&c⚡", 1.3, 1.25, 1);
 * }</pre>
 *
 * <p>The multipliers are modifiers on the mob's attributes, so they stack with
 * whatever its template and its skills set: a {@link MobSkill.Type#SPEED}
 * boost is still that much faster in an enraged phase.
 *
 * @param below  it enters this phase when its health (hits left in hits mode) drops
 *               below this share, {@code 0-1} exclusive
 * @param style  the look of the change, any of {@link MobSkills#STYLES}: blank for
 *               {@code enrage}, {@link MobSkills#NO_STYLE} for none. The mob is held
 *               still while it plays (since 1.200.0)
 * @param suffix appended to its name, after a space, while it is in this phase, in Exylia
 *               text notation; surrounding blanks are dropped
 * @param speed  its movement speed times this, {@code 0.1-10}
 * @param damage its attack damage times this, {@code 0.1-10}
 * @param resist the damage it takes is divided by this, {@code 0.1-10}; health mode only
 * @param size   a model mob is drawn this many times its size, {@code 0.1-4} (since 1.321.0)
 * @param glow   a model mob is outlined in this colour, {@code #rrggbb} or a named text colour;
 *               blank for none (since 1.321.0)
 * @since 1.198.0
 */
public record MobPhase(double below, @NotNull String style, @NotNull String suffix,
                       double speed, double damage, double resist, double size, @NotNull String glow) {

    /** A phase that leaves the model as it is, as 1.198.0 had it. */
    public MobPhase(double below, @NotNull String style, @NotNull String suffix, double speed, double damage,
                    double resist) {
        this(below, style, suffix, speed, damage, resist, 1, "");
    }

    public MobPhase {
        size = Double.isFinite(size) && size > 0 ? Math.max(0.1, Math.min(4, size)) : 1;
        glow = glow == null ? "" : glow.trim();
        below = Double.isFinite(below) ? Math.max(0, Math.min(1, below)) : 0;
        style = style == null ? "" : style.trim();
        suffix = suffix == null ? "" : suffix.strip();
        speed = multiplier(speed);
        damage = multiplier(damage);
        resist = multiplier(resist);
    }

    /**
     * The outline colour, as {@code 0xRRGGBB}.
     *
     * @return the colour, or {@code -1} for none or a name that is no colour
     * @since 1.321.0
     */
    public int glowColour() {
        if (glow.isEmpty()) return -1;
        net.kyori.adventure.text.format.TextColor colour = glow.startsWith("#")
                ? net.kyori.adventure.text.format.TextColor.fromHexString(glow)
                : net.kyori.adventure.text.format.NamedTextColor.NAMES.value(glow.toLowerCase(java.util.Locale.ROOT));
        return colour == null ? -1 : colour.value();
    }

    private static double multiplier(double value) {
        return Double.isFinite(value) && value > 0 ? Math.max(0.1, Math.min(10, value)) : 1;
    }
}
