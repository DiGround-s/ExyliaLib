package net.exylia.lib.npc.internal;

import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcPose;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

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

    /**
     * Announces the identity, without drawing anything yet.
     *
     * <p>Separate from the body on purpose: see {@link #spawn}.
     */
    void announce(List<Player> viewers, NpcModel model);

    /** Draws the body and dresses it. */
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
    void move(List<Player> viewers, int entityId, double dx, double dy, double dz,
              float yaw, float pitch, boolean onGround);

    /**
     * Puts it at a place outright, rather than a step from where it was.
     *
     * <p>For the jumps a relative step cannot carry: a pearl, a respawn, or a
     * replay seeking to somewhere else entirely. The client does not smooth
     * one of these, which is exactly what is wanted when nothing continuous
     * happened between the two places.
     */
    void teleport(List<Player> viewers, int entityId, Location to);

    /**
     * Puts one thing in one slot, leaving the other five alone.
     *
     * @param item what goes there, or {@code null} to empty the slot
     */
    void equip(List<Player> viewers, int entityId, EquipmentSlot slot, ItemStack item);

    /**
     * Whether it is holding an item up: drawing a bow, raising a shield,
     * eating.
     *
     * <p>The one piece of a fight that a body otherwise stands perfectly still
     * through. Without it somebody eating a golden apple is somebody doing
     * nothing for two seconds.
     */
    void using(List<Player> viewers, int entityId, boolean using);

    /** Makes it flinch. */
    void hurt(List<Player> viewers, int entityId);

    /** Swings its main arm, the way a player swinging at something does. */
    void swing(List<Player> viewers, int entityId);

    /** Changes how it is holding itself. */
    void pose(List<Player> viewers, int entityId, NpcModel model, NpcPose pose);

    /** Removes the body and the identity behind it. */
    void destroy(List<Player> viewers, int entityId, UUID profile);
}
