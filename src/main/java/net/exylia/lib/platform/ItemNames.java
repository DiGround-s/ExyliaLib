package net.exylia.lib.platform;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * The name an item shows, on every supported server.
 *
 * <p>Paper 1.21.4 added {@code ItemStack#effectiveName()}; on 1.21.1 it is a
 * {@code NoSuchMethodError}. This returns the same thing there, built the way
 * the client picks it: the custom name, else the item name component, else the
 * type's translatable name.
 *
 * <pre>{@code
 * String weapon = PlainTextComponentSerializer.plainText().serialize(ItemNames.of(held));
 * }</pre>
 *
 * @since 1.448.0
 */
public final class ItemNames {

    private static final boolean DIRECT = hasDirect();

    private ItemNames() {
    }

    /** The custom name, the item name, or the type's translatable name, without brackets. */
    public static @NotNull Component of(@NotNull ItemStack item) {
        if (DIRECT) {
            return Direct.of(item);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) return name;
        }
        if (meta != null && meta.hasItemName()) {
            return meta.itemName();
        }
        return Component.translatable(item.translationKey());
    }

    private static boolean hasDirect() {
        try {
            ItemStack.class.getMethod("effectiveName");
            return true;
        } catch (NoSuchMethodException | LinkageError older) {
            return false;
        }
    }

    @RequiresServer("1.21.4")
    private static final class Direct {
        static Component of(ItemStack item) {
            return item.effectiveName();
        }
    }
}
