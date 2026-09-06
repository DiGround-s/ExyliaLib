package net.exylia.lib.api.chatcosmetics.event;

import net.exylia.lib.api.chatcosmetics.Entitlement;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A grant ran out.
 *
 * <p>Fired by the sweep that notices it, which is why it arrives shortly after the expiry rather than exactly on it. Only for a player who is online: what ran out while somebody was away is announced to them when they next join.
 *
 * <p>Fired on the player's thread when they are online, on the global thread
 * otherwise — a grant for somebody absent is still worth hearing about.
 *
 * @since 1.0.0
 */
public final class EntitlementExpiredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Entitlement entitlement;

    /**
     * @param entitlement the grant this is about
     */
    public EntitlementExpiredEvent(@NotNull Entitlement entitlement) {
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
