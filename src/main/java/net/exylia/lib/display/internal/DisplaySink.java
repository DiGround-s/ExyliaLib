package net.exylia.lib.display.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * What reaches a client, without naming the packet library.
 *
 * <p>The engine works in poses and milliseconds and never in packets, so
 * {@link DisplayPackets} is the only class in the module that references
 * PacketEvents and the only one that fails to load without it. It is also what
 * lets the timing be tested by asserting on what would have been sent.
 */
public interface DisplaySink {

    /**
     * Spawns a display and sends its first pose.
     *
     * <p>One call, because a display with no metadata is an invisible
     * zero-sized nothing: sending the spawn and the pose apart is a flicker at
     * the start of every effect.
     */
    void spawn(List<Player> viewers, int entityId, DisplayModel model,
               Location at, DisplayKeyframe pose);

    /**
     * Sends a pose the client is to reach over {@code overTicks}.
     *
     * <p>This is the whole module in one call: the client draws every frame
     * between the pose it holds and this one, at its own frame rate, from a
     * packet the server sends once.
     */
    void pose(List<Player> viewers, int entityId, DisplayKeyframe pose, int overTicks);

    /** Removes a display from every client that was shown it. */
    void destroy(List<Player> viewers, int entityId);
}
