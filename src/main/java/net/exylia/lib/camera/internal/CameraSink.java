package net.exylia.lib.camera.internal;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * What reaches a client, without naming the packet library.
 *
 * <p>The engine works in positions and milliseconds and never in packets, so
 * {@link CameraPackets} is the only class in the module that references
 * PacketEvents and the only one that fails to load without it. It is also what
 * lets the timing be tested by asserting on what would have been sent.
 */
interface CameraSink {

    /** Reserves an entity id that cannot collide with a real entity. */
    int newEntityId();

    /**
     * Puts the camera in the world, ready to be moved.
     *
     * <p>Told at the same time how long it has to reach each position it will
     * be sent, because that is a property of the entity rather than of any one
     * move: the client reads it when a position arrives, and a camera that was
     * never told it snaps between them at the server's tick rate.
     */
    void spawn(List<Player> viewers, int entityId, CameraPath.Frame at, int holdTicks);

    /** Sends the next position and the way it looks from there. */
    void move(List<Player> viewers, int entityId, CameraPath.Frame to);

    /**
     * Tells clients which entity they are looking through.
     *
     * <p>Their own entity id gives them their eyes back, and that is the only
     * way back: nothing else the server can send undoes this one.
     */
    void look(List<Player> viewers, int entityId);

    /** Takes the camera off every client that was shown it. */
    void destroy(List<Player> viewers, int entityId);
}
