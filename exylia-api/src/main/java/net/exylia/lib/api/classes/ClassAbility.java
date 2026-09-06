package net.exylia.lib.api.classes;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * One thing a class can do, and what it costs.
 *
 * <p>What the ability actually applies — its potion effects, its particles, its
 * sound — is left out: those are lines the server owner writes in the plugin's
 * own syntax, and they change with the version that reads them. What is here is
 * what a menu, a shop or a heads-up display needs to describe the ability
 * without playing it.
 *
 * @param name       what the class calls it, with colour codes still in it
 * @param trigger    the item a player holds to use it; also its key within the
 *                   class, so no two abilities share one
 * @param type       who it reaches
 * @param cooldown   seconds before it can be used again
 * @param energyCost how much of the class's energy it spends
 * @param radius     how far it reaches, in blocks; ignored by
 *                   {@link AbilityType#SELF}
 * @since 1.0.0
 */
public record ClassAbility(
        @NotNull String name,
        @NotNull Material trigger,
        @NotNull AbilityType type,
        int cooldown,
        double energyCost,
        double radius) {
}
