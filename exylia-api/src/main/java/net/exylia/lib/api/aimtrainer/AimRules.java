package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;

/**
 * The rules one session was played by: the drill after the player's settings.
 *
 * @param drillId            the drill
 * @param kind               what it asked of the player
 * @param width              target width in blocks as drawn
 * @param height             target height in blocks as drawn
 * @param distanceMin        nearest a target was placed
 * @param distanceMax        farthest a target was placed
 * @param sizeMultiplier     the size setting that applied; 1.0 in a duel
 * @param distanceMultiplier the distance setting that applied; 1.0 in a duel
 * @param difficulty         what the rating multiplies the score by
 * @param seed               the random seed, identical on both sides of a duel
 * @since 1.5.0
 */
public record AimRules(@NotNull String drillId, @NotNull AimDrillKind kind, double width, double height,
                       double distanceMin, double distanceMax, double sizeMultiplier, double distanceMultiplier,
                       double difficulty, long seed) {
}
