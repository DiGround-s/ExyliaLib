package net.exylia.lib.api.classes;

import org.jetbrains.annotations.NotNull;

/**
 * The resource a class spends on its abilities.
 *
 * <p>Belongs to the class rather than to the player: every archer has the same
 * ceiling and the same refill rate, and what any one of them holds right now is
 * {@link ClassesService#energyOf}. Classes that spend nothing declare none.
 *
 * @param name         what the class calls its resource — mana, focus, rage
 * @param symbol       the character a bar or a placeholder draws it with
 * @param max          the ceiling; energy never rises past it
 * @param regenPerTick how much comes back each server tick, so a bar can be
 *                     predicted between updates rather than polled
 * @since 1.0.0
 */
public record ClassEnergy(
        @NotNull String name,
        @NotNull String symbol,
        double max,
        double regenPerTick) {
}
