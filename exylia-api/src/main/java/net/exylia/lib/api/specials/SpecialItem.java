package net.exylia.lib.api.specials;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * A special item as it is defined, not as a player holds it.
 *
 * <p>One of these per file in the plugin's {@code items/} folder, shared by
 * every copy in the world. Everything that belongs to a single copy — how many
 * uses it has left — lives on the {@link org.bukkit.inventory.ItemStack} and is
 * read with {@link SpecialsService#usesLeft(org.bukkit.inventory.ItemStack)}.
 *
 * <p>A snapshot: reloading the plugin replaces the definition, so ask again
 * rather than keeping one.
 *
 * @param id              the id used in commands and in the item's own NBT
 * @param material        what the item is made of, and what a cooldown overlay
 *                        is drawn on
 * @param trigger         what makes the item fire
 * @param maxUses         how many uses a fresh copy is given, {@code -1} when
 *                        the item never runs out
 * @param cooldownSeconds the wait the item puts on a player after use,
 *                        {@code 0} when it has none
 * @since 1.0.0
 */
public record SpecialItem(
        @NotNull String id,
        @NotNull Material material,
        @NotNull TriggerType trigger,
        int maxUses,
        double cooldownSeconds) {

    /**
     * Whether copies of this item are consumed by use.
     *
     * @return {@code true} when the item has a finite number of uses
     */
    public boolean limitedUses() {
        return maxUses >= 0;
    }
}
