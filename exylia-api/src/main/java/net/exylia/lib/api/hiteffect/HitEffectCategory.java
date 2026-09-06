package net.exylia.lib.api.hiteffect;

import org.jetbrains.annotations.NotNull;

/**
 * A named group of hit effects, as {@code effects.yml} declares it.
 *
 * <p>Categories exist so a rank can be sold as "every infernal effect" rather
 * than as a list of ids that grows every time the owner adds one. Its
 * {@link #permission()} grants all of them at once.
 *
 * @param id         the key in the file, normalised
 * @param name       what the menu calls the tab
 * @param icon       the item the tab is drawn with, as the file names it
 * @param priority   where it sits among the tabs; lower comes first
 * @param permission the permission that grants every effect in it
 * @since 1.0.0
 */
public record HitEffectCategory(
        @NotNull String id,
        @NotNull String name,
        @NotNull String icon,
        int priority,
        @NotNull String permission) {
}
