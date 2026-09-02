package net.exylia.lib.npc;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

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
}
