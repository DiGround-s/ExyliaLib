package net.exylia.lib.npc.internal;

import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcPose;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * What reaches a client, without naming the packet library.
 *
 * <p>{@link NpcPackets} is the only class in the module that references
 * PacketEvents and the only one that fails to load without it, which is what
 * lets the lifetimes be tested by asserting on what would have been sent.
 */
public interface NpcSink {

    /** Announces the identity, spawns the body, and dresses it. */
    void spawn(List<Player> viewers, int entityId, NpcModel model, Location at);

    /** Turns the head and body. */
    void look(List<Player> viewers, int entityId, float yaw, float pitch);

    /**
     * Shifts it by a small step and turns it, in one packet.
     *
     * <p>Relative rather than absolute on purpose: the client draws its own
     * frames between two relative moves, so a body driven at twenty steps a
     * second is seen moving smoothly rather than in twenty jumps.
     */
    void move(List<Player> viewers, int entityId, double dx, double dy, double dz, float yaw);

    /** Makes it flinch. */
    void hurt(List<Player> viewers, int entityId);

    /** Changes how it is holding itself. */
    void pose(List<Player> viewers, int entityId, NpcModel model, NpcPose pose);

    /** Removes the body and the identity behind it. */
    void destroy(List<Player> viewers, int entityId, UUID profile);
}
