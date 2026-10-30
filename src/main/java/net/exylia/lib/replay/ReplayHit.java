package net.exylia.lib.replay;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One attack in a recording, and what it looked like from both ends of the
 * connection: reach by the server's positions, reach by where the attacker's
 * own screen had the target, how far off the look was, and how hard it turned
 * just before.
 *
 * <p>Enough for staff to tell a long reach that was only lag from one that was
 * not, a hit with the crosshair off the body, and a snap onto a target in the
 * tick before the click. A number that could not be worked out is
 * {@code NaN}, never a guess.
 *
 * @param attacker    who attacked
 * @param target      who or what was attacked
 * @param tick        the tick of the recording it happened on
 * @param reachServer blocks from the attacker's eyes to the target's hitbox, by
 *                    the server's positions: what the server checked
 * @param reachSeen   the same, with the target where the attacker's screen had
 *                    it, {@link #seenLag} ticks earlier
 * @param angleOff    degrees between the attacker's look and the target's hitbox
 *                    as the attacker saw it; 0 when the look goes through it
 * @param flick       the largest turn in the three ticks before the attack, in
 *                    degrees
 * @param seenLag     ticks the attacker's screen was behind the server, or -1
 * @param estimated   whether {@link #seenLag} is the ping's estimate rather than
 *                    measured by the client's own answers
 * @param damage      what the hit did after armour, or {@code NaN} when not known
 * @param confirmed   whether the server counted it: {@code false} for an attack
 *                    the client sent that no damage went with (out of reach,
 *                    invulnerable, cancelled)
 * @since 1.403.0
 */
public record ReplayHit(@NotNull UUID attacker, @NotNull UUID target, int tick, double reachServer,
                        double reachSeen, double angleOff, double flick, int seenLag,
                        boolean estimated, double damage, boolean confirmed) {

    /**
     * Where one hit stands in a fight: which hit of that attacker on that
     * target it was, counting only the ones the server counted, and how much
     * damage they had done by then, after armour.
     *
     * @param number the hit's number, from one; for an attack the server never
     *               counted, how many came before it
     * @param damage the damage of those hits together, in health points
     * @since 1.404.0
     */
    public record Tally(int number, double damage) {
    }
}
