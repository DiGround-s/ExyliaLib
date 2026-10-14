package net.exylia.lib.npc.internal;

import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.npc.NpcSpec;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * What reaches a client for a placed NPC, without naming the packet library.
 *
 * <p>Per viewer rather than per list: a mirrored skin and a head turned at each viewer
 * are different packets for every player.
 */
interface PlacedSink {

    /**
     * Announces the identity, unlisted, and the team that hides its nametag, without
     * drawing anything yet.
     *
     * @param skin {@code {value, signature}}, or {@code null} for the default skin
     * @param glow the outline colour as {@code 0xRRGGBB}, or negative for none; the
     *             team's colour is what the client outlines it in
     */
    void announce(Player viewer, UUID profile, String name, String[] skin, int glow);

    /** Draws the body, dressed and posed, facing the given way. */
    void draw(Player viewer, int entityId, UUID profile, String name, NpcSpec spec,
              Location at, float yaw, float pitch);

    /**
     * Takes the identity back out of the viewer's player list once the body is drawn.
     *
     * <p>The client keeps the skin on a drawn body, but every entry still in its list is
     * a name it offers when completing a command, so an entry left there is a fake
     * player in {@code /kill <tab>}.
     */
    default void unlist(Player viewer, UUID profile) {
    }

    /** Turns the head and body. */
    void look(Player viewer, int entityId, float yaw, float pitch);

    /** Puts it somewhere outright. */
    void teleport(Player viewer, int entityId, Location to);

    /** Puts one item in one slot, or empties it. */
    void equip(Player viewer, int entityId, EquipmentSlot slot, ItemStack item);

    /** Changes how it holds itself. */
    void pose(Player viewer, int entityId, NpcPose pose);

    /** Swings its main arm. */
    void swing(Player viewer, int entityId);

    /** Makes it flinch. */
    void hurt(Player viewer, int entityId);

    /** Swings its off-hand arm. */
    void swingOffhand(Player viewer, int entityId);

    /**
     * Spawns an invisible seat at {@code at} and sits the drawn body on it.
     *
     * <p>A client only draws a player seated while it rides something.
     */
    void seat(Player viewer, int seatId, int entityId, Location at);

    /** Removes the seat; the body stands up where it is. */
    void unseat(Player viewer, int seatId);

    /** Removes the body, the identity and the team that hid its name. */
    void destroy(Player viewer, int entityId, UUID profile, String name);
}
