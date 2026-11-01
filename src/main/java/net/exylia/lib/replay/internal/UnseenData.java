package net.exylia.lib.replay.internal;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.exylia.lib.platform.RequiresServer;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/** Kept out of line: naming a data component type needs a server that has them. */
@RequiresServer("1.21.4")
final class UnseenData {

    private static final Set<DataComponentType> TYPES = Set.of(DataComponentTypes.DAMAGE);

    private UnseenData() {
    }

    static boolean alike(ItemStack a, ItemStack b) {
        return a.matchesWithoutData(b, TYPES, true);
    }
}
