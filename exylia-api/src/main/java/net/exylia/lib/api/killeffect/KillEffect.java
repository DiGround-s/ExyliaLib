package net.exylia.lib.api.killeffect;

import org.jetbrains.annotations.NotNull;

/**
 * One kill effect, as {@code effects.yml} declares it.
 *
 * <p>A snapshot of what the file said when you asked. A reload replaces the
 * whole catalogue, so an effect held across one is the old description of an id
 * that may no longer exist; look it up again rather than keeping it.
 *
 * <p>What the effect actually draws — the particle steps the sequence engine
 * compiles — is not here. It is this version's implementation of the effect,
 * changes whenever the owner edits a line, and means nothing outside the plugin
 * that plays it.
 *
 * @param id          the key in {@code effects.yml}, lowercased and normalised;
 *                    this is what every other method here takes
 * @param categoryId  the category it belongs to, normalised the same way
 * @param name        what menus and placeholders call it, in the server's own
 *                    formatting
 * @param icon        the item it is drawn with, as the file names it; a
 *                    material name, or a head texture the server understands
 * @param description the file's own words, line breaks and all
 * @param priority    where it sits in its category; lower comes first
 * @param permission  the permission that grants this one effect, so a shop can
 *                    sell it without knowing how the node is built
 * @since 1.0.0
 */
public record KillEffect(
        @NotNull String id,
        @NotNull String categoryId,
        @NotNull String name,
        @NotNull String icon,
        @NotNull String description,
        int priority,
        @NotNull String permission) {
}
