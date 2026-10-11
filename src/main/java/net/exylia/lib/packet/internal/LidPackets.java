package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockAction;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The block event that swings a chest's lid, sent to chosen clients.
 *
 * <p>The only class behind {@code ChestLids} that names PacketEvents, so a
 * server without it never loads this one.
 */
@ApiStatus.Internal
public final class LidPackets {

    /** The block event a chest, an ender chest and a shulker box share: how many are looking in. */
    private static final int VIEWERS_EVENT = 1;

    private LidPackets() {
    }

    /**
     * Opens or closes the lid at a block for these viewers.
     *
     * <p>The block type travels with the packet but the client ignores it and
     * animates whatever it has at that position, so air is sent: it is always
     * a valid entry in the block registry, which a wrong number is not.
     */
    public static void send(List<Player> viewers, int x, int y, int z, boolean open) {
        Broadcast.send(viewers, new WrapperPlayServerBlockAction(new Vector3i(x, y, z),
                VIEWERS_EVENT, open ? 1 : 0, 0));
    }
}
