package net.exylia.lib.api.practice;

import org.jetbrains.annotations.NotNull;

/**
 * One saved arrangement of a kit, belonging to one player.
 *
 * <p>A player may keep several of a kit and pick one before a match. Only the
 * label is here: the items themselves are a private inventory layout, and the
 * only thing that can be done with them is to give them to their owner, which
 * the plugin already does when the match starts.
 *
 * @param kitId        the kit this loadout arranges
 * @param slotIndex    which of the player's slots for that kit this is
 * @param name         what the player called it
 * @param iconMaterial the Bukkit material name the player chose for it, or
 *                     empty to let the menu decide
 * @since 1.0.0
 */
public record KitLoadout(
        @NotNull String kitId,
        int slotIndex,
        @NotNull String name,
        @NotNull String iconMaterial) {
}
