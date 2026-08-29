package net.exylia.lib.packet;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Decides whether one player may see another.
 *
 * <p>Asked for every viewer when a target is {@link Visibility#refresh
 * refreshed}. Must be cheap and must not touch the world: it can run from a
 * packet thread.
 *
 * @since 1.75.0
 */
@FunctionalInterface
public interface VisibilityRule {

    /**
     * Returns whether {@code viewer} may see {@code target}.
     *
     * @param viewer the player looking
     * @param target the player looked at
     * @return {@code false} to hide the target from this viewer
     */
    boolean canSee(@NotNull Player viewer, @NotNull Player target);
}
