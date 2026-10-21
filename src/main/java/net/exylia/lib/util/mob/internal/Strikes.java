package net.exylia.lib.util.mob.internal;

import net.exylia.lib.util.mob.MobAttack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Which of a mob's own attacks it strikes with, when the blow lands, and
 * whether it lands.
 *
 * <p>Pure, so every rule can be asserted without a server; {@link MobBrain}
 * reads the world and acts on the answers.
 */
final class Strikes {

    /** How long a blow takes to land when its animation says nothing, milliseconds. */
    static final long DEFAULT_WINDUP = 350;
    /** How much further than its reach a target may have stepped by the time a blow lands. */
    static final double GRACE = 0.4;

    private Strikes() {
    }

    /**
     * The attack it strikes with now: the first in its list that is ready and
     * reaches, a blow before a throw when both do, so it does not shoot at
     * somebody standing at its feet.
     *
     * @param attacks its attacks, in the order they were written
     * @param readyAt per attack, when it is ready again
     * @param gap     how far its target is from its body, blocks
     * @param now     the clock
     * @return the attack's index, or {@code -1}
     */
    static int pick(List<MobAttack> attacks, long[] readyAt, double gap, long now) {
        int throwing = -1;
        for (int index = 0; index < attacks.size(); index++) {
            MobAttack attack = attacks.get(index);
            if (readyAt[index] > now || gap > attack.reach()) {
                continue;
            }
            if (!attack.ranged()) {
                return index;
            }
            if (throwing < 0) {
                throwing = index;
            }
        }
        return throwing;
    }

    /** The furthest any of them reaches, blocks; what it closes to. */
    static double reach(List<MobAttack> attacks) {
        double reach = 0;
        for (MobAttack attack : attacks) {
            reach = Math.max(reach, attack.reach());
        }
        return reach;
    }

    /** The shortest a blow reaches, blocks; how close it must be before it stops closing in. */
    static double closest(List<MobAttack> attacks) {
        double reach = Double.MAX_VALUE;
        for (MobAttack attack : attacks) {
            reach = Math.min(reach, attack.reach());
        }
        return reach == Double.MAX_VALUE ? 0 : reach;
    }

    /**
     * How long after the attack starts its blow lands.
     *
     * @param animationStrike when its animation strikes at its own pace, milliseconds; {@code -1} for none
     * @param windup          the attack's own wind-up, milliseconds; {@code 0} for the animation's
     */
    static long delay(long animationStrike, long windup) {
        if (windup > 0) return windup;
        return animationStrike > 0 ? animationStrike : DEFAULT_WINDUP;
    }

    /**
     * Whether a blow lands on a target that is now this far and this far round.
     *
     * @param gap    blocks between its body and the target's
     * @param angle  degrees between where it faces and the target, {@code 0-180}
     * @param seen   whether nothing solid stands between them
     */
    static boolean lands(MobAttack attack, double gap, double angle, boolean seen) {
        if (!seen || gap > attack.reach() + GRACE) return false;
        return attack.ranged() || attack.arc() >= 360 || angle <= attack.arc() / 2;
    }

    /** The degrees between a facing and a direction, {@code 0-180}. */
    static double angle(double yawDegrees, double dx, double dz) {
        if (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9) return 0;
        double towards = Math.toDegrees(Math.atan2(-dx, dz));
        double difference = Math.abs(((towards - yawDegrees) % 360 + 540) % 360 - 180);
        return difference;
    }

    /** What it deals: the attack's own damage, else the template's. */
    static double damage(MobAttack attack, @Nullable Double templateDamage) {
        if (attack.damage() > 0) return attack.damage();
        return templateDamage != null && templateDamage > 0 ? templateDamage : 3;
    }
}
