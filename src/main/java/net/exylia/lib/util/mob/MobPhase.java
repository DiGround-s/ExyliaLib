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
 * @param style  the look of the change; blank for the default. Stored for the style
 *               library, which nothing reads yet
 * @param suffix appended to its name, after a space, while it is in this phase, in Exylia
 *               text notation; surrounding blanks are dropped
 * @param speed  its movement speed times this, {@code 0.1-10}
 * @param damage its attack damage times this, {@code 0.1-10}
 * @param resist the damage it takes is divided by this, {@code 0.1-10}; health mode only
 * @since 1.198.0
 */
public record MobPhase(double below, @NotNull String style, @NotNull String suffix,
                       double speed, double damage, double resist) {

    public MobPhase {
        below = Double.isFinite(below) ? Math.max(0, Math.min(1, below)) : 0;
        style = style == null ? "" : style.trim();
        suffix = suffix == null ? "" : suffix.strip();
        speed = multiplier(speed);
        damage = multiplier(damage);
        resist = multiplier(resist);
    }

    private static double multiplier(double value) {
        return Double.isFinite(value) && value > 0 ? Math.max(0.1, Math.min(10, value)) : 1;
    }
}
