package net.exylia.lib.api.practice;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The items a kit hands out, as the kit itself defines them.
 *
 * <p>Never a player's saved loadout: this is the kit's own layout, for a plugin
 * that equips players with it on its own terms. Every stack is a copy made for
 * this call, so the arrays and their items are the caller's to change.
 *
 * <pre>{@code
 * practice.kitItems("nodebuff").ifPresent(items -> {
 *     PlayerInventory inv = player.getInventory();
 *     inv.setStorageContents(items.contents());
 *     inv.setArmorContents(items.armor());
 *     inv.setItemInOffHand(items.offhand());
 * });
 * }</pre>
 *
 * @param contents the 36 storage slots, hotbar first; empty slots are {@code null}
 * @param armor    the 4 armour slots in Bukkit's order (boots, leggings,
 *                 chestplate, helmet), ready for
 *                 {@link org.bukkit.inventory.PlayerInventory#setArmorContents}
 * @param offhand  the off-hand item, or {@code null}
 * @since 3.5.0
 */
public record KitItems(
        @Nullable ItemStack @NotNull [] contents,
        @Nullable ItemStack @NotNull [] armor,
        @Nullable ItemStack offhand) {
}
