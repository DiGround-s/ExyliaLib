package net.exylia.lib.util.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import io.github.retrooper.packetevents.util.viaversion.ViaVersionUtil;
import org.bukkit.entity.Player;

/**
 * The protocol version a player's client really speaks.
 *
 * <p>PacketEvents clamps what it does not know: a client newer than the
 * installed build reports itself as the newest version PacketEvents supports,
 * not as newer, so {@code ClientVersion} alone cannot tell a 26.1 client apart
 * from a 1.21.11 one on a server whose PacketEvents predates 26.1. ViaVersion
 * knows the real number, and is asked first.
 */
public final class ClientProtocol {

    /**
     * 1.21.11, where the player metadata layout last shifted.
     *
     * <p>That release lifted skin layers and main hand out of the player into a
     * shared avatar class (mannequins arrived with it), and the two indices that
     * used to sit above them, absorption and score, moved below.
     */
    public static final int V_1_21_11 = 774;

    private ClientProtocol() {
    }

    /** What this client speaks, or what the server speaks when nothing knows. */
    public static int of(Player player) {
        try {
            if (ViaVersionUtil.isAvailable()) {
                int real = ViaVersionUtil.getProtocolVersion(player);
                if (real > 0) {
                    return real;
                }
            }
        } catch (Throwable ignored) {
            // Via absent or not tracking this player yet: fall through.
        }
        try {
            ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
            if (version != null && version.getProtocolVersion() > 0) {
                return version.getProtocolVersion();
            }
        } catch (RuntimeException ignored) {
            // Fall through.
        }
        return PacketEvents.getAPI().getServerManager().getVersion().getProtocolVersion();
    }

    /**
     * Whether the installed PacketEvents knows how to write packets for this client.
     *
     * <p>A wrapper written for a newer client uses the newest layout PacketEvents
     * knows and skips ViaVersion's translation, so the client cannot decode it.
     */
    public static boolean writable(Player player) {
        int real = of(player);
        return real >= ClientVersion.getOldest().getProtocolVersion()
                && real <= ClientVersion.getLatest().getProtocolVersion();
    }
}
