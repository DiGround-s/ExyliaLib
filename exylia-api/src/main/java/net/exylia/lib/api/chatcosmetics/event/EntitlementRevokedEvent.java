package net.exylia.lib.api.chatcosmetics.event;

import net.exylia.lib.api.chatcosmetics.Entitlement;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A grant was taken away.
 *
 * <p>One event per grant, so revoking every grant of one cosmetic fires this several times. The player may still own the cosmetic afterwards through a permission node or another grant; ask rather than assume.
 *
 * <p>Fired on the player's thread when they are online, on the global thread
 * otherwise — a grant for somebody absent is still worth hearing about.
 *
 * @since 1.0.0
 */
public final class EntitlementRevokedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Entitlement entitlement;

    /**
     * @param entitlement the grant this is about
     */
    public EntitlementRevokedEvent(@NotNull Entitlement entitlement) {
        this.entitlement = entitlement;
    }

    /**
     * The grant this is about.
     *
     * @return the entitlement
     */
    public @NotNull Entitlement entitlement() {
        return entitlement;
    }

    /**
     * Who holds it.
     *
     * @return the player's uuid
     */
    public @NotNull UUID player() {
        return entitlement.player();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * @return the handler list Bukkit registers against
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
