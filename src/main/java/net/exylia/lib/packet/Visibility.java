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
 * metadata, movement, entity sounds, tab-list entries — is dropped on its way
 * to a viewer who may not see them, so a plugin that sends its own packets
 * cannot leak a vanished player.
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
}
