package net.exylia.lib.api.arrows;

import org.jetbrains.annotations.NotNull;

/**
 * An arrow effect the server declares, as it was when you asked.
 *
 * <p>A snapshot of the catalogue entry and nothing more. What the effect
 * actually draws — the flash at the bow, the trail, the impact — is a compiled
 * sequence belonging to the plugin that plays it, changes with the version that
 * draws it, and is of no use to a third party.
 *
 * @param id          the id used everywhere an effect is named, lowercase
 * @param categoryId  the category it belongs to, for grouping a shop the way
 *                    the menu groups its tabs
 * @param name        what menus and placeholders call it, with colour codes
 *                    still in it
 * @param permission  the node granting this one effect, so a rank or shop
 *                    plugin can sell it without building the string
 * @param description the server owner's own words, line breaks and all
 * @since 1.0.0
 */
public record ArrowEffect(
        @NotNull String id,
        @NotNull String categoryId,
        @NotNull String name,
        @NotNull String permission,
        @NotNull String description) {
}
