package net.exylia.lib.internal;

import com.github.retrooper.packetevents.PacketEvents;
import org.jetbrains.annotations.ApiStatus;

import java.util.logging.Logger;

/**
 * Whether PacketEvents read the server's Minecraft version correctly.
 *
 * <p>PacketEvents names each packet by its id in the server's protocol. On a server whose version
 * it cannot read (Youer and other NeoForge hybrids) it assumes the newest release it knows, so a
 * 1.21.1 server is decoded with 26.x ids: a packet the library rewrites is taken for another, and
 * writing it back disconnects the player. There every packet feature of the library stays off,
 * which is said once in the console. A server newer than PacketEvents knows is not refused here:
 * that is an outdated PacketEvents, which warns about itself.
 *
 * <p>Only call it where PacketEvents is known to be loaded.
 */
@ApiStatus.Internal
public final class PacketEventsVersion {

    private static volatile Boolean agrees;

    private PacketEventsVersion() {
    }

    /** Whether the version PacketEvents decodes with is not newer than the server running. */
    public static boolean agrees() {
        Boolean known = agrees;
        if (known != null) return known;
        synchronized (PacketEventsVersion.class) {
            if (agrees == null) agrees = check();
            return agrees;
        }
    }

    private static boolean check() {
        String detected = PacketEvents.getAPI().getServerManager().getVersion().getReleaseName();
        String running = MinimumVersion.running();
        if (!MinimumVersion.olderThan(running, detected)) return true;
        Logger logger = Logger.getLogger("ExyliaLib");
        logger.severe("************************************************************");
        logger.severe(" PacketEvents reads this server as Minecraft " + detected + ", but it runs " + running + ".");
        logger.severe(" It decodes every packet with the wrong ids, which disconnects players");
        logger.severe(" as soon as a packet is rewritten. Packet features of ExyliaLib are OFF:");
        logger.severe(" vanish by packets, overlays, holograms, NPCs, models, borders, outlines.");
        logger.severe(" Run a server PacketEvents supports (Paper, Purpur, Folia) to use them.");
        logger.severe("************************************************************");
        return false;
    }
}
