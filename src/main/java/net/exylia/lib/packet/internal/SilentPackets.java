package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.entity.Player;

/** Writes past every packet listener, to players who still have a client to receive it. */
public final class SilentPackets {

    private SilentPackets() {
    }

    /**
     * A silent write goes through PacketEvents' encoder by name. A connection without one (an NPC that is a Player,
     * or one already closing) has no client to send to, and writing there throws a NullPointerException.
     */
    public static void send(Player player, PacketWrapper<?> packet) {
        Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(player);
        if (channel == null || ChannelHelper.getPipelineHandler(channel, PacketEvents.ENCODER_NAME) == null) return;
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(channel, packet);
    }
}
