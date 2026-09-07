package net.exylia.lib.packet;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Drawing invisible players for one viewer, for staff.
 *
 * <p>A client draws a player invisible because of one bit in the entity flags
 * the server sends. On its way to a viewer who asked for this, that bit is
 * either cleared — the player is drawn whole — or paired with the glow bit,
 * which outlines them through the world. Nothing on the server changes: the
 * potion still runs, and every other viewer still sees nothing.
 *
 * <pre>{@code
 * packets.reveal().show(staff, RevealStyle.OUTLINE);
 * // ...
 * packets.reveal().hide(staff);
 * }</pre>
 *
 * <h2>Limits</h2>
 * Only players are drawn: an invisible armour stand holding a hologram stays
 * invisible. A player another plugin hid with {@code hidePlayer} is never sent
 * to the viewer at all, so there is no packet to rewrite — that one needs the
 * owning plugin, not this.
 *
 * <p>When the plugin is disabled the filter stops at once; a viewer looking at
 * an invisible player right then keeps the render they were given until those
 * flags change again.
 *
 * @since 1.116.0
 */
public interface Reveal {

    /**
     * Shows this viewer the invisible players around them.
     *
     * <p>Players already invisible are tracked again so the new flags reach the
     * client; the viewer sees them respawn a tick later. Calling it again with
     * another style replaces the first.
     *
     * @param viewer the player looking
     * @param style  whole body or outline
     */
    void show(@NotNull Player viewer, @NotNull RevealStyle style);

    /**
     * Stops showing them, and hides again what is on screen.
     *
     * <p>Does nothing when another plugin is the one revealing.
     *
     * @param viewer the player looking
     */
    void hide(@NotNull Player viewer);

    /**
     * Returns how this plugin is drawing the invisible for a viewer.
     *
     * @param viewer the player looking
     * @return the style, or {@code null} when this plugin reveals nothing to them
     */
    @Nullable RevealStyle styleOf(@NotNull Player viewer);
}
