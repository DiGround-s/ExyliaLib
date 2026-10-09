package net.exylia.lib.modifier;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * A payout a plugin announces, so a booster screen can offer it.
 *
 * <p>Only a listing: a source nobody registered still works, it just is not
 * offered on screen (a custom mob names its own).
 *
 * @param id    the source id, lower case, as callers pass it to {@link Modifiers}
 * @param icon  what a screen draws it with, anything a menu's {@code material:} accepts
 * @param types the types it pays, lower case
 * @since 1.263.0
 */
public record ModifierSource(@NotNull String id, @NotNull String icon, @NotNull Set<String> types) {

    public ModifierSource {
        types = Set.copyOf(types);
    }

    /** Whether a modifier of this type does anything under this source. */
    public boolean supports(@NotNull String type) {
        return types.contains(Modifiers.normalise(type));
    }
}
