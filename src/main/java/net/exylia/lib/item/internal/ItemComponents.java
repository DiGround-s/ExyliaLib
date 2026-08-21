package net.exylia.lib.item.internal;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.inventory.ItemStack;

/**
 * Writes data components onto an item.
 *
 * <p>Its own class on purpose. {@link DataComponentTypes} resolves each of its
 * constants against {@code Registry.DATA_COMPONENT_TYPE} in a static
 * initialiser, so a class that so much as names it needs a running server to
 * load. Keeping that reference here means {@link ItemRenderer} stays loadable
 * without one, which is what lets its decisions be tested.
 *
 * <p>The same isolation rule the library already applies to PacketEvents and to
 * Folia types, for the same reason.
 */
final class ItemComponents implements ItemRenderer.Components {

    static final ItemRenderer.Components INSTANCE = new ItemComponents();

    private ItemComponents() {
    }

    /**
     * Disables the tooltip block an item type writes for itself.
     *
     * <p>{@code ItemFlag.HIDE_ADDITIONAL_TOOLTIP} cannot do this: a flag is
     * only persisted alongside the data it hides, and a smithing template's
     * "Applies to: Armor" is not data on the item — it comes from the type.
     * The component is defined against the type, so it reaches it.
     */
    @Override
    public void hideAdditionalTooltip(ItemStack item) {
        item.setData(DataComponentTypes.HIDE_ADDITIONAL_TOOLTIP);
    }
}
