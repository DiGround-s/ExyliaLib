package net.exylia.lib.platform;

import com.google.common.collect.Multimap;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Hides the attribute lines vanilla draws on a tool or a piece of armour
 * ("When in Main Hand: 7 Attack Damage", "When on Body: +8 Armor"), on every
 * supported server.
 *
 * <p>Up to 1.21.4 {@link ItemFlag#HIDE_ATTRIBUTES} is not stored on its own:
 * it is the {@code show_in_tooltip} field of the item's
 * {@code attribute_modifiers} component. A sword that only has the modifiers
 * its type declares carries no such component, so the flag is dropped the
 * moment the meta is set and the lines stay. This writes the type's own
 * modifiers onto the meta first, so the flag has somewhere to live. The item
 * keeps its stats: they are the very modifiers it already had.
 *
 * <p>From 1.21.5 a flag is an entry of {@code tooltip_display} and is stored by
 * itself, so there only the flag is added.
 *
 * <pre>{@code
 * ItemMeta meta = item.getItemMeta();
 * AttributeLines.hide(meta, item.getType());   // after the last attribute change
 * item.setItemMeta(meta);
 * }</pre>
 *
 * @since 1.475.0
 */
public final class AttributeLines {

    private AttributeLines() {
    }

    /**
     * Adds {@link ItemFlag#HIDE_ATTRIBUTES} in a way the server keeps.
     *
     * <p>Call it after the meta's last attribute change: modifiers written onto
     * the item later through the data component API come back with their lines
     * shown.
     *
     * @param meta the meta about to be set on an item of {@code type}
     * @param type the item's type, whose own modifiers are written when the meta has none
     * @return whether the item has attribute lines at all, so whether the meta needs setting
     */
    public static boolean hide(@NotNull ItemMeta meta, @NotNull Material type) {
        return hide(meta, type::getDefaultAttributeModifiers, Features.tooltipDisplay());
    }

    /**
     * The decision, with the type's modifiers and the server's answer passed in.
     * Package-private for tests: a {@code Material} answers about its modifiers
     * only on a live server.
     */
    static boolean hide(ItemMeta meta, Supplier<Multimap<Attribute, AttributeModifier>> typeModifiers,
                        boolean flagStoredAlone) {
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (meta.hasAttributeModifiers() || flagStoredAlone) {
            return true;
        }
        Multimap<Attribute, AttributeModifier> own = typeModifiers.get();
        if (own.isEmpty()) {
            return false;
        }
        meta.setAttributeModifiers(own);
        return true;
    }
}
