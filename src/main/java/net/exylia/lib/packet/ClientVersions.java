package net.exylia.lib.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Which Minecraft version a player's client really runs, as PacketEvents saw it at login: a player
 * joining an older client through ViaBackwards reads their own version, not the server's.
 *
 * <pre>{@code
 * if (!ClientVersions.atLeast(player, ClientVersion.V_1_21_4)) openClassicMenu(player);
 * }</pre>
 *
 * <p>An unknown version (no PacketEvents, no user yet, {@code UNKNOWN}) counts as the server's own,
 * so a server without ViaVersion behaves exactly as if this check were not there. Safe from any thread.
 *
 * @since 1.431.0
 */
public final class ClientVersions {

    private ClientVersions() {
    }

    /** Whether the player's client is {@code minimum} or newer; unknown counts as the server's version. */
    public static boolean atLeast(@NotNull Player player, @NotNull ClientVersion minimum) {
        ClientVersion server;
        try {
            server = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        } catch (Throwable noPacketEvents) {
            return true;
        }
        return atLeast(of(player), server, minimum);
    }

    /**
     * {@link #atLeast(Player, ClientVersion)} for a version as a file writes it ({@code 1.21.4}), for a plugin that does
     * not compile against PacketEvents.
     *
     * @throws IllegalArgumentException if {@link #known} says no
     * @since 1.548.0
     */
    public static boolean reaches(@NotNull Player player, @NotNull String minimum) {
        return atLeast(player, ClientVersion.valueOf("V_" + minimum.trim().replace('.', '_')));
    }

    /**
     * Whether this server's PacketEvents knows a version as a file writes it ({@code 1.21.4}).
     *
     * @since 1.548.0
     */
    public static boolean known(@NotNull String version) {
        try {
            ClientVersion.valueOf("V_" + version.trim().replace('.', '_'));
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    /** The player's client version, or {@code null} when it is not known. */
    public static @Nullable ClientVersion of(@NotNull Player player) {
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            ClientVersion version = user == null ? null : user.getClientVersion();
            return version == null || version == ClientVersion.UNKNOWN ? null : version;
        } catch (Throwable noPacketEvents) {
            return null;
        }
    }

    /** The decision itself: {@code client} (or {@code server} when it is unknown) against {@code minimum}. */
    static boolean atLeast(@Nullable ClientVersion client, @NotNull ClientVersion server, @NotNull ClientVersion minimum) {
        ClientVersion version = client == null || client == ClientVersion.UNKNOWN ? server : client;
        return version.isNewerThanOrEquals(minimum);
    }
}
