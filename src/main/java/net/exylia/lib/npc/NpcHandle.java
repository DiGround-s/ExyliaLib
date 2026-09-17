package net.exylia.lib.npc;

import org.bukkit.Location;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An NPC that is currently on somebody's screen.
 *
 * <p>Held only by code that wants to change it or take it away early. One that
 * is left alone removes itself when its life is up, so most callers throw the
 * handle away.
 *
 * @since 1.88.2
 */
public interface NpcHandle {

    /**
     * Removes it now, rather than when its life is up.
     *
     * <p>Safe from any thread and safe to call twice.
     */
    void remove();

    /** Whether it is still on somebody's screen. */
    boolean isShowing();

    /**
     * Turns it to face a direction.
     *
     * @param yaw   degrees, as Minecraft counts them
     * @param pitch degrees, negative being up
     */
    void look(float yaw, float pitch);

    /**
     * Turns it to face a place.
     *
     * @param target where to look
     */
    void lookAt(@NotNull Location target);

    /**
     * Changes how it is holding itself.
     *
     * @param pose the new pose
     */
    void pose(@NotNull NpcPose pose);

    /**
     * Puts it somewhere else.
     *
     * <p>A short step is sent relatively, which the client draws smoothly over
     * the frames it has until the next one &mdash; so a body driven twenty
     * times a second walks rather than stutters. A jump too far for a relative
     * step, a pearl or a seek, is sent outright instead and arrives without
     * being smoothed, which is what should happen when nothing continuous
     * joined the two places.
     *
     * @param to where it goes, facing the location's yaw and pitch
     * @since 1.175.0
     */
    void moveTo(@NotNull Location to);

    /**
     * The same, saying whether it is standing on something.
     *
     * <p>Worth carrying. A body told it is always in the air is drawn falling
     * through its own floor on the frames the client has to invent between two
     * steps, which reads as a player sunk into the ground.
     *
     * @param to       where it goes
     * @param onGround whether it is standing on something
     * @since 1.180.0
     */
    void moveTo(@NotNull Location to, boolean onGround);

    /**
     * Whether it is holding an item up: drawing a bow, raising a shield, eating.
     *
     * @param using whether the main hand is in use
     * @since 1.180.0
     */
    void using(boolean using);

    /**
     * Changes one thing it is wearing or holding.
     *
     * @param slot which slot
     * @param item what goes there, or {@code null} to empty it
     * @since 1.175.0
     */
    void equip(@NotNull EquipmentSlot slot, @Nullable ItemStack item);

    /**
     * Swings its main arm, the way a player swinging at something does.
     *
     * @since 1.175.0
     */
    void swing();

    /**
     * Makes it flinch, the way a player taking a hit does.
     *
     * @since 1.175.0
     */
    void hurt();
}
