package net.exylia.lib.api.practice;

import org.jetbrains.annotations.NotNull;

/**
 * One group of kits, as the menus sort them into.
 *
 * <p>Presentation and nothing else: a category decides where a kit is drawn,
 * never what it may be played in. A kit can be in several, or in none.
 *
 * @param id           the category id, stable for the category's whole life
 * @param displayName  the name menus show
 * @param description  the one-line description menus show, or empty
 * @param enabled      whether menus draw it
 * @param priority     the order menus list categories in, higher first
 * @param iconMaterial the Bukkit material name menus draw it with
 * @since 1.0.0
 */
public record KitCategory(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String description,
        boolean enabled,
        int priority,
        @NotNull String iconMaterial) {
}
