package net.exylia.lib.api.chatcosmetics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * One thing a player can own and wear, as the catalogue describes it.
 *
 * <p>A snapshot of what the files said when you asked. Catalogues are reloaded
 * whole, so hold the {@link #key()} rather than this record if you need to look
 * the same cosmetic up again later.
 *
 * <p>Whether a given player owns or wears it is not here: that depends on the
 * player and is asked of {@link CosmeticsService}, so reading the catalogue
 * stays a plain lookup.
 *
 * @param key            what names it
 * @param name           what players see, with the plugin's colour placeholders
 *                       still in it
 * @param category       the catalogue tab it belongs to
 * @param icon           the material name a menu draws it with
 * @param description    the lines shown under the name
 * @param priority       lower sorts first within its category
 * @param hidden         whether menus leave it out of the list
 * @param permissionNode the node that grants it outright, when the entry allows
 *                       being granted by permission at all
 * @since 1.0.0
 */
public record Cosmetic(
        @NotNull CosmeticKey key,
        @NotNull String name,
        @NotNull String category,
        @NotNull String icon,
        @NotNull @Unmodifiable List<String> description,
        int priority,
        boolean hidden,
        @NotNull String permissionNode) {

    public Cosmetic {
        description = List.copyOf(description);
    }

    /**
     * The cosmetic type, the first half of the key.
     *
     * @return the type id
     */
    @NotNull
    public String type() {
        return key.type();
    }

    /**
     * The entry id, the second half of the key.
     *
     * @return the cosmetic id
     */
    @NotNull
    public String id() {
        return key.id();
    }
}
