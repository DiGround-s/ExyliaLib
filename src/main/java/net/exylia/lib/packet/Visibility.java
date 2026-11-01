package net.exylia.lib.packet;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Per-viewer hiding of players, for vanish.
 *
 * <p>A plugin registers one {@link VisibilityRule}; every rule from every
 * plugin must agree before a viewer sees a target. When a target's visibility
 * changes the plugin calls {@link #refresh}, and the module despawns them for
 * viewers who lost sight and respawns them for viewers who regained it.
 *
 * <p>Between refreshes, every outbound packet about a hidden target — spawn,
 * metadata, movement, entity sounds, tab-list entries, their names among
 * command suggestions — is dropped on its way to a viewer who may not see
 * them, so a plugin that sends its own packets cannot leak a vanished player.
 *
 * <h2>Limits</h2>
 * The server still knows where the target is: they collide, block arrows,
 * and appear in {@code getNearbyEntities}. Positional sounds and particles
 * carry no entity id and pass through. Pair with
 * {@code target.setCollidable(false)} and {@code setSilent(true)} where it
 * matters.
 *
 * @since 1.75.0
 */
public interface Visibility {

    /**
     * Registers this plugin's rule, replacing its previous one.
     *
     * @param rule the rule
     */
    void rule(@NotNull VisibilityRule rule);

    /**
     * Re-evaluates who may see {@code target} and sends the difference.
     *
     * <p>Call after anything that changes a rule's answer: vanish toggled, a
     * permission granted, a viewer joined. Safe from any thread.
     *
     * @param target the player whose visibility changed
     */
    void refresh(@NotNull Player target);

    /**
     * Returns whether {@code viewer} currently sees {@code target}.
     *
     * @param viewer the player looking
     * @param target the player looked at
     * @return {@code false} while the target is hidden from this viewer
     */
    boolean canSee(@NotNull Player viewer, @NotNull Player target);

    /**
     * Keeps a box empty for one viewer while a screen stands in it: every entity in it that is not the
     * screen's own is out of their sight, whoever drew it (the server's mobs, items and players, another
     * plugin's packet-only NPCs and holograms, this library's), and comes back when it leaves the box or
     * the box is lifted. {@code null} lifts it. Each plugin seals one box per viewer; a viewer in several
     * plugins' boxes sees nothing in any of them. Forgotten on quit, lifted when the plugin is disabled.
     *
     * <p>The screen's own pieces are what is spawned to the viewer after the box goes up: seal before
     * drawing. An entity already there, or spawned again later under the id it had (a hologram another
     * plugin redraws, an NPC respawned), is not the screen's, nor is any real player.
     *
     * <p>Nothing is despawned: an entity in the box is moved, for this viewer alone, far beyond what any
     * client draws, and moved back to where it really is on lifting, so another plugin's NPC keeps the
     * skin, equipment, metadata and passengers it was sent with and that plugin is never asked to draw it
     * again. A vehicle the viewer rides is never moved. Since 1.442.0; before, only entities spawned in
     * packets after the box went up were kept out, and displays never.
     *
     * <h4>Limits</h4>
     * An entity another plugin spawns for the first time while the box is up, with a brand-new id, counts
     * as the screen's (only real players are told apart then). The server still has every entity where it
     * is: it collides, and {@code getNearbyEntities} finds it.
     *
     * @param viewer the player
     * @param box    the box, in the viewer's world, or {@code null}
     * @since 1.420.0
     */
    default void seal(@NotNull Player viewer, @org.jetbrains.annotations.Nullable Volume box) {
    }
}
