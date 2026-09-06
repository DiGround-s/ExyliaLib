package net.exylia.lib.api.classes;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;

/**
 * A class the server declares, as it was when you asked.
 *
 * <p>Nobody picks a class here: a player is in one because of what they are
 * wearing, which is why {@link #equipment()} is part of the public shape. A kit
 * plugin that hands out those four pieces has handed out the class, and one
 * that takes a piece away has taken it back.
 *
 * @param id         the id used everywhere a class is named
 * @param name       what menus and messages call it, with colour codes still
 *                   in it
 * @param permission the node a player needs to enter it, or an empty string
 *                   when the class is open to everyone
 * @param equipment  the armor the wearer must have on for this class to apply
 * @since 1.0.0
 */
public record PlayerClass(
        @NotNull String id,
        @NotNull String name,
        @NotNull String permission,
        @NotNull @Unmodifiable Map<EquipmentSlot, Material> equipment) {

    /**
     * Whether entering this class needs a permission at all.
     *
     * @return {@code true} when {@link #permission()} has to be granted
     */
    public boolean gated() {
        return !permission.isEmpty();
    }
}
