package net.exylia.lib.item;

import org.jetbrains.annotations.NotNull;

/**
 * The decorative trim on a piece of armour.
 *
 * <p>Both parts are text rather than resolved registry values because the case
 * that motivated this is a trim editor, where the pattern being previewed is
 * whatever the player selected:
 *
 * <pre>{@code
 * armor_trim:
 *   pattern: "%helmet_trim_pattern%"
 *   material: "%helmet_trim_material%"
 * }</pre>
 *
 * <p>Names are vanilla keys — {@code sentry}, {@code redstone} — and resolve
 * through the server's registry when the item is built.
 *
 * @param pattern  the trim pattern key
 * @param material the trim material key
 * @since 1.22.0
 */
public record Trim(@NotNull String pattern, @NotNull String material) {

    /** Returns whether this trim needs a viewer before it can be resolved. */
    public boolean isDynamic() {
        return pattern.indexOf('%') >= 0 || material.indexOf('%') >= 0;
    }
}
