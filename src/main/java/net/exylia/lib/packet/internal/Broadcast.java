package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Sends one packet to many players, written once.
 *
 * <h2>The same bytes, not the same work</h2>
 * Sending a wrapper to a player writes it: the fields are serialised into a
 * fresh buffer, which is then handed to that player's connection. Sending the
 * same wrapper to thirty players does that thirty times. For a pose or an
 * animation that is a handful of numbers and nobody notices; for anything
 * carrying a skin it is the better part of a kilobyte of base64 per player, and
 * a tick where fifty bodies appear in front of everyone in the arena is
 * thousands of copies of bytes that were identical every time.
 *
 * <p>They are identical because a server writes a packet in its own format. What
 * a client on an older version receives is translated for it further down its
 * own pipeline, by ViaVersion, from exactly these bytes. So the packet is
 * written once and every player is handed their own reader over it.
 *
 * <p>A proxy is the exception: there a packet is written per client version, and
 * one buffer would be wrong for everyone it was not written for. There, and
 * wherever only one player is watching, this falls back to writing per player.
 *
 * <p>Only classes that have already decided PacketEvents is present may call
 * this; it names the library in every method.
 */
@ApiStatus.Internal
public final class Broadcast {

    private static Boolean proxy;

    private Broadcast() {
        throw new AssertionError("No instances.");
    }

    /**
     * Sends one packet to everyone listed, skipping whoever has already left.
     *
     * @param viewers who it goes to
     * @param packet  what to send; written here and not reusable afterwards
     */
    public static void send(List<Player> viewers, PacketWrapper<?> packet) {
        if (viewers.size() < 2 || proxied()) {
            for (Player viewer : viewers) {
                send(viewer, packet);
            }
            return;
        }
        Object[] written;
        try {
            Object first = channelOf(viewers);
            if (first == null) {
                return;
            }
            // Takes the buffers off the wrapper, which is what sending it to one
            // player does too. From here they are ours to hand out and to free.
            written = PacketEvents.getAPI().getProtocolManager()
                    .transformWrappers(packet, first, true);
        } catch (Throwable failed) {
            // Half-written bytes cannot be written again without doubling them,
            // so the packet is dropped rather than sent wrong: a missing pose is
            // invisible, and a malformed one disconnects everybody watching.
            return;
        }
        try {
            for (Player viewer : viewers) {
                if (!viewer.isOnline()) {
                    continue;
                }
                try {
                    Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(viewer);
                    for (Object buffer : written) {
                        PacketEvents.getAPI().getProtocolManager()
                                .sendPacket(channel, ByteBufHelper.retainedDuplicate(buffer));
                    }
                } catch (Throwable gone) {
                    // This one left between the check and the write. The rest are
                    // still watching.
                }
            }
        } finally {
            for (Object buffer : written) {
                ByteBufHelper.release(buffer);
            }
        }
    }

    /**
     * Sends one packet to one player, unless they have already gone.
     *
     * <p>A packet-driven body, hologram or NPC outlives the moment it was made
     * by design, and a player can quit inside that window. Their connection is
     * gone but the module's list of viewers is not, and writing to it is an
     * exception nobody caused.
     */
    public static void send(Player viewer, PacketWrapper<?> packet) {
        if (!viewer.isOnline()) {
            return;
        }
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        } catch (Throwable gone) {
            // Disconnected between the check and the write. Nothing to do and
            // nothing worth logging: the client that would have drawn it is not
            // there any more.
        }
    }

    /** The channel of the first viewer still here, or {@code null} if none are. */
    private static Object channelOf(List<Player> viewers) {
        for (Player viewer : viewers) {
            if (viewer.isOnline()) {
                Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(viewer);
                if (channel != null) {
                    return channel;
                }
            }
        }
        return null;
    }

    /**
     * Whether packets are written per client version here rather than in the
     * server's own format.
     *
     * <p>Asked once: it cannot change while the server is up, and it is read for
     * every packet of every effect.
     */
    private static boolean proxied() {
        Boolean known = proxy;
        if (known == null) {
            try {
                known = PacketEvents.getAPI().getInjector().isProxy();
            } catch (Throwable unknown) {
                known = Boolean.TRUE;
            }
            proxy = known;
        }
        return known;
    }
}
