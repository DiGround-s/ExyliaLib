package net.exylia.lib.api.armortrims;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/**
 * A trim a player can wear, as it was when you asked.
 *
 * <p>Two kinds share this shape. A <em>preset</em> is an entry the server owner
 * named in configuration: it has an id of its own and one permission node. Any
 * other trim is a combination the player built by hand out of a pattern and a
 * material, whose id is those two halves written {@code pattern/material} and
 * whose permission is the pair of nodes behind them. Everything that takes a
 * trim id takes either.
 *
 * @param id         the id used everywhere a trim is named: the preset's own
 *                   id, or {@code pattern/material} for a combination
 * @param name       what menus and items call it, with colour codes still in it
 * @param permission the node granting a preset on every piece it fits; empty
 *                   for a combination, which is granted by its two halves
 *                   instead — see {@link ArmorTrimService#patterns()}
 * @param pieces     the armor slots it can be worn in
 * @param pattern    the trim pattern registry key
 * @param material   the trim material registry key
 * @param preset     whether the server owner named this trim
 * @since 1.0.0
 */
public record ArmorTrim(
        @NotNull String id,
        @NotNull String name,
        @NotNull String permission,
        @NotNull @Unmodifiable Set<ArmorPiece> pieces,
        @NotNull String pattern,
        @NotNull String material,
        boolean preset) {

    /**
     * Whether this trim can be worn on a piece.
     *
     * @param piece the armor slot
     * @return {@code true} when the trim fits there
     */
    public boolean supports(@NotNull ArmorPiece piece) {
        return pieces.contains(piece);
    }
}
