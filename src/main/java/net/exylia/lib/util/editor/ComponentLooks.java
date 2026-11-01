package net.exylia.lib.util.editor;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.exylia.lib.platform.RequiresServer;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Strips what only changes how an item looks, through data components.
 *
 * <p>Its own class so that a server without the data component API (1.21.1)
 * never loads it; {@link Loadout} asks {@code Features.dataComponents()} first
 * and goes through the meta otherwise.
 */
@RequiresServer("1.21.4")
final class ComponentLooks {

    /** A holder, since the types need a server to load. */
    private static final class All {
        static final List<DataComponentType> TYPES = List.of(
                DataComponentTypes.CUSTOM_NAME, DataComponentTypes.ITEM_NAME, DataComponentTypes.LORE,
                DataComponentTypes.ITEM_MODEL, DataComponentTypes.CUSTOM_MODEL_DATA,
                DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,
                DataComponentTypes.DYED_COLOR, DataComponentTypes.TRIM,
                DataComponentTypes.BANNER_PATTERNS, DataComponentTypes.BASE_COLOR);
    }

    private ComponentLooks() {
    }

    static void strip(ItemStack item) {
        All.TYPES.forEach(item::resetData);
    }
}
