package net.exylia.lib.hologram.internal;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * What reaches a client, without naming the packet library.
 *
 * <p>Everything the engine sends goes through here, so the engine itself never
 * references PacketEvents: {@link DisplayPackets} is the only class that does,
 * and it is only loaded once PacketEvents is known to be present. It is also
 * what lets the whole module be tested by asserting on the packets a hologram
 * would have written.
 */
public interface DisplaySink {

    /** Spawns a display and sends its full state. */
    void spawn(Player viewer, DisplayState display, Location at);

    /** Sends only the text of a display whose line changed. */
    void text(Player viewer, DisplayState display, Component text);

    /** Moves a display without respawning it. */
    void teleport(Player viewer, DisplayState display, Location to);

    /** Makes displays ride an entity. */
    void mount(Player viewer, int vehicleId, int[] passengers);

    /** Removes displays from a client. */
    void destroy(Player viewer, int[] entityIds);
}
