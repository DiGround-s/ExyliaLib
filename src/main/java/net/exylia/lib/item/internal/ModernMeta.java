package net.exylia.lib.item.internal;

import net.exylia.lib.platform.RequiresServer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Meta setters newer than the oldest supported server.
 *
 * <p>Only loaded after the matching {@code Features} probe said yes, so
 * {@link ItemRenderer} stays loadable on 1.21.1.
 */
@RequiresServer("1.21.4")
final class ModernMeta {

    private ModernMeta() {
    }

    /** {@code item_model}, Minecraft 1.21.4. */
    static void itemModel(ItemMeta meta, NamespacedKey key) {
        meta.setItemModel(key);
    }

    /** {@code tooltip_style}, Minecraft 1.21.2. */
    static void tooltipStyle(ItemMeta meta, NamespacedKey key) {
        meta.setTooltipStyle(key);
    }
}
