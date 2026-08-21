package net.exylia.lib.item.internal;

import io.papermc.paper.datacomponent.DataComponentType;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;

/**
 * Writes data components onto an item.
 *
 * <p>Its own class on purpose, and the component is looked up by name rather
 * than named as a field. {@code DataComponentTypes} resolves its constants
 * against the server's registry in a static initialiser, so naming the class at
 * all needs a live server — and naming one of its <em>fields</em> binds this jar
 * to the exact version it was compiled against.
 *
 * <p>That second part is not theoretical. The library compiles against
 * paper-api 1.21.4, where the component is {@code hide_additional_tooltip}; on
 * 1.21.11 that constant is gone, replaced by {@code tooltip_display}. A direct
 * field reference compiled fine and then threw {@code NoSuchFieldError} on a
 * live 1.21.11 server, which took the whole menu down with it — a cosmetic
 * tooltip is never worth a broken screen.
 *
 * <p>The registry is the portable way through: it exists in both versions,
 * answers by name, and answers with nothing when the name is gone. Nothing here
 * throws.
 */
final class ItemComponents implements ItemRenderer.Components {

    static final ItemRenderer.Components INSTANCE = new ItemComponents();

    /** The name the component goes by on the versions that have it. */
    private static final NamespacedKey HIDE_ADDITIONAL_TOOLTIP =
            NamespacedKey.minecraft("hide_additional_tooltip");

    private ItemComponents() {
    }

    /**
     * Disables the tooltip block an item type writes for itself, where the
     * server still knows that component.
     *
     * <p>{@code ItemFlag.HIDE_ADDITIONAL_TOOLTIP} does not cover this on its
     * own: a flag is only persisted alongside the data it hides, and a smithing
     * template's "Applies to: Armor" is not data on the item — it comes from
     * the type. The component is defined against the type, so it reaches it.
     *
     * <p>Where the server does not know the name, the flags applied beside this
     * are the whole of what happens. The block may stay visible; the menu opens
     * either way.
     */
    @Override
    public void hideAdditionalTooltip(ItemStack item, TraitApplier.Reporter problems) {
        try {
            DataComponentType type =
                    Registry.DATA_COMPONENT_TYPE.get(HIDE_ADDITIONAL_TOOLTIP);
            if (type instanceof DataComponentType.NonValued nonValued) {
                item.setData(nonValued);
                return;
            }
            problems.found("hide-attributes", "this server does not know the "
                    + "\"hide_additional_tooltip\" component, so the block an "
                    + "item type writes for itself stays visible");
        } catch (Throwable unsupported) {
            // Never out of here. Whatever a later version does to this
            // component, an item that cannot hide one line still has to draw:
            // a tooltip is not worth a menu that does not open.
            problems.found("hide-attributes", "the \"hide_additional_tooltip\" "
                    + "component could not be written: " + unsupported);
        }
    }
}
