package net.exylia.lib.api.arrows;

import org.jetbrains.annotations.NotNull;

/**
 * A named group of arrow effects, as the server declares it.
 *
 * <p>Its permission grants every effect inside it at once, which is how a rank
 * is sold as "every storm effect" without listing them one by one.
 *
 * @param id         the id used everywhere a category is named, lowercase
 * @param name       what the menu calls it, with colour codes still in it
 * @param permission the node granting every effect in this category
 * @since 1.0.0
 */
public record ArrowCategory(
        @NotNull String id,
        @NotNull String name,
        @NotNull String permission) {
}
