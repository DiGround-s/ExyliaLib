package net.exylia.lib.api.armortrims;

import org.jetbrains.annotations.NotNull;

/**
 * One half of a trim a player can combine by hand: a pattern, or a material.
 *
 * <p>Both halves are the same shape, so they are the same record. Which half
 * one is comes from the method that handed it over —
 * {@link ArmorTrimService#patterns()} or {@link ArmorTrimService#materials()} —
 * rather than from a field nobody would branch on.
 *
 * <p>These exist because a combination is sold in halves. Giving a player a
 * preset never quietly gives them its pattern to recombine with something else,
 * so a shop selling the freedom to mix has to sell these.
 *
 * @param id         the registry key, lowercase
 * @param name       what menus call it, with colour codes still in it
 * @param permission the node granting this half
 * @since 1.0.0
 */
public record TrimOption(
        @NotNull String id,
        @NotNull String name,
        @NotNull String permission) {
}
