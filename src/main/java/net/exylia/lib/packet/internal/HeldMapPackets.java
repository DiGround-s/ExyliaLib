package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The PacketEvents half of {@code HeldMaps}; loaded only when PacketEvents is.
 */
public final class HeldMapPackets {

    /** Window id of the player's own inventory. */
    private static final int PLAYER_INVENTORY = 0;
    /** Index of hotbar slot 0 in the player inventory window. */
    private static final int HOTBAR = 36;

    private HeldMapPackets() {
    }

    public static void show(Player player, int mapId, int heldSlot, byte[] pixels) {
        ItemStack map = ItemStack.builder().type(ItemTypes.FILLED_MAP).amount(1)
                .component(ComponentTypes.MAP_ID, mapId).build();
        var players = PacketEvents.getAPI().getPlayerManager();
        // Picture first, so the item never renders blank. Silently: an overlay
        // that rewrites this player's hotbar must not swallow the map.
        players.sendPacketSilently(player, new WrapperPlayServerMapData(mapId, (byte) 0, false, true,
                List.of(), 128, 128, 0, 0, pixels));
        players.sendPacketSilently(player, new WrapperPlayServerSetSlot(PLAYER_INVENTORY, 0, HOTBAR + heldSlot, map));
    }
}
